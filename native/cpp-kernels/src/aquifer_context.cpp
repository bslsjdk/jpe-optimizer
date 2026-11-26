#include "jpe_native_api.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

static_assert(sizeof(JpeAquiferContextCreateInfo) == 128,
              "Aquifer context create ABI size mismatch");
static_assert(offsetof(JpeAquiferContextCreateInfo, density_graph_handles) == 0,
              "Aquifer context graph-handle offset mismatch");
static_assert(offsetof(JpeAquiferContextCreateInfo, world_fingerprint) == 48,
              "Aquifer context world-fingerprint offset mismatch");
static_assert(offsetof(JpeAquiferContextCreateInfo, semantic_fingerprint) == 80,
              "Aquifer context semantic-fingerprint offset mismatch");
static_assert(offsetof(JpeAquiferContextCreateInfo, seed_lo) == 88,
              "Aquifer context seed_lo offset mismatch");
static_assert(offsetof(JpeAquiferContextCreateInfo, seed_hi) == 96,
              "Aquifer context seed_hi offset mismatch");
static_assert(offsetof(JpeAquiferContextCreateInfo, random_mode) == 104,
              "Aquifer context random-mode offset mismatch");
static_assert(offsetof(JpeAquiferContextCreateInfo, max_status_cache_entries) == 120,
              "Aquifer context cache-capacity offset mismatch");

namespace {
constexpr uint32_t kSamplerCount = 6;
constexpr uint32_t kMaxCacheEntries = 1024U * 1024U;
constexpr uint64_t kMaxVolumeElements = 1024ULL * 1024ULL;

struct AquiferContext {
    std::array<uint64_t, kSamplerCount> graph_handles{};
    std::array<uint8_t, 32> world_fingerprint{};
    uint64_t semantic_fingerprint = 0;
    uint64_t seed_lo = 0;
    uint64_t seed_hi = 0;
    uint64_t status_cache_handle = 0;
    uint32_t random_mode = 0;
    uint32_t air_type = 0;
    uint32_t water_type = 0;
    uint32_t lava_type = 0;

