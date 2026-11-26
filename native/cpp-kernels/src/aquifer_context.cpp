#include "jpe_native_api.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <shared_mutex>
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
    std::shared_mutex picker_mutex;
    bool picker_configured = false;
    int32_t sea_level = 63;
    int32_t min_y = -2032;
    uint32_t default_fluid_type = 0;
    uint32_t default_fluid_is_air = 0;
    uint32_t debug_disable_fluid_generation = 0;
    std::mutex surface_cache_mutex;
    std::unordered_map<uint64_t, int32_t> surface_level_cache;

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
int32_t floor_divide_i32(int32_t value, int32_t divisor) noexcept {
    int32_t quotient = value / divisor;
    if (value % divisor < 0) --quotient;
    return quotient;
}
int32_t wrap_add_i32(int32_t a, int32_t b) noexcept {
    return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b));
}
uint64_t block_pos_as_long(int32_t x, int32_t y, int32_t z) noexcept {
    constexpr uint64_t horizontal_mask = (1ULL << 26) - 1ULL;
    constexpr uint64_t vertical_mask = (1ULL << 12) - 1ULL;
    return ((static_cast<uint64_t>(static_cast<uint32_t>(x)) & horizontal_mask) << 38)
        | ((static_cast<uint64_t>(static_cast<uint32_t>(z)) & horizontal_mask) << 12)
        | (static_cast<uint64_t>(static_cast<uint32_t>(y)) & vertical_mask);
}
uint64_t chunk_pos_pack(int32_t x, int32_t z) noexcept {
    return static_cast<uint64_t>(static_cast<uint32_t>(x))
        | (static_cast<uint64_t>(static_cast<uint32_t>(z)) << 32);
}
JpeAquiferFluidStatus global_fluid_picker(const AquiferContext& context, int32_t y) noexcept {
    if (context.debug_disable_fluid_generation != 0) {
        return JpeAquiferFluidStatus{context.min_y * 2, context.air_type};
    }
    if (y < std::min(-54, context.sea_level)) {
        return JpeAquiferFluidStatus{-54, context.lava_type};
    }
    return JpeAquiferFluidStatus{context.sea_level, context.default_fluid_type};
}
bool global_fluid_type_is_air(const AquiferContext& context, int32_t y) noexcept {
    if (context.debug_disable_fluid_generation != 0) return true;
    return y < std::min(-54, context.sea_level) ? false : context.default_fluid_is_air != 0;
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

extern "C" JPE_API int32_t jpe_cpp_aquifer_context_configure_global_fluid_picker(
    uint64_t context_handle,
    const uint8_t* expected_world_fingerprint,
    uint64_t expected_semantic_fingerprint,
    int32_t sea_level,
    int32_t min_y,
    uint32_t default_fluid_type,
    uint32_t default_fluid_is_air,
    uint32_t debug_disable_fluid_generation
) {
    if (expected_world_fingerprint == nullptr || expected_semantic_fingerprint == 0
        || default_fluid_is_air > 1U || debug_disable_fluid_generation > 1U
        || min_y > std::numeric_limits<int32_t>::max() / 2
        || min_y < std::numeric_limits<int32_t>::min() / 2) return 1;
    const auto context = acquire_context(context_handle);
    if (!context) return 1;
    if (context->semantic_fingerprint != expected_semantic_fingerprint
        || std::memcmp(context->world_fingerprint.data(), expected_world_fingerprint,
                       context->world_fingerprint.size()) != 0) return 1;

    std::unique_lock<std::shared_mutex> lock(context->picker_mutex);
    if (context->picker_configured) {
        return context->sea_level == sea_level && context->min_y == min_y
            && context->default_fluid_type == default_fluid_type
            && context->default_fluid_is_air == default_fluid_is_air
            && context->debug_disable_fluid_generation == debug_disable_fluid_generation ? 0 : 1;
    }
    context->sea_level = sea_level;
    context->min_y = min_y;
    context->default_fluid_type = default_fluid_type;
    context->default_fluid_is_air = default_fluid_is_air;
    context->debug_disable_fluid_generation = debug_disable_fluid_generation;
    context->picker_configured = true;
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
    // Fluid registry tokens are scoped to this context. Do not accept an input
    // batch compiled against another world's lava token table.
    for (uint32_t i = 0; i < count; ++i) {
        if (inputs[i].lava_type != context->lava_type) return 1;
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

extern "C" JPE_API int32_t jpe_cpp_aquifer_context_compute_fluid_status_points_batch(
    uint64_t context_handle,
    const uint8_t* expected_world_fingerprint,
    uint64_t expected_semantic_fingerprint,
    const uint64_t* cache_keys,
    const int32_t* point_x, const int32_t* point_y, const int32_t* point_z,
    uint32_t count,
    JpeAquiferFluidStatus* outputs
) try {
    constexpr uint32_t kMaxStatusBatch = 65536U;
    constexpr uint32_t kSurfaceProbeCount = 13U;
    constexpr uint32_t kNoiseSamplerCount = 4U;
    constexpr int32_t surface_offset_x[kSurfaceProbeCount] = {
        0, -2, -1, 0, 1, -3, -2, -1, 1, -2, -1, 0, 1
    };
    constexpr int32_t surface_offset_z[kSurfaceProbeCount] = {
        0, -1, -1, -1, -1, 0, 0, 0, 0, 1, 1, 1, 1
    };
    if (expected_world_fingerprint == nullptr || expected_semantic_fingerprint == 0
        || cache_keys == nullptr || point_x == nullptr || point_y == nullptr
        || point_z == nullptr || outputs == nullptr || count == 0 || count > kMaxStatusBatch) return 1;

    const size_t key_bytes = static_cast<size_t>(count) * sizeof(*cache_keys);
    const size_t coordinate_bytes = static_cast<size_t>(count) * sizeof(int32_t);
    const size_t output_bytes = static_cast<size_t>(count) * sizeof(*outputs);
    if (ranges_overlap(outputs, output_bytes, cache_keys, key_bytes)
        || ranges_overlap(outputs, output_bytes, point_x, coordinate_bytes)
        || ranges_overlap(outputs, output_bytes, point_y, coordinate_bytes)
        || ranges_overlap(outputs, output_bytes, point_z, coordinate_bytes)
        || ranges_overlap(outputs, output_bytes, expected_world_fingerprint, 32U)) return 1;

    const auto context = acquire_context(context_handle);
    if (!context) return 1;
    if (context->semantic_fingerprint != expected_semantic_fingerprint
        || std::memcmp(context->world_fingerprint.data(), expected_world_fingerprint,
                       context->world_fingerprint.size()) != 0) return 1;
    std::shared_lock<std::shared_mutex> picker_lock(context->picker_mutex);
    if (!context->picker_configured) return 1;

    for (uint32_t i = 0; i < count; ++i) {
        if (cache_keys[i] != block_pos_as_long(point_x[i], point_y[i], point_z[i])) return 1;
    }

    std::vector<JpeAquiferFluidStatus> resolved(count);
    std::vector<uint8_t> found(count, 0);
    if (jpe_cpp_aquifer_status_cache_lookup_batch(
            context->status_cache_handle, cache_keys, count,
            resolved.data(), found.data()) != 0) return 2;

    std::vector<uint32_t> missing_indices;
    missing_indices.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        if (found[i] == 0) missing_indices.push_back(i);
    }

    if (!missing_indices.empty()) {
        const uint32_t missing_count = static_cast<uint32_t>(missing_indices.size());
        const uint64_t surface_count64 = static_cast<uint64_t>(missing_count) * kSurfaceProbeCount;
        if (surface_count64 > kMaxVolumeElements) return 1;
        const size_t surface_count = static_cast<size_t>(surface_count64);

        std::vector<JpeAquiferFluidStatusInput> fluid_inputs(missing_count);
        std::vector<uint32_t> surface_indices(surface_count, std::numeric_limits<uint32_t>::max());
        std::vector<int32_t> surface_values(surface_count, 0);
        std::unordered_map<uint64_t, uint32_t> local_surface_indices;
        local_surface_indices.reserve(surface_count);
        std::vector<uint64_t> new_surface_keys;
        std::vector<int32_t> new_surface_x, new_surface_y, new_surface_z;
        new_surface_keys.reserve(surface_count);
        new_surface_x.reserve(surface_count);
        new_surface_y.reserve(surface_count);
        new_surface_z.reserve(surface_count);

        {
            std::lock_guard<std::mutex> lock(context->surface_cache_mutex);
            for (uint32_t missing = 0; missing < missing_count; ++missing) {
                const uint32_t request = missing_indices[missing];
                for (uint32_t probe = 0; probe < kSurfaceProbeCount; ++probe) {
                    const int32_t raw_x = wrap_add_i32(point_x[request], surface_offset_x[probe] * 16);
                    const int32_t raw_z = wrap_add_i32(point_z[request], surface_offset_z[probe] * 16);
                    const int32_t quantized_x = floor_divide_i32(raw_x, 4) * 4;
                    const int32_t quantized_z = floor_divide_i32(raw_z, 4) * 4;
                    const uint64_t surface_key = chunk_pos_pack(quantized_x, quantized_z);
                    const size_t slot = static_cast<size_t>(missing) * kSurfaceProbeCount + probe;
                    const auto cached = context->surface_level_cache.find(surface_key);
                    if (cached != context->surface_level_cache.end()) {
                        surface_values[slot] = cached->second;
                        continue;
                    }
                    const auto inserted = local_surface_indices.emplace(
                        surface_key, static_cast<uint32_t>(new_surface_keys.size()));
                    if (inserted.second) {
                        new_surface_keys.push_back(surface_key);
                        new_surface_x.push_back(quantized_x);
                        new_surface_y.push_back(0);
                        new_surface_z.push_back(quantized_z);
                    }
                    surface_indices[slot] = inserted.first->second;
                }
            }
        }

        std::vector<int32_t> new_surface_values(new_surface_keys.size());
        if (!new_surface_keys.empty()) {
            std::vector<float> surface_samples(new_surface_keys.size());
            if (jpe_cpp_density_graph_evaluate_points_f32(
                    context->graph_handles[5], new_surface_x.data(), new_surface_y.data(),
                    new_surface_z.data(), static_cast<uint32_t>(new_surface_keys.size()),
                    surface_samples.data(), static_cast<uint64_t>(surface_samples.size())) != 0) return 2;
            for (size_t i = 0; i < surface_samples.size(); ++i) {
                const double sample = static_cast<double>(surface_samples[i]);
                if (std::isnan(sample)) {
                    new_surface_values[i] = 0;
                } else {
                    const double floored = std::floor(sample);
                    if (floored >= static_cast<double>(std::numeric_limits<int32_t>::max())) {
                        new_surface_values[i] = std::numeric_limits<int32_t>::max();
                    } else if (floored <= static_cast<double>(std::numeric_limits<int32_t>::min())) {
                        new_surface_values[i] = std::numeric_limits<int32_t>::min();
                    } else {
                        new_surface_values[i] = static_cast<int32_t>(floored);
                    }
                }
            }
        }
        for (size_t slot = 0; slot < surface_indices.size(); ++slot) {
            const uint32_t index = surface_indices[slot];
            if (index != std::numeric_limits<uint32_t>::max()) surface_values[slot] = new_surface_values[index];
        }

        const std::array<uint64_t, kNoiseSamplerCount> noise_handles = {
            context->graph_handles[1], context->graph_handles[2],
            context->graph_handles[3], context->graph_handles[4]
        };
        const size_t noise_count = static_cast<size_t>(missing_count) * kNoiseSamplerCount;
        std::vector<int32_t> noise_x(noise_count), noise_y(noise_count), noise_z(noise_count);
        std::vector<float> noise_values(noise_count);
        for (uint32_t missing = 0; missing < missing_count; ++missing) {
            const uint32_t request = missing_indices[missing];
            const int32_t x = point_x[request], y = point_y[request], z = point_z[request];
            noise_x[missing] = x; noise_y[missing] = y; noise_z[missing] = z;
            noise_x[missing_count + missing] = floor_divide_i32(x, 16);
            noise_y[missing_count + missing] = floor_divide_i32(y, 40);
            noise_z[missing_count + missing] = floor_divide_i32(z, 16);
            noise_x[2U * missing_count + missing] = floor_divide_i32(x, 64);
            noise_y[2U * missing_count + missing] = floor_divide_i32(y, 40);
            noise_z[2U * missing_count + missing] = floor_divide_i32(z, 64);
            noise_x[3U * missing_count + missing] = x;
            noise_y[3U * missing_count + missing] = y;
            noise_z[3U * missing_count + missing] = z;
        }
        if (jpe_cpp_density_graph_evaluate_multi_points_f32(
                noise_handles.data(), kNoiseSamplerCount,
                noise_x.data(), noise_y.data(), noise_z.data(),
                missing_count, noise_values.data(), static_cast<uint64_t>(noise_values.size())) != 0) return 2;

        for (uint32_t missing = 0; missing < missing_count; ++missing) {
            const uint32_t request = missing_indices[missing];
            auto& input = fluid_inputs[missing];
            input.block_y = point_y[request];
            input.global_fluid = global_fluid_picker(*context, point_y[request]);
            input.lava_type = context->lava_type;
            input.floodedness_noise = noise_values[missing];
            input.fluid_level_spread_noise = noise_values[missing_count + missing];
            input.lava_noise = noise_values[2U * missing_count + missing];
            input.exclusion_noise = noise_values[3U * missing_count + missing];
            for (uint32_t probe = 0; probe < kSurfaceProbeCount; ++probe) {
                const size_t slot = static_cast<size_t>(missing) * kSurfaceProbeCount + probe;
                const int32_t surface = surface_values[slot];
                input.surface_level[probe] = surface;
                const int32_t adjusted_surface = wrap_add_i32(surface, 8);
                auto& global_probe = input.global_fluid_at_surface[probe];
                global_probe.status = global_fluid_picker(*context, adjusted_surface);
                global_probe.valid = 1;
                global_probe.fluid_type_is_air =
                    global_fluid_type_is_air(*context, adjusted_surface) ? 1U : 0U;
            }
        }

        std::vector<JpeAquiferFluidStatus> computed(missing_count);
        if (jpe_cpp_aquifer_compute_fluid_status_batch(
                fluid_inputs.data(), missing_count, computed.data()) != 0) return 1;
        std::vector<uint64_t> missing_keys(missing_count);
        for (uint32_t missing = 0; missing < missing_count; ++missing) {
            missing_keys[missing] = cache_keys[missing_indices[missing]];
        }
        if (jpe_cpp_aquifer_status_cache_insert_batch(
                context->status_cache_handle, missing_keys.data(),
                computed.data(), missing_count) != 0) return 2;

        if (!new_surface_keys.empty()) {
            std::lock_guard<std::mutex> lock(context->surface_cache_mutex);
            for (size_t i = 0; i < new_surface_keys.size(); ++i) {
                context->surface_level_cache.try_emplace(new_surface_keys[i], new_surface_values[i]);
            }
        }
    }

    std::fill(found.begin(), found.end(), static_cast<uint8_t>(0));
    if (jpe_cpp_aquifer_status_cache_lookup_batch(
            context->status_cache_handle, cache_keys, count,
            resolved.data(), found.data()) != 0) return 2;
    for (uint8_t is_found : found) {
        if (is_found == 0) return 2;
    }
    for (uint32_t i = 0; i < count; ++i) outputs[i] = resolved[i];
    return 0;
} catch (...) {
    return 2;
}

extern "C" JPE_API int32_t jpe_cpp_aquifer_context_compute_substance_volume_f32(
    uint64_t context_handle,
    const uint8_t* expected_world_fingerprint,
    uint64_t expected_semantic_fingerprint,
    int32_t skip_sampling_above_y,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float* density_values,
    uint32_t density_count,
    JpeAquiferCoreOutput* outputs
) try {
    constexpr uint32_t kMaxVolumeVoxels = 1024U * 1024U;
    constexpr uint32_t kNeighbors = 4U;
    constexpr uint32_t kStatusBatchMax = 65536U;

    if (context_handle == 0 || expected_world_fingerprint == nullptr
        || expected_semantic_fingerprint == 0 || size_x == 0 || size_y == 0 || size_z == 0
        || step_x <= 0 || step_y <= 0 || step_z <= 0
        || density_values == nullptr || outputs == nullptr) return 1;

    const uint64_t plane = static_cast<uint64_t>(size_x) * size_y;
    if (plane == 0 || plane > std::numeric_limits<uint64_t>::max() / size_z) return 1;
    const uint64_t total64 = plane * size_z;
    if (total64 == 0 || total64 > kMaxVolumeVoxels || density_count != total64) return 1;
    const size_t total = static_cast<size_t>(total64);
    const size_t density_bytes = total * sizeof(*density_values);
    const size_t output_bytes = total * sizeof(*outputs);
    if (ranges_overlap(outputs, output_bytes, density_values, density_bytes)
        || ranges_overlap(outputs, output_bytes, expected_world_fingerprint, 32U)) return 1;

    const auto context = acquire_context(context_handle);
    if (!context) return 1;
    if (context->semantic_fingerprint != expected_semantic_fingerprint
        || std::memcmp(context->world_fingerprint.data(), expected_world_fingerprint,
                       context->world_fingerprint.size()) != 0) return 1;

    uint32_t random_mode = 0;
    uint64_t seed_lo = 0;
    uint64_t seed_hi = 0;
    int32_t sea_level = 0;
    int32_t picker_min_y = 0;
    uint32_t default_fluid_type = 0;
    uint32_t default_fluid_is_air = 0;
    uint32_t debug_disable_fluid_generation = 0;
    {
        std::shared_lock<std::shared_mutex> lock(context->picker_mutex);
        if (!context->picker_configured) return 1;
        random_mode = context->random_mode;
        seed_lo = context->seed_lo;
        seed_hi = context->seed_hi;
        sea_level = context->sea_level;
        picker_min_y = context->min_y;
        default_fluid_type = context->default_fluid_type;
        default_fluid_is_air = context->default_fluid_is_air;
        debug_disable_fluid_generation = context->debug_disable_fluid_generation;
    }

    auto global_picker = [&](int32_t y) noexcept -> JpeAquiferFluidStatus {
        if (debug_disable_fluid_generation != 0) {
            const int32_t empty_level = static_cast<int32_t>(
                static_cast<uint32_t>(picker_min_y) * 2U);
            return JpeAquiferFluidStatus{empty_level, context->air_type};
        }
        if (y < std::min(-54, sea_level)) {
            return JpeAquiferFluidStatus{-54, context->lava_type};
        }
        return JpeAquiferFluidStatus{sea_level, default_fluid_type};
    };
    auto fluid_at = [&](const JpeAquiferFluidStatus& status, int32_t y) noexcept -> uint32_t {
        return y < status.fluid_level ? status.fluid_type : context->air_type;
    };

    // Stage every result first. The caller's output array is committed only after
    // nearest-cell selection, FluidStatus lookup, barrier sampling and core math succeed.
    std::vector<JpeAquiferCoreOutput> staged(total);
    std::vector<uint32_t> active_indices;
    std::vector<int32_t> active_x, active_y, active_z;
    active_indices.reserve(total);
    active_x.reserve(total);
    active_y.reserve(total);
    active_z.reserve(total);

    for (uint32_t index = 0; index < density_count; ++index) {
        const float density = density_values[index];
        if (density > 0.0F) {
            staged[index] = JpeAquiferCoreOutput{0U, context->air_type, 0U};
            continue;
        }

        // DensityVolume.indexUnchecked is y + (x + z * sizeX) * sizeY.
        const uint32_t y_index = static_cast<uint32_t>(index % size_y);
        const uint32_t x_index = static_cast<uint32_t>((index / size_y) % size_x);
        const uint32_t z_index = static_cast<uint32_t>(index / plane);
        const int32_t x = static_cast<int32_t>(
            static_cast<uint32_t>(min_x) + static_cast<uint32_t>(x_index) * static_cast<uint32_t>(step_x));
        const int32_t y = static_cast<int32_t>(
            static_cast<uint32_t>(min_y) + static_cast<uint32_t>(y_index) * static_cast<uint32_t>(step_y));
        const int32_t z = static_cast<int32_t>(
            static_cast<uint32_t>(min_z) + static_cast<uint32_t>(z_index) * static_cast<uint32_t>(step_z));

        const JpeAquiferFluidStatus global = global_picker(y);
        const uint32_t global_state = fluid_at(global, y);
        if (y > skip_sampling_above_y) {
            staged[index] = JpeAquiferCoreOutput{1U, global_state, 0U};
            continue;
        }
        if (global_state == context->lava_type) {
            staged[index] = JpeAquiferCoreOutput{
                1U,
                debug_disable_fluid_generation != 0 ? context->air_type : context->lava_type,
                0U
            };
            continue;
        }

        active_indices.push_back(index);
        active_x.push_back(x);
        active_y.push_back(y);
        active_z.push_back(z);
    }

    if (!active_indices.empty()) {
        const uint32_t active_count = static_cast<uint32_t>(active_indices.size());
        const size_t neighbor_count = static_cast<size_t>(active_count) * kNeighbors;
        std::vector<int32_t> center_x(neighbor_count), center_y(neighbor_count);
        std::vector<int32_t> center_z(neighbor_count), distances(neighbor_count);
        if (jpe_cpp_aquifer_nearest_four(
                random_mode, seed_lo, seed_hi,
                active_x.data(), active_y.data(), active_z.data(), active_count,
                center_x.data(), center_y.data(), center_z.data(), distances.data()) != 0) {
            return 2;
        }

        std::vector<uint64_t> status_keys(neighbor_count);
        for (size_t i = 0; i < neighbor_count; ++i) {
            status_keys[i] = block_pos_as_long(center_x[i], center_y[i], center_z[i]);
        }
        std::vector<JpeAquiferFluidStatus> closest_statuses(neighbor_count);
        // Status point API has a bounded per-call batch. Four neighbours per
        // voxel means at most 16,384 voxels are sent in one status batch.
        constexpr uint32_t kVoxelsPerStatusBatch = kStatusBatchMax / kNeighbors;
        for (uint32_t start = 0; start < active_count; start += kVoxelsPerStatusBatch) {
            const uint32_t voxels = std::min(kVoxelsPerStatusBatch, active_count - start);
            const size_t offset = static_cast<size_t>(start) * kNeighbors;
            const uint32_t statuses = voxels * kNeighbors;
            if (jpe_cpp_aquifer_context_compute_fluid_status_points_batch(
                    context_handle, expected_world_fingerprint, expected_semantic_fingerprint,
                    status_keys.data() + offset,
                    center_x.data() + offset, center_y.data() + offset, center_z.data() + offset,
                    statuses, closest_statuses.data() + offset) != 0) {
                return 2;
            }
        }

        std::vector<float> barrier_values(active_count);
        if (jpe_cpp_density_graph_evaluate_points_f32(
                context->graph_handles[0],
                active_x.data(), active_y.data(), active_z.data(), active_count,
                barrier_values.data(), static_cast<uint64_t>(barrier_values.size())) != 0) {
            return 2;
        }

        std::vector<JpeAquiferCoreInput> core_inputs(active_count);
        std::vector<JpeAquiferCoreOutput> core_outputs(active_count);
        for (uint32_t i = 0; i < active_count; ++i) {
            const uint32_t source_index = active_indices[i];
            auto& input = core_inputs[i];
            input.block_y = active_y[i];
            input.density = static_cast<double>(density_values[source_index]);
            input.barrier_noise = barrier_values[i];
            for (uint32_t neighbor = 0; neighbor < kNeighbors; ++neighbor) {
                const size_t neighbor_index = static_cast<size_t>(i) * kNeighbors + neighbor;
                input.distance_sqr[neighbor] = distances[neighbor_index];
                input.closest[neighbor] = closest_statuses[neighbor_index];
            }
            const int32_t below_y = static_cast<int32_t>(
                static_cast<uint32_t>(active_y[i]) - 1U);
            const JpeAquiferFluidStatus global_below = global_picker(below_y);
            input.global_fluid_below_type = fluid_at(global_below, below_y);
            input.air_type = context->air_type;
            input.water_type = context->water_type;
            input.lava_type = context->lava_type;
            input.debug_disable_fluid_generation = debug_disable_fluid_generation;
        }
        if (jpe_cpp_aquifer_compute_substance_batch(
                core_inputs.data(), active_count, core_outputs.data()) != 0) {
            return 1;
        }
        for (uint32_t i = 0; i < active_count; ++i) {
            staged[active_indices[i]] = core_outputs[i];
        }
    }

    for (size_t i = 0; i < total; ++i) outputs[i] = staged[i];
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
