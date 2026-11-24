#include "jpe_native_api.h"

#include <cstddef>
#include <cstdint>
#include <limits>

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
    if (layers == nullptr || layer_count == 0 || layer_count > 64 || output == nullptr
        || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0) {
        return 1;
    }

    // Validate the complete request before touching output. Otherwise a bad
    // descriptor late in the stack could leave a partially accumulated volume.
    constexpr uint64_t max_u64 = std::numeric_limits<uint64_t>::max();
    if (static_cast<uint64_t>(size_x) > max_u64 / size_y) return 1;
    const uint64_t xy_count = static_cast<uint64_t>(size_x) * size_y;
    if (xy_count > max_u64 / size_z) return 1;
    const uint64_t expected = xy_count * size_z;
    if (expected != output_length
        || expected > static_cast<uint64_t>(std::numeric_limits<size_t>::max() / sizeof(float))) {
        return 1;
    }
    for (uint32_t i = 0; i < layer_count; ++i) {
        if (layers[i].permutation == nullptr) return 1;
    }

    for (uint32_t i = 0; i < layer_count; ++i) {
        const JpePerlinLayer& layer = layers[i];
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
