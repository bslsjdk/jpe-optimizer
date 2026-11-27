#include "jpe_native_api.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

extern "C" int32_t jpe_cpp_perlin_add_stack_fused(
    const JpePerlinLayer*, uint32_t, uint32_t, uint32_t, uint32_t,
    int32_t, int32_t, int32_t, int32_t, int32_t, int32_t,
    float*, uint64_t, uint32_t) noexcept;
extern "C" int32_t jpe_cpp_perlin_add_smeared_stack_fused(
    const JpePerlinLayer*, const double*, uint32_t, uint32_t, uint32_t, uint32_t,
    int32_t, int32_t, int32_t, int32_t, int32_t, int32_t,
    float*, uint64_t, uint32_t) noexcept;
extern "C" int32_t jpe_cpp_perlin_sample_point(
    const uint8_t*, int32_t, int32_t, int32_t,
    double, double, double, double, double, float, float*) noexcept;

namespace {
constexpr uint32_t kAllowZeroSteps = 1U << 31;
struct PreparedLayer {
    std::array<uint8_t, 256> permutation{};
    double offset_x = 0.0;
    double offset_y = 0.0;
    double offset_z = 0.0;
    double base_xz_frequency = 0.0;
    double base_y_frequency = 0.0;
    float base_amplitude = 0.0F;
    double fudge_y_scale = 0.0;
};
struct PreparedStack {
    std::vector<PreparedLayer> layers;
};
std::mutex g_prepared_mutex;
uint64_t g_next_prepared_handle = 1;
std::unordered_map<uint64_t, std::shared_ptr<const PreparedStack>> g_prepared_stacks;

bool valid_prepared_volume(uint32_t sx, uint32_t sy, uint32_t sz,
                           int32_t dx, int32_t dy, int32_t dz,
                           uint64_t output_length, uint32_t flags) noexcept {
    if (sx == 0 || sy == 0 || sz == 0
        || ((flags & kAllowZeroSteps) == 0 && (dx == 0 || dy == 0 || dz == 0))) return false;
    const uint64_t xy = static_cast<uint64_t>(sx) * sy;
    if (xy > 1'048'576ULL / sz) return false;
    return xy * sz == output_length && output_length <= 1'048'576ULL;
}
}

extern "C" uint64_t jpe_cpp_smeared_perlin_stack_prepare(
    const JpePerlinLayer* layers, const double* fudge_y_scales, uint32_t layer_count
) noexcept {
    if (layers == nullptr || fudge_y_scales == nullptr || layer_count == 0 || layer_count > 64) return 0;
    try {
        auto prepared = std::make_shared<PreparedStack>();
        prepared->layers.reserve(layer_count);
        for (uint32_t i = 0; i < layer_count; ++i) {
            const JpePerlinLayer& source = layers[i];
            const double fudge = fudge_y_scales[i];
            if (source.permutation == nullptr || !std::isfinite(fudge) || fudge <= 0.0) return 0;
            PreparedLayer layer;
            std::memcpy(layer.permutation.data(), source.permutation, layer.permutation.size());
            layer.offset_x = source.offset_x;
            layer.offset_y = source.offset_y;
            layer.offset_z = source.offset_z;
            // The Java bridge bakes NoiseFunction and NoiseStack frequency transforms
            // into each descriptor; fudge_y_scale remains in the same Y coordinate space.
            layer.base_xz_frequency = source.xz_scale;
            layer.base_y_frequency = source.y_scale;
            layer.base_amplitude = source.amplitude;
            layer.fudge_y_scale = fudge;
            prepared->layers.push_back(layer);
        }
        std::lock_guard<std::mutex> lock(g_prepared_mutex);
        uint64_t handle = g_next_prepared_handle++;
        if (handle == 0) handle = g_next_prepared_handle++;
        g_prepared_stacks.emplace(handle, std::move(prepared));
        return handle;
    } catch (...) {
        return 0;
    }
}

