#include "jpe_native_api.h"

#include <cmath>
#include <cstdint>

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

extern "C" JPE_API int32_t jpe_runtime_aquifer_compute_substance_core(
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
