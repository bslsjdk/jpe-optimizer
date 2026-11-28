#include "jpe_terrain_api.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include "chunk_cache.h"
#include "jpe_terrain_handle.h"
#include "jpe_terrain_internal.h"

namespace {

using jpe::Config;
using jpe::Generator;
using jpe::Scratch;

// ---------------------------------------------------------------------------
// Worker pool
// ---------------------------------------------------------------------------
//
// One pool per generator handle. Work is dispatched by an atomic index so a
// batch of chunks is split without any per-chunk locking, and every worker owns
// a thread-local scratch block so generation allocates nothing.
class Pool {
public:
    explicit Pool(uint32_t threads) {
        if (threads == 0) threads = 1;
        workers_.reserve(threads);
        for (uint32_t i = 0; i < threads; ++i) {
            workers_.emplace_back([this] { worker_loop(); });
        }
    }

    ~Pool() {
        {
            std::lock_guard<std::mutex> lock(m_);
            stop_ = true;
        }
        work_cv_.notify_all();
        for (auto &t : workers_) {
            if (t.joinable()) t.join();
        }
    }

    uint32_t size() const { return static_cast<uint32_t>(workers_.size()); }

    void parallel_for(uint32_t count, const std::function<void(uint32_t)> &fn) {
        if (count == 0) return;
        if (workers_.empty()) {
            for (uint32_t i = 0; i < count; ++i) fn(i);
            return;
        }
        {
            std::lock_guard<std::mutex> lock(m_);
            task_ = &fn;
            count_ = count;
            next_.store(0, std::memory_order_relaxed);
            remaining_ = count;
            ++generation_;
        }
        work_cv_.notify_all();

        // The calling thread participates: it keeps one core busy and avoids a
        // wake-up stall for short batches.
        for (;;) {
            const uint32_t i = next_.fetch_add(1, std::memory_order_relaxed);
            if (i >= count) break;
            fn(i);
            finish_one();
        }

        std::unique_lock<std::mutex> lock(m_);
        done_cv_.wait(lock, [this] { return remaining_ == 0; });
        task_ = nullptr;
    }

private:
    void finish_one() {
        std::lock_guard<std::mutex> lock(m_);
        if (remaining_ > 0 && --remaining_ == 0) done_cv_.notify_all();
    }

    void worker_loop() {
        uint64_t seen = 0;
        for (;;) {
            const std::function<void(uint32_t)> *task = nullptr;
            uint32_t count = 0;
            {
                std::unique_lock<std::mutex> lock(m_);
                work_cv_.wait(lock, [&] { return stop_ || generation_ != seen; });
                if (stop_) return;
                seen = generation_;
                task = task_;
                count = count_;
            }
            if (task == nullptr) continue;
            for (;;) {
                const uint32_t i = next_.fetch_add(1, std::memory_order_relaxed);
                if (i >= count) break;
                (*task)(i);
                finish_one();
            }
        }
    }

    std::vector<std::thread> workers_;
    std::mutex m_;
    std::condition_variable work_cv_;
    std::condition_variable done_cv_;
    const std::function<void(uint32_t)> *task_ = nullptr;
    uint32_t count_ = 0;
    uint32_t remaining_ = 0;
    std::atomic<uint32_t> next_{0};
    uint64_t generation_ = 0;
    bool stop_ = false;
};

struct Handle {
    Config cfg;
    Generator gen;
    std::unique_ptr<Pool> pool;

    // The streaming layer. Disabled (zero budget) until a host configures it,
    // so a v1 caller that never calls jpe_terrain_cache_config pays nothing
    // but one empty map and one branch.
    ChunkCache cache;

