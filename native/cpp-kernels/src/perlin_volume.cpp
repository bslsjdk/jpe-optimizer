#include "jpe_native_api.h"

#include <cmath>
#include <cstring>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>
#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
#include <immintrin.h>
#endif

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

struct XyPermutationCache {
    uint8_t xy00;
    uint8_t xy01;
    uint8_t xy10;
    uint8_t xy11;
};

#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
__attribute__((target("avx2")))
inline void perlin_evaluate_8_avx2(
    const float* relative_y, const float* alpha_y, float* output,
    const float* corner_xz, const float* corner_y,
    float alpha_x, float alpha_z, float amplitude
) noexcept {
    const __m256 ry = _mm256_loadu_ps(relative_y);
    const __m256 ay = _mm256_loadu_ps(alpha_y);
    const __m256 one = _mm256_set1_ps(1.0F);
    const __m256 ax = _mm256_set1_ps(alpha_x);
    const __m256 az = _mm256_set1_ps(alpha_z);
    const __m256 amp = _mm256_set1_ps(amplitude);
    const __m256 ry1 = _mm256_sub_ps(ry, one);

    const __m256 x000 = _mm256_add_ps(_mm256_set1_ps(corner_xz[0]), _mm256_mul_ps(_mm256_set1_ps(corner_y[0]), ry));
    const __m256 x100 = _mm256_add_ps(_mm256_set1_ps(corner_xz[1]), _mm256_mul_ps(_mm256_set1_ps(corner_y[1]), ry));
    const __m256 x010 = _mm256_add_ps(_mm256_set1_ps(corner_xz[2]), _mm256_mul_ps(_mm256_set1_ps(corner_y[2]), ry1));
    const __m256 x110 = _mm256_add_ps(_mm256_set1_ps(corner_xz[3]), _mm256_mul_ps(_mm256_set1_ps(corner_y[3]), ry1));
    const __m256 x001 = _mm256_add_ps(_mm256_set1_ps(corner_xz[4]), _mm256_mul_ps(_mm256_set1_ps(corner_y[4]), ry));
    const __m256 x101 = _mm256_add_ps(_mm256_set1_ps(corner_xz[5]), _mm256_mul_ps(_mm256_set1_ps(corner_y[5]), ry));
    const __m256 x011 = _mm256_add_ps(_mm256_set1_ps(corner_xz[6]), _mm256_mul_ps(_mm256_set1_ps(corner_y[6]), ry1));
    const __m256 x111 = _mm256_add_ps(_mm256_set1_ps(corner_xz[7]), _mm256_mul_ps(_mm256_set1_ps(corner_y[7]), ry1));

    const __m256 lower0 = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(x100, x000), ax), x000);
    const __m256 lower1 = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(x110, x010), ax), x010);
    const __m256 upper0 = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(x101, x001), ax), x001);
    const __m256 upper1 = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(x111, x011), ax), x011);
    const __m256 lower = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(lower1, lower0), ay), lower0);
    const __m256 upper = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(upper1, upper0), ay), upper0);
    const __m256 interpolated = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(upper, lower), az), lower);
    const __m256 sample = _mm256_mul_ps(amp, interpolated);
    const __m256 old_output = _mm256_loadu_ps(output);
    _mm256_storeu_ps(output, _mm256_add_ps(old_output, sample));
}
__attribute__((target("avx2")))
inline void perlin_evaluate_8_avx2_independent(
    const float* relative_y, const float* alpha_y, float* output,
    const float corner_xz[8][8], const float corner_y[8][8],
    float alpha_x, float alpha_z, float amplitude
) noexcept {
    const __m256 ry = _mm256_loadu_ps(relative_y);
    const __m256 ay = _mm256_loadu_ps(alpha_y);
    const __m256 one = _mm256_set1_ps(1.0F);
    const __m256 ax = _mm256_set1_ps(alpha_x);
    const __m256 az = _mm256_set1_ps(alpha_z);
    const __m256 amp = _mm256_set1_ps(amplitude);
    const __m256 ry1 = _mm256_sub_ps(ry, one);
    __m256 values[8];
    for (int i = 0; i < 8; ++i) {
        const __m256 cx = _mm256_loadu_ps(corner_xz[i]);
        const __m256 cy = _mm256_loadu_ps(corner_y[i]);
        const __m256 relative = (i == 2 || i == 3 || i == 6 || i == 7) ? ry1 : ry;
        values[i] = _mm256_add_ps(cx, _mm256_mul_ps(cy, relative));
    }
    const __m256 lower0 = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(values[1], values[0]), ax), values[0]);
    const __m256 lower1 = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(values[3], values[2]), ax), values[2]);
    const __m256 upper0 = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(values[5], values[4]), ax), values[4]);
    const __m256 upper1 = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(values[7], values[6]), ax), values[6]);
    const __m256 lower = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(lower1, lower0), ay), lower0);
    const __m256 upper = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(upper1, upper0), ay), upper0);
    const __m256 interpolated = _mm256_add_ps(_mm256_mul_ps(_mm256_sub_ps(upper, lower), az), lower);
    const __m256 sample = _mm256_mul_ps(amp, interpolated);
    const __m256 old_output = _mm256_loadu_ps(output);
    _mm256_storeu_ps(output, _mm256_add_ps(old_output, sample));
}
#endif

