#include "jpe_native_api.h"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <string>
#include <algorithm>
#include <array>
#include <memory>
#include <limits>
#include <cmath>
#include <stdexcept>
#include <mutex>
#include <unordered_map>
#include <vector>

/* Defined in perlin_stack.cpp. Density graphs reuse the exact ordered-stack
 * kernel instead of introducing a second noise implementation. */
extern "C" int32_t jpe_cpp_perlin_add_stack(
    const JpePerlinLayer *layers, uint32_t layer_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float *output, uint64_t output_length, uint32_t flags
) noexcept;

/* INTERPOLATED re-enters the evaluator on the coarser cell grid, so the entry
 * point needs a forward declaration before its own definition. */
extern "C" int32_t jpe_cpp_density_graph_evaluate_multi_with_inputs(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float *inputs, uint32_t input_count,
    const uint32_t *output_nodes, uint32_t output_count,
    float *outputs, uint64_t output_length
);

namespace {
constexpr uint32_t kMaxNodes = 65536;
constexpr uint64_t kMaxVolumeElements = 1024ULL * 1024ULL;
constexpr uint32_t kNoInput = UINT32_MAX;
constexpr uint32_t kMaxNoisePlans = 64;
constexpr uint32_t kMaxNoiseLayers = 64;
constexpr uint64_t kMaxNoiseFloats = 4ULL * 1024ULL * 1024ULL; // 16 MiB of shared noise staging.


constexpr uint32_t kCacheFormatVersion = 1;
constexpr size_t kMaxCacheBytes = 16U * 1024U * 1024U;
constexpr size_t kMaxCacheKeyBytes = 4096U;
constexpr char kCacheMagic[8] = {'J','P','E','G','R','P','H','1'};

void append_u32(std::vector<uint8_t>& out, uint32_t value) {
    for (unsigned shift = 0; shift < 32; shift += 8) out.push_back(static_cast<uint8_t>(value >> shift));
}
void append_u64(std::vector<uint8_t>& out, uint64_t value) {
    for (unsigned shift = 0; shift < 64; shift += 8) out.push_back(static_cast<uint8_t>(value >> shift));
}
bool read_u32(const std::vector<uint8_t>& data, size_t& offset, uint32_t& value) {
    if (offset + 4 > data.size()) return false;
    value = 0;
    for (unsigned shift = 0; shift < 32; shift += 8) value |= static_cast<uint32_t>(data[offset++]) << shift;
    return true;
}
bool read_u64(const std::vector<uint8_t>& data, size_t& offset, uint64_t& value) {
    if (offset + 8 > data.size()) return false;
    value = 0;
    for (unsigned shift = 0; shift < 64; shift += 8) value |= static_cast<uint64_t>(data[offset++]) << shift;
    return true;
}
uint64_t checksum64(const uint8_t* bytes, size_t length) noexcept {
    uint64_t hash = 1469598103934665603ULL;
    for (size_t i = 0; i < length; ++i) {
        hash ^= bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}


struct CoordinateBinding {
    uint32_t node;
    uint32_t axis; // 0 = X, 1 = Y, 2 = Z
};

struct GradientBinding {
    uint32_t node;
    uint32_t axis;
    int32_t from_coordinate;
    int32_t min_coordinate;
    int32_t max_coordinate;
    float from_value;
    float coordinate_factor;
};

int32_t signed_i32(uint32_t bits) noexcept {
    int32_t value = 0;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

struct ArithmeticInstruction {
    uint32_t opcode;
    uint32_t input_a;
    uint32_t input_b;
    uint32_t output;
    float parameter_a;
    float parameter_b;
    uint32_t input_c; /* only RANGE_CHOICE uses a third operand */
};

float java_math_min(float a, float b) noexcept {
    if (std::isnan(a)) return a;
    if (std::isnan(b)) return b;
    if (a == b) {
        if (a == 0.0f && (std::signbit(a) || std::signbit(b))) return -0.0f;
        return a;
    }
    return a < b ? a : b;
}

float minecraft_clamp(float value, float minimum, float maximum) noexcept {
    return value < minimum ? minimum : java_math_min(value, maximum);
}

// Exact MinSampler/MaxSampler sampleVolume semantics: comparison-based replacement.
// NaN comparisons and equal values preserve the left operand, including its signed zero.
float minecraft_volume_min(float left, float right) noexcept { return right < left ? right : left; }
float minecraft_volume_max(float left, float right) noexcept { return right > left ? right : left; }

float apply_unary_density(uint32_t opcode, float value, float parameter = 0.0f) noexcept {
    switch (opcode) {
        case JPE_DENSITY_SQRT:
            return static_cast<float>(std::sqrt(static_cast<double>(value)));
        case JPE_DENSITY_LOG:
            return static_cast<float>(std::log(static_cast<double>(value)));
        case JPE_DENSITY_RECIPROCAL:
            return 1.0f / value;
        case JPE_DENSITY_SIGN:
            if (std::isnan(value) || value == 0.0f) return value;
            return std::copysign(1.0f, value);
        case JPE_DENSITY_LEAKY_RELU:
            return value > 0.0f ? value : parameter * value;
        case JPE_DENSITY_SQUEEZE: {
            const float clamped = minecraft_clamp(value, -1.0f, 1.0f);
            const float cube = (clamped * clamped) * clamped;
            return (clamped / 2.0f) - (cube / 24.0f);
        }
        default:
            return value;
    }
}

struct StaticBinding {
    uint32_t node;
    float value;
};

struct InputBinding { uint32_t node; uint32_t input_index; };

/* One immutable, world-prepared noise stack. The permutation bytes are owned by
 * the compiled graph so callers may release every source buffer after compile. */
struct NoisePlanData {
    std::vector<std::array<uint8_t, 256>> permutations;
    std::vector<JpePerlinLayer> layers;
    uint32_t flags = 0;
};

/* transposed != 0 selects the vanilla ShiftB layout: sample over (z,x,0) and
 * broadcast along Y. The plan's layer scales already encode 0.25 / 0.25 / 4.0. */
struct NoiseBinding { uint32_t node; uint32_t plan_slot; uint32_t transposed; };

/* Vanilla InterpolatedFunction: coarse cell grid plus trilinear expansion. */
struct InterpolatedBinding {
    uint32_t node;
    uint32_t input_node;
    uint32_t cell_size_xz;
    uint32_t cell_size_y;
};

struct CompiledGraph {
    uint64_t fingerprint = 0;
    uint64_t configuration_fingerprint = 0;
    uint32_t ir_version = 0;
    uint32_t root = 0;
    uint32_t references = 1;
    std::vector<JpeDensityNode> nodes;
    // Lowered once at compile/load time. Evaluation uses node-major tiles so
    // contiguous sample loops can be auto-vectorized by the native compiler.
    std::vector<StaticBinding> static_values;
    std::vector<InputBinding> inputs;
    std::vector<CoordinateBinding> coordinates;
    std::vector<GradientBinding> gradients;
    std::vector<ArithmeticInstruction> arithmetic;
    std::vector<NoiseBinding> noises;
    std::vector<NoisePlanData> noise_plans;
    std::vector<InterpolatedBinding> interpolateds;
};

void build_execution_plan(CompiledGraph& graph) {
    graph.static_values.clear();
    graph.inputs.clear();
    graph.coordinates.clear();
    graph.gradients.clear();
    graph.arithmetic.clear();
    graph.static_values.reserve(graph.nodes.size());
    graph.inputs.reserve(graph.nodes.size());
    graph.coordinates.reserve(graph.nodes.size());
    graph.gradients.reserve(graph.nodes.size());
    graph.arithmetic.reserve(graph.nodes.size());

    for (uint32_t i = 0; i < graph.nodes.size(); ++i) {
        const JpeDensityNode& node = graph.nodes[i];
        switch (node.opcode) {
            case JPE_DENSITY_CONST:
                graph.static_values.push_back({i, static_cast<float>(node.immediate)});
                break;
            case JPE_DENSITY_INPUT:
                graph.inputs.push_back({i, static_cast<uint32_t>(node.immediate)});
                break;
            case JPE_DENSITY_X:
                graph.coordinates.push_back({i, 0});
                break;
            case JPE_DENSITY_Y:
                graph.coordinates.push_back({i, 1});
                break;
            case JPE_DENSITY_Z:
                graph.coordinates.push_back({i, 2});
                break;
            case JPE_DENSITY_GRADIENT_CLAMPED_X:
            case JPE_DENSITY_GRADIENT_CLAMPED_Y:
            case JPE_DENSITY_GRADIENT_CLAMPED_Z: {
                uint64_t packed = 0;
                std::memcpy(&packed, &node.immediate, sizeof(packed));
                const uint32_t from_bits = static_cast<uint32_t>(packed >> 32);
                const uint32_t factor_bits = static_cast<uint32_t>(packed);
                float from_value = 0.0f, factor = 0.0f;
                std::memcpy(&from_value, &from_bits, sizeof(from_value));
                std::memcpy(&factor, &factor_bits, sizeof(factor));
                const uint32_t axis = node.opcode == JPE_DENSITY_GRADIENT_CLAMPED_X ? 0U
                    : node.opcode == JPE_DENSITY_GRADIENT_CLAMPED_Y ? 1U : 2U;
                graph.gradients.push_back({i, axis, signed_i32(node.input_a),
                    signed_i32(node.input_b), signed_i32(node.reserved), from_value, factor});
                break;
            }
            case JPE_DENSITY_OPAQUE:
                graph.static_values.push_back({i, std::numeric_limits<float>::quiet_NaN()});
                break;
            case JPE_DENSITY_NOISE:
                graph.noises.push_back({i, node.input_a, 0});
                break;
            case JPE_DENSITY_NOISE_TRANSPOSED:
                graph.noises.push_back({i, node.input_a, 1});
                break;
            case JPE_DENSITY_INTERPOLATED:
                graph.interpolateds.push_back({i, node.input_a, node.input_b, node.reserved});
                break;
            case JPE_DENSITY_RANGE_CHOICE: {
                uint64_t packed = 0;
                std::memcpy(&packed, &node.immediate, sizeof(packed));
                const uint32_t min_bits = static_cast<uint32_t>(packed >> 32);
                const uint32_t max_bits = static_cast<uint32_t>(packed);
                float minimum = 0.0f, maximum = 0.0f;
                std::memcpy(&minimum, &min_bits, sizeof(minimum));
                std::memcpy(&maximum, &max_bits, sizeof(maximum));
                // Comparing as float is only equivalent when the double bounds are
                // exactly representable; vanilla's range_choice data always is.
                if (static_cast<double>(minimum) != static_cast<double>(static_cast<float>(minimum))
                    || static_cast<double>(maximum) != static_cast<double>(static_cast<float>(maximum))) {
                    throw std::invalid_argument("RangeChoice bounds must be exactly float-representable");
                }
                graph.arithmetic.push_back({node.opcode, node.input_a, node.input_b, i,
                                            minimum, maximum, node.reserved});
                break;
            }
            case JPE_DENSITY_ADD:
            case JPE_DENSITY_MUL:
            case JPE_DENSITY_SUB:
            case JPE_DENSITY_DIV:
            case JPE_DENSITY_MIN:
            case JPE_DENSITY_MAX:
                graph.arithmetic.push_back({node.opcode, node.input_a, node.input_b, i, 0.0f, 0.0f, 0});
                break;
            case JPE_DENSITY_NEGATE:
            case JPE_DENSITY_ABS:
            case JPE_DENSITY_SQUARE:
            case JPE_DENSITY_CUBE:
            case JPE_DENSITY_SQRT:
            case JPE_DENSITY_LOG:
            case JPE_DENSITY_RECIPROCAL:
            case JPE_DENSITY_SIGN:
            case JPE_DENSITY_SQUEEZE:
                graph.arithmetic.push_back({node.opcode, node.input_a, kNoInput, i, 0.0f, 0.0f, 0});
                break;
            case JPE_DENSITY_LEAKY_RELU:
                graph.arithmetic.push_back({node.opcode, node.input_a, kNoInput, i,
                    static_cast<float>(node.immediate), 0.0f, 0});
                break;
            case JPE_DENSITY_CLAMP: {
                uint64_t packed = 0;
                std::memcpy(&packed, &node.immediate, sizeof(packed));
                const uint32_t min_bits = static_cast<uint32_t>(packed >> 32);
                const uint32_t max_bits = static_cast<uint32_t>(packed);
                float minimum = 0.0f, maximum = 0.0f;
                std::memcpy(&minimum, &min_bits, sizeof(minimum));
                std::memcpy(&maximum, &max_bits, sizeof(maximum));
                graph.arithmetic.push_back({node.opcode, node.input_a, kNoInput, i, minimum, maximum, 0});
                break;
            }
            default:
                // valid_graph() runs before this lowering step.
                throw std::invalid_argument("Cannot lower an unknown density opcode");
        }
    }
}

#ifdef JPE_DENSITY_GRAPH_TEST_REFERENCE
// Test-only per-thread override for same-process tile-size A/B benchmarks.
thread_local uint32_t g_test_tile_limit = 0;
#endif

std::mutex g_mutex;
uint64_t g_next_handle = 1;
std::unordered_map<uint64_t, std::shared_ptr<CompiledGraph>> g_handles;
std::unordered_multimap<uint64_t, uint64_t> g_cache;

uint64_t hash_graph(const JpeDensityNode* nodes, uint32_t count,
                    uint32_t root, uint32_t ir_version, uint64_t configuration) noexcept {
    // FNV-1a over explicit fields, avoiding struct padding and host pointer values.
    uint64_t hash = 1469598103934665603ULL;
    const auto mix = [&hash](const uint8_t* bytes, size_t length) {
        for (size_t i = 0; i < length; ++i) {
            hash ^= bytes[i];
            hash *= 1099511628211ULL;
        }
    };
    const auto mix_u32 = [&mix](uint32_t value) {
        for (unsigned shift = 0; shift < 32; shift += 8) {
            const uint8_t byte = static_cast<uint8_t>(value >> shift);
            mix(&byte, 1);
        }
    };
    const auto mix_u64 = [&mix](uint64_t value) {
        for (unsigned shift = 0; shift < 64; shift += 8) {
            const uint8_t byte = static_cast<uint8_t>(value >> shift);
            mix(&byte, 1);
        }
    };
    mix_u64(configuration);
    mix_u32(count);
    mix_u32(root);
    mix_u32(ir_version);
    for (uint32_t i = 0; i < count; ++i) {
        mix_u32(nodes[i].opcode);
        mix_u32(nodes[i].input_a);
        mix_u32(nodes[i].input_b);
        mix_u32(nodes[i].reserved);
        uint64_t immediate_bits;
        std::memcpy(&immediate_bits, &nodes[i].immediate, sizeof(immediate_bits));
        mix_u64(immediate_bits);
    }
    return hash;
}

/* Noise payloads are large (up to 64 plans x 64 layers x 256 bytes), so the
 * fingerprint mixes a compact digest and the cache hit path confirms it with a
 * full comparison. Both steps are one-time per graph, never per chunk. */
uint64_t hash_noise_payload(const JpeNoisePlan* plans, uint32_t count) noexcept {
    uint64_t hash = 1469598103934665603ULL;
    const auto mix = [&hash](const uint8_t* bytes, size_t length) {
        for (size_t i = 0; i < length; ++i) {
            hash ^= bytes[i];
            hash *= 1099511628211ULL;
        }
    };
    const auto mix_u64 = [&mix](uint64_t value) {
        for (unsigned shift = 0; shift < 64; shift += 8) {
            const uint8_t byte = static_cast<uint8_t>(value >> shift);
            mix(&byte, 1);
        }
    };
    for (uint32_t slot = 0; slot < count; ++slot) {
        const JpeNoisePlan& plan = plans[slot];
        mix_u64(plan.layer_count);
        mix_u64(plan.flags);
        for (uint32_t layer = 0; layer < plan.layer_count; ++layer) {
            const JpePerlinLayer& item = plan.layers[layer];
            uint64_t bits = 0;
            std::memcpy(&bits, &item.offset_x, sizeof(bits)); mix_u64(bits);
            std::memcpy(&bits, &item.offset_y, sizeof(bits)); mix_u64(bits);
            std::memcpy(&bits, &item.offset_z, sizeof(bits)); mix_u64(bits);
            std::memcpy(&bits, &item.xz_scale, sizeof(bits)); mix_u64(bits);
            std::memcpy(&bits, &item.y_scale, sizeof(bits)); mix_u64(bits);
            std::memcpy(&bits, &item.fudge_y_scale, sizeof(bits)); mix_u64(bits);
            uint32_t amplitude_bits = 0;
            std::memcpy(&amplitude_bits, &item.amplitude, sizeof(amplitude_bits));
            mix_u64(amplitude_bits);
            mix_u64(item.flags);
            if (item.permutation != nullptr) mix(item.permutation, 256);
        }
    }
    return hash;
}

bool same_noise_payload(const std::vector<NoisePlanData>& stored,
                        const JpeNoisePlan* plans, uint32_t count) noexcept {
    if (stored.size() != count) return false;
    for (uint32_t slot = 0; slot < count; ++slot) {
        const NoisePlanData& left = stored[slot];
        const JpeNoisePlan& right = plans[slot];
        if (left.flags != right.flags || left.layers.size() != right.layer_count) return false;
        for (uint32_t layer = 0; layer < right.layer_count; ++layer) {
            const JpePerlinLayer& a = left.layers[layer];
            const JpePerlinLayer& b = right.layers[layer];
            if (a.offset_x != b.offset_x || a.offset_y != b.offset_y || a.offset_z != b.offset_z
                || a.xz_scale != b.xz_scale || a.y_scale != b.y_scale
                || a.amplitude != b.amplitude || a.fudge_y_scale != b.fudge_y_scale
                || a.flags != b.flags) return false;
            if (b.permutation == nullptr) return false;
            if (std::memcmp(left.permutations[layer].data(), b.permutation, 256) != 0) return false;
        }
    }
    return true;
}

bool valid_graph(const JpeDensityNode* nodes, uint32_t count, uint32_t root) noexcept {    if (nodes == nullptr || count == 0 || count > kMaxNodes || root >= count) return false;
    for (uint32_t i = 0; i < count; ++i) {
        const JpeDensityNode& node = nodes[i];
        switch (node.opcode) {
            case JPE_DENSITY_CONST:
            case JPE_DENSITY_X:
            case JPE_DENSITY_Y:
            case JPE_DENSITY_Z:
                if (node.input_a != kNoInput || node.input_b != kNoInput || node.reserved != 0) return false;
                break;
            case JPE_DENSITY_GRADIENT_CLAMPED_X:
            case JPE_DENSITY_GRADIENT_CLAMPED_Y:
            case JPE_DENSITY_GRADIENT_CLAMPED_Z:
                if (signed_i32(node.input_b) > signed_i32(node.reserved)) return false;
                break;
            case JPE_DENSITY_INPUT:
                if (node.reserved != 0 || node.input_a != kNoInput || node.input_b != kNoInput
                    || !std::isfinite(node.immediate) || node.immediate < 0.0
                    || node.immediate > 255.0 || std::floor(node.immediate) != node.immediate) return false;
                break;
            case JPE_DENSITY_OPAQUE:
                // Explicit experimental sentinel. It is never emitted by STRICT capture.
                if (node.reserved != 1
                    || (node.input_a == kNoInput && node.input_b != kNoInput)
                    || (node.input_a != kNoInput && node.input_a >= i)
                    || (node.input_b != kNoInput && node.input_b >= i)) return false;
                break;
            case JPE_DENSITY_ADD:
            case JPE_DENSITY_MUL:
            case JPE_DENSITY_SUB:
            case JPE_DENSITY_DIV:
            case JPE_DENSITY_MIN:
            case JPE_DENSITY_MAX:
                if (node.reserved != 0 || node.input_a >= i || node.input_b >= i) return false;
                break;
            case JPE_DENSITY_NEGATE:
            case JPE_DENSITY_ABS:
            case JPE_DENSITY_SQUARE:
            case JPE_DENSITY_CUBE:
            case JPE_DENSITY_SQRT:
            case JPE_DENSITY_LOG:
            case JPE_DENSITY_RECIPROCAL:
            case JPE_DENSITY_SIGN:
            case JPE_DENSITY_SQUEEZE:
                if (node.reserved != 0 || node.input_a >= i || node.input_b != kNoInput) return false;
                break;
            case JPE_DENSITY_LEAKY_RELU:
                if (node.reserved != 0 || node.input_a >= i || node.input_b != kNoInput
                    || !std::isfinite(node.immediate)
                    || static_cast<double>(static_cast<float>(node.immediate)) != node.immediate) return false;
                break;
            case JPE_DENSITY_CLAMP:
                if (node.reserved != 0 || node.input_a >= i || node.input_b != kNoInput) return false;
                break;
            case JPE_DENSITY_NOISE:
            case JPE_DENSITY_NOISE_TRANSPOSED:
                // Plan-slot range is validated at compile time, where the count is known.
                if (node.input_b != kNoInput || node.reserved != 0 || node.immediate != 0.0) return false;
                break;
            case JPE_DENSITY_INTERPOLATED:
                if (node.input_a >= i || node.input_b == 0 || node.reserved == 0
                    || node.immediate != 0.0) return false;
                if (node.input_b > 64 || node.reserved > 64) return false;
                break;
            case JPE_DENSITY_RANGE_CHOICE:
                if (node.input_a >= i || node.input_b >= i || node.reserved >= i) return false;
                if (!std::isfinite(node.immediate)) return false;
                break;
            default:
                return false;
        }
    }
    return true;
}

/* Java floor semantics: C++ integer division truncates toward zero. */
/* INTERPOLATED re-enters the public evaluator. These thread-local guards bound the
 * recursion and restrict the nested call to the child's dependency prefix, because
 * topological order guarantees every dependency sits at a lower index. */
thread_local uint32_t g_node_limit = 0;
thread_local uint32_t g_interpolate_depth = 0;
constexpr uint32_t kMaxInterpolateDepth = 8;

int32_t floor_div(int32_t value, int32_t divisor) noexcept {
    int32_t quotient = value / divisor;
    if ((value % divisor != 0) && ((value < 0) != (divisor < 0))) --quotient;
    return quotient;
}

int32_t floor_mod(int32_t value, int32_t divisor) noexcept {
    int32_t remainder = value % divisor;
    if (remainder != 0 && ((remainder < 0) != (divisor < 0))) remainder += divisor;
    return remainder;
}

/* Exact Minecraft Mth.lerp(float, float, float). */
float lerp_f32(float delta, float start, float end) noexcept {
    return start + delta * (end - start);
}

int32_t wrapped_coordinate(int32_t minimum, uint32_t index, int32_t step) noexcept {
    const uint32_t bits = static_cast<uint32_t>(minimum)
        + index * static_cast<uint32_t>(step);
    int32_t result;
    std::memcpy(&result, &bits, sizeof(result));
    return result;
}

bool valid_volume(uint32_t sx, uint32_t sy, uint32_t sz,
                  int32_t dx, int32_t dy, int32_t dz,
                  uint64_t output_length) noexcept {
    if (sx == 0 || sy == 0 || sz == 0 || dx == 0 || dy == 0 || dz == 0) return false;
    const uint64_t xy = static_cast<uint64_t>(sx) * sy;
    if (xy > kMaxVolumeElements / sz) return false;
    return xy * sz == output_length && output_length <= kMaxVolumeElements;
}
} // namespace

extern "C" uint64_t jpe_cpp_density_graph_compile(
    const JpeDensityNode* nodes, uint32_t node_count, uint32_t root_node,
    uint32_t ir_version, uint64_t configuration_fingerprint
) try {
    if (ir_version != 10 || !valid_graph(nodes, node_count, root_node)) return 0;
    // A NOISE node needs a bound plan. This entry has no plan table, so accepting
    // one here would leave the node reading an empty staging buffer at evaluate time.
    for (uint32_t i = 0; i < node_count; ++i) {
        if (nodes[i].opcode == JPE_DENSITY_NOISE
            || nodes[i].opcode == JPE_DENSITY_NOISE_TRANSPOSED) return 0;
    }
    const uint64_t fingerprint = hash_graph(nodes, node_count, root_node, ir_version, configuration_fingerprint);
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto range = g_cache.equal_range(fingerprint);
    for (auto it = range.first; it != range.second; ++it) {
        const auto found = g_handles.find(it->second);
        if (found == g_handles.end()) continue;
        auto& graph = *found->second;
        if (graph.ir_version == ir_version
            && graph.configuration_fingerprint == configuration_fingerprint
            && graph.root == root_node && graph.nodes.size() == node_count
            && std::memcmp(graph.nodes.data(), nodes,
                           static_cast<size_t>(node_count) * sizeof(JpeDensityNode)) == 0) {
            ++graph.references;
            return found->first;
        }
    }

    auto graph = std::make_shared<CompiledGraph>();
    graph->fingerprint = fingerprint;
    graph->configuration_fingerprint = configuration_fingerprint;
    graph->ir_version = ir_version;
    graph->root = root_node;
    graph->nodes.assign(nodes, nodes + node_count);
    build_execution_plan(*graph);
    uint64_t handle = g_next_handle++;
    if (handle == 0) handle = g_next_handle++;
    g_handles.emplace(handle, std::move(graph));
    g_cache.emplace(fingerprint, handle);
    return handle;
} catch (...) { return 0; }

/* Noise-bearing graphs carry payload that hash_graph deliberately does not cover
 * (permutations and layer scalars live in the plan, not the node array), so this
 * entry never joins the deduplicating cache. The caller's configuration fingerprint
 * must already identify the noise configuration. */
extern "C" uint64_t jpe_cpp_density_graph_compile_with_noise(
    const JpeDensityNode* nodes, uint32_t node_count, uint32_t root_node,
    uint32_t ir_version, uint64_t configuration_fingerprint,
    const JpeNoisePlan* noise_plans, uint32_t noise_plan_count
) try {
    if (ir_version != 10 || !valid_graph(nodes, node_count, root_node)) return 0;
    if (noise_plan_count > kMaxNoisePlans) return 0;
    if (noise_plan_count > 0 && noise_plans == nullptr) return 0;
    std::vector<int8_t> slot_layout(noise_plan_count, -1);
    for (uint32_t i = 0; i < node_count; ++i) {
        if (nodes[i].opcode != JPE_DENSITY_NOISE
            && nodes[i].opcode != JPE_DENSITY_NOISE_TRANSPOSED) continue;
        if (nodes[i].input_a >= noise_plan_count) return 0;
        const int8_t layout = nodes[i].opcode == JPE_DENSITY_NOISE_TRANSPOSED ? 1 : 0;
        int8_t& existing = slot_layout[nodes[i].input_a];
        // One staging buffer per slot, so a slot cannot serve two layouts.
        if (existing != -1 && existing != layout) return 0;
        existing = layout;
    }

    const uint64_t base = hash_graph(nodes, node_count, root_node, ir_version, configuration_fingerprint);
    const uint64_t noise_hash = hash_noise_payload(noise_plans, noise_plan_count);
    // Boost-style mix keeps node identity and noise payload in one cache key.
    const uint64_t fingerprint =
        base ^ (noise_hash + 0x9E3779B97F4A7C15ULL + (base << 6) + (base >> 2));

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto range = g_cache.equal_range(fingerprint);
        for (auto it = range.first; it != range.second; ++it) {
            const auto found = g_handles.find(it->second);
            if (found == g_handles.end()) continue;
            auto& graph = *found->second;
            if (graph.ir_version == ir_version
                && graph.configuration_fingerprint == configuration_fingerprint
                && graph.root == root_node && graph.nodes.size() == node_count
                && std::memcmp(graph.nodes.data(), nodes,
                               static_cast<size_t>(node_count) * sizeof(JpeDensityNode)) == 0
                && same_noise_payload(graph.noise_plans, noise_plans, noise_plan_count)) {
                ++graph.references;
                return found->first;
            }
        }
    }

    auto graph = std::make_shared<CompiledGraph>();
    graph->fingerprint = fingerprint;
    graph->configuration_fingerprint = configuration_fingerprint;
    graph->ir_version = ir_version;
    graph->root = root_node;
    graph->nodes.assign(nodes, nodes + node_count);

    graph->noise_plans.resize(noise_plan_count);
    for (uint32_t slot = 0; slot < noise_plan_count; ++slot) {
        const JpeNoisePlan& plan = noise_plans[slot];
        if (plan.layers == nullptr || plan.layer_count == 0 || plan.layer_count > kMaxNoiseLayers) return 0;
        NoisePlanData& target = graph->noise_plans[slot];
        target.flags = plan.flags;
        target.permutations.resize(plan.layer_count);
        target.layers.assign(plan.layers, plan.layers + plan.layer_count);
        for (uint32_t layer = 0; layer < plan.layer_count; ++layer) {
            if (plan.layers[layer].permutation == nullptr) return 0;
            std::memcpy(target.permutations[layer].data(), plan.layers[layer].permutation, 256);
            // Repoint at graph-owned storage; the caller's buffer may now be freed.
            target.layers[layer].permutation = target.permutations[layer].data();
        }
    }

    build_execution_plan(*graph);
    std::lock_guard<std::mutex> lock(g_mutex);
    uint64_t handle = g_next_handle++;
    if (handle == 0) handle = g_next_handle++;
    g_handles.emplace(handle, std::move(graph));
    g_cache.emplace(fingerprint, handle);
    return handle;
} catch (...) { return 0; }


extern "C" int32_t jpe_cpp_density_graph_save_cache(
    uint64_t graph_handle, const char* path, const char* cache_key
) try {
    if (path == nullptr || cache_key == nullptr) return 1;
    const size_t key_length = std::strlen(cache_key);
    if (key_length == 0 || key_length > kMaxCacheKeyBytes) return 1;
    std::shared_ptr<CompiledGraph> graph;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto found = g_handles.find(graph_handle);
        if (found == g_handles.end()) return 2;
        graph = found->second;
    }
    // The on-disk format carries nodes only; a noise plan must be rebound by the
    // caller, so persisting a noise-bearing graph would silently lose its payload.
    if (!graph->noises.empty()) return 1;
    std::vector<uint8_t> bytes;
    bytes.reserve(40U + key_length + graph->nodes.size() * 24U);
    bytes.insert(bytes.end(), kCacheMagic, kCacheMagic + sizeof(kCacheMagic));
    append_u32(bytes, kCacheFormatVersion);
    append_u32(bytes, graph->ir_version);
    append_u64(bytes, graph->configuration_fingerprint);
    append_u32(bytes, graph->root);
    append_u32(bytes, static_cast<uint32_t>(graph->nodes.size()));
    append_u32(bytes, static_cast<uint32_t>(key_length));
    bytes.insert(bytes.end(), cache_key, cache_key + key_length);
    for (const JpeDensityNode& node : graph->nodes) {
        append_u32(bytes, node.opcode);
        append_u32(bytes, node.input_a);
        append_u32(bytes, node.input_b);
        append_u32(bytes, node.reserved);
        uint64_t immediate_bits;
        std::memcpy(&immediate_bits, &node.immediate, sizeof(immediate_bits));
        append_u64(bytes, immediate_bits);
    }
    append_u64(bytes, checksum64(bytes.data(), bytes.size()));
    if (bytes.size() > kMaxCacheBytes) return 1;
    const std::string temporary = std::string(path) + ".tmp";
    {
        std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
        if (!output) return 3;
        output.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
        output.flush();
        if (!output) return 3;
    }
    if (std::rename(temporary.c_str(), path) != 0) {
        std::remove(temporary.c_str());
        return 3;
    }
    return 0;
} catch (...) { return 3; }