    std::atomic<uint64_t> chunks{0};
    std::atomic<uint64_t> blocks{0};
    std::atomic<uint64_t> density_samples{0};
    std::atomic<uint64_t> noise_samples{0};
    std::atomic<uint64_t> carve_cells{0};
    std::atomic<uint64_t> nanos{0};
};

std::mutex g_handles_mutex;
std::unordered_map<uint64_t, std::unique_ptr<Handle>> g_handles;
std::atomic<uint64_t> g_next_handle{1};

Handle *lookup(uint64_t handle) {
    if (handle == 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(handle);
    return it == g_handles.end() ? nullptr : it->second.get();
}

}  // namespace

/// Streaming-layer bridge. Declared in jpe_terrain_handle.h; defined here
/// because the handle map is internal to this translation unit.
///
/// Deliberately NOT nested inside the anonymous namespace above: a plain
/// `namespace jpe {}` there would open (anonymous)::jpe, a different namespace
/// from the real ::jpe, and every jpe type (ChunkCache, HandleView,
/// BLOCK_COUNT) would fail to resolve. Closing the anonymous namespace first
/// keeps this definition in the global ::jpe.
namespace jpe {

bool resolve_handle(uint64_t id, HandleView *out) {
    if (!out) return false;
    Handle *h = lookup(id);
    if (!h) return false;
    out->gen = &h->gen;
    out->cache = &h->cache;
    out->bytes_per_chunk = h->cfg.chunk_blocks() * sizeof(uint16_t);
    return true;
}

}  // namespace jpe

namespace {

thread_local Scratch g_scratch;

constexpr const char *kBlockNames[jpe::BLOCK_COUNT] = {
    "air",         "stone",      "grass_block", "dirt",       "coarse_dirt",
    "podzol",      "sand",       "red_sand",    "sandstone",  "gravel",
    "clay",        "water",      "lava",        "bedrock",    "deepslate",
    "snow_block",  "ice",        "terracotta",  "andesite",   "granite",
    "calcite",     "moss_block", "mud",         "packed_ice", "mycelium",
    "tuff",        "dripstone_block", "snow_layer"};

uint32_t default_threads() {
    const unsigned hw = std::thread::hardware_concurrency();
    if (hw <= 1) return 1;
    return hw;
}

}  // namespace

extern "C" {

void jpe_terrain_default_config(JpeTerrainConfig *out) {
    if (!out) return;
    out->seed = 0;
    out->min_y = JPE_TERRAIN_DEFAULT_MIN_Y;
    out->height = JPE_TERRAIN_DEFAULT_HEIGHT;
    out->sea_level = JPE_TERRAIN_DEFAULT_SEA_LEVEL;
    out->lava_level = JPE_TERRAIN_DEFAULT_LAVA_LEVEL;
    out->stages = JPE_TERRAIN_STAGE_ALL;
    out->threads = 0;
}

uint32_t jpe_terrain_abi_version(void) { return JPE_TERRAIN_ABI_VERSION; }

uint64_t jpe_terrain_create(const JpeTerrainConfig *config) {
    if (!config) return 0;
    Config cfg;
    cfg.seed = config->seed;
    cfg.min_y = config->min_y;
    cfg.height = config->height;
    cfg.sea_level = config->sea_level;
    cfg.lava_level = config->lava_level;
    cfg.stages = config->stages == 0 ? static_cast<uint32_t>(JPE_TERRAIN_STAGE_ALL) : config->stages;
    cfg.threads = config->threads;

    auto handle = std::make_unique<Handle>();
    if (!handle->gen.init(cfg)) return 0;
    handle->cfg = cfg;

    const uint32_t threads = config->threads == 0 ? default_threads() : config->threads;
    handle->pool = std::make_unique<Pool>(threads);

    const uint64_t id = g_next_handle.fetch_add(1);
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    g_handles.emplace(id, std::move(handle));
    return id;
}

void jpe_terrain_destroy(uint64_t handle) {
    if (handle == 0) return;
    std::unique_ptr<Handle> owned;
    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        auto it = g_handles.find(handle);
        if (it == g_handles.end()) return;
        owned = std::move(it->second);
        g_handles.erase(it);
    }
    // Pool destructor joins its workers.
    owned.reset();
}

uint64_t jpe_terrain_chunk_blocks(uint64_t handle) {
    Handle *h = lookup(handle);
    return h ? h->cfg.chunk_blocks() : 0;
}

int32_t jpe_terrain_generate_chunk(uint64_t handle, int32_t chunk_x, int32_t chunk_z,
                                   uint16_t *out, uint64_t out_length) {
    Handle *h = lookup(handle);
    if (!h) return -1;
    const uint64_t need = h->cfg.chunk_blocks();
    if (!out || out_length < need) return -2;
    const auto t0 = std::chrono::steady_clock::now();
    h->gen.generate_chunk(chunk_x, chunk_z, out, g_scratch);
    const auto t1 = std::chrono::steady_clock::now();
    h->chunks.fetch_add(1, std::memory_order_relaxed);
    h->blocks.fetch_add(need, std::memory_order_relaxed);
    h->nanos.fetch_add(
        static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count()),
        std::memory_order_relaxed);
    const int gy_max = h->cfg.height / 8;
    h->density_samples.fetch_add(static_cast<uint64_t>(25) * (gy_max + 1), std::memory_order_relaxed);
    return 0;
}