template<bool CacheY, bool CacheXY, bool UseAvx2>
int32_t perlin_add_volume_impl(
    const uint8_t* permutation,
    uint32_t size_x,
    uint32_t size_y,
    uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
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
    if (permutation == nullptr || output == nullptr || step_x == 0 || step_y == 0 || step_z == 0) return 1;

    const uint64_t max_u64 = std::numeric_limits<uint64_t>::max();
    if (static_cast<uint64_t>(size_x) > max_u64 / size_y) return 1;
    const uint64_t xy_count = static_cast<uint64_t>(size_x) * size_y;
    if (xy_count > max_u64 / size_z) return 1;
    const uint64_t expected = xy_count * size_z;
    if (expected != output_length) return 1;
    if (expected > static_cast<uint64_t>(std::numeric_limits<size_t>::max() / sizeof(float))) return 1;

    const bool far_lands = (flags & 1U) != 0U;

    // Y coordinates are identical for every X/Z column in this volume.
    // Cache them once per worker thread instead of repeating wrap/floor work
    // for every column. The stored values preserve the exact scalar operation
    // order used by the original inner loop.
    thread_local std::vector<double> y_coordinates;
    thread_local std::vector<int32_t> y_floors;
    thread_local std::vector<float> y_relative;
    thread_local std::vector<float> y_alpha;
    if (y_floors.size() < size_y) {
        y_floors.resize(size_y);
        if constexpr (CacheY) {
            y_relative.resize(size_y);
            y_alpha.resize(size_y);
        } else {
            y_coordinates.resize(size_y);
        }
    }
    for (uint32_t iy = 0; iy < size_y; ++iy) {
        const double y = wrap_coordinate(
            static_cast<double>(axis_coordinate(min_y, iy, step_y)) * y_scale, far_lands) + offset_y;
        const int32_t floor_y = floor_to_i32(y);
        y_floors[iy] = floor_y;
        if constexpr (CacheY) {
            const float relative_y = static_cast<float>(y - static_cast<double>(floor_y));
            y_relative[iy] = relative_y;
            y_alpha[iy] = smoothstep(relative_y);
        } else {
            y_coordinates[iy] = y;
        }
    }

    // X coordinates and their lattice lookups are also invariant across Z.
    // Cache them once per volume so every Z slice avoids repeating wrap/floor,
    // smoothstep and the first two permutation lookups.
    thread_local std::vector<float> x_relative;
    thread_local std::vector<float> x_alpha;
    thread_local std::vector<int32_t> x_lattice0;
    thread_local std::vector<int32_t> x_lattice1;
    if (x_relative.size() < size_x) {
        x_relative.resize(size_x);
        x_alpha.resize(size_x);
        x_lattice0.resize(size_x);
        x_lattice1.resize(size_x);
    }
    for (uint32_t ix = 0; ix < size_x; ++ix) {
        const double x = wrap_coordinate(static_cast<double>(axis_coordinate(min_x, ix, step_x)) * xz_scale, far_lands) + offset_x;
        const int32_t floor_x = floor_to_i32(x);
        const float relative_x = static_cast<float>(x - static_cast<double>(floor_x));
        x_relative[ix] = relative_x;
        x_alpha[ix] = smoothstep(relative_x);
        x_lattice0[ix] = static_cast<int32_t>(permute_sum(permutation, floor_x));
        x_lattice1[ix] = static_cast<int32_t>(permute_sum(permutation, floor_x, 1));
    }

    // The four XY permutation results do not depend on Z. Cache one compact
    // four-byte record per X/Y coordinate and reuse it for every Z slice.
    thread_local std::vector<XyPermutationCache> xy_cache;
    if constexpr (CacheXY) {
        const size_t xy_count = static_cast<size_t>(size_x) * size_y;
        if (xy_cache.size() < xy_count) xy_cache.resize(xy_count);
        for (uint32_t ix = 0; ix < size_x; ++ix) {
            const int32_t x0 = x_lattice0[ix];
            const int32_t x1 = x_lattice1[ix];
            const size_t row = static_cast<size_t>(ix) * size_y;
            for (uint32_t iy = 0; iy < size_y; ++iy) {
                const int32_t floor_y = y_floors[iy];
                xy_cache[row + iy] = XyPermutationCache{
                    static_cast<uint8_t>(permute_sum(permutation, x0, floor_y)),
                    static_cast<uint8_t>(permute_sum(permutation, x0, floor_y, 1)),
                    static_cast<uint8_t>(permute_sum(permutation, x1, floor_y)),
                    static_cast<uint8_t>(permute_sum(permutation, x1, floor_y, 1))
                };
            }
        }
    }

    // Cross-floor SIMD needs four XY permutation values per Y lane. Those
    // values are independent of Z, so prepare them once per X/Y only for
    // eight-lane blocks that actually cross a Y lattice floor. This avoids
    // repeating 32 byte-permutation lookups for every Z slice.
    thread_local std::vector<XyPermutationCache> cross_floor_xy_cache;
    thread_local std::vector<uint8_t> cross_floor_xy_valid;
    if constexpr (CacheY && UseAvx2) {
        const size_t cross_count = static_cast<size_t>(size_x) * size_y;
        if (cross_floor_xy_cache.size() < cross_count) cross_floor_xy_cache.resize(cross_count);
        if (cross_floor_xy_valid.size() < cross_count) cross_floor_xy_valid.resize(cross_count);
        std::fill(cross_floor_xy_valid.begin(), cross_floor_xy_valid.begin() + cross_count, 0);
        for (uint32_t ix = 0; ix < size_x; ++ix) {
            const int32_t x0 = x_lattice0[ix];
            const int32_t x1 = x_lattice1[ix];
            const size_t row = static_cast<size_t>(ix) * size_y;
            for (uint32_t block = 0; block + 8 <= size_y; block += 8) {
                bool same_floor = true;
                for (uint32_t lane = 1; lane < 8; ++lane) {
                    if (y_floors[block + lane] != y_floors[block]) {
                        same_floor = false;
                        break;
                    }
                }
                if (same_floor) continue;
                for (uint32_t lane = 0; lane < 8; ++lane) {
                    const int32_t floor_y = y_floors[block + lane];
                    cross_floor_xy_cache[row + block + lane] = XyPermutationCache{
                        static_cast<uint8_t>(permute_sum(permutation, x0, floor_y)),
                        static_cast<uint8_t>(permute_sum(permutation, x0, floor_y, 1)),
                        static_cast<uint8_t>(permute_sum(permutation, x1, floor_y)),
                        static_cast<uint8_t>(permute_sum(permutation, x1, floor_y, 1))
                    };
                    cross_floor_xy_valid[row + block + lane] = 1;
                }
            }
        }
    }

    for (uint32_t iz = 0; iz < size_z; ++iz) {
        const double z = wrap_coordinate(static_cast<double>(axis_coordinate(min_z, iz, step_z)) * xz_scale, far_lands) + offset_z;
        const int32_t floor_z = floor_to_i32(z);
        const float relative_z = static_cast<float>(z - static_cast<double>(floor_z));
        const float alpha_z = smoothstep(relative_z);

        for (uint32_t ix = 0; ix < size_x; ++ix) {
            const float relative_x = x_relative[ix];
            const float alpha_x = x_alpha[ix];
            const int32_t x0 = x_lattice0[ix];
            const int32_t x1 = x_lattice1[ix];
            const uint64_t base = (static_cast<uint64_t>(iz) * size_x + ix) * size_y;

            uint32_t iy = 0;
            while (iy < size_y) {
#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
                if constexpr (CacheY && UseAvx2) {
                    if (iy + 8 <= size_y) {
                        bool same_floor = true;
                        for (uint32_t lane = 1; lane < 8; ++lane) {
                            if (y_floors[iy + lane] != y_floors[iy]) {
                                same_floor = false;
                                break;
                            }
                        }
                        if (!same_floor) {
                            float lane_corner_xz[8][8];
                            float lane_corner_y[8][8];
                            for (uint32_t lane = 0; lane < 8; ++lane) {
                                const int32_t lane_floor = y_floors[iy + lane];
                                const XyPermutationCache& lane_xy =
                                    cross_floor_xy_cache[static_cast<size_t>(ix) * size_y + iy + lane];
                                const int32_t lane_xy00 = lane_xy.xy00;
                                const int32_t lane_xy01 = lane_xy.xy01;
                                const int32_t lane_xy10 = lane_xy.xy10;
                                const int32_t lane_xy11 = lane_xy.xy11;
                                corner(permutation, static_cast<uint32_t>(lane_xy00) + static_cast<uint32_t>(floor_z), relative_x, relative_z, lane_corner_xz[0][lane], lane_corner_y[0][lane]);
                                corner(permutation, static_cast<uint32_t>(lane_xy10) + static_cast<uint32_t>(floor_z), relative_x - 1.0F, relative_z, lane_corner_xz[1][lane], lane_corner_y[1][lane]);
                                corner(permutation, static_cast<uint32_t>(lane_xy01) + static_cast<uint32_t>(floor_z), relative_x, relative_z, lane_corner_xz[2][lane], lane_corner_y[2][lane]);
                                corner(permutation, static_cast<uint32_t>(lane_xy11) + static_cast<uint32_t>(floor_z), relative_x - 1.0F, relative_z, lane_corner_xz[3][lane], lane_corner_y[3][lane]);
                                corner(permutation, static_cast<uint32_t>(lane_xy00) + static_cast<uint32_t>(floor_z) + 1U, relative_x, relative_z - 1.0F, lane_corner_xz[4][lane], lane_corner_y[4][lane]);
                                corner(permutation, static_cast<uint32_t>(lane_xy10) + static_cast<uint32_t>(floor_z) + 1U, relative_x - 1.0F, relative_z - 1.0F, lane_corner_xz[5][lane], lane_corner_y[5][lane]);
                                corner(permutation, static_cast<uint32_t>(lane_xy01) + static_cast<uint32_t>(floor_z) + 1U, relative_x, relative_z - 1.0F, lane_corner_xz[6][lane], lane_corner_y[6][lane]);
                                corner(permutation, static_cast<uint32_t>(lane_xy11) + static_cast<uint32_t>(floor_z) + 1U, relative_x - 1.0F, relative_z - 1.0F, lane_corner_xz[7][lane], lane_corner_y[7][lane]);
                            }
                            perlin_evaluate_8_avx2_independent(
                                y_relative.data() + iy, y_alpha.data() + iy,
                                output + base + iy, lane_corner_xz, lane_corner_y,
                                alpha_x, alpha_z, amplitude);
                            iy += 8;
                            continue;
                        }
                    }
                }
#endif
                // Gradient corners depend on floor(Y), not on the fractional Y
                // coordinate. Reuse them for the whole contiguous run that maps
                // to the same lattice cell.
                const int32_t floor_y = y_floors[iy];

                int32_t xy00;
                int32_t xy01;
                int32_t xy10;
                int32_t xy11;
                if constexpr (CacheXY) {
                    const XyPermutationCache& cached = xy_cache[
                        static_cast<size_t>(ix) * size_y + iy];
                    xy00 = cached.xy00;
                    xy01 = cached.xy01;
                    xy10 = cached.xy10;
                    xy11 = cached.xy11;
                } else {
                    xy00 = static_cast<int32_t>(permute_sum(permutation, x0, floor_y));
                    xy01 = static_cast<int32_t>(permute_sum(permutation, x0, floor_y, 1));
                    xy10 = static_cast<int32_t>(permute_sum(permutation, x1, floor_y));
                    xy11 = static_cast<int32_t>(permute_sum(permutation, x1, floor_y, 1));
                }

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

#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
                if constexpr (CacheY && UseAvx2) {
                    uint32_t run_end = iy + 1;
                    while (run_end < size_y && y_floors[run_end] == floor_y) ++run_end;
                    while (iy + 8 <= run_end) {
                        perlin_evaluate_8_avx2(
                            y_relative.data() + iy, y_alpha.data() + iy,
                            output + base + iy, corner_xz, corner_y,
                            alpha_x, alpha_z, amplitude);
                        iy += 8;
                    }
                }
#endif
                while (iy < size_y && y_floors[iy] == floor_y) {
                    float relative_y;
                    float alpha_y;
                    if constexpr (CacheY) {
                        relative_y = y_relative[iy];
                        alpha_y = y_alpha[iy];
                    } else {
                        relative_y = static_cast<float>(
                            y_coordinates[iy] - static_cast<double>(floor_y));
                        alpha_y = smoothstep(relative_y);
                    }
                    const float relative_y1 = relative_y - 1.0F;

                    const float x000 = corner_xz[0] + corner_y[0] * relative_y;
                    const float x100 = corner_xz[1] + corner_y[1] * relative_y;
                    const float x010 = corner_xz[2] + corner_y[2] * relative_y1;
                    const float x110 = corner_xz[3] + corner_y[3] * relative_y1;
                    const float x001 = corner_xz[4] + corner_y[4] * relative_y;
                    const float x101 = corner_xz[5] + corner_y[5] * relative_y;
                    const float x011 = corner_xz[6] + corner_y[6] * relative_y1;
                    const float x111 = corner_xz[7] + corner_y[7] * relative_y1;

                    const float lower = lerp(alpha_y,
                        lerp(alpha_x, x000, x100),
                        lerp(alpha_x, x010, x110));
                    const float upper = lerp(alpha_y,
                        lerp(alpha_x, x001, x101),
                        lerp(alpha_x, x011, x111));
                    const float sample = amplitude * lerp(alpha_z, lower, upper);
                    output[base + iy] = output[base + iy] + sample;
                    ++iy;
                }
            }
        }
    }
    return 0;
}
} // namespace

