#ifndef JPE_TERRAIN_API_H
#define JPE_TERRAIN_API_H

/*
 * JPE native terrain generator (C ABI v1).
 *
 * A complete, self-contained overworld generator: no Java, no Minecraft, no
 * JVM, no resource pack. It owns seed -> noise router -> density -> aquifer ->
 * carvers -> surface rules -> biome -> chunk block data.
 *
 * Output contract
 * ---------------
 * One chunk is `16 * 16 * height` u16 block ids, ordered section-major so a
 * 16x16x16 section is one contiguous 4096-element run:
 *
 *     index = (section_y_local * 16 + local_y) * 256 + local_z * 16 + local_x
 *           = ((block_y - min_y) << 8) | (block_z & 15) << 4 | (block_x & 15)
 *
 * which is exactly the layout the Rust mesher consumes (y << 8 | z << 4 | x).
 * Block id 0 is always air.
 *
 * Threading
 * ---------
 * A generator handle is safe to call from one thread at a time. Batch calls
 * (`jpe_terrain_generate_chunks`) use the generator's internal pool and are
 * therefore called from one thread as well; they parallelise across chunks.
 *
 * Determinism
 * -----------
 * Output depends only on (seed, config, chunk coordinate, flags). Nothing is
 * time-, thread- or order-dependent, and no fast-math flags are used, so a
 * chunk generated alone equals the same chunk generated in a batch of 4096.
 */

#include <stdint.h>

#if defined(_WIN32)
  #if defined(JPE_NATIVE_BUILD)
    #define JPE_TERRAIN_API __declspec(dllexport)
  #else
    #define JPE_TERRAIN_API __declspec(dllimport)
  #endif
#else
  #define JPE_TERRAIN_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define JPE_TERRAIN_ABI_VERSION 1u

/* Blocks per chunk edge, and the default overworld column height. */
#define JPE_TERRAIN_CHUNK_SIZE 16
#define JPE_TERRAIN_DEFAULT_MIN_Y (-64)
#define JPE_TERRAIN_DEFAULT_HEIGHT 384
#define JPE_TERRAIN_DEFAULT_SEA_LEVEL 63
#define JPE_TERRAIN_DEFAULT_LAVA_LEVEL (-54)

/* Stage switches. Every stage is on by default; turning one off is a
 * diagnostic tool for isolating a stage, not a supported production mode. */
enum {
    JPE_TERRAIN_STAGE_DENSITY = 1u << 0,
    JPE_TERRAIN_STAGE_AQUIFER = 1u << 1,
    JPE_TERRAIN_STAGE_CARVERS = 1u << 2,
    JPE_TERRAIN_STAGE_SURFACE = 1u << 3,
    JPE_TERRAIN_STAGE_BIOMES = 1u << 4,
    JPE_TERRAIN_STAGE_ALL = 0x1Fu
};

typedef struct JpeTerrainConfig {
    int64_t seed;
    int32_t min_y;      /* must be a multiple of 16; default -64 */
    int32_t height;     /* multiple of 16; default 384 */
    int32_t sea_level;  /* default 63 */
    int32_t lava_level; /* default -54 */
    uint32_t stages;    /* JPE_TERRAIN_STAGE_*; 0 means all */
    uint32_t threads;   /* 0 = auto (process-visible CPUs), 1 = caller thread only */
} JpeTerrainConfig;

/* Fills `out` with the default overworld configuration. */
JPE_TERRAIN_API void jpe_terrain_default_config(JpeTerrainConfig *out);

/* Returns a generator handle, or 0 on invalid configuration. */
JPE_TERRAIN_API uint64_t jpe_terrain_create(const JpeTerrainConfig *config);

/* Releases a generator and joins its pool. Outstanding chunk pointers become
 * invalid. Passing 0 is a no-op. */
JPE_TERRAIN_API void jpe_terrain_destroy(uint64_t handle);

JPE_TERRAIN_API uint32_t jpe_terrain_abi_version(void);

/* Blocks per chunk for this handle (16 * 16 * height). */
JPE_TERRAIN_API uint64_t jpe_terrain_chunk_blocks(uint64_t handle);

/* Generates one chunk into `out` (must hold jpe_terrain_chunk_blocks u16).
 * Returns 0 on success, negative on error (-1 handle, -2 bad buffer, -3 bounds). */
JPE_TERRAIN_API int32_t jpe_terrain_generate_chunk(
    uint64_t handle, int32_t chunk_x, int32_t chunk_z, uint16_t *out, uint64_t out_length);

/* Generates `count` chunks in parallel. `chunk_xz` is 2*count int32 (x,z pairs),
 * `out` is count * jpe_terrain_chunk_blocks u16, chunk-major and in input order.
 * `threads` overrides the pool size for this call (0 = generator default). */
JPE_TERRAIN_API int32_t jpe_terrain_generate_chunks(
    uint64_t handle, const int32_t *chunk_xz, uint32_t count,
    uint16_t *out, uint64_t out_length, uint32_t threads);

/* Per-chunk summary, useful for tests and for the host's LOD/culling decisions. */
typedef struct JpeTerrainColumnInfo {
    int32_t biome;          /* biome id, see jpe_terrain_biome_name */
    int32_t surface_y;      /* highest solid block, min_y-1 when the column is empty */
    int32_t water_top_y;    /* highest fluid block, min_y-1 when dry */
    uint32_t solid_blocks;  /* non-air, non-fluid blocks in the column */
    uint32_t fluid_blocks;
} JpeTerrainColumnInfo;

/* Fills 256 column infos in (z * 16 + x) order. */
JPE_TERRAIN_API int32_t jpe_terrain_column_info(
    uint64_t handle, int32_t chunk_x, int32_t chunk_z,
    JpeTerrainColumnInfo *out, uint64_t out_length);

/* Heightmap only: 256 surface heights, (z * 16 + x). Cheap path for streaming
 * decisions that do not need block data. */
JPE_TERRAIN_API int32_t jpe_terrain_heightmap(
    uint64_t handle, int32_t chunk_x, int32_t chunk_z, int32_t *out, uint64_t out_length);

/* The five climate parameters at one block column: continentalness, erosion,
 * temperature, vegetation, weirdness. Diagnostic/tooling only; the generator
 * interpolates a coarse grid, so a direct sample can differ slightly from the
 * value that selected the column's biome. */
JPE_TERRAIN_API int32_t jpe_terrain_climate(
    uint64_t handle, int32_t block_x, int32_t block_z, float *out, uint64_t out_length);

typedef struct JpeTerrainStats {
    uint64_t chunks_generated;
    uint64_t blocks_written;
    uint64_t density_samples;
    uint64_t noise_samples;
    uint64_t carve_cells;
    uint64_t total_nanos;
    uint32_t threads;
    uint32_t biome_count;
} JpeTerrainStats;

/* Snapshot of the counters. `total_nanos` is wall time spent inside the
 * generator, summed across workers. */
JPE_TERRAIN_API int32_t jpe_terrain_stats(uint64_t handle, JpeTerrainStats *out);

/* Static description of a block id. Never null, never allocates. */
JPE_TERRAIN_API const char *jpe_terrain_block_name(uint16_t id);

/* Biome id -> name. Returns "unknown" for an out-of-range id. */
JPE_TERRAIN_API const char *jpe_terrain_biome_name(int32_t id);

#ifdef __cplusplus
}
#endif

#endif /* JPE_TERRAIN_API_H */