extern "C" uint64_t jpe_cpp_density_graph_load_cache(
    const char* path, const char* cache_key
) try {
    if (path == nullptr || cache_key == nullptr) return 0;
    const size_t key_length = std::strlen(cache_key);
    if (key_length == 0 || key_length > kMaxCacheKeyBytes) return 0;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return 0;
    const std::streamoff file_size = input.tellg();
    if (file_size < 48 || static_cast<uint64_t>(file_size) > kMaxCacheBytes) return 0;
    std::vector<uint8_t> bytes(static_cast<size_t>(file_size));
    input.seekg(0);
    input.read(reinterpret_cast<char*>(bytes.data()), file_size);
    if (!input || bytes.size() < 8U + 4U + 4U + 8U + 4U + 4U + 4U + 8U) return 0;
    const size_t payload_length = bytes.size() - 8U;
    size_t checksum_offset = payload_length;
    uint64_t stored_checksum = 0;
    if (!read_u64(bytes, checksum_offset, stored_checksum)
        || stored_checksum != checksum64(bytes.data(), payload_length)) return 0;
    if (std::memcmp(bytes.data(), kCacheMagic, sizeof(kCacheMagic)) != 0) return 0;
    size_t offset = sizeof(kCacheMagic);
    uint32_t format = 0, ir_version = 0, root = 0, count = 0, stored_key_length = 0;
    uint64_t configuration = 0;
    if (!read_u32(bytes, offset, format) || format != kCacheFormatVersion
        || !read_u32(bytes, offset, ir_version)
        || !read_u64(bytes, offset, configuration)
        || !read_u32(bytes, offset, root)
        || !read_u32(bytes, offset, count)
        || !read_u32(bytes, offset, stored_key_length)) return 0;
    if (stored_key_length != key_length || offset + stored_key_length > payload_length
        || std::memcmp(bytes.data() + offset, cache_key, key_length) != 0) return 0;
    offset += stored_key_length;
    if (count == 0 || count > kMaxNodes
        || static_cast<uint64_t>(count) * 24ULL > payload_length - offset) return 0;
    std::vector<JpeDensityNode> nodes(count);
    for (uint32_t i = 0; i < count; ++i) {
        uint64_t immediate_bits = 0;
        JpeDensityNode& node = nodes[i];
        if (!read_u32(bytes, offset, node.opcode)
            || !read_u32(bytes, offset, node.input_a)
            || !read_u32(bytes, offset, node.input_b)
            || !read_u32(bytes, offset, node.reserved)
            || !read_u64(bytes, offset, immediate_bits)) return 0;
        std::memcpy(&node.immediate, &immediate_bits, sizeof(immediate_bits));
    }
    if (offset != payload_length || ir_version != 10 || !valid_graph(nodes.data(), count, root)) return 0;
    return jpe_cpp_density_graph_compile(nodes.data(), count, root, ir_version, configuration);
} catch (...) { return 0; }

