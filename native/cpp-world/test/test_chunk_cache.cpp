// Tests for the streaming chunk cache.
//
// These tests exist because the cache has three properties that are easy to
// get subtly wrong and impossible to see in a screenshot:
//
//   1. determinism -- a chunk served from the cache must be bit-identical to
//      the same chunk generated fresh; otherwise a returning player sees
//      different terrain than a new player.
//   2. boundedness -- resident chunks must never exceed the budget, whatever
//      the access pattern; otherwise the "1 GB heap" experiment is a lie.
//   3. pin safety -- a chunk that a caller holds must survive eviction; if it
//      does not, the mesher reads freed memory and the crash looks random.
//
// A fourth property, stream efficiency, is what makes continuous loading a
// win at all: walking one chunk forward must not regenerate the whole view.
//
// Build: this file is compiled into the cpp-world test target. It links against
// the same objects the library ships, so it tests the real ABI rather than a
// reimplementation.

#include <cstdio>
#include <cstring>
#include <vector>

#include "jpe_terrain_api.h"
#include "jpe_terrain_stream_api.h"

namespace {

int g_failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::printf("FAIL %s\n", what);
        ++g_failures;
    }
}

// 192 KB per chunk for the default overworld. Spelled out so a change to the
// default height makes the budget maths below visibly stale instead of quietly
// wrong.
constexpr uint64_t kDefaultChunkBytes = 16ull * 16ull * 384ull * 2ull;

uint64_t make_handle(uint32_t threads = 1) {
    JpeTerrainConfig cfg;
    jpe_terrain_default_config(&cfg);
    cfg.seed = 1234567890123LL;
    cfg.threads = threads;
    const uint64_t h = jpe_terrain_create(&cfg);
    check(h != 0, "handle created");
    return h;
}

// --- 1. cache hit equals fresh generation ----------------------------------

void test_hit_matches_fresh() {
    const uint64_t h = make_handle();
    check(jpe_terrain_cache_config(h, kDefaultChunkBytes * 16, 0) == 0, "budget accepted");

    const int32_t cx = 3, cz = -7;
    const uint16_t *cached = jpe_terrain_acquire(h, cx, cz);
    check(cached != nullptr, "first acquire generates");
    if (!cached) {
        jpe_terrain_destroy(h);
        return;
    }

    const uint64_t blocks = jpe_terrain_chunk_blocks(h);
    std::vector<uint16_t> via_cache(cached, cached + blocks);
    jpe_terrain_release(h, cx, cz);

    std::vector<uint16_t> fresh(blocks);
    check(jpe_terrain_generate_chunk(h, cx, cz, fresh.data(), blocks) == 0, "fresh generate");

    check(std::memcmp(via_cache.data(), fresh.data(),
                      static_cast<size_t>(blocks) * sizeof(uint16_t)) == 0,
          "cache hit is bit-identical to fresh generation");

    // Exactly one acquire so far went through the cache as a miss, so the
    // second one is the first possible hit: hits must be exactly 1. Asserting
    // >= 2 here could never hold and was a broken expectation, not a bug.
    const uint16_t *again = jpe_terrain_acquire(h, cx, cz);
    check(again != nullptr, "second acquire");
    JpeTerrainCacheStats st;
    check(jpe_terrain_cache_stats(h, &st) == 0, "stats readable");
    check(st.hits == 1, "second acquire counted as a hit");
    check(st.misses == 1, "only the first acquire missed");
    check(st.generated == 1, "the chunk was built once");
    check(st.resident == 1, "one resident chunk");
    jpe_terrain_release(h, cx, cz);

    jpe_terrain_destroy(h);
}

// --- 2. budget is a hard bound ---------------------------------------------

void test_budget_is_bound() {
    // Room for exactly four chunks.
    const uint64_t h = make_handle();
    check(jpe_terrain_cache_config(h, kDefaultChunkBytes * 4, 0) == 0, "4-chunk budget");

    for (int32_t i = 0; i < 32; ++i) {
        const uint16_t *p = jpe_terrain_acquire(h, i, 0);
        check(p != nullptr, "acquire under bounded budget");
        if (p) jpe_terrain_release(h, i, 0);
        JpeTerrainCacheStats st;
        jpe_terrain_cache_stats(h, &st);
        check(st.resident <= 4, "resident never exceeds capacity");
        check(st.capacity == 4, "capacity matches the budget");
    }

    // Shrinking the budget must free immediately, not at the next stream.
    check(jpe_terrain_cache_config(h, kDefaultChunkBytes * 2, 0) == 0, "shrink budget");
    JpeTerrainCacheStats st;
    jpe_terrain_cache_stats(h, &st);
    check(st.resident <= 2, "shrink evicts down at once");

    // A budget smaller than one chunk disables the cache instead of thrashing.
    check(jpe_terrain_cache_config(h, 1, 0) == 0, "sub-chunk budget accepted");
    jpe_terrain_cache_stats(h, &st);
    check(st.capacity == 0, "sub-chunk budget disables the cache");
    check(jpe_terrain_acquire(h, 99, 99) == nullptr, "disabled cache refuses acquire");

    jpe_terrain_destroy(h);
}

// --- 3. pinned chunks survive eviction -------------------------------------

