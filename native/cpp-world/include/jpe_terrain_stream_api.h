#ifndef JPE_TERRAIN_STREAM_API_H
#define JPE_TERRAIN_STREAM_API_H

/*
 * JPE native terrain generator -- streaming layer (C ABI v2 addendum).
 *
 * v1 answers one question: "give me the blocks for this chunk". That is enough
 * to generate a world, and it is not enough to *run* one. A running world asks
 * a different set of questions:
 *
 *   "the player moved; which chunks are new?"     -> jpe_terrain_stream
 *   "can I have this chunk's blocks without copying?" -> jpe_terrain_acquire
 *   "how much memory is this costing me?"         -> jpe_terrain_cache_stats
 *
 * Everything here is additive. A v1 caller keeps working unchanged: with no
 * cache configured, acquire returns 0 and stream reports 0, exactly as if this
 * header did not exist. Nothing in the v1 ABI changed shape.
 *
 * Ownership. Chunk memory returned by acquire is owned by the generator handle
 * and stays valid until the next stream, cache-config or destroy call. Callers
 * must not free it and must not hold it across a stream. This is a pin, not a
 * borrow: pin, use, release, and never keep the pointer.
 *
 * Memory budget. bytes_per_chunk is 16 * 16 * height * 2, i.e. 196608 for the
 * default 384-block overworld, so one chunk is about 192 KB. A 256 MB budget
 * holds roughly 1300 chunks (radius ~20) and 512 MB holds roughly 2600
 * (radius ~28). The host is expected to derive the budget from the JVM heap it
 * was given: a 1 GB heap cannot afford a 512 MB cache.
 */

#include <stdint.h>

#include "jpe_terrain_api.h"

#ifdef __cplusplus
extern "C" {
#endif

#define JPE_TERRAIN_STREAM_ABI_VERSION 2u

/* Returns the streaming ABI version this build implements (2). A v1-only
 * build returns 1, and every function below then reports "cache disabled". */
JPE_TERRAIN_API uint32_t jpe_terrain_stream_abi_version(void);

/* Sets the resident chunk budget. bytes == 0 disables the cache: acquire then
 * returns 0 forever and stream does nothing. bytes_per_chunk must match the
 * handle's configuration, or the accounting will be wrong; pass 0 to have the
 * library derive it from the handle's height.
 *
 * Returns 0 on success, -1 on an unknown handle, -3 if bytes_per_chunk is
 * nonzero and disagrees with the handle. Reconfiguring drops resident chunks
 * first, so this is not a per-frame call. */
JPE_TERRAIN_API int32_t jpe_terrain_cache_config(uint64_t handle, uint64_t bytes,
                                                 uint64_t bytes_per_chunk);

/* Advances the resident working set to a view centred on (chunk_x, chunk_z).
 *
 * Configures nothing and generates nothing outside radius + prefetch_margin.
 * Chunks already resident are kept and only their age is refreshed; only the
 * difference between the old window and the new one costs generation. Chunks
 * beyond the budget are evicted coldest-first, with chunks outside `radius`
 * counted as coldest of all.
 *
 * Drops every outstanding pin: pointers from a previous acquire are invalid
 * after this returns, which is what lets eviction always make progress.
 *
 * Returns the number of chunks generated, or a negative error:
 *   -1 unknown handle, -2 cache disabled, -3 negative radius.
 */
JPE_TERRAIN_API int32_t jpe_terrain_stream(uint64_t handle, int32_t chunk_x, int32_t chunk_z,
                                           int32_t radius, int32_t prefetch_margin);

/* Pins a chunk and returns a pointer to its blocks.
 *
 * On a hit this is a hash lookup. On a miss the chunk is generated on the
 * calling thread and inserted, so this call can be as slow as one chunk. That
 * is deliberate: a caller who wants a hit to always be cheap should call
 * jpe_terrain_stream first and acquire afterwards.
 *
 * The pointer is valid until the next stream, cache_config or destroy, or
 * until the chunk is evicted; since eviction never takes a pinned chunk, in
 * practice it lives until the next stream. Release it when done to make it a
 * candidate for eviction.
 *
 * Returns 0 on failure: unknown handle, cache disabled, or a budget too small
 * to hold even one chunk. Overflow-free by construction: the block count is
 * validated at handle creation.
 */
JPE_TERRAIN_API const uint16_t *jpe_terrain_acquire(uint64_t handle, int32_t chunk_x,
                                                    int32_t chunk_z);

/* Drops the pin on a chunk. Harmless if the chunk was already evicted or was
 * never acquired: pins are counts, and they cannot go below zero. */
JPE_TERRAIN_API void jpe_terrain_release(uint64_t handle, int32_t chunk_x, int32_t chunk_z);

/* Streaming-layer counters. Separate from JpeTerrainStats because a host
 * wants "did the player move" and "did the cache work" as different questions. */
typedef struct JpeTerrainCacheStats {
    uint64_t resident;        /* chunks currently held */
    uint64_t capacity;        /* chunks the budget allows */
    uint64_t budget_bytes;    /* as configured */
    uint64_t bytes_per_chunk; /* as configured */
    uint64_t hits;            /* acquire / stream served from the cache */
    uint64_t misses;          /* acquire had to generate */
    uint64_t evictions;       /* chunks dropped to stay in budget */
    uint64_t generated;       /* chunks generated because of this layer */
    uint64_t streamed;        /* chunks generated by stream (subset of generated) */
    uint64_t pin_rejections;  /* acquires refused because every slot was pinned */
} JpeTerrainCacheStats;

/* Fills `out`. Returns 0 on success, -2 when the cache was never configured
 * (out is zeroed in that case so a host can print it unconditionally). */
JPE_TERRAIN_API int32_t jpe_terrain_cache_stats(uint64_t handle, JpeTerrainCacheStats *out);

/* Drops every resident chunk, keeping the budget. Use when leaving a world. */
JPE_TERRAIN_API void jpe_terrain_cache_clear(uint64_t handle);

/* Convenience for a host sizing its budget from its own heap: returns
 * 16 * 16 * height * 2 for the handle, or 0 for an unknown handle. */
JPE_TERRAIN_API uint64_t jpe_terrain_chunk_bytes(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif /* JPE_TERRAIN_STREAM_API_H */