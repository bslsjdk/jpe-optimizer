#pragma once
#include "jpe_native_api.h"
#include <array>
#include <cstdint>
#include <memory>
#include <vector>

struct JpeBeardifierContext {
    std::vector<JpeBeardifierRigid> pieces;
    std::vector<JpeBeardifierJunction> junctions;
    bool has_affected_box = false;
    int32_t min_x = 0, min_y = 0, min_z = 0;
    int32_t max_x = 0, max_y = 0, max_z = 0;
    std::shared_ptr<const std::array<float, 24 * 24 * 24>> kernel;
};

std::shared_ptr<const JpeBeardifierContext>
jpe_cpp_beardifier_context_acquire(uint64_t handle) noexcept;

float jpe_cpp_beardifier_context_sample(
    const JpeBeardifierContext& context, int32_t x, int32_t y, int32_t z) noexcept;