extern "C" uint64_t jpe_cpp_perlin_stack_prepare(
    const JpePerlinLayer* layers, uint32_t layer_count
) noexcept {
    if (layers == nullptr || layer_count == 0 || layer_count > 64) return 0;
    try {
        auto prepared = std::make_shared<PreparedStack>();
        prepared->layers.reserve(layer_count);
        for (uint32_t i = 0; i < layer_count; ++i) {
            const JpePerlinLayer& source = layers[i];
            if (source.permutation == nullptr) return 0;
            PreparedLayer layer;
            std::memcpy(layer.permutation.data(), source.permutation, layer.permutation.size());
            layer.offset_x = source.offset_x;
            layer.offset_y = source.offset_y;
            layer.offset_z = source.offset_z;
            // During preparation xz_scale/y_scale carry the immutable layer frequency.
            layer.base_xz_frequency = source.xz_scale;
            layer.base_y_frequency = source.y_scale;
            layer.base_amplitude = source.amplitude;
            prepared->layers.push_back(layer);
        }
        std::lock_guard<std::mutex> lock(g_prepared_mutex);
        uint64_t handle = g_next_prepared_handle++;
        if (handle == 0) handle = g_next_prepared_handle++;
        g_prepared_stacks.emplace(handle, std::move(prepared));
        return handle;
    } catch (...) {
        return 0;
    }
}

extern "C" int32_t jpe_cpp_perlin_stack_evaluate(
    uint64_t handle, double xz_scale, double y_scale, float amplitude,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    if (output == nullptr || !valid_prepared_volume(size_x, size_y, size_z,
            step_x, step_y, step_z, output_length, flags)) return 1;
    std::shared_ptr<const PreparedStack> prepared;
    {
        std::lock_guard<std::mutex> lock(g_prepared_mutex);
        const auto found = g_prepared_stacks.find(handle);
        if (found == g_prepared_stacks.end()) return 2;
        prepared = found->second;
    }

    try {
        // Each calling worldgen thread owns its descriptor scratch. The immutable
        // prepared permutations remain shared and are never copied per chunk.
        thread_local std::vector<JpePerlinLayer> descriptors;
        if (descriptors.size() < prepared->layers.size()) descriptors.resize(prepared->layers.size());
        for (size_t i = 0; i < prepared->layers.size(); ++i) {
            const PreparedLayer& source = prepared->layers[i];
            JpePerlinLayer& target = descriptors[i];
            target.permutation = source.permutation.data();
            target.offset_x = source.offset_x;
            target.offset_y = source.offset_y;
            target.offset_z = source.offset_z;
            target.xz_scale = xz_scale * source.base_xz_frequency;
            target.y_scale = y_scale * source.base_y_frequency;
            target.amplitude = amplitude * source.base_amplitude;
        }
        return jpe_cpp_perlin_add_stack_fused(descriptors.data(),
                static_cast<uint32_t>(prepared->layers.size()),
                size_x, size_y, size_z, min_x, min_y, min_z,
                step_x, step_y, step_z, output, output_length, flags);
    } catch (...) {
        return 3;
    }
}

extern "C" int32_t jpe_cpp_smeared_perlin_stack_evaluate(
    uint64_t handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    if (output == nullptr || !valid_prepared_volume(size_x, size_y, size_z,
            step_x, step_y, step_z, output_length, flags)) return 1;
    std::shared_ptr<const PreparedStack> prepared;
    {
        std::lock_guard<std::mutex> lock(g_prepared_mutex);
        const auto found = g_prepared_stacks.find(handle);
        if (found == g_prepared_stacks.end()) return 2;
        prepared = found->second;
    }
    try {
        thread_local std::vector<JpePerlinLayer> descriptors;
        thread_local std::vector<double> fudge_y_scales;
        if (descriptors.size() < prepared->layers.size()) descriptors.resize(prepared->layers.size());
        if (fudge_y_scales.size() < prepared->layers.size()) fudge_y_scales.resize(prepared->layers.size());
        for (size_t i = 0; i < prepared->layers.size(); ++i) {
            const PreparedLayer& source = prepared->layers[i];
            if (!(source.fudge_y_scale > 0.0)) return 1;
            JpePerlinLayer& target = descriptors[i];
            target.permutation = source.permutation.data();
            target.offset_x = source.offset_x;
            target.offset_y = source.offset_y;
            target.offset_z = source.offset_z;
            target.xz_scale = source.base_xz_frequency;
            target.y_scale = source.base_y_frequency;
            target.amplitude = source.base_amplitude;
            fudge_y_scales[i] = source.fudge_y_scale;
        }
        return jpe_cpp_perlin_add_smeared_stack_fused(
            descriptors.data(), fudge_y_scales.data(),
            static_cast<uint32_t>(prepared->layers.size()),
            size_x, size_y, size_z, min_x, min_y, min_z,
            step_x, step_y, step_z, output, output_length, flags);
    } catch (...) {
        return 3;
    }
}

