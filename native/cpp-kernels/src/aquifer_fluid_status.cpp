#include "jpe_native_api.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

static_assert(sizeof(JpeAquiferFluidStatus) == 8, "Aquifer fluid-status ABI mismatch");
static_assert(sizeof(JpeAquiferFluidProbe) == 16, "Aquifer fluid-probe ABI mismatch");
static_assert(sizeof(JpeAquiferFluidStatusInput) == 296, "Aquifer fluid-status input ABI mismatch");
static_assert(offsetof(JpeAquiferFluidStatusInput, global_fluid) == 4,
              "Aquifer global-fluid offset mismatch");
static_assert(offsetof(JpeAquiferFluidStatusInput, surface_level) == 12,
              "Aquifer surface-level offset mismatch");
static_assert(offsetof(JpeAquiferFluidStatusInput, global_fluid_at_surface) == 64,
              "Aquifer surface FluidPicker offset mismatch");
static_assert(offsetof(JpeAquiferFluidStatusInput, exclusion_noise) == 272,
              "Aquifer exclusion sample offset mismatch");
static_assert(offsetof(JpeAquiferFluidStatusInput, lava_type) == 292,
              "Aquifer lava token offset mismatch");

namespace {
constexpr uint32_t kProbeCount = 13;
constexpr uint32_t kMaxBatch = 1024U * 1024U;
// Minecraft 26.3 DimensionType.WAY_BELOW_MIN_Y (12 packed Y bits).
constexpr int32_t kWayBelowMinY = -32512;

constexpr int32_t kSurfaceOffsetX[kProbeCount] = {
    0, -2, -1, 0, 1, -3, -2, -1, 1, -2, -1, 0, 1
};
constexpr int32_t kSurfaceOffsetZ[kProbeCount] = {
    0, -1, -1, -1, -1, 0, 0, 0, 0, 1, 1, 1, 1
};
static_assert(sizeof(kSurfaceOffsetX) / sizeof(kSurfaceOffsetX[0]) == kProbeCount,
              "Aquifer surface X schedule must have thirteen entries");
static_assert(sizeof(kSurfaceOffsetZ) / sizeof(kSurfaceOffsetZ[0]) == kProbeCount,
              "Aquifer surface Z schedule must have thirteen entries");

int32_t from_i32_bits(uint32_t bits) noexcept {
    int32_t result;
    std::memcpy(&result, &bits, sizeof(result));
    return result;
}
int32_t wrapping_add(int32_t a, int32_t b) noexcept {
    return from_i32_bits(static_cast<uint32_t>(a) + static_cast<uint32_t>(b));
}
int32_t wrapping_subtract(int32_t a, int32_t b) noexcept {
    return from_i32_bits(static_cast<uint32_t>(a) - static_cast<uint32_t>(b));
}
int32_t wrapping_multiply(int32_t a, int32_t b) noexcept {
    return from_i32_bits(static_cast<uint32_t>(a) * static_cast<uint32_t>(b));
}
int32_t floor_divide(int32_t value, int32_t divisor) noexcept {
    int32_t quotient = value / divisor;
    const int32_t remainder = value % divisor;
    if (remainder < 0) --quotient;
    return quotient;
}
int32_t java_floor_to_i32(double value) noexcept {
    if (std::isnan(value)) return 0;
    const double floored = std::floor(value);
    if (floored >= static_cast<double>(std::numeric_limits<int32_t>::max())) {
        return std::numeric_limits<int32_t>::max();
    }
    if (floored <= static_cast<double>(std::numeric_limits<int32_t>::min())) {
        return std::numeric_limits<int32_t>::min();
    }
    return static_cast<int32_t>(floored);
}
double lerp(double alpha, double start, double end) noexcept {
    return start + alpha * (end - start);
}
double inverse_lerp(double value, double start, double end) noexcept {
    return (value - start) / (end - start);
}
double clamped_map(double value, double from_min, double from_max,
                   double to_min, double to_max) noexcept {
    const double alpha = inverse_lerp(value, from_min, from_max);
    if (alpha < 0.0) return to_min;
    if (alpha > 1.0) return to_max;
    return lerp(alpha, to_min, to_max);
}
bool address_ranges_overlap(const void* first, size_t first_size,
                            const void* second, size_t second_size) noexcept {
    if (first_size == 0 || second_size == 0) return false;
    const uintptr_t first_begin = reinterpret_cast<uintptr_t>(first);
    const uintptr_t second_begin = reinterpret_cast<uintptr_t>(second);
    constexpr uintptr_t max_address = std::numeric_limits<uintptr_t>::max();
    if (first_size > max_address - first_begin
        || second_size > max_address - second_begin) return true;
    const uintptr_t first_end = first_begin + static_cast<uintptr_t>(first_size);
    const uintptr_t second_end = second_begin + static_cast<uintptr_t>(second_size);
    return first_begin < second_end && second_begin < first_end;
}
bool probe_is_air_at(const JpeAquiferFluidProbe& probe, int32_t sample_y) noexcept {
    return sample_y >= probe.status.fluid_level || probe.fluid_type_is_air != 0;
}

bool validate_status_input(const JpeAquiferFluidStatusInput& input) noexcept {
    for (uint32_t i = 0; i < kProbeCount; ++i) {
        const auto& probe = input.global_fluid_at_surface[i];
        if (probe.valid > 1U || probe.fluid_type_is_air > 1U) return false;
    }

    const int32_t bottom_of_cell = wrapping_subtract(input.block_y, 12);
    const int32_t top_of_cell = wrapping_add(input.block_y, 12);
    for (uint32_t i = 0; i < kProbeCount; ++i) {
        const int32_t adjusted_surface = wrapping_add(input.surface_level[i], 8);
        const bool is_center = i == 0;
        if (is_center && bottom_of_cell > adjusted_surface) return true;

        const bool top_pokes_above_surface = top_of_cell > adjusted_surface;
        if (!top_pokes_above_surface && !is_center) continue;
        if (input.global_fluid_at_surface[i].valid == 0U) return false;

        // Vanilla returns immediately on the first non-air FluidPicker result
        // whose surface is above the aquifer-cell top. No later probe is read.
        if (!probe_is_air_at(input.global_fluid_at_surface[i], adjusted_surface)
            && top_pokes_above_surface) {
            return true;
        }
    }
    return true;
}

JpeAquiferFluidStatus compute_status(const JpeAquiferFluidStatusInput& input) noexcept {
    const int32_t bottom_of_cell = wrapping_subtract(input.block_y, 12);
    const int32_t top_of_cell = wrapping_add(input.block_y, 12);
    int32_t lowest_preliminary_surface = std::numeric_limits<int32_t>::max();
    bool surface_at_center_is_under_global_fluid_level = false;

    for (uint32_t i = 0; i < kProbeCount; ++i) {
        const int32_t preliminary_surface = input.surface_level[i];
        const int32_t adjusted_surface = wrapping_add(preliminary_surface, 8);
        const bool is_center = i == 0;
        if (is_center && bottom_of_cell > adjusted_surface) {
            return input.global_fluid;
        }

        const bool top_pokes_above_surface = top_of_cell > adjusted_surface;
        if (top_pokes_above_surface || is_center) {
            const auto& global_at_surface = input.global_fluid_at_surface[i];
            if (!probe_is_air_at(global_at_surface, adjusted_surface)) {
                if (is_center) {
                    surface_at_center_is_under_global_fluid_level = true;
                }
                if (top_pokes_above_surface) {
                    return global_at_surface.status;
                }
            }
        }
        lowest_preliminary_surface = std::min(lowest_preliminary_surface, preliminary_surface);
    }

    double partially_floodedness;
    double fully_floodedness;
    if (input.exclusion_noise > 0.0F) {
        partially_floodedness = -1.0;
        fully_floodedness = -1.0;
    } else {
        const int32_t distance_below_surface =
            wrapping_subtract(wrapping_add(lowest_preliminary_surface, 8), input.block_y);
        const double floodedness_factor = surface_at_center_is_under_global_fluid_level
            ? clamped_map(static_cast<double>(distance_below_surface), 0.0, 64.0, 1.0, 0.0)
            : 0.0;
        double floodedness_noise = static_cast<double>(input.floodedness_noise);
        // Preserve NaN exactly as Mth.clamp does; comparisons with NaN stay false.
        if (floodedness_noise < -1.0) floodedness_noise = -1.0;
        else if (floodedness_noise > 1.0) floodedness_noise = 1.0;

        const double fully_flooded_threshold =
            lerp(inverse_lerp(floodedness_factor, 1.0, 0.0), -0.3, 0.8);
        const double partially_flooded_threshold =
            lerp(inverse_lerp(floodedness_factor, 1.0, 0.0), -0.8, 0.4);
        partially_floodedness = floodedness_noise - partially_flooded_threshold;
        fully_floodedness = floodedness_noise - fully_flooded_threshold;
    }

    int32_t fluid_surface_level = kWayBelowMinY;
    if (fully_floodedness > 0.0) {
        fluid_surface_level = input.global_fluid.fluid_level;
    } else if (partially_floodedness > 0.0) {
        const int32_t fluid_level_cell_y = floor_divide(input.block_y, 40);
        const int32_t fluid_cell_middle_y =
            wrapping_add(wrapping_multiply(fluid_level_cell_y, 40), 20);
        // Vanilla's sampleValue returns float and the 10.0f product rounds as float
        // before the value is widened for Mth.quantize(double, 3).
        const float spread_scaled = input.fluid_level_spread_noise * 10.0F;
        const int32_t spread_quantized = wrapping_multiply(
            java_floor_to_i32(static_cast<double>(spread_scaled) / 3.0), 3);
        const int32_t target_fluid_surface_level =
            wrapping_add(fluid_cell_middle_y, spread_quantized);
        fluid_surface_level = std::min(lowest_preliminary_surface, target_fluid_surface_level);
    }

    uint32_t fluid_type = input.global_fluid.fluid_type;
    if (fluid_surface_level <= -10
        && fluid_surface_level != kWayBelowMinY
        && input.global_fluid.fluid_type != input.lava_type
        && std::fabs(static_cast<double>(input.lava_noise)) > 0.3) {
        fluid_type = input.lava_type;
    }
    return JpeAquiferFluidStatus{fluid_surface_level, fluid_type};
}
}

extern "C" JPE_API int32_t jpe_cpp_aquifer_compute_fluid_status_batch(
    const JpeAquiferFluidStatusInput* inputs,
    uint32_t count,
    JpeAquiferFluidStatus* outputs
) {
    if (inputs == nullptr || outputs == nullptr || count == 0 || count > kMaxBatch) return 1;
    const size_t input_bytes = static_cast<size_t>(count) * sizeof(*inputs);
    const size_t output_bytes = static_cast<size_t>(count) * sizeof(*outputs);
    if (address_ranges_overlap(inputs, input_bytes, outputs, output_bytes)) return 1;

    // Validate every probe that vanilla's short-circuit order will read before
    // touching any caller-owned output slot.
    for (uint32_t i = 0; i < count; ++i) {
        if (!validate_status_input(inputs[i])) return 1;
    }
    for (uint32_t i = 0; i < count; ++i) {
        outputs[i] = compute_status(inputs[i]);
    }
    return 0;
}
