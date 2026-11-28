#include "chunk_cache.h"

#include <algorithm>
#include <limits>

namespace jpe {

namespace {

/// Nodes visited per eviction sweep before falling back to a full scan. The
/// cache holds a few thousand chunks at most, so a full scan is not expensive,
/// but the common case is that the oldest node is already a good victim.
constexpr int kEvictScanLimit = 64;

}  // namespace

void ChunkCache::set_budget(uint64_t bytes, uint64_t bytes_per_chunk) {
    bytes_per_chunk_ = bytes_per_chunk;
    if (bytes_per_chunk == 0) {
        capacity_ = 0;
        clear();
        return;
    }
    capacity_ = bytes / bytes_per_chunk;
    // A budget below one chunk is not "a small cache", it is a cache that can
    // never serve a hit. Say so by staying disabled instead of thrashing.
    if (capacity_ == 0) {
        clear();
        return;
    }
    // Shrinking the budget must free memory immediately rather than at the
    // next stream_window, otherwise the host's accounting is a lie.
    while (chunks_.size() > capacity_) {
        CachedChunk *victim = pick_victim();
        if (!victim) break;
        evict(victim);
    }
}

const uint16_t *ChunkCache::acquire(Generator &gen, ChunkKey key) {
    if (!enabled()) return nullptr;
    const uint64_t pk = key.packed();
    auto it = chunks_.find(pk);
    if (it != chunks_.end()) {
        it->second.last_use = ++clock_;
        ++it->second.pins;
        ++stats_.hits;
        return it->second.blocks.data();
    }
    ++stats_.misses;
    CachedChunk *chunk = insert(gen, key);
    if (!chunk) return nullptr;
    chunk->last_use = ++clock_;
    ++chunk->pins;
    return chunk->blocks.data();
}

void ChunkCache::release(ChunkKey key) {
    auto it = chunks_.find(key.packed());
    if (it == chunks_.end()) return;
    if (it->second.pins > 0) --it->second.pins;
}

CachedChunk *ChunkCache::insert(Generator &gen, ChunkKey key) {
    if (capacity_ == 0) return nullptr;
    // Make room first. If room cannot be made (every slot pinned), the caller
    // gets nullptr rather than the cache silently growing past its budget.
    while (chunks_.size() >= capacity_) {
        CachedChunk *victim = pick_victim();
        if (!victim) {
            ++stats_.pin_rejections;
            return nullptr;
        }
        evict(victim);
    }

    const uint64_t pk = key.packed();
    CachedChunk &slot = chunks_[pk];
    slot.key = key;
    slot.blocks.resize(static_cast<size_t>(gen.config().chunk_blocks()));
    slot.pins = 0;
    slot.last_use = 0;

    Scratch scratch;
    gen.generate_chunk(key.x, key.z, slot.blocks.data(), scratch);
    ++stats_.generated;
    return &slot;
}

CachedChunk *ChunkCache::pick_victim() {
    CachedChunk *best = nullptr;
    uint64_t best_use = std::numeric_limits<uint64_t>::max();
    int scanned = 0;
    for (auto &entry : chunks_) {
        CachedChunk &c = entry.second;
        if (c.pins > 0) continue;
        if (c.last_use < best_use) {
            best_use = c.last_use;
            best = &c;
            if (best_use == 0) break;  // never accessed: already the best case
        }
        if (++scanned >= kEvictScanLimit) break;
    }
    return best;
}

void ChunkCache::evict(CachedChunk *chunk) {
    if (!chunk) return;
    const uint64_t pk = chunk->key.packed();
    auto it = chunks_.find(pk);
    if (it == chunks_.end()) return;
    chunks_.erase(it);
    ++stats_.evictions;
}

void ChunkCache::clear() {
    chunks_.clear();
    clock_ = 0;
}

uint32_t ChunkCache::stream_window(Generator &gen, int32_t center_x, int32_t center_z,
                                   int32_t radius, int32_t prefetch_margin) {
    if (!enabled()) return 0;
    if (radius < 0) radius = 0;
    if (prefetch_margin < 0) prefetch_margin = 0;

    // A view shift invalidates every outstanding pointer. Clearing pins first
    // means the eviction below can always make progress, and it matches the
    // documented contract: re-acquire after a stream update.
    for (auto &entry : chunks_) entry.second.pins = 0;

    const int32_t outer = radius + prefetch_margin;
    const int32_t outer_sq = outer * outer;
    const int32_t inner_sq = radius * radius;

    uint32_t generated = 0;

    // Prefetch ring: chunks just outside the visible radius are generated so a
    // moving view does not hit a generation cliff at the edge of the screen.
    for (int32_t dz = -outer; dz <= outer; ++dz) {
        for (int32_t dx = -outer; dx <= outer; ++dx) {
            const int32_t dist_sq = dx * dx + dz * dz;
            if (dist_sq > outer_sq) continue;
            ChunkKey key{center_x + dx, center_z + dz};
            const uint64_t pk = key.packed();
            auto it = chunks_.find(pk);
            if (it != chunks_.end()) {
                // Visible chunks stay "recently used"; the prefetch ring is
                // colder than the visible set by design.
                if (dist_sq <= inner_sq) {
                    it->second.last_use = ++clock_;
                    ++stats_.hits;
                }
                continue;
            }
            CachedChunk *chunk = insert(gen, key);
            if (!chunk) continue;  // budget too small: keep going, do not fail
            chunk->last_use = (dist_sq <= inner_sq) ? ++clock_ : clock_;
            ++generated;
        }
    }

    // Evict down to the budget. Chunks outside the visible radius go first,
    // then LRU order decides among the rest.
    auto outside_first = [&](const CachedChunk &a, const CachedChunk &b) {
        const int64_t adx = a.key.x - center_x;
        const int64_t adz = a.key.z - center_z;
        const int64_t bdx = b.key.x - center_x;
        const int64_t bdz = b.key.z - center_z;
        const bool a_out = adx * adx + adz * adz > inner_sq;
        const bool b_out = bdx * bdx + bdz * bdz > inner_sq;
        if (a_out != b_out) return a_out;      // outside sorts first
        return a.last_use < b.last_use;        // then coldest first
    };

    std::vector<CachedChunk *> order;
    order.reserve(chunks_.size());
    for (auto &entry : chunks_) order.push_back(&entry.second);
    std::sort(order.begin(), order.end(), outside_first);

    for (CachedChunk *c : order) {
        if (chunks_.size() <= capacity_) break;
        evict(c);
    }

    stats_.streamed += generated;
    return generated;
}

}  // namespace jpe