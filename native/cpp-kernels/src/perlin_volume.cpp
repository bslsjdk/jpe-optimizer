#include "jpe_native_api.h"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace {
constexpr int kGradientX[16] = {1,-1,1,-1,1,-1,1,-1,0,0,0,0,1,0,-1,0};
constexpr int kGradientY[16] = {1,1,-1,-1,0,0,0,0,1,-1,1,-1,1,-1,1,-1};
constexpr int kGradientZ[16] = {0,0,0,0,1,1,-1,-1,1,1,-1,-1,0,1,0,-1};
const double kHalfRoundOff = std::nextafter(16777216.0, -std::numeric_limits<double>::infinity());
constexpr double kRoundOff = 33554432.0;

inline double wrap_coordinate(double value, bool far_lands) noexcept {
    if (far_lands) return value;
    if (value >= -kHalfRoundOff && value < kHalfRoundOff) return value;
    return value - std::floor(value / kRoundOff + 0.5) * kRoundOff;
}

inline int32_t floor_to_i32(double value) noexcept {
    const double floored = std::floor(value);
    // Match Java's narrowing conversion semantics for NaN and out-of-range values.
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
    // Keep the Java float operation order. The build disables FP contraction.
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

int32_t perlin_add_volume(
    const uint8_t* permutation,
    const int32_t* block_x,
    const int32_t* block_y,
    const int32_t* block_z,
    uint32_t size_x,
    uint32_t size_y,
    uint32_t size_z,
    double offset_x,
    double offset_y,
    double offset_z,
    double xz_scale,
    double y_scale,
    float amplitude,
    float* output,
    uint64_t output_length,
    uint32_t flags
) noexcept {
    if (size_x == 0 || size_y == 0 || size_z == 0) return 1;
    if (permutation == nullptr || block_x == nullptr || block_y == nullptr
        || block_z == nullptr || output == nullptr) return 1;

    const uint64_t max_u64 = std::numeric_limits<uint64_t>::max();
    if (static_cast<uint64_t>(size_x) > max_u64 / size_y) return 1;
    const uint64_t xy_count = static_cast<uint64_t>(size_x) * size_y;
    if (xy_count > max_u64 / size_z) return 1;
    const uint64_t expected = xy_count * size_z;
    if (expected != output_length) return 1;
    if (expected > static_cast<uint64_t>(std::numeric_limits<size_t>::max() / sizeof(float))) return 1;

    const bool far_lands = (flags & 1U) != 0U;

    for (uint32_t iz = 0; iz < size_z; ++iz) {
        const double z = wrap_coordinate(static_cast<double>(block_z[iz]) * xz_scale, far_lands) + offset_z;
        const int32_t floor_z = floor_to_i32(z);
        const float relative_z = static_cast<float>(z - static_cast<double>(floor_z));
        const float alpha_z = smoothstep(relative_z);

        for (uint32_t ix = 0; ix < size_x; ++ix) {
            const double x = wrap_coordinate(static_cast<double>(block_x[ix]) * xz_scale, far_lands) + offset_x;
            const int32_t floor_x = floor_to_i32(x);
            const float relative_x = static_cast<float>(x - static_cast<double>(floor_x));
            const float alpha_x = smoothstep(relative_x);

            const int32_t x0 = static_cast<int32_t>(permute_sum(permutation, floor_x));
            const int32_t x1 = static_cast<int32_t>(permute_sum(permutation, floor_x, 1));
            const uint64_t base = (static_cast<uint64_t>(iz) * size_x + ix) * size_y;

            for (uint32_t iy = 0; iy < size_y; ++iy) {
                const double y_coord = wrap_coordinate(static_cast<double>(block_y[iy]) * y_scale, far_lands) + offset_y;
                const int32_t floor_y = floor_to_i32(y_coord);
                const float relative_y = static_cast<float>(y_coord - static_cast<double>(floor_y));
                const float relative_y1 = relative_y - 1.0F;

                const int32_t xy00 = static_cast<int32_t>(permute_sum(permutation, x0, floor_y));
                const int32_t xy01 = static_cast<int32_t>(permute_sum(permutation, x0, floor_y, 1));
                const int32_t xy10 = static_cast<int32_t>(permute_sum(permutation, x1, floor_y));
                const int32_t xy11 = static_cast<int32_t>(permute_sum(permutation, x1, floor_y, 1));

                float corner_xz[8];
                float corner_y[8];
                corner(permutation, static_cast<uint32_t>(xy00) + static_cast<uint32_t>(floor_z), relative_x, relative_z, corner_xz[0], corner_y[0]);
                corner(permutation, static_cast<uint32_t>(xy10) + static_cast<uint32_t>(floor_z), relative_x - 1.0F, relative_z, corner_xz[1], corner_y[1]);
                corner(permutation, static_cast<uint32_t>(xy01) + static_cast<uint32_t>(floor_z), relative_x, relative_z, corner_xz[2], corner_y[2]);
                corner(permutation, static_cast<uint32_t>(xy11) + static_cast<uint32_t>(floor_z), relative_x - 1.0F, relative_z, corner_xz[3], corner_y[3]);
                corner(permutation, static_cast<uint32_t>(xy00) + static_cast<uint32_t>(floor_z) + 1U, relative_x, relative_z - 1.0F, corner_xz[4], corner_y[4]);
                corner(permutation, static_cast<uint32_t>(xy10) + static_cast<uint32_t>(floor_z) + 1U, relative_x - 1.0F, relative_z - 1.0F, corner_xz[5], corner_y[5]);
                corner(permutation, static_cast<uint32_t>(xy01) + static_cast<uint32_t>(floor_z) + 1U, relative_x, relative_z - 1.0F, corner_xz[6], corner_y[6]);
                corner(permutation, static_cast<uint32_t>(xy11) + static_cast<uint32_t>(floor_z) + 1U, relative_x - 1.0F, relative_z - 1.0F, corner_xz[7], corner_y[7]);

                const float x000 = corner_xz[0] + corner_y[0] * relative_y;
                const float x100 = corner_xz[1] + corner_y[1] * relative_y;
                const float x010 = corner_xz[2] + corner_y[2] * relative_y1;
                const float x110 = corner_xz[3] + corner_y[3] * relative_y1;
                const float x001 = corner_xz[4] + corner_y[4] * relative_y;
                const float x101 = corner_xz[5] + corner_y[5] * relative_y;
                const float x011 = corner_xz[6] + corner_y[6] * relative_y1;
                const float x111 = corner_xz[7] + corner_y[7] * relative_y1;

                const float alpha_y = smoothstep(relative_y);
                const float lower = lerp(alpha_y,
                    lerp(alpha_x, x000, x100),
                    lerp(alpha_x, x010, x110));
                const float upper = lerp(alpha_y,
                    lerp(alpha_x, x001, x101),
                    lerp(alpha_x, x011, x111));
                const float sample = amplitude * lerp(alpha_z, lower, upper);
                output[base + iy] = output[base + iy] + sample;
            }
        }
    }
    return 0;
}
} // namespace

extern "C" int32_t jpe_cpp_perlin_add_volume(
    const uint8_t* permutation,
    const int32_t* block_x,
    const int32_t* block_y,
    const int32_t* block_z,
    uint32_t size_x,
    uint32_t size_y,
    uint32_t size_z,
    double offset_x,
    double offset_y,
    double offset_z,
    double xz_scale,
    double y_scale,
    float amplitude,
    float* output,
    uint64_t output_length,
    uint32_t flags
) noexcept {
    return perlin_add_volume(permutation, block_x, block_y, block_z,
        size_x, size_y, size_z, offset_x, offset_y, offset_z,
        xz_scale, y_scale, amplitude, output, output_length, flags);
}
