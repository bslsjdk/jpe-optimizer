#include "jpe_terrain_stream_api.h"

#include "chunk_cache.h"
#include "jpe_terrain_handle.h"

namespace {

using jpe::HandleView;

/// Small helper so each entry point can fail the same way.
HandleView *view_of(uint64_t handle, HandleView *storage) {
    if (handle == 0) return nullptr;
    if (!jpe::resolve_handle(handle, storage)) return nullptr;
    return storage;
}

}  // namespace

extern "C" {

uint32_t jpe_terrain_stream_abi_version(void) { return JPE_TERRAIN_STREAM_ABI_VERSION; }

uint64_t jpe_terrain_chunk_bytes(uint64_t handle) {
    HandleView view;
    if (!view_of(handle, &view)) return 0;
    return view.bytes_per_chunk;
}

int32_t jpe_terrain_cache_config(uint64_t handle, uint64_t bytes, uint64_t bytes_per_chunk) {
    HandleView view;
    if (!view_of(handle, &view)) return -1;

    uint64_t per_chunk = bytes_per_chunk;
    if (per_chunk == 0) {
        per_chunk = view.bytes_per_chunk;
    } else if (per_chunk != view.bytes_per_chunk) {
        // The host passed a chunk size that does not match this handle's
        // height. Accepting it would make the budget silently mean something
        // other than what the caller asked for, which is exactly the kind of
        // bug that surfaces as an OOM three minutes into a session.
        return -3;
    }
    view.cache->set_budget(bytes, per_chunk);
    return 0;
}

int32_t jpe_terrain_stream(uint64_t handle, int32_t chunk_x, int32_t chunk_z, int32_t radius,
                           int32_t prefetch_margin) {
    HandleView view;
    if (!view_of(handle, &view)) return -1;
    if (!view.cache->enabled()) return -2;
    if (radius < 0) return -3;
    if (prefetch_margin < 0) prefetch_margin = 0;

    const uint32_t generated =
        view.cache->stream_window(*view.gen, chunk_x, chunk_z, radius, prefetch_margin);
    if (generated > 0x7FFFFFFFu) return 0x7FFFFFFF;
    return static_cast<int32_t>(generated);
}

const uint16_t *jpe_terrain_acquire(uint64_t handle, int32_t chunk_x, int32_t chunk_z) {
    HandleView view;
    if (!view_of(handle, &view)) return nullptr;
    if (!view.cache->enabled()) return nullptr;
    jpe::ChunkKey key;
    key.x = chunk_x;
    key.z = chunk_z;
    return view.cache->acquire(*view.gen, key);
}

void jpe_terrain_release(uint64_t handle, int32_t chunk_x, int32_t chunk_z) {
    HandleView view;
    if (!view_of(handle, &view)) return;
    jpe::ChunkKey key;
    key.x = chunk_x;
    key.z = chunk_z;
    view.cache->release(key);
}

int32_t jpe_terrain_cache_stats(uint64_t handle, JpeTerrainCacheStats *out) {
    if (!out) return -2;
    // Zero first so a caller can print unconditionally even on error.
    out->resident = 0;
    out->capacity = 0;
    out->budget_bytes = 0;
    out->bytes_per_chunk = 0;
    out->hits = 0;
    out->misses = 0;
    out->evictions = 0;
    out->generated = 0;
    out->streamed = 0;
    out->pin_rejections = 0;

    HandleView view;
    if (!view_of(handle, &view)) return -1;
    if (!view.cache->enabled()) return -2;

    const jpe::CacheStats &s = view.cache->stats();
    out->resident = view.cache->resident();
    out->capacity = view.cache->capacity();
    out->budget_bytes = view.cache->capacity() * view.bytes_per_chunk;
    out->bytes_per_chunk = view.bytes_per_chunk;
    out->hits = s.hits;
    out->misses = s.misses;
    out->evictions = s.evictions;
    out->generated = s.generated;
    out->streamed = s.streamed;
    out->pin_rejections = s.pin_rejections;
    return 0;
}

void jpe_terrain_cache_clear(uint64_t handle) {
    HandleView view;
    if (!view_of(handle, &view)) return;
    view.cache->clear();
}

}  // extern "C"