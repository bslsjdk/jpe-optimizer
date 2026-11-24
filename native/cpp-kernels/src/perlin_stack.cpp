#include "jpe_native_api.h"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

namespace {
constexpr int kGradientX[16] = {1,-1,1,-1,1,-1,1,-1,0,0,0,0,1,0,-1,0};
constexpr int kGradientY[16] = {1,1,-1,-1,0,0,0,0,1,-1,1,-1,1,-1,1,-1};
constexpr int kGradientZ[16] = {0,0,0,0,1,1,-1,-1,1,1,-1,-1,0,1,0,-1};
const double kHalfRoundOff = std::nextafter(16777216.0, -std::numeric_limits<double>::infinity());
constexpr double kRoundOff = 33554432.0;

inline int32_t axis_coordinate(int32_t minimum, uint32_t index, int32_t step) noexcept {
    const uint32_t bits = static_cast<uint32_t>(minimum) + index * static_cast<uint32_t>(step);
    int32_t coordinate;
    std::memcpy(&coordinate, &bits, sizeof(coordinate));
    return coordinate;
}

inline double wrap_coordinate(double value, bool far_lands) noexcept {
    if (far_lands) return value;
    if (value >= -kHalfRoundOff && value < kHalfRoundOff) return value;
    return value - std::floor(value / kRoundOff + 0.5) * kRoundOff;
}

inline int32_t floor_to_i32(double value) noexcept {
    const double floored = std::floor(value);
    if (std::isnan(floored)) return 0;
    if (floored >= static_cast<double>(std::numeric_limits<int32_t>::max())) return std::numeric_limits<int32_t>::max();
    if (floored <= static_cast<double>(std::numeric_limits<int32_t>::min())) return std::numeric_limits<int32_t>::min();
    return static_cast<int32_t>(floored);
}

inline uint32_t permute_sum(const uint8_t* p, int32_t a, int32_t b = 0, int32_t c = 0) noexcept {
    const uint32_t index = static_cast<uint32_t>(a)
                         + static_cast<uint32_t>(b)
                         + static_cast<uint32_t>(c);
    return static_cast<uint32_t>(p[index & 255U]);
}

inline float smoothstep(float x) noexcept {
    return x * x * x * (x * (x * 6.0F - 15.0F) + 10.0F);
}

inline float lerp(float alpha, float from, float to) noexcept {
    return from + alpha * (to - from);
}

inline void corner(const uint8_t* p, uint32_t hash, float rx, float rz,
                   float& xz, float& y) noexcept {
    const uint32_t gradient = static_cast<uint32_t>(p[hash & 255U]) & 15U;
    xz = static_cast<float>(kGradientX[gradient]) * rx
       + static_cast<float>(kGradientZ[gradient]) * rz;
    y = static_cast<float>(kGradientY[gradient]);
}

struct LayerWorkspace {
    std::vector<float> x_relative;
    std::vector<float> x_alpha;
    std::vector<int32_t> x_lattice0;
    std::vector<int32_t> x_lattice1;
    std::vector<int32_t> y_floors;
    std::vector<float> y_relative;
    std::vector<float> y_alpha;
    int32_t z_floor = 0;
    float z_relative = 0.0F;
    float z_alpha = 0.0F;
};


bool validate_stack(
    const JpePerlinLayer* layers, uint32_t layer_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length
) noexcept {
    if (layers == nullptr || layer_count == 0 || layer_count > 64 || output == nullptr
        || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0) return false;

    constexpr uint64_t max_u64 = std::numeric_limits<uint64_t>::max();
    if (static_cast<uint64_t>(size_x) > max_u64 / size_y) return false;
    const uint64_t xy_count = static_cast<uint64_t>(size_x) * size_y;
    if (xy_count > max_u64 / size_z) return false;
    const uint64_t expected = xy_count * size_z;
    if (expected != output_length
        || expected > static_cast<uint64_t>(std::numeric_limits<size_t>::max() / sizeof(float))) return false;
    for (uint32_t i = 0; i < layer_count; ++i) {
        if (layers[i].permutation == nullptr) return false;
    }
    return true;
}
} // namespace

extern "C" int32_t jpe_cpp_perlin_add_volume(
    const uint8_t* permutation,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double offset_x, double offset_y, double offset_z,
    double xz_scale, double y_scale, float amplitude,
    float* output, uint64_t output_length, uint32_t flags
) noexcept;