    ~AquiferContext() {
        if (status_cache_handle != 0) {
            (void)jpe_cpp_aquifer_status_cache_destroy(status_cache_handle);
        }
        for (uint64_t handle : graph_handles) {
            if (handle != 0) (void)jpe_cpp_density_graph_release(handle);
        }
    }
};

std::mutex g_context_mutex;
uint64_t g_next_context_handle = 1;
std::unordered_map<uint64_t, std::shared_ptr<AquiferContext>> g_contexts;

std::shared_ptr<AquiferContext> acquire_context(uint64_t handle) {
    if (handle == 0) return {};
    std::lock_guard<std::mutex> lock(g_context_mutex);
    const auto found = g_contexts.find(handle);
    return found == g_contexts.end() ? std::shared_ptr<AquiferContext>{} : found->second;
}

bool all_zero(const uint8_t* bytes, size_t length) noexcept {
    for (size_t i = 0; i < length; ++i) {
        if (bytes[i] != 0) return false;
    }
    return true;
}

bool ranges_overlap(const void* first, size_t first_size,
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
}

extern "C" JPE_API uint64_t jpe_cpp_aquifer_context_create(
    const JpeAquiferContextCreateInfo* info
) try {
    if (info == nullptr || info->random_mode > 1U
        || info->air_type == info->water_type
        || info->air_type == info->lava_type
        || info->water_type == info->lava_type
        || info->semantic_fingerprint == 0
        || info->max_status_cache_entries == 0
        || info->max_status_cache_entries > kMaxCacheEntries
        || info->reserved != 0
        || all_zero(info->world_fingerprint, sizeof(info->world_fingerprint))) {
        return 0;
    }
    for (uint64_t graph_handle : info->density_graph_handles) {
        if (graph_handle == 0) return 0;
    }

    auto context = std::make_shared<AquiferContext>();
    context->world_fingerprint = {};
    for (size_t i = 0; i < context->world_fingerprint.size(); ++i) {
        context->world_fingerprint[i] = info->world_fingerprint[i];
    }
    context->semantic_fingerprint = info->semantic_fingerprint;
    context->seed_lo = info->seed_lo;
    context->seed_hi = info->seed_hi;
    context->random_mode = info->random_mode;
    context->air_type = info->air_type;
    context->water_type = info->water_type;
    context->lava_type = info->lava_type;

    context->status_cache_handle =
        jpe_cpp_aquifer_status_cache_create(info->max_status_cache_entries);
    if (context->status_cache_handle == 0) return 0;

    for (uint32_t i = 0; i < kSamplerCount; ++i) {
        const uint64_t graph_handle = info->density_graph_handles[i];
        if (jpe_cpp_density_graph_retain(graph_handle) != 0) return 0;
        context->graph_handles[i] = graph_handle;
    }

    std::lock_guard<std::mutex> lock(g_context_mutex);
    for (uint32_t attempt = 0; attempt < 4; ++attempt) {
        const uint64_t handle = g_next_context_handle++;
        if (handle == 0 || g_contexts.find(handle) != g_contexts.end()) continue;
        g_contexts.emplace(handle, std::move(context));
        return handle;
    }
    return 0;
} catch (...) {
    return 0;
}

extern "C" JPE_API int32_t jpe_cpp_aquifer_context_destroy(uint64_t context_handle) {
    if (context_handle == 0) return 1;
    std::shared_ptr<AquiferContext> retired;
    {
        std::lock_guard<std::mutex> lock(g_context_mutex);
        const auto found = g_contexts.find(context_handle);
        if (found == g_contexts.end()) return 1;
        retired = std::move(found->second);
        g_contexts.erase(found);
    }
    // In-flight calls hold their own shared_ptr and finish before resources are released.
    retired.reset();
    return 0;
}

extern "C" JPE_API int32_t jpe_cpp_aquifer_context_compute_fluid_status_batch(
    uint64_t context_handle,
    const uint8_t* expected_world_fingerprint,
    uint64_t expected_semantic_fingerprint,
    const uint64_t* cache_keys,
    const JpeAquiferFluidStatusInput* inputs,
    uint32_t count,
    JpeAquiferFluidStatus* outputs
) try {
    constexpr uint32_t kMaxStatusBatch = 65536U;
    if (expected_world_fingerprint == nullptr || expected_semantic_fingerprint == 0
        || cache_keys == nullptr || inputs == nullptr || outputs == nullptr
        || count == 0 || count > kMaxStatusBatch) return 1;

    const size_t key_bytes = static_cast<size_t>(count) * sizeof(*cache_keys);
    const size_t input_bytes = static_cast<size_t>(count) * sizeof(*inputs);
    const size_t output_bytes = static_cast<size_t>(count) * sizeof(*outputs);
    if (ranges_overlap(outputs, output_bytes, cache_keys, key_bytes)
        || ranges_overlap(outputs, output_bytes, inputs, input_bytes)
        || ranges_overlap(outputs, output_bytes, expected_world_fingerprint, 32U)) {
        return 1;
    }

    const auto context = acquire_context(context_handle);
    if (!context) return 1;
    if (context->semantic_fingerprint != expected_semantic_fingerprint
        || std::memcmp(context->world_fingerprint.data(), expected_world_fingerprint,
                       context->world_fingerprint.size()) != 0) {
        return 1;
    }

    // All intermediate state stays native. Caller output is copied only after
    // the cache has been filled and a final first-writer-wins lookup succeeds.
    std::vector<JpeAquiferFluidStatus> resolved(count);
    std::vector<uint8_t> found(count, 0);
    if (jpe_cpp_aquifer_status_cache_lookup_batch(
            context->status_cache_handle, cache_keys, count,
            resolved.data(), found.data()) != 0) {
        return 2;
    }

    std::vector<uint64_t> missing_keys;
    std::vector<JpeAquiferFluidStatusInput> missing_inputs;
    std::vector<JpeAquiferFluidStatus> missing_statuses;
    missing_keys.reserve(count);
    missing_inputs.reserve(count);
    missing_statuses.resize(count);

    for (uint32_t i = 0; i < count; ++i) {
        if (found[i] != 0) continue;
        missing_keys.push_back(cache_keys[i]);
        missing_inputs.push_back(inputs[i]);
    }

    if (!missing_keys.empty()) {
        if (jpe_cpp_aquifer_compute_fluid_status_batch(
                missing_inputs.data(), static_cast<uint32_t>(missing_inputs.size()),
                missing_statuses.data()) != 0) {
            return 1;
        }
        if (jpe_cpp_aquifer_status_cache_insert_batch(
                context->status_cache_handle, missing_keys.data(),
                missing_statuses.data(), static_cast<uint32_t>(missing_keys.size())) != 0) {
            return 2;
        }
    }

    std::fill(found.begin(), found.end(), static_cast<uint8_t>(0));
    if (jpe_cpp_aquifer_status_cache_lookup_batch(
            context->status_cache_handle, cache_keys, count,
            resolved.data(), found.data()) != 0) {
        return 2;
    }
    for (uint8_t is_found : found) {
        if (is_found == 0) return 2;
    }

    for (uint32_t i = 0; i < count; ++i) outputs[i] = resolved[i];
    return 0;
} catch (...) {
    return 2;
}

extern "C" JPE_API int32_t jpe_cpp_aquifer_context_evaluate_samplers_f32(
    uint64_t context_handle,
    const uint8_t* expected_world_fingerprint,
    uint64_t expected_semantic_fingerprint,
    const int32_t* point_x, const int32_t* point_y, const int32_t* point_z,
    uint32_t points_per_sampler, float* output, uint64_t output_length
) {
    if (expected_world_fingerprint == nullptr || expected_semantic_fingerprint == 0
        || point_x == nullptr || point_y == nullptr || point_z == nullptr || output == nullptr
        || points_per_sampler == 0) return 1;
    const uint64_t total = static_cast<uint64_t>(points_per_sampler) * kSamplerCount;
    if (total > kMaxVolumeElements || output_length != total) return 1;
    const size_t coordinate_bytes = static_cast<size_t>(total) * sizeof(int32_t);
    const size_t output_bytes = static_cast<size_t>(total) * sizeof(float);
    if (ranges_overlap(point_x, coordinate_bytes, output, output_bytes)
        || ranges_overlap(point_y, coordinate_bytes, output, output_bytes)
        || ranges_overlap(point_z, coordinate_bytes, output, output_bytes)) return 1;

    const auto context = acquire_context(context_handle);
    if (!context) return 1;
    if (context->semantic_fingerprint != expected_semantic_fingerprint
        || std::memcmp(context->world_fingerprint.data(), expected_world_fingerprint,
                       context->world_fingerprint.size()) != 0) {
        return 1;
    }
    return jpe_cpp_density_graph_evaluate_multi_points_f32(
        context->graph_handles.data(), kSamplerCount,
        point_x, point_y, point_z, points_per_sampler, output, output_length);
}
