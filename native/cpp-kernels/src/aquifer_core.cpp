#include "jpe_native_api.h"

#include <cmath>
#include <cstddef>
#include <cstdint>

static_assert(sizeof(JpeAquiferFluidStatus) == 8, "Aquifer fluid-status ABI mismatch");
static_assert(sizeof(JpeAquiferCoreInput) == 88, "Aquifer core input ABI mismatch");
static_assert(sizeof(JpeAquiferCoreOutput) == 12, "Aquifer core output ABI mismatch");
static_assert(sizeof(JpeAquiferCellLocation) == 12, "Aquifer cell-location ABI mismatch");
static_assert(sizeof(JpeAquiferNearestFour) == 32, "Aquifer nearest-four ABI mismatch");
// Offsets are consumed directly by NativeRuntime's Java 25 FFM packer.
static_assert(offsetof(JpeAquiferCoreInput, block_y) == 0, "Aquifer input block_y offset mismatch");
static_assert(offsetof(JpeAquiferCoreInput, density) == 8, "Aquifer input density offset mismatch");
static_assert(offsetof(JpeAquiferCoreInput, barrier_noise) == 16, "Aquifer input barrier offset mismatch");
static_assert(offsetof(JpeAquiferCoreInput, distance_sqr) == 20, "Aquifer input distance offset mismatch");
static_assert(offsetof(JpeAquiferCoreInput, closest) == 36, "Aquifer input status offset mismatch");
static_assert(offsetof(JpeAquiferCoreInput, global_fluid_below_type) == 68, "Aquifer input global fluid offset mismatch");
static_assert(offsetof(JpeAquiferCoreInput, debug_disable_fluid_generation) == 84, "Aquifer input debug flag offset mismatch");
static_assert(offsetof(JpeAquiferCoreOutput, has_block_state) == 0, "Aquifer output block flag offset mismatch");
static_assert(offsetof(JpeAquiferCoreOutput, block_state_type) == 4, "Aquifer output state token offset mismatch");
static_assert(offsetof(JpeAquiferCoreOutput, should_schedule_fluid_update) == 8, "Aquifer output update flag offset mismatch");

namespace {
double aquifer_similarity(int32_t first, int32_t second) noexcept {
    return 1.0 - static_cast<double>(static_cast<int64_t>(second) - first) / 25.0;
}
uint32_t fluid_at(const JpeAquiferFluidStatus& status, int32_t y, uint32_t air) noexcept {
    return y < status.fluid_level ? status.fluid_type : air;
}
bool same_status(const JpeAquiferFluidStatus& a, const JpeAquiferFluidStatus& b) noexcept {
    return a.fluid_level == b.fluid_level && a.fluid_type == b.fluid_type;
}
double calculate_pressure(const JpeAquiferCoreInput& in,
                          const JpeAquiferFluidStatus& a,
                          const JpeAquiferFluidStatus& b) noexcept {
    const uint32_t ta = fluid_at(a, in.block_y, in.air_type);
    const uint32_t tb = fluid_at(b, in.block_y, in.air_type);
    if ((ta == in.lava_type && tb == in.water_type)
        || (ta == in.water_type && tb == in.lava_type)) return 2.0;
    const int64_t diff = static_cast<int64_t>(a.fluid_level) - b.fluid_level;
    const double fluid_diff = static_cast<double>(diff < 0 ? -diff : diff);
    if (fluid_diff == 0.0) return 0.0;
    const int32_t summed = static_cast<int32_t>(
        static_cast<uint32_t>(a.fluid_level) + static_cast<uint32_t>(b.fluid_level));
    const double average = 0.5 * static_cast<double>(summed);
    const double above_average = static_cast<double>(in.block_y) + 0.5 - average;
    const double base = fluid_diff / 2.0;
    const double edge_distance = base - std::abs(above_average);
    double gradient;
    if (above_average > 0.0) {
        gradient = edge_distance > 0.0 ? edge_distance / 1.5 : edge_distance / 2.5;
    } else {
        const double center = 3.0 + edge_distance;
        gradient = center > 0.0 ? center / 3.0 : center / 10.0;
    }
    const double noise = gradient < -2.0 || gradient > 2.0
        ? 0.0 : static_cast<double>(in.barrier_noise);
    return 2.0 * (noise + gradient);
}
}

