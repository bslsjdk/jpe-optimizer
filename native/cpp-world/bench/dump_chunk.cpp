// Dumps one chunk's block ids (raw u16) plus a heightmap, for offline inspection.
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "jpe_terrain_api.h"

int main(int argc, char **argv) {
    JpeTerrainConfig cfg;
    jpe_terrain_default_config(&cfg);
    cfg.seed = argc > 1 ? atoll(argv[1]) : 1;
    cfg.threads = 1;
    uint64_t h = jpe_terrain_create(&cfg);
    if (!h) return 1;
    const int cx = argc > 2 ? atoi(argv[2]) : 0;
    const int cz = argc > 3 ? atoi(argv[3]) : 0;
    const uint64_t n = jpe_terrain_chunk_blocks(h);
    std::vector<uint16_t> data(n);
    jpe_terrain_generate_chunk(h, cx, cz, data.data(), n);
    FILE *f = fopen(argv[4] ? argv[4] : "chunk.bin", "wb");
    fwrite(data.data(), sizeof(uint16_t), n, f);
    fclose(f);
    jpe_terrain_destroy(h);
    return 0;
}
