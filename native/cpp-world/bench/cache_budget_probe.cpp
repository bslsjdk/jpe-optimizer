// Heap-budget probe for the streaming chunk cache.
//
// Answers one question: on a 1G / 2G heap, how much cache budget can we
// actually afford, and what view radius does that buy?
//
//   g++ -O2 -std=c++20 -Iinclude -Isrc \
//       src/generator.cpp src/terrain_api.cpp src/chunk_cache.cpp \
//       src/terrain_stream_api.cpp bench/cache_budget_probe.cpp \
//       -o cache_budget_probe -lpthread
//   ./cache_budget_probe <budget_mb> <radius> [moves]
//
// The loop is what a real server does: move the view centre, stream the new
// window, touch every chunk in it, then read the counters. Nothing is copied
// out, so peak RSS stays close to the cache's own footprint -- which is the
// number a host actually has to budget for.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include <sys/resource.h>

#include "jpe_terrain_api.h"
#include "jpe_terrain_stream_api.h"

static long peak_rss_kb() {
    struct rusage ru;
    if (getrusage(RUSAGE_SELF, &ru) != 0) return -1;
    return ru.ru_maxrss;
}

int main(int argc, char **argv) {
    const uint64_t budget_mb = argc > 1 ? strtoull(argv[1], nullptr, 10) : 256;
    const int32_t radius = argc > 2 ? (int32_t)atoi(argv[2]) : 20;
    const int moves = argc > 3 ? atoi(argv[3]) : 8;

    JpeTerrainConfig cfg;
    jpe_terrain_default_config(&cfg);
    cfg.seed = 20261128;
    cfg.threads = 0;  // single threaded: we want the cache cost, not the speedup
    const uint64_t h = jpe_terrain_create(&cfg);
    if (!h) {
        std::printf("FAIL create\n");
        return 1;
    }

    const uint64_t per_chunk = jpe_terrain_chunk_bytes(h);
    const uint64_t bytes = budget_mb * 1024ull * 1024ull;
    std::printf("stream_abi=%u chunk_bytes=%llu budget_mb=%llu radius=%d moves=%d\n",
                jpe_terrain_stream_abi_version(),
                (unsigned long long)per_chunk,
                (unsigned long long)budget_mb, radius, moves);
    std::printf("cache_config rc=%d\n", jpe_terrain_cache_config(h, bytes, 0));

    JpeTerrainCacheStats want;
    jpe_terrain_cache_stats(h, &want);
    std::printf("planned capacity=%llu chunks (budget=%llu bytes)\n",
                (unsigned long long)want.capacity,
                (unsigned long long)want.budget_bytes);

    // Radius r holds (2r+1)^2 chunks; report whether the budget even covers it.
    const uint64_t window = (uint64_t)(2 * radius + 1) * (uint64_t)(2 * radius + 1);
    std::printf("window=%llu chunks window_bytes=%llu fits=%s\n",
                (unsigned long long)window,
                (unsigned long long)(window * per_chunk),
                window <= want.capacity ? "yes" : "NO");

    uint64_t sum = 0;
    int checks = 0;
    int failed = 0;
    for (int m = 0; m < moves; ++m) {
        // Walk outward in a spiral so every move lands on mostly new chunks.
        const int32_t cx = (int32_t)(m % 4) * radius, cz = (int32_t)(m / 4) * radius;
        const int32_t gen = jpe_terrain_stream(h, cx, cz, radius, 1);
        if (gen < 0) {
            std::printf("FAIL stream rc=%d\n", gen);
            ++failed;
            break;
        }
        for (int32_t dx = -radius; dx <= radius; ++dx) {
            for (int32_t dz = -radius; dz <= radius; ++dz) {
                const uint16_t *blocks = jpe_terrain_acquire(h, cx + dx, cz + dz);
                if (!blocks) {
                    std::printf("FAIL acquire(%d,%d)\n", cx + dx, cz + dz);
                    ++failed;
                    continue;
                }
                sum += blocks[0];
                ++checks;
                jpe_terrain_release(h, cx + dx, cz + dz);
            }
        }
        JpeTerrainCacheStats s;
        const int rc = jpe_terrain_cache_stats(h, &s);
        const long rss = peak_rss_kb();
        std::printf("move=%d gen=%d resident=%llu cap=%llu hits=%llu miss=%llu "
                    "evict=%llu gen_total=%llu streamed=%llu reject=%llu "
                    "rss_mb=%ld\n",
                    m, gen, (unsigned long long)s.resident,
                    (unsigned long long)s.capacity, (unsigned long long)s.hits,
                    (unsigned long long)s.misses, (unsigned long long)s.evictions,
                    (unsigned long long)s.generated, (unsigned long long)s.streamed,
                    (unsigned long long)s.pin_rejections, rss < 0 ? -1 : rss / 1024);
        if (rc != 0) {
            std::printf("FAIL stats rc=%d\n", rc);
            ++failed;
        }
        if (s.resident > s.capacity) {
            std::printf("FAIL resident %llu exceeds capacity %llu\n",
                        (unsigned long long)s.resident,
                        (unsigned long long)s.capacity);
            ++failed;
        }
    }

    JpeTerrainCacheStats fin;
    jpe_terrain_cache_stats(h, &fin);
    const long rss = peak_rss_kb();
    std::printf("RESULT budget_mb=%llu radius=%d capacity=%llu resident=%llu "
                "checks=%d failed=%d peak_rss_mb=%ld sum=%llu\n",
                (unsigned long long)budget_mb, radius,
                (unsigned long long)fin.capacity, (unsigned long long)fin.resident,
                checks, failed, rss < 0 ? -1 : rss / 1024,
                (unsigned long long)sum);

    jpe_terrain_destroy(h);
    return failed == 0 ? 0 : 2;
}