extern "C" JPE_API int32_t jpe_cpp_aquifer_compute_substance_core(
    const JpeAquiferCoreInput* in, JpeAquiferCoreOutput* out) {
    if (in == nullptr || out == nullptr
        || in->air_type == in->water_type || in->air_type == in->lava_type
        || in->water_type == in->lava_type
        || in->distance_sqr[0] < 0 || in->distance_sqr[1] < 0
        || in->distance_sqr[2] < 0 || in->distance_sqr[3] < 0) return 1;

    out->has_block_state = 0;
    out->block_state_type = in->air_type;
    out->should_schedule_fluid_update = 0;
    if (in->density > 0.0) return 0;

    const auto& a = in->closest[0];
    const auto& b = in->closest[1];
    const auto& c = in->closest[2];
    const auto& d = in->closest[3];
    const double sim12 = aquifer_similarity(in->distance_sqr[0], in->distance_sqr[1]);
    const uint32_t fluid_type = fluid_at(a, in->block_y, in->air_type);
    out->has_block_state = 1;
    out->block_state_type = in->debug_disable_fluid_generation
        ? in->air_type : fluid_type;

    constexpr double flowing_update_similarity = 1.0 - 44.0 / 25.0;
    if (sim12 <= 0.0) {
        out->should_schedule_fluid_update =
            sim12 >= flowing_update_similarity && !same_status(a, b);
        return 0;
    }
    if (fluid_type == in->water_type && in->global_fluid_below_type == in->lava_type) {
        out->should_schedule_fluid_update = 1;
        return 0;
    }
    if (in->density + sim12 * calculate_pressure(*in, a, b) > 0.0) {
        out->has_block_state = 0;
        return 0;
    }
    const double sim13 = aquifer_similarity(in->distance_sqr[0], in->distance_sqr[2]);
    if (sim13 > 0.0 && in->density + sim12 * sim13 * calculate_pressure(*in, a, c) > 0.0) {
        out->has_block_state = 0;
        return 0;
    }
    const double sim23 = aquifer_similarity(in->distance_sqr[1], in->distance_sqr[2]);
    if (sim23 > 0.0 && in->density + sim12 * sim23 * calculate_pressure(*in, b, c) > 0.0) {
        out->has_block_state = 0;
        return 0;
    }
    const bool flow12 = !same_status(a, b);
    const bool flow23 = sim23 >= flowing_update_similarity && !same_status(b, c);
    const bool flow13 = sim13 >= flowing_update_similarity && !same_status(a, c);
    out->should_schedule_fluid_update =
        flow12 || flow23 || flow13
        || (sim13 >= flowing_update_similarity
            && aquifer_similarity(in->distance_sqr[0], in->distance_sqr[3])
                >= flowing_update_similarity
            && !same_status(a, d));
    return 0;
}


extern "C" JPE_API int32_t jpe_cpp_aquifer_compute_substance_batch(
    const JpeAquiferCoreInput* inputs, uint32_t count,
    JpeAquiferCoreOutput* outputs
) {
    constexpr uint32_t max_batch = 1024U * 1024U;
    if (inputs == nullptr || outputs == nullptr || count == 0 || count > max_batch) return 1;

    // Validate the complete input range before writing anything. This makes a
    // rejected batch transactional from the caller's perspective.
    for (uint32_t i = 0; i < count; ++i) {
        const auto& in = inputs[i];
        if (in.air_type == in.water_type || in.air_type == in.lava_type
            || in.water_type == in.lava_type
            || in.distance_sqr[0] < 0 || in.distance_sqr[1] < 0
            || in.distance_sqr[2] < 0 || in.distance_sqr[3] < 0) return 1;
    }

    for (uint32_t i = 0; i < count; ++i) {
        if (jpe_cpp_aquifer_compute_substance_core(&inputs[i], &outputs[i]) != 0) return 1;
    }
    return 0;
}

extern "C" JPE_API int32_t jpe_runtime_aquifer_find_nearest_four(
    int32_t block_x, int32_t block_y, int32_t block_z,
    const JpeAquiferCellLocation* candidates, uint32_t candidate_count,
    JpeAquiferNearestFour* output
) {
    if (candidates == nullptr || output == nullptr || candidate_count != 12) return 1;

    int32_t d1 = INT32_MAX;
    int32_t d2 = INT32_MAX;
    int32_t d3 = INT32_MAX;
    int32_t d4 = INT32_MAX;
    uint32_t i1 = 0, i2 = 0, i3 = 0, i4 = 0;
    for (uint32_t index = 0; index < candidate_count; ++index) {
        const int64_t dx = static_cast<int64_t>(candidates[index].x) - block_x;
        const int64_t dy = static_cast<int64_t>(candidates[index].y) - block_y;
        const int64_t dz = static_cast<int64_t>(candidates[index].z) - block_z;
        // Minecraft's squared-distance result is represented as int32.
        // Reject out-of-range deltas before multiplication so malformed or
        // extreme coordinates cannot overflow signed int64 during validation.
        constexpr int64_t max_axis_delta = 46340;
        if (dx < -max_axis_delta || dx > max_axis_delta
            || dy < -max_axis_delta || dy > max_axis_delta
            || dz < -max_axis_delta || dz > max_axis_delta) return 1;
        const int64_t squared = dx * dx + dy * dy + dz * dz;
        if (squared > INT32_MAX) return 1;
        const int32_t distance = static_cast<int32_t>(squared);

        // This ordering intentionally mirrors the 26.3 insertion chain:
        // >= means a later candidate wins a tie at the same rank.
        if (d1 >= distance) {
            d4 = d3; i4 = i3;
            d3 = d2; i3 = i2;
            d2 = d1; i2 = i1;
            d1 = distance; i1 = index;
        } else if (d2 >= distance) {
            d4 = d3; i4 = i3;
            d3 = d2; i3 = i2;
            d2 = distance; i2 = index;
        } else if (d3 >= distance) {
            d4 = d3; i4 = i3;
            d3 = distance; i3 = index;
        } else if (d4 >= distance) {
            d4 = distance; i4 = index;
        }
    }

    output->index[0] = i1;
    output->index[1] = i2;
    output->index[2] = i3;
    output->index[3] = i4;
    output->distance_sqr[0] = d1;
    output->distance_sqr[1] = d2;
    output->distance_sqr[2] = d3;
    output->distance_sqr[3] = d4;
    return 0;
}