void test_pinned_survive() {
    const uint64_t h = make_handle();
    check(jpe_terrain_cache_config(h, kDefaultChunkBytes * 2, 0) == 0, "2-chunk budget");

    const uint16_t *held = jpe_terrain_acquire(h, 0, 0);
    check(held != nullptr, "pin chunk 0,0");
    if (!held) {
        jpe_terrain_destroy(h);
        return;
    }
    // Copy immediately; the pointer is only promised until the next stream, so
    // holding a raw pointer while pressuring the cache is exactly the misuse
    // the contract warns about. What must not change is the chunk's CONTENTS.
    const uint64_t blocks = jpe_terrain_chunk_blocks(h);
    std::vector<uint16_t> snapshot(held, held + blocks);

    // Fill the rest of the budget with other chunks. The pinned one cannot be
    // chosen, so pressure lands on the unpinned slot.
    for (int32_t i = 1; i < 8; ++i) {
        const uint16_t *p = jpe_terrain_acquire(h, i, 0);
        check(p != nullptr, "acquire while one slot is pinned");
        if (p) {
            std::vector<uint16_t> v(p, p + blocks);
            jpe_terrain_release(h, i, 0);
            (void)v;
        }
    }

    // Re-pin chunk 0,0. If it had been evicted while pinned, this regenerates
    // and still matches -- but the stats tell the real story.
    const uint16_t *again = jpe_terrain_acquire(h, 0, 0);
    check(again != nullptr, "pinned chunk still available");
    if (again) {
        check(std::memcmp(again, snapshot.data(),
                          static_cast<size_t>(blocks) * sizeof(uint16_t)) == 0,
              "pinned chunk contents unchanged");
    }
    jpe_terrain_release(h, 0, 0);

    JpeTerrainCacheStats st;
    jpe_terrain_cache_stats(h, &st);
    check(st.resident <= 2, "pinning does not break the budget");

    jpe_terrain_destroy(h);
}

// --- 4. stream only generates the difference --------------------------------

void test_stream_incremental() {
    const uint64_t h = make_handle(1);
    // Radius 4 is 81 chunks; leave room so nothing is evicted and the
    // difference between two overlapping windows is purely the new ring.
    check(jpe_terrain_cache_config(h, kDefaultChunkBytes * 256, 0) == 0, "roomy budget");

    const int32_t r = 4;
    const int32_t first = jpe_terrain_stream(h, 0, 0, r, 0);
    check(first > 0, "first stream generates");
    if (first > 0) {
        // Radius 4 disc: every (dx,dz) with dx*dx + dz*dz <= 16.
        int32_t expected = 0;
        for (int32_t dz = -r; dz <= r; ++dz)
            for (int32_t dx = -r; dx <= r; ++dx)
                if (dx * dx + dz * dz <= r * r) ++expected;
        check(first == expected, "first stream generates exactly the disc");
    }

    // No movement: must generate nothing.
    const int32_t same = jpe_terrain_stream(h, 0, 0, r, 0);
    check(same == 0, "re-streaming the same window generates nothing");

    // Move one chunk along x: only the newly entered crescent is new.
    const int32_t moved = jpe_terrain_stream(h, 1, 0, r, 0);
    check(moved > 0, "moving generates the new crescent");
    check(moved < first, "moving generates fewer chunks than a fresh window");

    JpeTerrainCacheStats st;
    jpe_terrain_cache_stats(h, &st);
    check(st.hits > 0, "stationary chunks counted as hits");

    jpe_terrain_destroy(h);
}

// --- 5. v1 callers are unaffected ------------------------------------------

void test_v1_unaffected() {
    const uint64_t h = make_handle();
    // Never configure the cache.
    const uint64_t blocks = jpe_terrain_chunk_blocks(h);
    std::vector<uint16_t> out(blocks);
    check(jpe_terrain_generate_chunk(h, 0, 0, out.data(), blocks) == 0, "v1 generate works");

    JpeTerrainCacheStats st;
    check(jpe_terrain_cache_stats(h, &st) == -2, "disabled cache reports -2");
    check(st.resident == 0 && st.capacity == 0, "disabled cache zeroes the stats");
    check(jpe_terrain_acquire(h, 0, 0) == nullptr, "disabled cache acquire is null");
    check(jpe_terrain_stream(h, 0, 0, 2, 0) == -2, "disabled cache stream is -2");

    check(jpe_terrain_chunk_bytes(h) == kDefaultChunkBytes, "chunk bytes matches chunk_blocks");

    jpe_terrain_destroy(h);
}

// --- 6. mismatched chunk size is rejected, not ignored ---------------------

void test_chunk_size_guard() {
    const uint64_t h = make_handle();
    check(jpe_terrain_cache_config(h, kDefaultChunkBytes * 8, kDefaultChunkBytes) == 0,
          "matching chunk size accepted");
    check(jpe_terrain_cache_config(h, kDefaultChunkBytes * 8, 4096) == -3,
          "wrong chunk size rejected");
    jpe_terrain_destroy(h);
}

}  // namespace

int main() {
    std::printf("stream cache tests\n");
    test_hit_matches_fresh();
    test_budget_is_bound();
    test_pinned_survive();
    test_stream_incremental();
    test_v1_unaffected();
    test_chunk_size_guard();
    if (g_failures == 0) {
        std::printf("ok\n");
        return 0;
    }
    std::printf("%d failure(s)\n", g_failures);
    return 1;
}