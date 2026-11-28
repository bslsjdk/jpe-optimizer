#ifndef JPE_TERRAIN_HANDLE_H
#define JPE_TERRAIN_HANDLE_H

/*
 * Bridge between the v1 ABI translation unit and the streaming layer.
 *
 * Handle is an implementation detail of terrain_api.cpp, and the streaming
 * functions live in a separate translation unit so that a v1-only build can
 * simply omit that file. Rather than promoting the whole Handle layout into a
 * public header -- which would freeze the implementation for no benefit -- this
 * header exposes exactly the two things the streaming layer needs: a way to
 * reach the generator and the cache behind an opaque handle id.
 */

#include <cstdint>

#include "chunk_cache.h"

namespace jpe {

/// Result of resolving a handle. `gen` and `cache` are owned by the handle map
/// and live until jpe_terrain_destroy; they must not be retained across calls
/// that could destroy the handle.
struct HandleView {
    Generator *gen = nullptr;
    ChunkCache *cache = nullptr;
    /// Chunk size in bytes for this handle's configuration.
    uint64_t bytes_per_chunk = 0;
};

/// Resolves a handle id. Returns false for 0 or an unknown id.
/// The cache pointer is never null for a live handle.
bool resolve_handle(uint64_t id, HandleView *out);

}  // namespace jpe

#endif  // JPE_TERRAIN_HANDLE_H