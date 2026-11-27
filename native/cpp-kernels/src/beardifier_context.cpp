#include "jpe_beardifier_context.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <unordered_map>

namespace {
constexpr uint32_t kKernelCount = 24U * 24U * 24U;
constexpr uint32_t kMaxPieces = 16384U;
constexpr uint32_t kMaxJunctions = 65536U;

std::mutex g_context_mutex;
uint64_t g_next_context_handle = 1;
std::unordered_map<uint64_t, std::shared_ptr<const JpeBeardifierContext>> g_contexts;
std::mutex g_kernel_mutex;
std::shared_ptr<const std::array<float, kKernelCount>> g_kernel;

int32_t wrap_add(int32_t a, int32_t b) noexcept {
    return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b));
}
int32_t wrap_sub(int32_t a, int32_t b) noexcept {
    return static_cast<int32_t>(static_cast<uint32_t>(a) - static_cast<uint32_t>(b));
}
float length_squared(float x, float y, float z) noexcept {
    return x * x + y * y + z * z;
}
float minecraft_fast_inv_sqrt(double value) noexcept {
    double half = 0.5 * value;
    uint64_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    bits = 6910469410427058090ULL - (bits >> 1U);
    std::memcpy(&value, &bits, sizeof(value));
    value *= 1.5 - half * value * value;
    return static_cast<float>(value);
}
float bury_contribution(float dx, float dy, float dz) noexcept {
    const float distance_sq = length_squared(dx, dy, dz);
    if (distance_sq >= 36.0F) return 0.0F;
    return 1.0F - static_cast<float>(std::sqrt(distance_sq)) / 6.0F;
}
float beard_contribution(
    const JpeBeardifierContext& context,
    int32_t dx, int32_t dy, int32_t dz, int32_t y_to_ground) noexcept {
    const int32_t xi = wrap_add(dx, 12);
    const int32_t yi = wrap_add(dy, 12);
    const int32_t zi = wrap_add(dz, 12);
    if (xi < 0 || xi >= 24 || yi < 0 || yi >= 24 || zi < 0 || zi >= 24
        || !context.kernel) return 0.0F;
    const float dy_offset = static_cast<float>(y_to_ground) + 0.5F;
    const float distance_sq = length_squared(static_cast<float>(dx), dy_offset, static_cast<float>(dz));
    const float value = -dy_offset * minecraft_fast_inv_sqrt(static_cast<double>(distance_sq / 2.0F)) / 2.0F;
    const size_t kernel_index = static_cast<size_t>(zi) * 24U * 24U
        + static_cast<size_t>(xi) * 24U + static_cast<size_t>(yi);
    return value * (*context.kernel)[kernel_index];
}
}

std::shared_ptr<const JpeBeardifierContext>
jpe_cpp_beardifier_context_acquire(uint64_t handle) noexcept {
    if (handle == 0) return {};
    std::lock_guard<std::mutex> lock(g_context_mutex);
    const auto found = g_contexts.find(handle);
    return found == g_contexts.end() ? std::shared_ptr<const JpeBeardifierContext>{} : found->second;
}