extern "C" int32_t jpe_cpp_perlin_add_stack_fused(
    const JpePerlinLayer* layers, uint32_t layer_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    if (!validate_stack(layers, layer_count, size_x, size_y, size_z,
                        step_x, step_y, step_z, output, output_length)) return 1;

    const bool far_lands = (flags & 1U) != 0U;
    thread_local std::vector<LayerWorkspace> workspaces;
    if (workspaces.size() < layer_count) workspaces.resize(layer_count);

    // Cache each layer's invariant X/Y transforms once. The cache is worker-local,
    // reused across jobs, and sized only when a larger volume arrives.
    for (uint32_t li = 0; li < layer_count; ++li) {
        const JpePerlinLayer& layer = layers[li];
        LayerWorkspace& ws = workspaces[li];
        if (ws.x_relative.size() < size_x) {
            ws.x_relative.resize(size_x);
            ws.x_alpha.resize(size_x);
            ws.x_lattice0.resize(size_x);
            ws.x_lattice1.resize(size_x);
        }
        if (ws.y_floors.size() < size_y) {
            ws.y_floors.resize(size_y);
            ws.y_relative.resize(size_y);
            ws.y_alpha.resize(size_y);
        }
        for (uint32_t ix = 0; ix < size_x; ++ix) {
            const double x = wrap_coordinate(
                static_cast<double>(axis_coordinate(min_x, ix, step_x)) * layer.xz_scale,
                far_lands) + layer.offset_x;
            const int32_t floor_x = floor_to_i32(x);
            const float relative_x = static_cast<float>(x - static_cast<double>(floor_x));
            ws.x_relative[ix] = relative_x;
            ws.x_alpha[ix] = smoothstep(relative_x);
            ws.x_lattice0[ix] = static_cast<int32_t>(permute_sum(layer.permutation, floor_x));
            ws.x_lattice1[ix] = static_cast<int32_t>(permute_sum(layer.permutation, floor_x, 1));
        }
        for (uint32_t iy = 0; iy < size_y; ++iy) {
            const double y = wrap_coordinate(
                static_cast<double>(axis_coordinate(min_y, iy, step_y)) * layer.y_scale,
                far_lands) + layer.offset_y;
            const int32_t floor_y = floor_to_i32(y);
            const float relative_y = static_cast<float>(y - static_cast<double>(floor_y));
            ws.y_floors[iy] = floor_y;
            ws.y_relative[iy] = relative_y;
            ws.y_alpha[iy] = smoothstep(relative_y);
        }
    }

    float corner_xz[64][8];
    float corner_y[64][8];
    thread_local std::vector<float> row_accumulator;
    if (row_accumulator.size() < size_y) row_accumulator.resize(size_y);

    for (uint32_t iz = 0; iz < size_z; ++iz) {
        const int32_t block_z = axis_coordinate(min_z, iz, step_z);
        for (uint32_t li = 0; li < layer_count; ++li) {
            const JpePerlinLayer& layer = layers[li];
            LayerWorkspace& ws = workspaces[li];
            const double z = wrap_coordinate(
                static_cast<double>(block_z) * layer.xz_scale, far_lands) + layer.offset_z;
            ws.z_floor = floor_to_i32(z);
            ws.z_relative = static_cast<float>(z - static_cast<double>(ws.z_floor));
            ws.z_alpha = smoothstep(ws.z_relative);
        }

        for (uint32_t ix = 0; ix < size_x; ++ix) {
            const uint64_t base = (static_cast<uint64_t>(iz) * size_x + ix) * size_y;
            std::memcpy(row_accumulator.data(), output + base,
                        static_cast<size_t>(size_y) * sizeof(float));

            // Keep the old kernel's per-layer Y-run traversal, but accumulate
            // into a hot row buffer. This avoids both per-voxel floor-change
            // branches and repeated full-volume output read/write passes.
            for (uint32_t li = 0; li < layer_count; ++li) {
                const JpePerlinLayer& layer = layers[li];
                const LayerWorkspace& ws = workspaces[li];
                const float relative_x = ws.x_relative[ix];
                const float alpha_x = ws.x_alpha[ix];
                const int32_t x0 = ws.x_lattice0[ix];
                const int32_t x1 = ws.x_lattice1[ix];
                uint32_t iy = 0;
                while (iy < size_y) {
                    const int32_t floor_y = ws.y_floors[iy];
                    const int32_t xy00 = static_cast<int32_t>(permute_sum(layer.permutation, x0, floor_y));
                    const int32_t xy01 = static_cast<int32_t>(permute_sum(layer.permutation, x0, floor_y, 1));
                    const int32_t xy10 = static_cast<int32_t>(permute_sum(layer.permutation, x1, floor_y));
                    const int32_t xy11 = static_cast<int32_t>(permute_sum(layer.permutation, x1, floor_y, 1));
                    const float relative_z = ws.z_relative;
                    const uint32_t floor_z = static_cast<uint32_t>(ws.z_floor);
                    corner(layer.permutation, static_cast<uint32_t>(xy00) + floor_z, relative_x, relative_z, corner_xz[li][0], corner_y[li][0]);
                    corner(layer.permutation, static_cast<uint32_t>(xy10) + floor_z, relative_x - 1.0F, relative_z, corner_xz[li][1], corner_y[li][1]);
                    corner(layer.permutation, static_cast<uint32_t>(xy01) + floor_z, relative_x, relative_z, corner_xz[li][2], corner_y[li][2]);
                    corner(layer.permutation, static_cast<uint32_t>(xy11) + floor_z, relative_x - 1.0F, relative_z, corner_xz[li][3], corner_y[li][3]);
                    corner(layer.permutation, static_cast<uint32_t>(xy00) + floor_z + 1U, relative_x, relative_z - 1.0F, corner_xz[li][4], corner_y[li][4]);
                    corner(layer.permutation, static_cast<uint32_t>(xy10) + floor_z + 1U, relative_x - 1.0F, relative_z - 1.0F, corner_xz[li][5], corner_y[li][5]);
                    corner(layer.permutation, static_cast<uint32_t>(xy01) + floor_z + 1U, relative_x, relative_z - 1.0F, corner_xz[li][6], corner_y[li][6]);
                    corner(layer.permutation, static_cast<uint32_t>(xy11) + floor_z + 1U, relative_x - 1.0F, relative_z - 1.0F, corner_xz[li][7], corner_y[li][7]);

                    while (iy < size_y && ws.y_floors[iy] == floor_y) {
                        const float relative_y = ws.y_relative[iy];
                        const float relative_y1 = relative_y - 1.0F;
                        const float x000 = corner_xz[li][0] + corner_y[li][0] * relative_y;
                        const float x100 = corner_xz[li][1] + corner_y[li][1] * relative_y;
                        const float x010 = corner_xz[li][2] + corner_y[li][2] * relative_y1;
                        const float x110 = corner_xz[li][3] + corner_y[li][3] * relative_y1;
                        const float x001 = corner_xz[li][4] + corner_y[li][4] * relative_y;
                        const float x101 = corner_xz[li][5] + corner_y[li][5] * relative_y;
                        const float x011 = corner_xz[li][6] + corner_y[li][6] * relative_y1;
                        const float x111 = corner_xz[li][7] + corner_y[li][7] * relative_y1;
                        const float alpha_y = ws.y_alpha[iy];
                        const float lower = lerp(alpha_y,
                            lerp(alpha_x, x000, x100),
                            lerp(alpha_x, x010, x110));
                        const float upper = lerp(alpha_y,
                            lerp(alpha_x, x001, x101),
                            lerp(alpha_x, x011, x111));
                        const float sample = layer.amplitude * lerp(ws.z_alpha, lower, upper);
                        row_accumulator[iy] = row_accumulator[iy] + sample;
                        ++iy;
                    }
                }
            }

            std::memcpy(output + base, row_accumulator.data(),
                        static_cast<size_t>(size_y) * sizeof(float));
        }
    }
    return 0;
}


extern "C" int32_t jpe_cpp_perlin_add_stack(
    const JpePerlinLayer* layers, uint32_t layer_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    if (!validate_stack(layers, layer_count, size_x, size_y, size_z,
                        step_x, step_y, step_z, output, output_length)) return 1;

    // Keep the established cached kernel as the production path until the
    // fused implementation wins a repeatable benchmark on representative volumes.
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
