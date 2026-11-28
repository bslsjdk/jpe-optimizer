#ifndef JPE_TERRAIN_CACHE_H
#define JPE_TERRAIN_CACHE_H

/*
 * Chunk cache -- the runtime layer between the pure generator and a
 * continuously streamed world.
 *
 * The generator is pure: coordinates in, blocks out, and it owns nothing per
 * chunk. That is right for a benchmark, but it is not what a running world
 * needs. Walking back over terrain the player already saw must not regenerate
 * it, and a view that moves forward must generate only the chunks that just
 * entered the view, not the whole view again.
 *
 * Rules this layer implements:
 *   - chunks stay resident until the memory budget is full, not until a call
 *     returns;
 *   - eviction is LRU by last access, so the working set follows the player;
 *   - a chunk that a caller has pinned is invisible to eviction, so a mesher
 *     can never watch its data disappear mid-frame;
 *   - nothing is generated speculatively unless the caller asks for it, so
 *     latency per frame stays a decision the caller makes.
 *
 * Memory is owned here. acquire returns a pointer into that memory; callers
 * never free it. release drops the pin, and the slot is reclaimed later, when
 * it is both unpinned and chosen by LRU.
 *
 * Threading: a cache is not internally synchronised. The handle that owns it
 * is documented as single-caller-thread, and batch generation uses the
 * generator's own pool for the per-chunk work only.
 */

#include <cstdint>
#include <unordered_map>
#include <vector>

#include "jpe_terrain_internal.h"

namespace jpe {

// ---------------------------------------------------------------------------
// Keys and slots
// ---------------------------------------------------------------------------

/// A chunk coordinate packed into one 64-bit value. Two halves would work too,
/// but a one-word compare is cheaper, and this is on the hot path.
struct ChunkKey {
    int32_t x = 0;
    int32_t z = 0;

    uint64_t packed() const {
        return (static_cast<uint64_t>(static_cast<uint32_t>(z)) << 32) |
               static_cast<uint64_t>(static_cast<uint32_t>(x));
    }

    bool operator==(const ChunkKey &o) const { return x == o.x && z == o.z; }
    bool operator!=(const ChunkKey &o) const { return !(*this == o); }
};

struct ChunkKeyHash {
    size_t operator()(const ChunkKey &k) const noexcept {
        // Murmur-style finaliser over the packed fields. Chunk coordinates are
        // small and sequential in practice, so the avalanche matters: a plain
        // xor of x and z collides for every diagonal line of chunks.
        uint64_t h = k.packed();
        h ^= h >> 33;
        h *= 0xff51afd7ed558874ull;
        h ^= h >> 33;
        h *= 0xff51afd7ed558874ull;
        h ^= h >> 33;
        return static_cast<size_t>(h);
    }
};

/// One resident chunk: its blocks, when it was last used, and how many callers
/// currently hold a pointer into it. A pinned chunk is never evicted.
struct CachedChunk {
    ChunkKey key;
    std::vector<uint16_t> blocks;
    uint64_t last_use = 0;
    uint32_t pins = 0;
};

/// Counters a host can surface without instrumenting the streaming loop.
struct CacheStats {
    uint64_t hits = 0;
    uint64_t misses = 0;
    uint64_t evictions = 0;
    uint64_t generated = 0;
    uint64_t streamed = 0;
    uint64_t pin_rejections = 0;
};

// ---------------------------------------------------------------------------
// ChunkCache
// ---------------------------------------------------------------------------

class ChunkCache {
public:
    /// Sets the resident budget in bytes. 0 disables the cache, which is what
    /// the direct generate-chunk path does.
    void set_budget(uint64_t bytes, uint64_t bytes_per_chunk);

    /// Budget in bytes when the caller knows the chunk size; the common case.
    void set_budget_bytes(uint64_t bytes, uint64_t bytes_per_chunk) {
        set_budget(bytes, bytes_per_chunk);
    }

    /// Pins a chunk for reading. A resident chunk is a hit; a missing one is
    /// generated synchronously on the calling thread and inserted.
    /// Returns nullptr when the budget cannot hold even one chunk.
    const uint16_t *acquire(Generator &gen, ChunkKey key);

    /// Drops a pin taken by acquire. The chunk stays resident until evicted.
    /// Releasing a chunk that was evicted in the meantime is harmless.
    void release(ChunkKey key);

    /// Generates every chunk inside (radius + prefetch_margin) around the
    /// centre, then evicts down to the budget with every chunk outside
    /// `radius` as the first candidates. All pins are dropped first: a view
    /// shift invalidates every outstanding pointer, so the caller re-acquires.
    /// Returns the number of chunks that had to be generated.
    uint32_t stream_window(Generator &gen, int32_t center_x, int32_t center_z,
                           int32_t radius, int32_t prefetch_margin);

    /// Drops every chunk. Budget survives.
    void clear();

    bool enabled() const { return capacity_ > 0; }
    uint64_t capacity() const { return capacity_; }
    uint64_t resident() const { return chunks_.size(); }
    const CacheStats &stats() const { return stats_; }

private:
    /// Generates a chunk into a new slot and returns it. The slot is pinned by
    /// the caller, not here.
    CachedChunk *insert(Generator &gen, ChunkKey key);
    /// Picks an unpinned victim. Returns nullptr when every slot is pinned.
    CachedChunk *pick_victim();
    void evict(CachedChunk *chunk);

    std::unordered_map<uint64_t, CachedChunk> chunks_;
    uint64_t capacity_ = 0;
    uint64_t bytes_per_chunk_ = 0;
    uint64_t clock_ = 0;
    CacheStats stats_;
};

}  // namespace jpe

#endif  // JPE_TERRAIN_CACHE_H