struct PreparedWarpLayer {
    const PreparedLayer* layer = nullptr;
    double frequency_xz = 0.0;
    double frequency_y = 0.0;
};

inline int32_t sample_warped_prepared_plan(
    const PreparedWarpLayer* plan, size_t layer_count,
    int32_t x, int32_t y, int32_t z,
    double shift_x, double shift_y, double shift_z, float* output
) noexcept {
    if (plan == nullptr || output == nullptr || !std::isfinite(shift_x)
        || !std::isfinite(shift_y) || !std::isfinite(shift_z)) return 1;
    float result = 0.0F;
    for (size_t i = 0; i < layer_count; ++i) {
        const PreparedLayer& layer = *plan[i].layer;
        const int32_t status = jpe_cpp_perlin_sample_point(
            layer.permutation.data(), x, y, z,
            layer.offset_x + shift_x * layer.base_xz_frequency,
            layer.offset_y + shift_y * layer.base_y_frequency,
            layer.offset_z + shift_z * layer.base_xz_frequency,
            plan[i].frequency_xz, plan[i].frequency_y,
            layer.base_amplitude, &result);
        if (status != 0) return status;
    }
    *output = result;
    return 0;
}

inline int32_t sample_warped_prepared(
    const PreparedStack& prepared, int32_t x, int32_t y, int32_t z,
    double shift_x, double shift_y, double shift_z,
    double xz_scale, double y_scale, float* output
) noexcept {
    if (output == nullptr || !std::isfinite(xz_scale) || !std::isfinite(y_scale)) return 1;
    std::array<PreparedWarpLayer, 64> plan{};
    if (prepared.layers.size() > plan.size()) return 1;
    for (size_t i = 0; i < prepared.layers.size(); ++i) {
        plan[i] = PreparedWarpLayer{&prepared.layers[i],
            xz_scale * prepared.layers[i].base_xz_frequency,
            y_scale * prepared.layers[i].base_y_frequency};
    }
    return sample_warped_prepared_plan(plan.data(), prepared.layers.size(),
        x, y, z, shift_x, shift_y, shift_z, output);
}

inline int32_t wrapped_axis_coordinate(int32_t minimum, uint32_t index, int32_t step) noexcept {
    const uint32_t bits = static_cast<uint32_t>(minimum) + index * static_cast<uint32_t>(step);
    int32_t coordinate;
    std::memcpy(&coordinate, &bits, sizeof(coordinate));
    return coordinate;
}

/* Correctness-first scalar sampler for domain-warped NoiseFunction nodes.
 * Shift values are applied before each octave's immutable frequency, matching
 * the NoiseFunction coordinate transform. This bypasses regular-grid caches
 * because every voxel may have a different shift. */
extern "C" int32_t jpe_cpp_perlin_stack_sample_warped(
    uint64_t handle, int32_t x, int32_t y, int32_t z,
    double shift_x, double shift_y, double shift_z,
    double xz_scale, double y_scale, float* output
) noexcept {
    if (output == nullptr || !std::isfinite(shift_x) || !std::isfinite(shift_y)
        || !std::isfinite(shift_z) || !std::isfinite(xz_scale) || !std::isfinite(y_scale)) return 1;
    std::shared_ptr<const PreparedStack> prepared;
    {
        std::lock_guard<std::mutex> lock(g_prepared_mutex);
        const auto found = g_prepared_stacks.find(handle);
        if (found == g_prepared_stacks.end()) return 2;
        prepared = found->second;
    }
    return sample_warped_prepared(*prepared, x, y, z,
        shift_x, shift_y, shift_z, xz_scale, y_scale, output);
}

