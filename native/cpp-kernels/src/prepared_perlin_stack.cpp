#include "jpe_native_api.h"

#include <array>
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

namespace {
struct PreparedLayer {
    std::array<uint8_t, 256> permutation{};
    double offset_x = 0.0;
    double offset_y = 0.0;
    double offset_z = 0.0;
    double base_xz_frequency = 0.0;
    double base_y_frequency = 0.0;
    float base_amplitude = 0.0F;
};
struct PreparedStack {
    std::vector<PreparedLayer> layers;
};
std::mutex g_prepared_mutex;
uint64_t g_next_prepared_handle = 1;
std::unordered_map<uint64_t, std::shared_ptr<const PreparedStack>> g_prepared_stacks;

bool valid_prepared_volume(uint32_t sx, uint32_t sy, uint32_t sz,
                           int32_t dx, int32_t dy, int32_t dz,
                           uint64_t output_length) noexcept {
    if (sx == 0 || sy == 0 || sz == 0 || dx == 0 || dy == 0 || dz == 0) return false;
    const uint64_t xy = static_cast<uint64_t>(sx) * sy;
    if (xy > 1'048'576ULL / sz) return false;
    return xy * sz == output_length && output_length <= 1'048'576ULL;
}
}

extern "C" uint64_t jpe_cpp_perlin_stack_prepare(
    const JpePerlinLayer* layers, uint32_t layer_count
) noexcept {
    if (layers == nullptr || layer_count == 0 || layer_count > 64) return 0;
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
}

extern "C" int32_t jpe_cpp_perlin_stack_evaluate(
    uint64_t handle, double xz_scale, double y_scale, float amplitude,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length, uint32_t flags
) noexcept {
    if (output == nullptr || !valid_prepared_volume(size_x, size_y, size_z,
            step_x, step_y, step_z, output_length)) return 1;
    std::shared_ptr<const PreparedStack> prepared;
    {
        std::lock_guard<std::mutex> lock(g_prepared_mutex);
        const auto found = g_prepared_stacks.find(handle);
        if (found == g_prepared_stacks.end()) return 2;
        prepared = found->second;
    }

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
}

extern "C" int32_t jpe_cpp_perlin_stack_release(uint64_t handle) noexcept {
    std::lock_guard<std::mutex> lock(g_prepared_mutex);
    return g_prepared_stacks.erase(handle) == 1 ? 0 : 2;
}