extern "C" int32_t jpe_cpp_density_graph_evaluate_multi_with_inputs(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float* inputs, uint32_t input_count,
    const uint32_t* output_nodes, uint32_t output_count,
    float* outputs, uint64_t output_length
) try {
    if (outputs == nullptr || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0
        || input_count > 256 || output_count > 256
        || (input_count > 0 && inputs == nullptr)) return 1;
    const uint64_t xy = static_cast<uint64_t>(size_x) * size_y;
    if (xy > kMaxVolumeElements / size_z) return 1;
    const uint64_t volume_length = xy * size_z;
    if (volume_length == 0) return 1;

    std::shared_ptr<CompiledGraph> graph;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto found = g_handles.find(graph_handle);
        if (found == g_handles.end()) return 2;
        graph = found->second;
    }

    std::vector<uint32_t> selected_roots;
    if (output_count == 0) {
        if (output_nodes != nullptr) return 1;
        selected_roots.push_back(graph->root);
    } else {
        if (output_nodes == nullptr) return 1;
        selected_roots.assign(output_nodes, output_nodes + output_count);
        for (uint32_t root : selected_roots) if (root >= graph->nodes.size()) return 1;
    }
    if (selected_roots.empty()
        || static_cast<uint64_t>(input_count + selected_roots.size()) > 16777216ULL / volume_length
        || output_length != volume_length * selected_roots.size()) return 1;
    const uint32_t node_limit = g_node_limit == 0
        ? static_cast<uint32_t>(graph->nodes.size()) : g_node_limit;

    // Evaluate a bounded tile of samples at once. Node-major storage lets the
    // compiler vectorize each ADD/MUL across contiguous samples while preserving
    // the topological order (and therefore every float32 rounding point).
    // A 51-round full-chunk sweep using the captured 164-node vanilla operation
    // inventory found 256 samples fastest among 256/384/512/768/1024/1225.
    // Complex graphs still shrink tiles automatically under the 1 MiB node budget.
    constexpr uint32_t kMaxTileSamples = 256;
    constexpr size_t kMaxTileValues = 262144; // <= 1 MiB of float scratch.
    const size_t node_count = graph->nodes.size();
    uint32_t tile_sample_limit = kMaxTileSamples;
