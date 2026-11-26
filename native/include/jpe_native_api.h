#ifndef JPE_NATIVE_API_H
#define JPE_NATIVE_API_H
#include <stdint.h>
#if defined(_WIN32)
  #if defined(JPE_NATIVE_BUILD)
    #define JPE_API __declspec(dllexport)
  #else
    #define JPE_API __declspec(dllimport)
  #endif
#else
  #define JPE_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
/* ABI v8. Axis coordinates use min + index * step with Java int wrap semantics. */
JPE_API uint32_t jpe_runtime_abi_version(void);
/* requested=0 selects all process-visible CPUs; positive values are capped to that count. */
/* Zero selects available CPUs. Repeating the active count is safe after startup;
 * changing the count after worker creation returns -1. */
JPE_API int32_t jpe_runtime_configure_workers(uint32_t requested);
JPE_API int32_t jpe_runtime_add_f32(const float *input, float *output, uint32_t length);
/* Synchronous entry retained for exact parity tests. */
JPE_API int32_t jpe_runtime_perlin_add_volume(
    const uint8_t *permutation,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double offset_x, double offset_y, double offset_z, double xz_scale, double y_scale,
    float amplitude, float *output, uint64_t output_length, uint32_t flags
);
/* One ordered octave/layer descriptor. The permutation is borrowed for the duration of the call. */
typedef struct JpePerlinLayer {
    const uint8_t *permutation;
    double offset_x, offset_y, offset_z;
    double xz_scale, y_scale;
    float amplitude;
    double fudge_y_scale;
    uint32_t flags; /* bit 0: SmearedPerlinNoise layer */
    uint32_t reserved;
} JpePerlinLayer;
/* Batch a complete ordered stack into one output volume. */
JPE_API int32_t jpe_runtime_perlin_add_stack(
    const JpePerlinLayer *layers, uint32_t layer_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float *output, uint64_t output_length, uint32_t flags
);
/* Async stack API borrows the descriptor array, every 256-byte permutation, and output_buffer
 * until terminal job status and release. The caller must keep all borrowed memory valid and
 * exclusively reserved for the job's full lifetime. layer_count is limited to 64. */
JPE_API uint64_t jpe_runtime_submit_perlin_stack(
    const JpePerlinLayer *layers, uint32_t layer_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float *output_buffer, uint64_t output_length, uint32_t flags
);
/* Async API copies the permutation but borrows output_buffer until terminal job status.
 * Caller must keep it valid and exclusively reserved until release. Zero means rejected/full. */
JPE_API uint64_t jpe_runtime_submit_perlin_volume(
    const uint8_t *permutation,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double offset_x, double offset_y, double offset_z, double xz_scale, double y_scale,
    float amplitude, float *output_buffer, uint64_t output_length, uint32_t flags
);
/* status: 0 complete, 1 pending, 2 running, 3 failed, 4 cancelled, -1 unknown job */
JPE_API int32_t jpe_runtime_job_status(uint64_t job_id);
JPE_API int32_t jpe_runtime_wait_job(uint64_t job_id, uint64_t timeout_nanos);
JPE_API int32_t jpe_runtime_cancel_job(uint64_t job_id);
JPE_API int32_t jpe_runtime_release_job(uint64_t job_id);

/*
 * Experimental density-graph IR v8. This additive API is not wired into Minecraft yet.
 * Nodes are topologically ordered; inputs must reference earlier nodes.
 * Volume output order is ((z * size_x + x) * size_y + y).
 */
typedef struct JpeDensityNode {
    uint32_t opcode;
    uint32_t input_a;
    uint32_t input_b;
    uint32_t reserved;
    double immediate;
} JpeDensityNode;
enum {
    JPE_DENSITY_CONST = 1,
    JPE_DENSITY_X = 2,
    JPE_DENSITY_Y = 3,
    JPE_DENSITY_Z = 4,
    JPE_DENSITY_ADD = 5,
    JPE_DENSITY_MUL = 6,
    /* Experimental-only: emits NaN while allowing surrounding known nodes to execute. */
    JPE_DENSITY_OPAQUE = 7,
    JPE_DENSITY_SUB = 8,
    /* Reads one external float32 volume from the evaluate_with_inputs input matrix. */
    JPE_DENSITY_INPUT = 9,
    /* IEEE-754 float32 division; operands are evaluated in the encoded order. */
    JPE_DENSITY_DIV = 10,
    /* Exact Minecraft Mth.clamp(value,min,max); immediate packs two raw float32 bounds. */
    JPE_DENSITY_CLAMP = 11,
    JPE_DENSITY_MIN = 12,
    JPE_DENSITY_MAX = 13,
    JPE_DENSITY_NEGATE = 14,
    JPE_DENSITY_ABS = 15,
    JPE_DENSITY_SQUARE = 16,
    JPE_DENSITY_CUBE = 17,
    JPE_DENSITY_SQRT = 18,
    JPE_DENSITY_LOG = 19,
    JPE_DENSITY_RECIPROCAL = 20,
    JPE_DENSITY_SIGN = 21,
    JPE_DENSITY_SQUEEZE = 22
};
/* Returns a retained compiled-plan handle, or zero for an invalid graph. */
JPE_API uint64_t jpe_runtime_density_graph_compile(
    const JpeDensityNode *nodes, uint32_t node_count, uint32_t root_node,
    uint32_t ir_version, uint64_t configuration_fingerprint
);
/* Evaluates the root over a complete volume. Coordinates use min + index * step. */
JPE_API int32_t jpe_runtime_density_graph_evaluate(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float *output, uint64_t output_length
);
/* Evaluates a graph with input-major external volumes: inputs[i * volume_length + sample]. */
JPE_API int32_t jpe_runtime_density_graph_evaluate_with_inputs(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float *inputs, uint32_t input_count,
    float *output, uint64_t output_length
);
/* Evaluates multiple graph roots in one pass. Outputs are output-major:
 * outputs[i * volume_length + sample]. output_count is 1..256 and total
 * output floats are capped at 16,777,216 (64 MiB). */