extern "C" int32_t jpe_cpp_perlin_add_volume(
    const uint8_t* permutation,
    uint32_t size_x,
    uint32_t size_y,
    uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
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
#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
    static const bool avx2_available = []() noexcept {
        __builtin_cpu_init();
        return __builtin_cpu_supports("avx2");
    }();
    const double y_step_magnitude = std::abs(static_cast<double>(step_y) * y_scale);
    if (avx2_available && y_step_magnitude <= (1.0 / 16.0)) {
        return perlin_add_volume_impl<true, false, true>(permutation,
            size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z, offset_x, offset_y, offset_z,
            xz_scale, y_scale, amplitude, output, output_length, flags);
    }
#endif
    return perlin_add_volume_impl<true, false, false>(permutation,
        size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z, offset_x, offset_y, offset_z,
        xz_scale, y_scale, amplitude, output, output_length, flags);
}

extern "C" int32_t jpe_cpp_perlin_add_volume_scalar_cached_y(
    const uint8_t* permutation,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double offset_x, double offset_y, double offset_z,
    double xz_scale, double y_scale, float amplitude,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    return perlin_add_volume_impl<true, false, false>(permutation,
        size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z, offset_x, offset_y, offset_z,
        xz_scale, y_scale, amplitude, output, output_length, flags);
}