template <typename ShiftScalar>
int32_t sample_warped_prepared_batch(
    uint64_t handle, uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const ShiftScalar* shift_x, const ShiftScalar* shift_y, const ShiftScalar* shift_z,
    double xz_scale, double y_scale, float* output, uint64_t output_length
) noexcept {
    if (shift_x == nullptr || shift_y == nullptr || shift_z == nullptr || output == nullptr
        || size_x == 0 || size_y == 0 || size_z == 0
        || !std::isfinite(xz_scale) || !std::isfinite(y_scale)) return 1;
    const uint64_t xy = static_cast<uint64_t>(size_x) * size_y;
    if (xy > 1'048'576ULL / size_z || xy * size_z != output_length) return 1;
    std::shared_ptr<const PreparedStack> prepared;
    {
        std::lock_guard<std::mutex> lock(g_prepared_mutex);
        const auto found = g_prepared_stacks.find(handle);
        if (found == g_prepared_stacks.end()) return 2;
        prepared = found->second;
    }
    // Layer frequencies are invariant across the whole volume. Build the
    // octave plan once, not once per voxel, and keep it on the stack (max 64 layers).
    std::array<PreparedWarpLayer, 64> plan{};
    if (prepared->layers.size() > plan.size()) return 1;
    for (size_t i = 0; i < prepared->layers.size(); ++i) {
        const PreparedLayer& layer = prepared->layers[i];
        plan[i] = PreparedWarpLayer{&layer,
            xz_scale * layer.base_xz_frequency,
            y_scale * layer.base_y_frequency};
    }
    size_t out = 0;
    for (uint32_t z = 0; z < size_z; ++z) {
        const int32_t world_z = wrapped_axis_coordinate(min_z, z, step_z);
        for (uint32_t x = 0; x < size_x; ++x) {
            const int32_t world_x = wrapped_axis_coordinate(min_x, x, step_x);
            for (uint32_t y = 0; y < size_y; ++y) {
                const int32_t world_y = wrapped_axis_coordinate(min_y, y, step_y);
                const int32_t status = sample_warped_prepared_plan(plan.data(),
                    prepared->layers.size(), world_x, world_y, world_z,
                    static_cast<double>(shift_x[out]), static_cast<double>(shift_y[out]),
                    static_cast<double>(shift_z[out]), &output[out]);
                if (status != 0) return status;
                ++out;
            }
        }
    }
    return 0;
}

extern "C" int32_t jpe_cpp_perlin_stack_sample_warped_batch_f32(
    uint64_t handle, uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float* shift_x, const float* shift_y, const float* shift_z,
    double xz_scale, double y_scale, float* output, uint64_t output_length
) noexcept {
    return sample_warped_prepared_batch(handle, size_x, size_y, size_z,
        min_x, min_y, min_z, step_x, step_y, step_z, shift_x, shift_y, shift_z,
        xz_scale, y_scale, output, output_length);
}

extern "C" int32_t jpe_cpp_perlin_stack_sample_warped_batch_f64(
    uint64_t handle, uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const double* shift_x, const double* shift_y, const double* shift_z,
    double xz_scale, double y_scale, float* output, uint64_t output_length
) noexcept {
    return sample_warped_prepared_batch(handle, size_x, size_y, size_z,
        min_x, min_y, min_z, step_x, step_y, step_z, shift_x, shift_y, shift_z,
        xz_scale, y_scale, output, output_length);
}

extern "C" uint64_t jpe_cpp_perlin_stack_retain(uint64_t handle) noexcept {
    std::lock_guard<std::mutex> lock(g_prepared_mutex);
    const auto found = g_prepared_stacks.find(handle);
    if (found == g_prepared_stacks.end()) return 0;
    uint64_t retained = g_next_prepared_handle++;
    if (retained == 0) retained = g_next_prepared_handle++;
    try {
        g_prepared_stacks.emplace(retained, found->second);
        return retained;
    } catch (...) {
        return 0;
    }
}

extern "C" int32_t jpe_cpp_perlin_stack_release(uint64_t handle) noexcept {
    std::lock_guard<std::mutex> lock(g_prepared_mutex);
    return g_prepared_stacks.erase(handle) == 1 ? 0 : 2;
}

extern "C" uint64_t jpe_runtime_retain_prepared_perlin_stack(uint64_t handle) {
    return jpe_cpp_perlin_stack_retain(handle);
}