JPE_API int32_t jpe_runtime_density_graph_evaluate_multi_with_inputs(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float *inputs, uint32_t input_count,
    const uint32_t *output_nodes, uint32_t output_count,
    float *outputs, uint64_t output_length
);
/* Releases one retained handle. Active evaluations keep their immutable plan alive. */
JPE_API int32_t jpe_runtime_density_graph_release(uint64_t graph_handle);
/* Native-owned persistent plan cache. A cache miss returns handle 0; save returns status 0 on success. */
JPE_API uint64_t jpe_runtime_density_graph_load_cache(const char *path, const char *cache_key);
JPE_API int32_t jpe_runtime_density_graph_save_cache(uint64_t graph_handle, const char *path, const char *cache_key);


/* Exact Minecraft XOROSHIRO Aquifer cell-center offsets.
 * seed_lo/seed_hi are the final XoroshiroPositionalRandomFactory fields obtained
 * once from RandomState's named "aquifer" factory. The function performs Mth.getSeed,
 * Xoroshiro128++ initialization, and nextInt(10), nextInt(9), nextInt(10).
 */
JPE_API int32_t jpe_cpp_aquifer_xoroshiro_offsets(
    uint64_t seed_lo, uint64_t seed_hi,
    int32_t grid_x, int32_t grid_y, int32_t grid_z,
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z
);

JPE_API int32_t jpe_runtime_aquifer_xoroshiro_offsets(
    uint64_t seed_lo, uint64_t seed_hi,
    int32_t grid_x, int32_t grid_y, int32_t grid_z,
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z
);

JPE_API int32_t jpe_cpp_aquifer_legacy_offsets(
    uint64_t factory_seed,
    int32_t grid_x, int32_t grid_y, int32_t grid_z,
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z
);
JPE_API int32_t jpe_runtime_aquifer_legacy_offsets(
    uint64_t factory_seed,
    int32_t grid_x, int32_t grid_y, int32_t grid_z,
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z
);

/* Batch nearest-four Aquifer center search. random_mode: 0=XOROSHIRO, 1=LEGACY.
 * Input coordinates are parallel arrays. Results are sample-major, four neighbors each.
 * The exact center cache is scoped to this volume call.
 */
JPE_API int32_t jpe_runtime_aquifer_nearest_four(
    uint32_t random_mode, uint64_t seed_lo, uint64_t seed_hi,
    const int32_t *block_x, const int32_t *block_y, const int32_t *block_z,
    uint32_t sample_count,
    int32_t *center_x, int32_t *center_y, int32_t *center_z,
    int32_t *squared_distances
);


/* Precomputes the complete vanilla Aquifer location cache in its native flat-array order.
 * random_mode: 0=XOROSHIRO, 1=LEGACY. grid_size_y is derived from cell_count/(grid_size_x*grid_size_z).
 * Each output is packed exactly like BlockPos.asLong(x,y,z).
 */
JPE_API int32_t jpe_runtime_aquifer_grid_centers(
    uint32_t random_mode, uint64_t seed_lo, uint64_t seed_hi,
    int32_t min_grid_x, int32_t min_grid_y, int32_t min_grid_z,
    uint32_t grid_size_x, uint32_t grid_size_z,
    uint32_t cell_count, uint64_t *packed_locations
);

/* Exact Minecraft 26.3 Blender density adapter.
 * Each view exposes raw BlendingData.densities columns. Column pointers may be null.
 * chunk_x/chunk_z are chunk coordinates; min_section_y is LevelHeightAccessor.getMinSectionY().
 * The density map array must preserve the iteration order of Blender's densityBlendingData.forEach.
 */
enum { JPE_BLENDING_COLUMN_COUNT = 16 };
typedef struct JpeBlendingDataView {
    int32_t chunk_x;
    int32_t chunk_z;
    int32_t min_section_y;
    uint32_t section_count;
    const float *density_columns[JPE_BLENDING_COLUMN_COUNT];
} JpeBlendingDataView;

/* height_data models Blender.heightAndBiomeBlendingData for exact fixed-cell lookup.
 * density_data models Blender.densityBlendingData for weighted neighborhood blending.
 * The call is synchronous; all descriptors and source float columns may be released on return.
 */
JPE_API int32_t jpe_runtime_blend_density_volume(
    const JpeBlendingDataView *height_data, uint32_t height_data_count,
    const JpeBlendingDataView *density_data, uint32_t density_data_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float *noise_values, float *outputs, uint64_t output_count
);

#ifdef __cplusplus
}
#endif
#endif
