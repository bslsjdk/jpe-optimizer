#include "jpe_native_api.h"
#include <cstdint>

namespace {
int32_t add_f32_kernel(const float *input, float *output, uint32_t length) noexcept {
    if (length == 0) return 0;
    if (input == nullptr || output == nullptr) return 1;
    try {
        // Scalar ABI baseline. SIMD is added only after equivalent-input benchmarks.
        for (uint32_t i = 0; i < length; ++i) output[i] = input[i] + input[i];
        return 0;
    } catch (...) {
        // No C++ exception may cross the C ABI boundary.
        return 2;
    }
}
}

extern "C" int32_t jpe_cpp_add_f32(
    const float *input, float *output, uint32_t length
) noexcept {
    return add_f32_kernel(input, output, length);
}
