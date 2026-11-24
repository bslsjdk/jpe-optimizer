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

/* ABI version 1. Lengths are element counts, not byte counts. */
JPE_API uint32_t jpe_runtime_abi_version(void);

/* Diagnostic boundary probe; not used by Minecraft world generation. */
JPE_API int32_t jpe_runtime_add_f32(const float *input, float *output, uint32_t length);

/*
 * Adds vanilla-compatible Perlin volume samples to output in DensityVolume order:
 * ((zIndex * sizeX) + xIndex) * sizeY + yIndex.
 * flags bit 0: enable Far Lands behavior (disable coordinate wrapping).
 */
JPE_API int32_t jpe_runtime_perlin_add_volume(
    const uint8_t *permutation,
    const int32_t *block_x,
    const int32_t *block_y,
    const int32_t *block_z,
    uint32_t size_x,
    uint32_t size_y,
    uint32_t size_z,
    double offset_x,
    double offset_y,
    double offset_z,
    double xz_scale,
    double y_scale,
    float amplitude,
    float *output,
    uint64_t output_length,
    uint32_t flags
);

#ifdef __cplusplus
}
#endif
#endif
