#include "jpe_native_api.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {

struct Entry {
    uint64_t key = 0;
    int32_t fluid_level = 0;
    uint32_t fluid_type = 0;
    bool occupied = false;
};

struct Cache {
    explicit Cache(uint32_t maximum_entries)
        : max_entries(maximum_entries), slots(table_capacity(maximum_entries)) {}

    const uint32_t max_entries;
    std::vector<Entry> slots;
    uint32_t size = 0;
    std::mutex mutex;

    static size_t table_capacity(uint32_t maximum_entries) {
        size_t required = static_cast<size_t>(maximum_entries) * 2U;
        size_t capacity = 2U;
        while (capacity < required) capacity <<= 1U;
        return capacity;
    }
};

std::mutex g_registry_mutex;
std::unordered_map<uint64_t, std::shared_ptr<Cache>> g_caches;
std::atomic<uint64_t> g_next_handle{1};

uint64_t mix64(uint64_t value) noexcept {
    value ^= value >> 30U;
    value *= 0xbf58476d1ce4e5b9ULL;
    value ^= value >> 27U;
    value *= 0x94d049bb133111ebULL;
    return value ^ (value >> 31U);
}

std::shared_ptr<Cache> acquire_cache(uint64_t handle) {
    if (handle == 0) return {};
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const auto found = g_caches.find(handle);
    return found == g_caches.end() ? std::shared_ptr<Cache>{} : found->second;
}

Entry* find_slot(Cache& cache, uint64_t key) noexcept {
    const size_t mask = cache.slots.size() - 1U;
    size_t index = static_cast<size_t>(mix64(key)) & mask;
    for (size_t probe = 0; probe < cache.slots.size(); ++probe) {
        Entry& entry = cache.slots[index];
        if (!entry.occupied || entry.key == key) return &entry;
        index = (index + 1U) & mask;
    }
    return nullptr;
}

} // namespace

extern "C" uint64_t jpe_cpp_aquifer_status_cache_create(uint32_t maximum_entries) {
    if (maximum_entries == 0 || maximum_entries > 1U * 1024U * 1024U) return 0;
    try {
        auto cache = std::make_shared<Cache>(maximum_entries);
        uint64_t handle = g_next_handle.fetch_add(1, std::memory_order_relaxed);
        if (handle == 0) handle = g_next_handle.fetch_add(1, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        g_caches.emplace(handle, std::move(cache));
        return handle;
    } catch (...) {
        return 0;
    }
}

extern "C" int32_t jpe_cpp_aquifer_status_cache_destroy(uint64_t handle) {
    if (handle == 0) return 1;
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    return g_caches.erase(handle) == 1U ? 0 : 1;
}

extern "C" int32_t jpe_cpp_aquifer_status_cache_lookup_batch(
    uint64_t handle,
    const uint64_t* keys,
    uint32_t count,
    JpeAquiferFluidStatus* statuses,
    uint8_t* found
) {
    constexpr uint32_t kMaxBatch = 1024U * 1024U;
    if (keys == nullptr || statuses == nullptr || found == nullptr
        || count == 0 || count > kMaxBatch) return 1;
    auto cache = acquire_cache(handle);
    if (!cache) return 1;

    std::lock_guard<std::mutex> lock(cache->mutex);
    for (uint32_t i = 0; i < count; ++i) {
        Entry* entry = find_slot(*cache, keys[i]);
        if (entry == nullptr || !entry->occupied) {
            found[i] = 0;
            continue;
        }
        statuses[i].fluid_level = entry->fluid_level;
        statuses[i].fluid_type = entry->fluid_type;
        found[i] = 1;
    }
    return 0;
}

extern "C" int32_t jpe_cpp_aquifer_status_cache_insert_batch(
    uint64_t handle,
    const uint64_t* keys,
    const JpeAquiferFluidStatus* statuses,
    uint32_t count
) {
    constexpr uint32_t kMaxBatch = 1024U * 1024U;
    if (keys == nullptr || statuses == nullptr || count == 0 || count > kMaxBatch) return 1;
    auto cache = acquire_cache(handle);
    if (!cache) return 1;

    std::lock_guard<std::mutex> lock(cache->mutex);
    uint32_t staged = 0;
    const auto rollback = [&](uint32_t last_processed) {
        for (uint32_t j = 0; j <= last_processed; ++j) {
            Entry* entry = find_slot(*cache, keys[j]);
            if (entry != nullptr && entry->occupied && entry->pending) {
                *entry = Entry{};
            }
        }
    };

    // Stage only missing keys in the table itself. The per-cache lock prevents
    // other lookups from observing these pending entries. This avoids heap
    // allocations while still making a capacity failure all-or-nothing.
    for (uint32_t i = 0; i < count; ++i) {
        Entry* entry = find_slot(*cache, keys[i]);
        if (entry == nullptr) {
            rollback(i == 0 ? 0 : i - 1);
            return 2;
        }
        if (entry->occupied) {
            // Existing or already-staged key. The first input status wins.
            continue;
        }
        entry->key = keys[i];
        entry->fluid_level = statuses[i].fluid_level;
        entry->fluid_type = statuses[i].fluid_type;
        entry->occupied = true;
        entry->pending = true;
        ++staged;
        if (staged > cache->max_entries - cache->size) {
            rollback(i);
            return 2;
        }
    }

    // Commit the staged entries. Duplicate input keys encounter pending=false
    // after the first occurrence and therefore increment the size only once.
    for (uint32_t i = 0; i < count; ++i) {
        Entry* entry = find_slot(*cache, keys[i]);
        if (entry != nullptr && entry->occupied && entry->pending) {
            entry->pending = false;
            ++cache->size;
        }
    }
    return 0;
}
