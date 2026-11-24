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

/* Diagnostic boundary probe, not a Minecraft world-generation function. */
JPE_API int32_t jpe_runtime_add_f32(const float *input, float *output, uint32_t length);

#ifdef __cplusplus
}
#endif
#endif
