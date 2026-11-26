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
/* ABI v7. Axis coordinates use min + index * step with Java int wrap semantics. */
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
/* Prepared immutable NoiseStack. Preparation copies all permutations once;
 * evaluation supplies only sampler scales, volume coordinates and output storage. */
JPE_API uint64_t jpe_runtime_prepare_perlin_stack(const JpePerlinLayer *layers, uint32_t layer_count);
JPE_API int32_t jpe_runtime_evaluate_prepared_perlin_stack(
    uint64_t handle, double xz_scale, double y_scale, float amplitude,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float *output, uint64_t output_length, uint32_t flags
);
JPE_API int32_t jpe_runtime_release_prepared_perlin_stack(uint64_t handle);
/* Retains a prepared stack under an independent handle for compiled graph ownership. */
JPE_API uint64_t jpe_runtime_retain_prepared_perlin_stack(uint64_t handle);
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
 * Experimental density-graph IR v1. Minecraft integration is opt-in and falls back for unsupported graphs.
 * Nodes are topologically ordered; inputs must reference earlier nodes.
 * Volume output order is ((z * size_x + x) * size_y + y).
 */
typedef struct JpeDensityNode {
    uint32_t opcode;
    uint32_t input_a;
    uint32_t input_b;
    uint32_t reserved;
    double immediate;
    double auxiliary;
    double tertiary;
} JpeDensityNode;
enum {
    JPE_DENSITY_CONST = 1,
    JPE_DENSITY_X = 2,
    JPE_DENSITY_Y = 3,
    JPE_DENSITY_Z = 4,
    JPE_DENSITY_ADD = 5,
    JPE_DENSITY_MUL = 6,
    /* PERLIN_STACK: immediate=prepared handle; auxiliary=xz scale; tertiary=y scale; reserved=f32 amplitude bits. */
    JPE_DENSITY_PERLIN_STACK = 7,
    JPE_DENSITY_SUB = 8,
    JPE_DENSITY_DIV = 9,
    JPE_DENSITY_MIN = 10,
    JPE_DENSITY_MAX = 11,
    JPE_DENSITY_ABS = 12,
    JPE_DENSITY_NEGATE = 13,
    JPE_DENSITY_SQUARE = 14,
    JPE_DENSITY_CUBE = 15,
    JPE_DENSITY_SQRT = 16,
    JPE_DENSITY_HALF_NEGATIVE = 17,
    JPE_DENSITY_QUARTER_NEGATIVE = 18,
    JPE_DENSITY_RECIPROCAL = 19,
    JPE_DENSITY_SIGN = 20,
    JPE_DENSITY_POW = 21,
    /* CLAMP: input_a=source; input_b/reserved=f32 minimum/maximum bits. */
    JPE_DENSITY_CLAMP = 22,
    JPE_DENSITY_SQUEEZE = 23,
    JPE_DENSITY_LERP = 24,
    /* GRADIENT: input_a=axis (0=X,1=Y,2=Z); input_b=tiling (0=clamp,1=repeat,2=mirrored repeat); reserved=f32 fromValue bits; immediate/auxiliary=from/to coordinates; tertiary=toValue. */
    JPE_DENSITY_GRADIENT = 25,
    JPE_DENSITY_INTERPOLATED = 26,
    JPE_DENSITY_RANGE_CHOICE = 27,
    JPE_DENSITY_ROUND = 28,
    JPE_DENSITY_LOG = 29,
    /* DISTANCE_TO_POINT: input_a=DistanceMetric (0=CHEBYSHEV, 1=EUCLIDEAN, 2=EUCLIDEAN_SQUARED, 3=MANHATTAN); immediate/auxiliary/tertiary=integer point x/y/z. */
    JPE_DENSITY_DISTANCE_TO_POINT = 30,
    /* PERLIN_STACK_WARP: input_a/input_b/reserved=shift X/Y/Z node indices; amplitude is 1.0. */
    JPE_DENSITY_PERLIN_STACK_WARP = 31,
    /* SLICE: input_a=child graph root; input_b=axis (0=X,1=Y,2=Z); immediate=fixed int coordinate. */
    JPE_DENSITY_SLICE = 32
};
/* Returns a retained compiled-plan handle, or zero for an invalid graph. */
JPE_API uint64_t jpe_runtime_density_graph_compile(
    const JpeDensityNode *nodes, uint32_t node_count, uint32_t root_node,
    uint64_t configuration_fingerprint
);
/* Evaluates the root over a complete volume. Coordinates use min + index * step. */
JPE_API int32_t jpe_runtime_density_graph_evaluate(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double *output, uint64_t output_length
);
/* Strict IEEE-754 single-precision evaluation for Minecraft 26.3 density nodes. */
JPE_API int32_t jpe_runtime_density_graph_evaluate_f32(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float *output, uint64_t output_length
);
/* Releases one retained handle. Active evaluations keep their immutable plan alive. */
JPE_API int32_t jpe_runtime_density_graph_release(uint64_t graph_handle);