#ifdef JPE_DENSITY_GRAPH_TEST_REFERENCE
    // Benchmark-only override deliberately permits testing above the production ceiling.
    if (g_test_tile_limit != 0) {
        tile_sample_limit = g_test_tile_limit;
    }
#endif
    const uint32_t tile_capacity = static_cast<uint32_t>(
        std::max<size_t>(1, std::min<size_t>(tile_sample_limit, kMaxTileValues / node_count)));
    // These scratch buffers are deliberately local rather than thread_local:
    // INTERPOLATED re-enters this evaluator on the coarser cell grid, and a shared
    // buffer would be resized underneath the outer call. The bounded sizes keep the
    // per-call allocation small (under 1 MiB) relative to a chunk's own work.
    std::vector<float> tile_values(node_count * tile_capacity);
    std::vector<float> coordinate_scratch(static_cast<size_t>(tile_capacity) * 3);
    float* x_values = coordinate_scratch.data();
    float* y_values = x_values + tile_capacity;
    float* z_values = y_values + tile_capacity;
    std::vector<int32_t> integer_coordinate_scratch(static_cast<size_t>(tile_capacity) * 3);
    int32_t* x_coordinates = integer_coordinate_scratch.data();
    int32_t* y_coordinates = x_coordinates + tile_capacity;
    int32_t* z_coordinates = y_coordinates + tile_capacity;
    const uint64_t plane_size = static_cast<uint64_t>(size_x) * size_y;

    // Static leaves do not change between tiles, so initialize their lanes once
    // per volume rather than refilling every tile.
    for (const StaticBinding& binding : graph->static_values) {
        float* destination = tile_values.data() + static_cast<size_t>(binding.node) * tile_capacity;
        std::fill_n(destination, tile_capacity, binding.value);
    }
    for (const InputBinding& binding : graph->inputs) {
        if (binding.input_index >= input_count) return 1;
    }

    // Noise nodes are whole-volume: one stack pass per plan, then the tile lanes
    // read slices of that buffer. This keeps the tile sweep free of per-sample
    // noise work and preserves the kernel's own Y-loop hoisting.
    std::vector<float> noise_values;
    if (!graph->noises.empty()) {
        if (graph->noise_plans.empty()) return 1;
        const uint64_t staged = static_cast<uint64_t>(graph->noise_plans.size()) * volume_length;
        if (staged > kMaxNoiseFloats) return 1;
        noise_values.resize(static_cast<size_t>(staged));
        for (const NoiseBinding& binding : graph->noises) {
            const NoisePlanData& plan = graph->noise_plans[binding.plan_slot];
            float* destination = noise_values.data()
                + static_cast<uint64_t>(binding.plan_slot) * volume_length;
            if (binding.transposed == 0) {
                const int32_t status = jpe_cpp_perlin_add_stack(
                    plan.layers.data(), static_cast<uint32_t>(plan.layers.size()),
                    size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z,
                    destination, volume_length, plan.flags);
                if (status != 0) return 3;
                continue;
            }
            // Vanilla ShiftB samples a (z,x,1) transposed plane, then writes the
            // same value into every Y lane of that column.
            const uint32_t plane_x = size_z;
            const uint32_t plane_y = size_x;
            const uint64_t plane_length = static_cast<uint64_t>(plane_x) * plane_y;
            std::vector<float> transposed_plane(static_cast<size_t>(plane_length));
            const int32_t status = jpe_cpp_perlin_add_stack(
                plan.layers.data(), static_cast<uint32_t>(plan.layers.size()),
                plane_x, plane_y, 1, min_z, min_x, 0, step_z, step_x, 1,
                transposed_plane.data(), plane_length, plan.flags);
            if (status != 0) return 3;
            for (uint32_t z = 0; z < size_z; ++z) {
                for (uint32_t x = 0; x < size_x; ++x) {
                    const float value = transposed_plane[static_cast<uint64_t>(z) * size_x + x];
                    float* column = destination
                        + (static_cast<uint64_t>(z) * size_x + x) * size_y;
                    std::fill_n(column, size_y, value);
                }
            }
        }
    }

    // INTERPOLATED evaluates its child on a coarse cell grid, then expands with the
    // same Z->X->Y order vanilla uses. The Y axis is a running sum, not a fresh lerp,
    // because vanilla accumulates valueStep; recomputing it would change the bits.
    std::vector<float> interpolated_values;
    if (!graph->interpolateds.empty()) {
        if (!graph->inputs.empty()) {
            // External volumes are staged at output resolution, so a coarser child
            // grid cannot index them. Refuse rather than silently read the wrong data.
            return 1;
        }
        const uint64_t staged = static_cast<uint64_t>(graph->interpolateds.size()) * volume_length;
        if (staged > kMaxNoiseFloats) return 1;
        interpolated_values.resize(static_cast<size_t>(staged));
        for (uint32_t index = 0; index < graph->interpolateds.size(); ++index) {
            const InterpolatedBinding& binding = graph->interpolateds[index];
            if (binding.node >= node_limit) continue;
            const int32_t csz = static_cast<int32_t>(binding.cell_size_xz);
            const int32_t csy = static_cast<int32_t>(binding.cell_size_y);
            float* destination = interpolated_values.data()
                + static_cast<uint64_t>(index) * volume_length;

            const bool on_grid =
                (step_x == csz || size_x == 1) && (step_y == csy || size_y == 1)
                && (step_z == csz || size_z == 1)
                && floor_mod(min_x, csz) == 0 && floor_mod(min_y, csy) == 0
                && floor_mod(min_z, csz) == 0;
            const auto recurse = [&](uint32_t nx, uint32_t ny, uint32_t nz,
                                     int32_t mx, int32_t my, int32_t mz,
                                     int32_t rsx, int32_t rsy, int32_t rsz,
                                     float* target, uint64_t target_length) -> int32_t {
                if (g_interpolate_depth >= kMaxInterpolateDepth) return 1;
                ++g_interpolate_depth;
                const uint32_t saved_limit = g_node_limit;
                g_node_limit = binding.input_node + 1;
                const int32_t status = jpe_cpp_density_graph_evaluate_multi_with_inputs(
                    graph_handle, nx, ny, nz, mx, my, mz, rsx, rsy, rsz,
                    inputs, input_count, &binding.input_node, 1, target, target_length);
                g_node_limit = saved_limit;
                --g_interpolate_depth;
                return status;
            };

            if (on_grid) {
                const int32_t status = recurse(
                    size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z,
                    destination, volume_length);
                if (status != 0) return status;
                continue;
            }
            if (step_x <= 0 || step_y <= 0 || step_z <= 0) return 1;

            if (step_x != 1 || step_y != 1 || step_z != 1) {
                // Re-expand to a unit-step block volume first, mirroring vanilla's
                // sampleWithNonBlockStep, then subsample at the original steps.
                const uint32_t bx = size_x * static_cast<uint32_t>(step_x);
                const uint32_t by = size_y * static_cast<uint32_t>(step_y);
                const uint32_t bz = size_z * static_cast<uint32_t>(step_z);
                const uint64_t block_length = static_cast<uint64_t>(bx) * by * bz;
                if (block_length > kMaxNoiseFloats) return 1;
                std::vector<float> block_buffer(static_cast<size_t>(block_length));
                const int32_t status = recurse(
                    bx, by, bz, min_x, min_y, min_z, 1, 1, 1,
                    block_buffer.data(), block_length);
                if (status != 0) return status;
                for (uint32_t z = 0; z < size_z; ++z) {
                    for (uint32_t x = 0; x < size_x; ++x) {
                        for (uint32_t y = 0; y < size_y; ++y) {
                            const uint64_t source =
                                (static_cast<uint64_t>(z * static_cast<uint32_t>(step_z)) * bx
                                 + x * static_cast<uint32_t>(step_x)) * by
                                + y * static_cast<uint32_t>(step_y);
                            destination[(static_cast<uint64_t>(z) * size_x + x) * size_y + y]
                                = block_buffer[static_cast<size_t>(source)];
                        }
                    }
                }
                continue;
            }

            const int32_t max_block_x = min_x + static_cast<int32_t>(size_x - 1) * step_x;
            const int32_t max_block_y = min_y + static_cast<int32_t>(size_y - 1) * step_y;
            const int32_t max_block_z = min_z + static_cast<int32_t>(size_z - 1) * step_z;
            const int32_t min_cell_x = floor_div(min_x, csz);
            const int32_t min_cell_y = floor_div(min_y, csy);
            const int32_t min_cell_z = floor_div(min_z, csz);
            const int32_t max_cell_x = floor_div(max_block_x, csz);
            const int32_t max_cell_y = floor_div(max_block_y, csy);
            const int32_t max_cell_z = floor_div(max_block_z, csz);
            const uint32_t cell_count_x = static_cast<uint32_t>(max_cell_x - min_cell_x + 1);
            const uint32_t cell_count_y = static_cast<uint32_t>(max_cell_y - min_cell_y + 1);
            const uint32_t cell_count_z = static_cast<uint32_t>(max_cell_z - min_cell_z + 1);
            const uint32_t grid_x = floor_mod(max_block_x, csz) == 0 ? cell_count_x : cell_count_x + 1;
            const uint32_t grid_y = floor_mod(max_block_y, csy) == 0 ? cell_count_y : cell_count_y + 1;
            const uint32_t grid_z = floor_mod(max_block_z, csz) == 0 ? cell_count_z : cell_count_z + 1;
            const uint64_t cell_length = static_cast<uint64_t>(grid_x) * grid_y * grid_z;
            if (cell_length > kMaxNoiseFloats) return 1;
            std::vector<float> cell_buffer(static_cast<size_t>(cell_length));
            const int32_t status = recurse(
                grid_x, grid_y, grid_z,
                min_cell_x * csz, min_cell_y * csy, min_cell_z * csz,
                csz, csy, csz, cell_buffer.data(), cell_length);
            if (status != 0) return status;

            const float inv_xz = 1.0f / static_cast<float>(csz);
            const float inv_y = 1.0f / static_cast<float>(csy);
            const auto cell_at = [&](uint32_t ix, uint32_t iy, uint32_t iz) {
                return cell_buffer[(static_cast<uint64_t>(iz) * grid_x + ix) * grid_y + iy];
            };
            for (uint32_t cell_z = 0; cell_z < cell_count_z; ++cell_z) {
                const uint32_t next_z = std::min(cell_z + 1, grid_z - 1);
                for (uint32_t cell_x = 0; cell_x < cell_count_x; ++cell_x) {
                    const uint32_t next_x = std::min(cell_x + 1, grid_x - 1);
                    float v000 = cell_at(cell_x, 0, cell_z);
                    float v100 = cell_at(next_x, 0, cell_z);
                    float v001 = cell_at(cell_x, 0, next_z);
                    float v101 = cell_at(next_x, 0, next_z);
                    for (uint32_t cell_y = 0; cell_y < cell_count_y; ++cell_y) {
                        const uint32_t next_y = std::min(cell_y + 1, grid_y - 1);
                        const float v010 = cell_at(cell_x, next_y, cell_z);
                        const float v110 = cell_at(next_x, next_y, cell_z);
                        const float v011 = cell_at(cell_x, next_y, next_z);
                        const float v111 = cell_at(next_x, next_y, next_z);

                        const int32_t out_x = (min_cell_x + static_cast<int32_t>(cell_x)) * csz - min_x;
                        const int32_t out_y = (min_cell_y + static_cast<int32_t>(cell_y)) * csy - min_y;
                        const int32_t out_z = (min_cell_z + static_cast<int32_t>(cell_z)) * csz - min_z;
                        const int32_t x0 = std::max(0, -out_x);
                        const int32_t y0 = std::max(0, -out_y);
                        const int32_t z0 = std::max(0, -out_z);
                        const int32_t x1 = std::min(csz, static_cast<int32_t>(size_x) - out_x) - 1;
                        const int32_t y1 = std::min(csy, static_cast<int32_t>(size_y) - out_y) - 1;
                        const int32_t z1 = std::min(csz, static_cast<int32_t>(size_z) - out_z) - 1;
                        for (int32_t zz = z0; zz <= z1; ++zz) {
                            const int32_t output_z = out_z + zz;
                            const float alpha_z = static_cast<float>(zz) * inv_xz;
                            const float vz00 = lerp_f32(alpha_z, v000, v001);
                            const float vz01 = lerp_f32(alpha_z, v010, v011);
                            const float vz10 = lerp_f32(alpha_z, v100, v101);
                            const float vz11 = lerp_f32(alpha_z, v110, v111);
                            for (int32_t xx = x0; xx <= x1; ++xx) {
                                const int32_t output_x = out_x + xx;
                                const float alpha_x = static_cast<float>(xx) * inv_xz;
                                const float low = lerp_f32(alpha_x, vz00, vz10);
                                const float high = lerp_f32(alpha_x, vz01, vz11);
                                const float value_step = (high - low) * inv_y;
                                float value = low + value_step * static_cast<float>(y0);
                                uint64_t output_index =
                                    (static_cast<uint64_t>(output_z) * size_x
                                     + static_cast<uint32_t>(output_x)) * size_y
                                    + static_cast<uint32_t>(out_y + y0);
                                for (int32_t yy = y0; yy <= y1; ++yy) {
                                    destination[output_index++] = value;
                                    value += value_step;
                                }
                            }
                        }

                        v000 = v010;
                        v100 = v110;
                        v001 = v011;
                        v101 = v111;
                    }
                }
            }
        }
    }

    for (uint64_t tile_start = 0; tile_start < volume_length; tile_start += tile_capacity) {
        const uint32_t count = static_cast<uint32_t>(
            std::min<uint64_t>(tile_capacity, volume_length - tile_start));

        const uint32_t z_start = static_cast<uint32_t>(tile_start / plane_size);
        const uint64_t within_plane = tile_start - static_cast<uint64_t>(z_start) * plane_size;
        uint32_t x_index = static_cast<uint32_t>(within_plane / size_y);
        uint32_t y_index = static_cast<uint32_t>(within_plane - static_cast<uint64_t>(x_index) * size_y);
        uint32_t z_index = z_start;

        for (uint32_t sample = 0; sample < count; ++sample) {
            x_coordinates[sample] = wrapped_coordinate(min_x, x_index, step_x);
            y_coordinates[sample] = wrapped_coordinate(min_y, y_index, step_y);
            z_coordinates[sample] = wrapped_coordinate(min_z, z_index, step_z);
            x_values[sample] = static_cast<float>(x_coordinates[sample]);
            y_values[sample] = static_cast<float>(y_coordinates[sample]);
            z_values[sample] = static_cast<float>(z_coordinates[sample]);
            if (++y_index == size_y) {
                y_index = 0;
                if (++x_index == size_x) {
                    x_index = 0;
                    ++z_index;
                }
            }
        }

        for (const InputBinding& binding : graph->inputs) {
            const float* source = inputs + static_cast<uint64_t>(binding.input_index) * volume_length + tile_start;
            float* destination = tile_values.data() + static_cast<size_t>(binding.node) * tile_capacity;
            std::copy_n(source, count, destination);
        }

        for (const NoiseBinding& binding : graph->noises) {
            const float* source = noise_values.data()
                + static_cast<uint64_t>(binding.plan_slot) * volume_length + tile_start;
            float* destination = tile_values.data() + static_cast<size_t>(binding.node) * tile_capacity;
            std::copy_n(source, count, destination);
        }

        for (uint32_t index = 0; index < graph->interpolateds.size(); ++index) {
            const float* source = interpolated_values.data()
                + static_cast<uint64_t>(index) * volume_length + tile_start;
            float* destination = tile_values.data()
                + static_cast<size_t>(graph->interpolateds[index].node) * tile_capacity;
            std::copy_n(source, count, destination);
        }

        for (const CoordinateBinding& binding : graph->coordinates) {
            float* destination = tile_values.data() + static_cast<size_t>(binding.node) * tile_capacity;
            const float* source = binding.axis == 0 ? x_values
                : binding.axis == 1 ? y_values : z_values;
            std::copy_n(source, count, destination);
        }

        for (const GradientBinding& binding : graph->gradients) {
            const int32_t* coordinates = binding.axis == 0 ? x_coordinates
                : binding.axis == 1 ? y_coordinates : z_coordinates;
            float* destination = tile_values.data() + static_cast<size_t>(binding.node) * tile_capacity;
            for (uint32_t sample = 0; sample < count; ++sample) {
                const int32_t coordinate = coordinates[sample];
                const int32_t clamped = coordinate < binding.min_coordinate ? binding.min_coordinate
                    : coordinate > binding.max_coordinate ? binding.max_coordinate : coordinate;
                const uint32_t delta_bits = static_cast<uint32_t>(clamped)
                    - static_cast<uint32_t>(binding.from_coordinate);
                const int32_t delta = signed_i32(delta_bits);
                volatile float scaled = static_cast<float>(delta) * binding.coordinate_factor;
                destination[sample] = binding.from_value + scaled;
            }
        }

        for (const ArithmeticInstruction& instruction : graph->arithmetic) {
            const float* left = tile_values.data()
                + static_cast<size_t>(instruction.input_a) * tile_capacity;
            const float* right = instruction.input_b == kNoInput ? nullptr
                : tile_values.data() + static_cast<size_t>(instruction.input_b) * tile_capacity;
            float* destination = tile_values.data()
                + static_cast<size_t>(instruction.output) * tile_capacity;
            if (instruction.opcode == JPE_DENSITY_ADD) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = left[sample] + right[sample];
                }
            } else if (instruction.opcode == JPE_DENSITY_MUL) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = left[sample] * right[sample];
                }
            } else if (instruction.opcode == JPE_DENSITY_SUB) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = left[sample] - right[sample];
                }
            } else if (instruction.opcode == JPE_DENSITY_MIN) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = minecraft_volume_min(left[sample], right[sample]);
                }
            } else if (instruction.opcode == JPE_DENSITY_MAX) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = minecraft_volume_max(left[sample], right[sample]);
                }
            } else if (instruction.opcode == JPE_DENSITY_CLAMP) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = minecraft_clamp(left[sample],
                        instruction.parameter_a, instruction.parameter_b);
                }
            } else if (instruction.opcode == JPE_DENSITY_NEGATE) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = -left[sample];
                }
            } else if (instruction.opcode == JPE_DENSITY_ABS) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = std::fabs(left[sample]);
                }
            } else if (instruction.opcode == JPE_DENSITY_SQUARE) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = left[sample] * left[sample];
                }
            } else if (instruction.opcode == JPE_DENSITY_CUBE) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    const float square = left[sample] * left[sample];
                    destination[sample] = square * left[sample];
                }
            } else if (instruction.opcode == JPE_DENSITY_SQRT) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = static_cast<float>(std::sqrt(static_cast<double>(left[sample])));
                }
            } else if (instruction.opcode == JPE_DENSITY_LOG) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = static_cast<float>(std::log(static_cast<double>(left[sample])));
                }
            } else if (instruction.opcode == JPE_DENSITY_RECIPROCAL) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = 1.0f / left[sample];
                }
            } else if (instruction.opcode == JPE_DENSITY_SIGN) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    const float value = left[sample];
                    destination[sample] = (std::isnan(value) || value == 0.0f)
                        ? value : std::copysign(1.0f, value);
                }
            } else if (instruction.opcode == JPE_DENSITY_SQUEEZE) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    const float value = minecraft_clamp(left[sample], -1.0f, 1.0f);
                    const float cube = (value * value) * value;
                    destination[sample] = (value / 2.0f) - (cube / 24.0f);
                }
            } else if (instruction.opcode == JPE_DENSITY_LEAKY_RELU) {
                const float negative_factor = instruction.parameter_a;
                for (uint32_t sample = 0; sample < count; ++sample) {
                    const float value = left[sample];
                    destination[sample] = value > 0.0f ? value : negative_factor * value;
                }
            } else if (instruction.opcode == JPE_DENSITY_RANGE_CHOICE) {
                const float* input_lane = tile_values.data()
                    + static_cast<size_t>(instruction.input_a) * tile_capacity;
                const float* in_range_lane = tile_values.data()
                    + static_cast<size_t>(instruction.input_b) * tile_capacity;
                const float* out_range_lane = tile_values.data()
                    + static_cast<size_t>(instruction.input_c) * tile_capacity;
                const float minimum = instruction.parameter_a;
                const float maximum = instruction.parameter_b;
                for (uint32_t sample = 0; sample < count; ++sample) {
                    const float value = input_lane[sample];
                    // Vanilla keeps the in-range branch only for min <= v < max; NaN falls through.
                    destination[sample] = (value >= minimum && value < maximum)
                        ? in_range_lane[sample] : out_range_lane[sample];
                }
            } else {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = left[sample] / right[sample];
                }
            }
        }

        for (size_t output_index = 0; output_index < selected_roots.size(); ++output_index) {
            const float* root_values = tile_values.data()
                + static_cast<size_t>(selected_roots[output_index]) * tile_capacity;
            std::copy_n(root_values, count,
                outputs + static_cast<uint64_t>(output_index) * volume_length + tile_start);
        }
    }
    return 0;
} catch (...) { return 3; }

