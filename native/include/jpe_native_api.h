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
    /* PERLIN_STACK: immediate=prepared handle; input_a/input_b/reserved=f32 bits for xz/y/amplitude. */
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
    JPE_DENSITY_GRADIENT = 25,
    JPE_DENSITY_INTERPOLATED = 26,
    JPE_DENSITY_RANGE_CHOICE = 27,
    JPE_DENSITY_ROUND = 28,
    JPE_DENSITY_LOG = 29
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

#ifdef __cplusplus
}
#endif
#endif