/*
 * Experimental Aquifer decision core. Fluid types are runtime registry IDs;
 * air/water/lava IDs are supplied explicitly so the native kernel does not
 * assume vanilla numeric registry assignments.
 */
typedef struct JpeAquiferFluidStatus {
    int32_t fluid_level;
    uint32_t fluid_type;
} JpeAquiferFluidStatus;

typedef struct JpeAquiferCoreInput {
    int32_t block_y;
    double density;
    float barrier_noise;
    int32_t distance_sqr[4];
    JpeAquiferFluidStatus closest[4];
    uint32_t global_fluid_below_type;
    uint32_t air_type;
    uint32_t water_type;
    uint32_t lava_type;
    uint32_t debug_disable_fluid_generation;
} JpeAquiferCoreInput;

typedef struct JpeAquiferCoreOutput {
    uint32_t has_block_state;
    uint32_t block_state_type;
    uint32_t should_schedule_fluid_update;
} JpeAquiferCoreOutput;

/* Returns 0 on success, 1 for invalid input. A false has_block_state means
 * computeSubstance returns null and density terrain should remain untouched. */
JPE_API int32_t jpe_runtime_aquifer_compute_substance_core(
    const JpeAquiferCoreInput *input, JpeAquiferCoreOutput *output
);
/* Batch form for a complete chunk fill pass. Inputs and outputs are contiguous
 * and must not overlap; count must be in [1, 1,048,576]. No allocation or
 * per-voxel ABI crossing occurs. The full input range is validated before writes. */
JPE_API int32_t jpe_runtime_aquifer_compute_substance_batch(
    const JpeAquiferCoreInput *inputs, uint32_t count,
    JpeAquiferCoreOutput *outputs
);

/* Internal C++ kernels linked by the Rust runtime. Public jpe_runtime_* symbols
 * are exported by Rust wrappers so the cdylib exposes a stable ABI. */
JPE_API int32_t jpe_cpp_aquifer_compute_substance_core(
    const JpeAquiferCoreInput *input, JpeAquiferCoreOutput *output
);
JPE_API int32_t jpe_cpp_aquifer_compute_substance_batch(
    const JpeAquiferCoreInput *inputs, uint32_t count,
    JpeAquiferCoreOutput *outputs
);

typedef struct JpeAquiferCellLocation {
    int32_t x;
    int32_t y;
    int32_t z;
} JpeAquiferCellLocation;

typedef struct JpeAquiferNearestFour {
    uint32_t index[4];
    int32_t distance_sqr[4];
};

/* Select the four nearest of the twelve Minecraft 26.3 aquifer neighbor cells.
 * Equal distances retain Minecraft's insertion behavior (later equal candidates
 * are inserted before earlier ones). */
JPE_API int32_t jpe_runtime_aquifer_find_nearest_four(
    int32_t block_x, int32_t block_y, int32_t block_z,
    const JpeAquiferCellLocation *candidates, uint32_t candidate_count,
    JpeAquiferNearestFour *output
);

/* Exact positional-random offsets used by Minecraft 26.3 Aquifer cells.
 * random mode 0 is XOROSHIRO; mode 1 is Legacy. */
JPE_API int32_t jpe_runtime_aquifer_xoroshiro_offsets(
    uint64_t seed_lo, uint64_t seed_hi,
    int32_t grid_x, int32_t grid_y, int32_t grid_z,
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z
);
JPE_API int32_t jpe_runtime_aquifer_legacy_offsets(
    uint64_t factory_seed,
    int32_t grid_x, int32_t grid_y, int32_t grid_z,
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z
);

/* Precompute the full packed location cache. The number of Y layers is
 * derived from cell_count / (grid_size_x * grid_size_z). */
JPE_API int32_t jpe_runtime_aquifer_grid_centers(
    uint32_t random_mode, uint64_t seed_lo, uint64_t seed_hi,
    int32_t min_grid_x, int32_t min_grid_y, int32_t min_grid_z,
    uint32_t grid_size_x, uint32_t grid_size_z, uint32_t cell_count,
    uint64_t *packed_locations
);

/* Batch nearest-four lookup. Input arrays contain sample_count coordinates.
 * Each output array needs sample_count * 4 entries; outputs are sample-major.
 * Input and output arrays must be non-overlapping. Distances preserve Java int32 wrap. */
JPE_API int32_t jpe_runtime_aquifer_nearest_four(
    uint32_t random_mode, uint64_t seed_lo, uint64_t seed_hi,
    const int32_t *block_x, const int32_t *block_y, const int32_t *block_z,
    uint32_t sample_count,
    int32_t *center_x, int32_t *center_y, int32_t *center_z,
    int32_t *squared_distances
);

#ifdef __cplusplus
}
#endif
#endif