extern "C" int32_t jpe_cpp_perlin_avx2_supported() noexcept {
#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
    __builtin_cpu_init();
    return __builtin_cpu_supports("avx2") ? 1 : 0;
#else
    return 0;
#endif
}

extern "C" int32_t jpe_cpp_perlin_add_volume_uncached_y(
    const uint8_t* permutation,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double offset_x, double offset_y, double offset_z,
    double xz_scale, double y_scale, float amplitude,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    return perlin_add_volume_impl<false, false, false>(permutation,
        size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z, offset_x, offset_y, offset_z,
        xz_scale, y_scale, amplitude, output, output_length, flags);
}

extern "C" int32_t jpe_cpp_perlin_add_volume_cached_xy(
    const uint8_t* permutation,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double offset_x, double offset_y, double offset_z,
    double xz_scale, double y_scale, float amplitude,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    return perlin_add_volume_impl<true, true, false>(permutation,
        size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z, offset_x, offset_y, offset_z,
        xz_scale, y_scale, amplitude, output, output_length, flags);
}

extern "C" int32_t jpe_cpp_perlin_add_volume_uncached_xy(
    const uint8_t* permutation,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double offset_x, double offset_y, double offset_z,
    double xz_scale, double y_scale, float amplitude,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    return perlin_add_volume_impl<true, false, false>(permutation,
        size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z, offset_x, offset_y, offset_z,
        xz_scale, y_scale, amplitude, output, output_length, flags);
}

