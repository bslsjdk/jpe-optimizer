// Standalone benchmark / sanity harness for the native terrain generator.
//
//   g++ -O2 -std=c++17 -I../include -I../src bench_terrain.cpp ../src/*.cpp -o bench_terrain
//   ./bench_terrain [seed] [chunks] [threads]
//
// Prints a block histogram, a heightmap, and chunks/second. No dependencies.

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <vector>

#include "jpe_terrain_api.h"

static const char *shade_for(uint16_t id) {
    switch (id) {
        case 0: return " ";
        case 11: return "~";
        case 16: return "*";
        case 15: return "^";
        case 2: return ".";
        case 6: return ":";
        case 9: return ",";
        case 12: return "!";
        case 13: return "#";
        case 14: return "d";
        default: return "#";
    }
}

int main(int argc, char **argv) {
    const int64_t seed = argc > 1 ? atoll(argv[1]) : 20261128;
    const int chunks = argc > 2 ? atoi(argv[2]) : 256;
    const uint32_t threads = argc > 3 ? static_cast<uint32_t>(atoi(argv[3])) : 0;

    JpeTerrainConfig cfg;
    jpe_terrain_default_config(&cfg);
    cfg.seed = seed;
    cfg.threads = threads;
    if (argc > 4) cfg.stages = static_cast<uint32_t>(strtoul(argv[4], nullptr, 0));

    const uint64_t h = jpe_terrain_create(&cfg);
    if (!h) {
        std::printf("create failed\n");
        return 1;
    }
    const uint64_t per_chunk = jpe_terrain_chunk_blocks(h);
    std::printf("abi=%u chunk_blocks=%llu\n", jpe_terrain_abi_version(),
                static_cast<unsigned long long>(per_chunk));

    // Square-ish region.
    const int side = static_cast<int>(std::ceil(std::sqrt(static_cast<double>(chunks))));
    std::vector<int32_t> coords;
    std::vector<uint16_t> data(static_cast<size_t>(per_chunk) * chunks);
    for (int i = 0; i < chunks; ++i) {
        coords.push_back(i % side);
        coords.push_back(i / side);
    }

    // Warm-up (one chunk) then timed batch.
    jpe_terrain_generate_chunk(h, 0, 0, data.data(), per_chunk);
    std::fill(data.begin(), data.end(), 0);

    const auto t0 = std::chrono::steady_clock::now();
    const int rc = jpe_terrain_generate_chunks(h, coords.data(), chunks, data.data(),
                                              static_cast<uint64_t>(data.size()), threads);
    const auto t1 = std::chrono::steady_clock::now();
    if (rc != 0) {
        std::printf("generate failed rc=%d\n", rc);
        return 1;
    }
    const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();

    JpeTerrainStats st;
    jpe_terrain_stats(h, &st);
    std::printf("threads=%u chunks=%d wall=%.2f ms  %.2f ms/chunk  %.0f chunks/s  %.1f Mblocks/s\n",
                st.threads, chunks, ms, ms / chunks, chunks * 1000.0 / ms,
                (double)chunks * per_chunk / (ms * 1000.0));

    // Block histogram.
    std::map<uint16_t, uint64_t> hist;
    for (uint16_t v : data) hist[v]++;
    std::printf("block histogram:\n");
    for (auto &kv : hist) {
        std::printf("  %-14s %8.3f%%\n", jpe_terrain_block_name(kv.first),
                    100.0 * kv.second / (double)data.size());
    }

    // Heightmap of an 8x8 chunk region (128x128 columns), coarse ASCII.
    {
        constexpr int R = 8;
        std::vector<int32_t> hm(static_cast<size_t>(R) * 16 * R * 16, 0);
        for (int cz = 0; cz < R; ++cz) {
            for (int cx = 0; cx < R; ++cx) {
                int32_t local[256];
                jpe_terrain_heightmap(h, cx, cz, local, 256);
                for (int z = 0; z < 16; ++z) {
                    for (int x = 0; x < 16; ++x) {
                        hm[(static_cast<size_t>(cz * 16 + z)) * (R * 16) + (cx * 16 + x)] =
                            local[z * 16 + x];
                    }
                }
            }
        }
        std::printf("heightmap (%dx%d columns, sea=%d):\n", R * 16, R * 16, cfg.sea_level);
        for (int z = 0; z < R * 16; ++z) {
            for (int x = 0; x < R * 16; ++x) {
                const int32_t top = hm[static_cast<size_t>(z) * (R * 16) + x];
                char c;
                if (top < cfg.sea_level - 1) c = '~';
                else if (top < cfg.sea_level + 1) c = '.';
                else if (top < 70) c = '-';
                else if (top < 80) c = '+';
                else if (top < 95) c = '*';
                else if (top < 115) c = '#';
                else c = '^';
                std::putchar(c);
            }
            std::putchar('\n');
        }
    }

    // Column info sample.
    JpeTerrainColumnInfo info[256];
    jpe_terrain_column_info(h, 0, 0, info, 256);
    std::printf("chunk(0,0) biome sample: %s  surface_y[0]=%d water_top[0]=%d solid[0]=%u\n",
                jpe_terrain_biome_name(info[0].biome), info[0].surface_y, info[0].water_top_y,
                info[0].solid_blocks);

    // Content checksum, so a SIMD/scalar build can be compared byte for byte.
    {
        uint64_t fnv = 0xcbf29ce484222325ull;
        for (uint16_t v : data) {
            fnv ^= v;
            fnv *= 0x100000001b3ull;
        }
        std::printf("checksum=%016llx\n", static_cast<unsigned long long>(fnv));
    }

    // Determinism: same chunk alone vs inside a batch.
    std::vector<uint16_t> single(per_chunk);
    jpe_terrain_generate_chunk(h, 3, 5, single.data(), per_chunk);
    const int idx = 5 * side + 3;
    if (idx < chunks) {
        const uint16_t *batched = data.data() + per_chunk * idx;
        const bool same = std::memcmp(single.data(), batched, per_chunk * sizeof(uint16_t)) == 0;
        std::printf("determinism (single vs batch): %s\n", same ? "identical" : "MISMATCH");
    }

    jpe_terrain_destroy(h);
    return 0;
}