int32_t jpe_terrain_generate_chunks(uint64_t handle, const int32_t *chunk_xz, uint32_t count,
                                    uint16_t *out, uint64_t out_length, uint32_t threads) {
    Handle *h = lookup(handle);
    if (!h) return -1;
    if (!chunk_xz || !out) return -2;
    const uint64_t per_chunk = h->cfg.chunk_blocks();
    if (out_length < per_chunk * static_cast<uint64_t>(count)) return -2;
    if (count == 0) return 0;

    const auto t0 = std::chrono::steady_clock::now();
    if (threads == 1 || h->pool->size() == 1) {
        for (uint32_t i = 0; i < count; ++i) {
            h->gen.generate_chunk(chunk_xz[i * 2], chunk_xz[i * 2 + 1],
                                  out + per_chunk * static_cast<uint64_t>(i), g_scratch);
        }
    } else {
        h->pool->parallel_for(count, [&](uint32_t i) {
            h->gen.generate_chunk(chunk_xz[i * 2], chunk_xz[i * 2 + 1],
                                  out + per_chunk * static_cast<uint64_t>(i), g_scratch);
        });
    }
    const auto t1 = std::chrono::steady_clock::now();
    h->chunks.fetch_add(count, std::memory_order_relaxed);
    h->blocks.fetch_add(per_chunk * count, std::memory_order_relaxed);
    h->nanos.fetch_add(
        static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count()),
        std::memory_order_relaxed);
    const int gy_max = h->cfg.height / 8;
    h->density_samples.fetch_add(static_cast<uint64_t>(count) * 25 * (gy_max + 1),
                                 std::memory_order_relaxed);
    return 0;
}

int32_t jpe_terrain_column_info(uint64_t handle, int32_t chunk_x, int32_t chunk_z,
                                JpeTerrainColumnInfo *out, uint64_t out_length) {
    Handle *h = lookup(handle);
    if (!h) return -1;
    if (!out || out_length < 256) return -2;

    int32_t surface[256];
    int32_t water[256];
    int32_t biome[256];
    uint32_t solid[256];
    uint32_t fluid[256];
    h->gen.column_info(chunk_x, chunk_z, surface, water, biome, solid, fluid, g_scratch);
    for (int i = 0; i < 256; ++i) {
        out[i].biome = biome[i];
        out[i].surface_y = surface[i];
        out[i].water_top_y = water[i];
        out[i].solid_blocks = solid[i];
        out[i].fluid_blocks = fluid[i];
    }
    return 0;
}

int32_t jpe_terrain_heightmap(uint64_t handle, int32_t chunk_x, int32_t chunk_z, int32_t *out,
                              uint64_t out_length) {
    Handle *h = lookup(handle);
    if (!h) return -1;
    if (!out || out_length < 256) return -2;
    h->gen.column_info(chunk_x, chunk_z, out, nullptr, nullptr, nullptr, nullptr, g_scratch);
    return 0;
}

int32_t jpe_terrain_climate(uint64_t handle, int32_t block_x, int32_t block_z, float *out,
                            uint64_t out_length) {
    Handle *h = lookup(handle);
    if (!h) return -1;
    if (!out || out_length < 5) return -2;
    h->gen.climate_at(block_x, block_z, out);
    return 0;
}

int32_t jpe_terrain_stats(uint64_t handle, JpeTerrainStats *out) {
    Handle *h = lookup(handle);
    if (!h) return -1;
    if (!out) return -2;
    out->chunks_generated = h->chunks.load(std::memory_order_relaxed);
    out->blocks_written = h->blocks.load(std::memory_order_relaxed);
    out->density_samples = h->density_samples.load(std::memory_order_relaxed);
    out->noise_samples = h->noise_samples.load(std::memory_order_relaxed);
    out->carve_cells = h->carve_cells.load(std::memory_order_relaxed);
    out->total_nanos = h->nanos.load(std::memory_order_relaxed);
    out->threads = h->pool ? h->pool->size() : 0;
    out->biome_count = static_cast<uint32_t>(jpe::BIOME_COUNT);
    return 0;
}

const char *jpe_terrain_block_name(uint16_t id) {
    if (id >= jpe::BLOCK_COUNT) return "unknown";
    return kBlockNames[id];
}

const char *jpe_terrain_biome_name(int32_t id) {
    return jpe::biome_def(id).name;
}

}  // extern "C"