extern "C" int32_t jpe_cpp_density_graph_evaluate_with_inputs(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float* inputs, uint32_t input_count,
    float* output, uint64_t output_length
) {
    return jpe_cpp_density_graph_evaluate_multi_with_inputs(
        graph_handle, size_x, size_y, size_z,
        min_x, min_y, min_z, step_x, step_y, step_z,
        inputs, input_count, nullptr, 0, output, output_length);
}

extern "C" int32_t jpe_cpp_density_graph_evaluate(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length
) {
    return jpe_cpp_density_graph_evaluate_with_inputs(
        graph_handle, size_x, size_y, size_z,
        min_x, min_y, min_z, step_x, step_y, step_z,
        nullptr, 0, output, output_length);
}

#ifdef JPE_DENSITY_GRAPH_TEST_REFERENCE
// Test-only scalar reference retained to quantify the tiled evaluator against
// the previous sample-major interpreter. It is intentionally not declared in
// the public native API header.
extern "C" int32_t jpe_cpp_density_graph_evaluate_scalar_reference(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length
) try {
    if (output == nullptr || !valid_volume(size_x, size_y, size_z,
            step_x, step_y, step_z, output_length)) return 1;

    std::shared_ptr<CompiledGraph> graph;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto found = g_handles.find(graph_handle);
        if (found == g_handles.end()) return 2;
        graph = found->second;
    }

    std::vector<float> values(graph->nodes.size());
    uint64_t output_index = 0;
    for (uint32_t z = 0; z < size_z; ++z) {
        const float world_z = static_cast<float>(wrapped_coordinate(min_z, z, step_z));
        for (uint32_t x = 0; x < size_x; ++x) {
            const float world_x = static_cast<float>(wrapped_coordinate(min_x, x, step_x));
            for (uint32_t y = 0; y < size_y; ++y) {
                const float world_y = static_cast<float>(wrapped_coordinate(min_y, y, step_y));
                for (size_t n = 0; n < graph->nodes.size(); ++n) {
                    const JpeDensityNode& node = graph->nodes[n];
                    switch (node.opcode) {
                        case JPE_DENSITY_CONST: values[n] = static_cast<float>(node.immediate); break;
                        case JPE_DENSITY_X: values[n] = world_x; break;
                        case JPE_DENSITY_Y: values[n] = world_y; break;
                        case JPE_DENSITY_Z: values[n] = world_z; break;
                        case JPE_DENSITY_OPAQUE:
                            values[n] = std::numeric_limits<float>::quiet_NaN();
                            break;
                        case JPE_DENSITY_ADD:
                            values[n] = values[node.input_a] + values[node.input_b];
                            break;
                        case JPE_DENSITY_SUB:
                            values[n] = values[node.input_a] - values[node.input_b];
                            break;
                        case JPE_DENSITY_MUL:
                            values[n] = values[node.input_a] * values[node.input_b];
                            break;
                        case JPE_DENSITY_DIV:
                            values[n] = values[node.input_a] / values[node.input_b];
                            break;
                        case JPE_DENSITY_MIN:
                            values[n] = minecraft_volume_min(values[node.input_a], values[node.input_b]);
                            break;
                        case JPE_DENSITY_MAX:
                            values[n] = minecraft_volume_max(values[node.input_a], values[node.input_b]);
                            break;
                        case JPE_DENSITY_NEGATE:
                            values[n] = -values[node.input_a];
                            break;
                        case JPE_DENSITY_ABS:
                            values[n] = std::fabs(values[node.input_a]);
                            break;
                        case JPE_DENSITY_SQUARE:
                            values[n] = values[node.input_a] * values[node.input_a];
                            break;
                        case JPE_DENSITY_CUBE: {
                            const float square = values[node.input_a] * values[node.input_a];
                            values[n] = square * values[node.input_a];
                            break;
                        }
                        case JPE_DENSITY_SQRT:
                        case JPE_DENSITY_LOG:
                        case JPE_DENSITY_RECIPROCAL:
                        case JPE_DENSITY_SIGN:
                        case JPE_DENSITY_SQUEEZE:
                            values[n] = apply_unary_density(node.opcode, values[node.input_a]);
                            break;
                        case JPE_DENSITY_LEAKY_RELU:
                            values[n] = apply_unary_density(node.opcode, values[node.input_a],
                                static_cast<float>(node.immediate));
                            break;
                        case JPE_DENSITY_CLAMP: {
                            uint64_t packed = 0;
                            std::memcpy(&packed, &node.immediate, sizeof(packed));
                            const uint32_t min_bits = static_cast<uint32_t>(packed >> 32);
                            const uint32_t max_bits = static_cast<uint32_t>(packed);
                            float minimum = 0.0f, maximum = 0.0f;
                            std::memcpy(&minimum, &min_bits, sizeof(minimum));
                            std::memcpy(&maximum, &max_bits, sizeof(maximum));
                            values[n] = minecraft_clamp(values[node.input_a], minimum, maximum);
                            break;
                        }
                        default: return 1;
                    }
                }
                output[output_index++] = values[graph->root];
            }
        }
    }
    return 0;
} catch (...) { return 3; }

extern "C" void jpe_cpp_density_graph_set_test_tile_limit(uint32_t max_samples) noexcept {
    g_test_tile_limit = max_samples;
}

#endif // JPE_DENSITY_GRAPH_TEST_REFERENCE

extern "C" int32_t jpe_cpp_density_graph_release(uint64_t graph_handle) try {
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto found = g_handles.find(graph_handle);
    if (found == g_handles.end()) return 2;
    auto& graph = *found->second;
    if (--graph.references != 0) return 0;
    const uint64_t fingerprint = graph.fingerprint;
    g_handles.erase(found);
    const auto range = g_cache.equal_range(fingerprint);
    for (auto it = range.first; it != range.second; ++it) {
        if (it->second == graph_handle) {
            g_cache.erase(it);
            break;
        }
    }
    return 0;
} catch (...) { return 3; }
