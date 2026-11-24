#include "jpe_native_api.h"

#include <cstdint>

extern "C" int32_t jpe_cpp_perlin_add_volume(
    const uint8_t* permutation,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double offset_x, double offset_y, double offset_z,
    double xz_scale, double y_scale, float amplitude,
    float* output, uint64_t output_length, uint32_t flags
) noexcept;

extern "C" int32_t jpe_cpp_perlin_add_stack(
    const JpePerlinLayer* layers, uint32_t layer_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    if (layers == nullptr || layer_count == 0 || layer_count > 64 || output == nullptr) return 1;
    for (uint32_t i = 0; i < layer_count; ++i) {
        const JpePerlinLayer& layer = layers[i];
        if (layer.permutation == nullptr) return 1;
        const int32_t status = jpe_cpp_perlin_add_volume(
            layer.permutation,
            size_x, size_y, size_z,
            min_x, min_y, min_z,
            step_x, step_y, step_z,
            layer.offset_x, layer.offset_y, layer.offset_z,
            layer.xz_scale, layer.y_scale, layer.amplitude,
            output, output_length, flags
        );
        if (status != 0) return status;
    }
    return 0;
}