float jpe_cpp_beardifier_context_sample(
    const JpeBeardifierContext& context, int32_t x, int32_t y, int32_t z) noexcept {
    if (!context.has_affected_box
        || x < context.min_x || x > context.max_x
        || y < context.min_y || y > context.max_y
        || z < context.min_z || z > context.max_z) return 0.0F;

    float noise_value = 0.0F;
    for (const JpeBeardifierRigid& rigid : context.pieces) {
        const int32_t dx = std::max(0, std::max(
            wrap_sub(rigid.min_x, x), wrap_sub(x, rigid.max_x)));
        const int32_t dz = std::max(0, std::max(
            wrap_sub(rigid.min_z, z), wrap_sub(z, rigid.max_z)));
        const int32_t ground_y = wrap_add(rigid.min_y, rigid.ground_level_delta);
        const int32_t dy_to_ground = wrap_sub(y, ground_y);
        int32_t dy = 0;
        switch (rigid.terrain_adjustment) {
            case 0: dy = 0; break;
            case 1: case 2: dy = dy_to_ground; break;
            case 3: dy = std::max(0, std::max(wrap_sub(ground_y, y), wrap_sub(y, rigid.max_y))); break;
            case 4: dy = std::max(0, std::max(wrap_sub(rigid.min_y, y), wrap_sub(y, rigid.max_y))); break;
            default: return std::numeric_limits<float>::quiet_NaN();
        }
        float contribution = 0.0F;
        switch (rigid.terrain_adjustment) {
            case 0: contribution = 0.0F; break;
            case 1: contribution = bury_contribution(
                static_cast<float>(dx), static_cast<float>(dy) / 2.0F, static_cast<float>(dz)); break;
            case 2: case 3: contribution = beard_contribution(
                context, dx, dy, dz, dy_to_ground) * 0.8F; break;
            case 4: contribution = bury_contribution(
                static_cast<float>(dx) / 2.0F, static_cast<float>(dy) / 2.0F,
                static_cast<float>(dz) / 2.0F) * 0.8F; break;
            default: return std::numeric_limits<float>::quiet_NaN();
        }
        noise_value += contribution;
    }
    for (const JpeBeardifierJunction& junction : context.junctions) {
        const int32_t dx = wrap_sub(x, junction.source_x);
        const int32_t dy = wrap_sub(y, junction.source_ground_y);
        const int32_t dz = wrap_sub(z, junction.source_z);
        noise_value += beard_contribution(context, dx, dy, dz, dy) * 0.4F;
    }
    return noise_value;
}

extern "C" JPE_API uint64_t jpe_cpp_beardifier_context_create(
    const JpeBeardifierContextCreateInfo* info) try {
    if (info == nullptr || info->piece_count > kMaxPieces || info->junction_count > kMaxJunctions
        || (info->piece_count != 0 && info->pieces == nullptr)
        || (info->junction_count != 0 && info->junctions == nullptr)
        || info->has_affected_box > 1U || info->reserved0 != 0 || info->reserved1 != 0
        || info->reserved2 != 0 || info->reserved3 != 0) return 0;
    if (info->has_affected_box != 0U
        && (info->min_x > info->max_x || info->min_y > info->max_y || info->min_z > info->max_z)) return 0;
    for (uint32_t i = 0; i < info->piece_count; ++i) {
        const auto& p = info->pieces[i];
        if (p.min_x > p.max_x || p.min_y > p.max_y || p.min_z > p.max_z
            || p.terrain_adjustment > 4U) return 0;
    }

    std::shared_ptr<const std::array<float, kKernelCount>> kernel;
    if (info->piece_count != 0 || info->junction_count != 0) {
        if (info->beard_kernel == nullptr || info->beard_kernel_count != kKernelCount) return 0;
        std::lock_guard<std::mutex> kernel_lock(g_kernel_mutex);
        if (!g_kernel) {
            auto values = std::make_shared<std::array<float, kKernelCount>>();
            std::copy(info->beard_kernel, info->beard_kernel + kKernelCount, values->begin());
            g_kernel = values;
        } else if (std::memcmp(g_kernel->data(), info->beard_kernel,
                               sizeof(float) * kKernelCount) != 0) {
            return 0;
        }
        kernel = g_kernel;
    }

    auto context = std::make_shared<JpeBeardifierContext>();
    context->pieces.assign(info->pieces, info->pieces + info->piece_count);
    context->junctions.assign(info->junctions, info->junctions + info->junction_count);
    context->has_affected_box = info->has_affected_box != 0U;
    context->min_x = info->min_x; context->min_y = info->min_y; context->min_z = info->min_z;
    context->max_x = info->max_x; context->max_y = info->max_y; context->max_z = info->max_z;
    context->kernel = std::move(kernel);

    std::lock_guard<std::mutex> lock(g_context_mutex);
    if (g_next_context_handle == 0 || g_next_context_handle == UINT64_MAX) return 0;
    const uint64_t handle = g_next_context_handle++;
    g_contexts.emplace(handle, std::move(context));
    return handle;
} catch (...) {
    return 0;
}

extern "C" JPE_API int32_t jpe_cpp_beardifier_context_destroy(uint64_t handle) {
    if (handle == 0) return 1;
    std::lock_guard<std::mutex> lock(g_context_mutex);
    return g_contexts.erase(handle) == 1 ? 0 : 2;
}
