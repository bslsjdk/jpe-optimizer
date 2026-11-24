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
/* ABI v4. Lengths are element counts, not byte counts. Configure workers before first submit. */
JPE_API uint32_t jpe_runtime_abi_version(void);
/* requested=0 selects all process-visible CPUs; positive values are capped to that count. */
JPE_API int32_t jpe_runtime_configure_workers(uint32_t requested);
JPE_API int32_t jpe_runtime_add_f32(const float *input, float *output, uint32_t length);
/* Synchronous entry retained for exact parity tests. */
JPE_API int32_t jpe_runtime_perlin_add_volume(
    const uint8_t *permutation, const int32_t *block_x, const int32_t *block_y, const int32_t *block_z,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    double offset_x, double offset_y, double offset_z, double xz_scale, double y_scale,
    float amplitude, float *output, uint64_t output_length, uint32_t flags
);
/* Async API: submit copies input into bounded Rust-owned buffers. Zero means rejected/full. */
JPE_API uint64_t jpe_runtime_submit_perlin_volume(
    const uint8_t *permutation, const int32_t *block_x, const int32_t *block_y, const int32_t *block_z,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    double offset_x, double offset_y, double offset_z, double xz_scale, double y_scale,
    float amplitude, const float *initial_output, uint64_t output_length, uint32_t flags
);
/* status: 0 complete, 1 pending, 2 running, 3 failed, 4 cancelled, -1 unknown job */
JPE_API int32_t jpe_runtime_job_status(uint64_t job_id);
JPE_API int32_t jpe_runtime_copy_job_output(uint64_t job_id, float *destination, uint64_t capacity);
JPE_API int32_t jpe_runtime_cancel_job(uint64_t job_id);
JPE_API int32_t jpe_runtime_release_job(uint64_t job_id);
#ifdef __cplusplus
}
#endif
#endif
