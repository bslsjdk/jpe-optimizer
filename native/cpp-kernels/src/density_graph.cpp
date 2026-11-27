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
extern "C" int32_t jpe_cpp_perlin_add_stack_shifted(
    const JpePerlinLayer* layers, uint32_t layer_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float* shift_x, const float* shift_z,
    float* output, uint64_t output_length, uint32_t flags
) noexcept;

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
/* Payload ceilings. Every table is copied at compile, so each bound keeps one
 * graph's retained footprint inside a predictable envelope. */
constexpr uint32_t kMaxSplineNodes = 4096;
constexpr uint32_t kMaxSplinePoints = 16384;
constexpr uint32_t kMaxIntervals = 256;
constexpr uint32_t kMaxIntervalThresholds = 1024;
constexpr uint32_t kMaxIntervalBranches = 1024;
constexpr uint32_t kMaxDistancePoints = 256;
constexpr uint64_t kMaxBlendCells = 1ULL << 20;


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

/* Vanilla NoiseFunction.ShiftedXzSampler: the plan is sampled at
 * (blockX * xz_scale + shiftX, blockY * y_scale, blockZ * xz_scale + shiftZ).
 * The shift children are evaluated over the whole volume first, then the stack
 * runs with per-sample offsets. */
struct ShiftedNoiseBinding {
    uint32_t node;
    uint32_t plan_slot;
    uint32_t shift_x;
    uint32_t shift_z;
};

/* Vanilla InterpolatedFunction: coarse cell grid plus trilinear expansion. */
struct InterpolatedBinding {
    uint32_t node;
    uint32_t input_node;
    uint32_t cell_size_xz;
    uint32_t cell_size_y;
};

/* Spline tree stored flat: nodes index into a shared point table, and every
 * point names its own child node, so the tree needs no recursion at compile. */
struct SplineBinding { uint32_t node; uint32_t root; };

/* One pinned-axis sample. The child is evaluated on the reduced volume once per
 * call, then broadcast along the pinned axis. */
struct SliceBinding {
    uint32_t node;
    uint32_t input_node;
    uint32_t axis;
    int32_t coordinate;
};

struct RoundBinding {
    uint32_t node;
    uint32_t input_node;
    uint32_t multiple_node; /* kNoInput means 1.0 */
    uint32_t type;
};

struct IntervalBinding {
    uint32_t node;
    uint32_t input_node;
    uint32_t first_threshold;
    uint32_t threshold_count;
    uint32_t first_branch;
};

struct TopSurfaceBinding {
    uint32_t node;
    uint32_t density_node;
    uint32_t upper_node;
    int32_t lower_bound;
    int32_t cell_height;
};

/* Pre-resolved blend region. Everything is owned, so callers free their grids
 * as soon as compile returns. */
struct BlendRegion {
    bool active = false;
    int32_t base_x = 0, base_y = 0, base_z = 0;
    uint32_t cells_x = 0, cells_y = 0, cells_z = 0;
    std::vector<float> fixed_density;
    std::vector<float> density;
    std::vector<uint8_t> density_mask;
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
    std::vector<ShiftedNoiseBinding> shifted_noises;
    std::vector<NoisePlanData> noise_plans;
    std::vector<InterpolatedBinding> interpolateds;
    std::vector<SplineBinding> splines;
    std::vector<JpeSplineNode> spline_nodes;
    std::vector<JpeSplinePoint> spline_points;
    std::vector<SliceBinding> slices;
    std::vector<RoundBinding> rounds;
    std::vector<JpeIntervalSelect> interval_tables;
    std::vector<IntervalBinding> intervals;
    std::vector<float> interval_thresholds;
    std::vector<uint32_t> interval_branches;
    std::vector<TopSurfaceBinding> top_surfaces;
    std::vector<JpeDistancePoint> distance_points;
    std::vector<uint32_t> distance_bindings; /* node -> index into distance_points */
    BlendRegion blend;
    std::vector<uint8_t> end_island_permutation;
    std::vector<uint32_t> end_island_nodes;
    /* node -> binding slot, so the tile sweep can dispatch in node order. */
    std::vector<uint32_t> op_slot;
};

void build_execution_plan(CompiledGraph& graph) {
    graph.static_values.clear();
    graph.inputs.clear();
    graph.coordinates.clear();
    graph.gradients.clear();
    graph.arithmetic.clear();
    graph.splines.clear();
    graph.slices.clear();
    graph.rounds.clear();
    graph.intervals.clear();
    graph.top_surfaces.clear();
    graph.distance_bindings.clear();
    graph.end_island_nodes.clear();
    graph.static_values.reserve(graph.nodes.size());
    graph.inputs.reserve(graph.nodes.size());
    graph.coordinates.reserve(graph.nodes.size());
    graph.gradients.reserve(graph.nodes.size());
    graph.arithmetic.reserve(graph.nodes.size());
    // One slot per node names the binding that drives it, so the tile sweep can
    // walk nodes strictly in index order. Topological order is the only thing
    // that keeps a composite node from reading a stale child lane.
    graph.op_slot.assign(graph.nodes.size(), kNoInput);

    for (uint32_t i = 0; i < graph.nodes.size(); ++i) {
        const JpeDensityNode& node = graph.nodes[i];
        switch (node.opcode) {
            case JPE_DENSITY_CONST:
                graph.op_slot[i] = static_cast<uint32_t>(graph.static_values.size());
                graph.static_values.push_back({i, static_cast<float>(node.immediate)});
                break;
            case JPE_DENSITY_INPUT:
                graph.op_slot[i] = static_cast<uint32_t>(graph.inputs.size());
                graph.inputs.push_back({i, static_cast<uint32_t>(node.immediate)});
                break;
            case JPE_DENSITY_X:
                graph.op_slot[i] = static_cast<uint32_t>(graph.coordinates.size());
                graph.coordinates.push_back({i, 0});
                break;
            case JPE_DENSITY_Y:
                graph.op_slot[i] = static_cast<uint32_t>(graph.coordinates.size());
                graph.coordinates.push_back({i, 1});
                break;
            case JPE_DENSITY_Z:
                graph.op_slot[i] = static_cast<uint32_t>(graph.coordinates.size());
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
                graph.op_slot[i] = static_cast<uint32_t>(graph.gradients.size());
                graph.gradients.push_back({i, axis, signed_i32(node.input_a),
                    signed_i32(node.input_b), signed_i32(node.reserved), from_value, factor});
                break;
            }
            case JPE_DENSITY_OPAQUE:
                graph.op_slot[i] = static_cast<uint32_t>(graph.static_values.size());
                graph.static_values.push_back({i, std::numeric_limits<float>::quiet_NaN()});
                break;
            case JPE_DENSITY_NOISE:
                graph.op_slot[i] = static_cast<uint32_t>(graph.noises.size());
                graph.noises.push_back({i, node.input_a, 0});
                break;
            case JPE_DENSITY_NOISE_TRANSPOSED:
                graph.op_slot[i] = static_cast<uint32_t>(graph.noises.size());
                graph.noises.push_back({i, node.input_a, 1});
                break;
            case JPE_DENSITY_NOISE_SHIFTED_XZ:
                graph.op_slot[i] = static_cast<uint32_t>(graph.shifted_noises.size());
                graph.shifted_noises.push_back({i, node.input_a, node.input_b, node.reserved});
                break;
            case JPE_DENSITY_INTERPOLATED:
                graph.op_slot[i] = static_cast<uint32_t>(graph.interpolateds.size());
                graph.interpolateds.push_back({i, node.input_a, node.input_b, node.reserved});
                break;
            case JPE_DENSITY_SPLINE:
                graph.op_slot[i] = static_cast<uint32_t>(graph.splines.size());
                graph.splines.push_back({i, node.input_a});
                break;
            case JPE_DENSITY_SLICE_X:
            case JPE_DENSITY_SLICE_Y:
            case JPE_DENSITY_SLICE_Z:
                graph.op_slot[i] = static_cast<uint32_t>(graph.slices.size());
                graph.slices.push_back({i, node.input_a,
                    node.opcode == JPE_DENSITY_SLICE_X ? 0U
                        : node.opcode == JPE_DENSITY_SLICE_Y ? 1U : 2U,
                    static_cast<int32_t>(node.immediate)});
                break;
            case JPE_DENSITY_ROUND:
                graph.op_slot[i] = static_cast<uint32_t>(graph.rounds.size());
                graph.rounds.push_back({i, node.input_a, node.input_b, node.reserved});
                break;
            case JPE_DENSITY_INTERVAL_SELECT: {
                graph.op_slot[i] = static_cast<uint32_t>(graph.intervals.size());
                const JpeIntervalSelect& table = graph.interval_tables[node.input_a];
                graph.intervals.push_back({i, table.input_node, table.first_threshold,
                                           table.threshold_count, table.first_branch});
                break;
            }
            case JPE_DENSITY_FIND_TOP_SURFACE:
                graph.op_slot[i] = static_cast<uint32_t>(graph.top_surfaces.size());
                graph.top_surfaces.push_back({i, node.input_a, node.input_b,
                                              static_cast<int32_t>(node.immediate),
                                              static_cast<int32_t>(node.reserved)});
                break;
            case JPE_DENSITY_DISTANCE_TO_POINT:
                graph.op_slot[i] = static_cast<uint32_t>(graph.distance_bindings.size());
                graph.distance_bindings.push_back(node.input_a);
                break;
            case JPE_DENSITY_BLEND_DENSITY:
                // Graph-level payload: the node only names its child.
                graph.op_slot[i] = node.input_a;
                break;
            case JPE_DENSITY_END_ISLAND:
                graph.op_slot[i] = static_cast<uint32_t>(graph.end_island_nodes.size());
                graph.end_island_nodes.push_back(i);
                break;
            case JPE_DENSITY_LERP:
                graph.op_slot[i] = static_cast<uint32_t>(graph.arithmetic.size());
                graph.arithmetic.push_back({node.opcode, node.input_a, node.input_b, i,
                                            0.0f, 0.0f, node.reserved});
                break;
            case JPE_DENSITY_POW:
                graph.op_slot[i] = static_cast<uint32_t>(graph.arithmetic.size());
                graph.arithmetic.push_back({node.opcode, node.input_a, node.input_b, i,
                                            0.0f, 0.0f, kNoInput});
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
                graph.op_slot[i] = static_cast<uint32_t>(graph.arithmetic.size());
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
                graph.op_slot[i] = static_cast<uint32_t>(graph.arithmetic.size());
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
                graph.op_slot[i] = static_cast<uint32_t>(graph.arithmetic.size());
                graph.arithmetic.push_back({node.opcode, node.input_a, kNoInput, i, 0.0f, 0.0f, 0});
                break;
            case JPE_DENSITY_LEAKY_RELU:
                graph.op_slot[i] = static_cast<uint32_t>(graph.arithmetic.size());
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
                graph.op_slot[i] = static_cast<uint32_t>(graph.arithmetic.size());
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
            case JPE_DENSITY_NOISE_SHIFTED_XZ:
                // Plan-slot range is validated at compile time, where the count is known.
                if (node.input_b >= i || node.reserved >= i || node.immediate != 0.0) return false;
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
            case JPE_DENSITY_LERP:
                if (node.input_a >= i || node.input_b >= i || node.reserved >= i) return false;
                break;
            case JPE_DENSITY_POW:
                if (node.input_a >= i || node.input_b >= i || node.reserved != 0) return false;
                break;
            case JPE_DENSITY_SPLINE:
                // The spline root index is range-checked against the program table.
                if (node.input_b != kNoInput || node.reserved != 0 || node.immediate != 0.0) return false;
                break;
            case JPE_DENSITY_SLICE_X:
            case JPE_DENSITY_SLICE_Y:
            case JPE_DENSITY_SLICE_Z:
                if (node.input_a >= i || node.input_b != kNoInput || node.reserved != 0) return false;
                if (!std::isfinite(node.immediate) || std::floor(node.immediate) != node.immediate) return false;
                if (node.immediate < -2147483648.0 || node.immediate > 2147483647.0) return false;
                break;
            case JPE_DENSITY_ROUND:
                if (node.input_a >= i || node.reserved > 3 || node.immediate != 0.0) return false;
                if (node.input_b != kNoInput && node.input_b >= i) return false;
                break;
            case JPE_DENSITY_INTERVAL_SELECT:
                if (node.input_b != kNoInput || node.reserved != 0 || node.immediate != 0.0) return false;
                break;
            case JPE_DENSITY_FIND_TOP_SURFACE:
                if (node.input_a >= i || node.input_b >= i) return false;
                if (node.reserved == 0 || node.reserved > 64) return false;
                if (!std::isfinite(node.immediate) || std::floor(node.immediate) != node.immediate) return false;
                if (node.immediate < -2147483648.0 || node.immediate > 2147483647.0) return false;
                break;
            case JPE_DENSITY_DISTANCE_TO_POINT:
                if (node.input_b != kNoInput || node.reserved != 0 || node.immediate != 0.0) return false;
                break;
            case JPE_DENSITY_BLEND_DENSITY:
                if (node.input_a >= i || node.input_b != kNoInput || node.reserved != 0
                    || node.immediate != 0.0) return false;
                break;
            case JPE_DENSITY_END_ISLAND:
                if (node.input_a != kNoInput || node.input_b != kNoInput
                    || node.reserved != 0 || node.immediate != 0.0) return false;
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
/* Slices, top-surface probes and blends re-enter the public evaluator too. */
thread_local uint32_t g_reentry_depth = 0;
constexpr uint32_t kMaxInterpolateDepth = 8;
constexpr uint32_t kMaxSplineDepth = 16;
// Nested evaluation depth. Top-surface probes and interpolated cell grids each
// re-enter once; a shifted noise stages two shift volumes, and a shift volume can
// itself contain an interpolated or shifted node, so the chain is deeper than the
// probe-only path this limit was sized for.
constexpr uint32_t kMaxTopSurfaceDepth = 8;

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

/* Java double lerp: Mth.lerp(double, double, double). */
double lerp_f64(double delta, double start, double end) noexcept {
    return start + delta * (end - start);
}

/* Exact java.lang.Math.round(float): floor(a + 0.5) without ever forming a+0.5,
 * because that sum is not representable once |a| reaches 2^23. */
int32_t java_math_round(float value) noexcept {
    if (std::isnan(value)) return 0;
    if (value >= 2147483647.0f) return INT32_MAX;
    if (value <= -2147483648.0f) return INT32_MIN;
    const float base = std::floor(value);
    return static_cast<int32_t>(base) + ((value - base) >= 0.5f ? 1 : 0);
}

/* RoundFunction.roundToInteger, in vanilla's Type order. */
float round_to_integer(float input, uint32_t type) noexcept {
    switch (type) {
        case 0: return static_cast<float>(std::floor(static_cast<double>(input)));
        case 1: return static_cast<float>(java_math_round(input));
        case 2: return static_cast<float>(std::ceil(static_cast<double>(input)));
        default:
            // TRUNCATE: vanilla tests input > 0, so NaN takes the ceil branch.
            return static_cast<float>(input > 0.0f
                ? std::floor(static_cast<double>(input))
                : std::ceil(static_cast<double>(input)));
    }
}

/* DistanceMetric.compute(dx, dy, dz); dx/dy/dz are already point - block. */
float distance_metric(uint32_t metric, float dx, float dy, float dz) noexcept {
    switch (metric) {
        case 1: return dx * dx + dy * dy + dz * dz;
        case 2: return std::fabs(dx) + std::fabs(dy) + std::fabs(dz);
        case 3: return std::max(std::max(std::fabs(dx), std::fabs(dy)), std::fabs(dz));
        default:
            return static_cast<float>(std::sqrt(static_cast<double>(
                dx * dx + dy * dy + dz * dz)));
    }
}

/* CubicSpline.sample, mirrored exactly. The tree is flat: each node owns a
 * coordinate lane and a slice of the shared point table, and every point names
 * its own child node. Vanilla's edge case is a linear extension from the first
 * or last point, not a clamp, so a derivative of zero must return the value
 * untouched rather than forming value + 0 * delta. */
float spline_sample(const CompiledGraph& graph, uint32_t index,
                    const float* lanes, uint32_t stride, uint32_t sample,
                    uint32_t depth, int32_t* hint) noexcept {
    if (depth > kMaxSplineDepth || index >= graph.spline_nodes.size()) {
        return std::numeric_limits<float>::quiet_NaN();
    }
    const JpeSplineNode& node = graph.spline_nodes[index];
    if (node.kind == JPE_SPLINE_CONSTANT) return node.constant;
    const JpeSplinePoint* points = graph.spline_points.data() + node.first_point;
    const uint32_t count = node.point_count;
    if (count == 0) return std::numeric_limits<float>::quiet_NaN();
    const float input = lanes[static_cast<size_t>(node.coordinate) * stride + sample];

    // The first index whose location is greater than input, which is exactly
    // Mth.binarySearch(0, n, i -> input < locations[i]). Neighbouring voxels
    // almost always land in the same interval, so a per-node hint removes the
    // search from the hot path; the bounds check keeps the hint advisory only.
    int32_t start = hint != nullptr ? hint[index] : -1;
    if (start >= -1 && static_cast<uint32_t>(start + 1) < count
        && (start < 0 || !(input < points[start].location))
        && input < points[start + 1].location) {
        // Hint still valid.
    } else {
        uint32_t first_greater = 0;
        for (uint32_t span = count; span > 0;) {
            const uint32_t half = span >> 1;
            const uint32_t probe = first_greater + half;
            if (input < points[probe].location) {
                span = half;
            } else {
                first_greater = probe + 1;
                span -= half + 1;
            }
        }
        start = static_cast<int32_t>(first_greater) - 1;
        if (hint != nullptr) hint[index] = start;
    }
    const uint32_t last = count - 1;
    if (start < 0 || static_cast<uint32_t>(start) == last) {
        const uint32_t edge = start < 0 ? 0U : last;
        const float value = spline_sample(graph, points[edge].child, lanes, stride, sample, depth + 1, hint);
        const float derivative = points[edge].derivative;
        return derivative == 0.0f
            ? value
            : value + derivative * (input - points[edge].location);
    }
    const float x1 = points[start].location;
    const float x2 = points[start + 1].location;
    const float t = (input - x1) / (x2 - x1);
    const float d1 = points[start].derivative;
    const float d2 = points[start + 1].derivative;
    const float y1 = spline_sample(graph, points[start].child, lanes, stride, sample, depth + 1, hint);
    const float y2 = spline_sample(graph, points[start + 1].child, lanes, stride, sample, depth + 1, hint);
    const float a = d1 * (x2 - x1) - (y2 - y1);
    const float b = -d2 * (x2 - x1) + (y2 - y1);
    return lerp_f32(t, y1, y2) + t * (1.0f - t) * lerp_f32(t, a, b);
}

/* ---- SimplexNoise (2D) -----------------------------------------------
 * net.minecraft.world.level.levelgen.synth.SimplexNoise with
 * noiseOffsetScale 0, which is exactly how EndIslandFunction builds it. */
constexpr double kSimplexF2 = 0.3660254037844386;   /* 0.5 * (sqrt(3) - 1) */
constexpr double kSimplexG2 = 0.21132486540518713;  /* (3 - sqrt(3)) / 6 */

int32_t simplex_permute(const uint8_t* perms, int32_t value) noexcept {
    return static_cast<int32_t>(perms[value & 0xFF]);
}

/* GRADIENT[i].dot(x, y, 0) for the 16-entry table, folded into integer math. */
double simplex_corner(int32_t index, double x, double y, double base) noexcept {
    static constexpr int8_t kGradient[16][3] = {
        {1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},
        {1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
        {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1},
        {1,1,0},{0,-1,1},{-1,1,0},{0,-1,-1}
    };
    double t = base - x * x - y * y;
    if (t < 0.0) return 0.0;
    t *= t;
    const int8_t* g = kGradient[index & 15];
    return t * t * (static_cast<double>(g[0]) * x + static_cast<double>(g[1]) * y);
}

float simplex_noise_2d(const uint8_t* perms, double xin, double yin) noexcept {
    const double s = (xin + yin) * kSimplexF2;
    const int32_t i = static_cast<int32_t>(std::floor(xin + s));
    const int32_t j = static_cast<int32_t>(std::floor(yin + s));
    const double t = static_cast<double>(i + j) * kSimplexG2;
    const double x0 = xin - (static_cast<double>(i) - t);
    const double y0 = yin - (static_cast<double>(j) - t);
    const int32_t i1 = x0 > y0 ? 1 : 0;
    const int32_t j1 = x0 > y0 ? 0 : 1;
    const double x1 = x0 - static_cast<double>(i1) + kSimplexG2;
    const double y1 = y0 - static_cast<double>(j1) + kSimplexG2;
    const double x2 = x0 - 1.0 + 2.0 * kSimplexG2;
    const double y2 = y0 - 1.0 + 2.0 * kSimplexG2;
    const int32_t ii = i & 0xFF;
    const int32_t jj = j & 0xFF;
    const int32_t gi0 = simplex_permute(perms, ii + simplex_permute(perms, jj)) % 12;
    const int32_t gi1 = simplex_permute(perms, ii + i1 + simplex_permute(perms, jj + j1)) % 12;
    const int32_t gi2 = simplex_permute(perms, ii + 1 + simplex_permute(perms, jj + 1)) % 12;
    const double n0 = simplex_corner(gi0, x0, y0, 0.5);
    const double n1 = simplex_corner(gi1, x1, y1, 0.5);
    const double n2 = simplex_corner(gi2, x2, y2, 0.5);
    return static_cast<float>(70.0 * (n0 + n1 + n2));
}

/* EndIslandFunction.getHeightValue. */
float end_island_height(const uint8_t* perms, int32_t section_x, int32_t section_z) noexcept {
    const int32_t chunk_x = section_x / 2;
    const int32_t chunk_z = section_z / 2;
    const int32_t sub_x = section_x % 2;
    const int32_t sub_z = section_z % 2;
    float doffs = -100.0f;
    for (int32_t xo = -12; xo <= 12; ++xo) {
        for (int32_t zo = -12; zo <= 12; ++zo) {
            const int64_t total_x = static_cast<int64_t>(chunk_x) + xo;
            const int64_t total_z = static_cast<int64_t>(chunk_z) + zo;
            if (total_x * total_x + total_z * total_z <= 4096LL) continue;
            if (!(simplex_noise_2d(perms, static_cast<double>(total_x),
                                   static_cast<double>(total_z)) < -0.9f)) continue;
            const float island_size = std::fmod(
                static_cast<float>(total_x < 0 ? -total_x : total_x) * 3439.0f
                + static_cast<float>(total_z < 0 ? -total_z : total_z) * 147.0f, 13.0f) + 9.0f;
            const int32_t xd = sub_x - xo * 2;
            const int32_t zd = sub_z - zo * 2;
            const float sum = static_cast<float>(xd * xd + zd * zd);
            float candidate = 100.0f - static_cast<float>(std::sqrt(static_cast<double>(sum))) * island_size;
            // Mth.clamp(candidate, -100, 80)
            if (candidate < -100.0f) candidate = -100.0f;
            else candidate = std::min(candidate, 80.0f);
            doffs = std::max(doffs, candidate);
        }
    }
    return doffs;
}

/* ---- Blender.blendDensity --------------------------------------------
 * Vanilla walks its blending maps per voxel through ChunkPos lookups; the caller
 * flattens both maps into dense cell grids, so this is one indexed read plus the
 * same weighted average over the 3x3x3 cell neighbourhood. */
float blend_density(const BlendRegion& region, int32_t block_x, int32_t block_y,
                    int32_t block_z, float noise_value) noexcept {
    const int32_t cell_x = block_x >> 2;
    const int32_t cell_y = block_y / 8;
    const int32_t cell_z = block_z >> 2;
    const auto index_of = [&region](int64_t x, int64_t y, int64_t z) -> size_t {
        return (static_cast<size_t>(x) * region.cells_y + static_cast<size_t>(y))
            * region.cells_z + static_cast<size_t>(z);
    };
    const int64_t fx = static_cast<int64_t>(cell_x) - region.base_x;
    const int64_t fy = static_cast<int64_t>(cell_y) - region.base_y;
    const int64_t fz = static_cast<int64_t>(cell_z) - region.base_z;
    if (fx >= 0 && fy >= 0 && fz >= 0
        && fx < region.cells_x && fy < region.cells_y && fz < region.cells_z) {
        const float fixed_value = region.fixed_density[index_of(fx, fy, fz)];
        if (fixed_value != std::numeric_limits<float>::max()) return fixed_value;
    }

    double total_weight = 0.0;
    double weighted = 0.0;
    double closest = std::numeric_limits<double>::infinity();
    for (int32_t probe_y = cell_y - 1; probe_y <= cell_y + 1; ++probe_y) {
        const int64_t iy = static_cast<int64_t>(probe_y) - region.base_y;
        if (iy < 0 || iy >= static_cast<int64_t>(region.cells_y)) continue;
        for (uint32_t ix = 0; ix < region.cells_x; ++ix) {
            for (uint32_t iz = 0; iz < region.cells_z; ++iz) {
                const size_t index = index_of(ix, iy, iz);
                if (region.density_mask[index] == 0) continue;
                const int32_t delta_x = cell_x - (region.base_x + static_cast<int32_t>(ix));
                const int32_t delta_y = (cell_y - probe_y) * 2;
                const int32_t delta_z = cell_z - (region.base_z + static_cast<int32_t>(iz));
                // Mth.length(int,int,int) resolves to the float overload.
                const float fx_d = static_cast<float>(delta_x);
                const float fy_d = static_cast<float>(delta_y);
                const float fz_d = static_cast<float>(delta_z);
                const float distance = static_cast<float>(std::sqrt(static_cast<double>(
                    fx_d * fx_d + fy_d * fy_d + fz_d * fz_d)));
                if (distance > 2.0) continue;
                if (static_cast<double>(distance) < closest) closest = static_cast<double>(distance);
                const double d = static_cast<double>(distance);
                const double weight = 1.0 / (d * d * d * d);
                weighted += static_cast<double>(region.density[index]) * weight;
                total_weight += weight;
            }
        }
    }
    if (closest == std::numeric_limits<double>::infinity()) return noise_value;
    const double average = weighted / total_weight;
    double alpha = closest / 3.0;
    if (alpha < 0.0) alpha = 0.0;
    else if (alpha > 1.0) alpha = 1.0;
    return static_cast<float>(lerp_f64(alpha, average, static_cast<double>(noise_value)));
}

/* Visits every coordinate node a spline tree depends on. Interpolation, slices
 * and top-surface probes do not recurse here: they own their children through a
 * nested evaluation, so the outer sweep never needs those lanes. */
template <typename Visit>
void visit_spline_coordinates(const CompiledGraph& graph, uint32_t index,
                              Visit&& visit, uint32_t depth = 0) {
    if (depth > kMaxSplineDepth || index >= graph.spline_nodes.size()) return;
    const JpeSplineNode& node = graph.spline_nodes[index];
    if (node.kind != JPE_SPLINE_MULTIPOINT) return;
    visit(node.coordinate);
    for (uint32_t point = 0; point < node.point_count; ++point) {
        visit_spline_coordinates(graph, graph.spline_points[node.first_point + point].child,
                                 visit, depth + 1);
    }
}

int32_t wrapped_coordinate(int32_t minimum, uint32_t index, int32_t step) noexcept {    const uint32_t bits = static_cast<uint32_t>(minimum)
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
    // These opcodes read a program payload table. This entry has no payload, so
    // accepting one here would leave the node reading an empty staging buffer.
    for (uint32_t i = 0; i < node_count; ++i) {
        switch (nodes[i].opcode) {
            case JPE_DENSITY_NOISE:
            case JPE_DENSITY_NOISE_TRANSPOSED:
            case JPE_DENSITY_NOISE_SHIFTED_XZ:
            case JPE_DENSITY_SPLINE:
            case JPE_DENSITY_INTERVAL_SELECT:
            case JPE_DENSITY_DISTANCE_TO_POINT:
            case JPE_DENSITY_BLEND_DENSITY:
            case JPE_DENSITY_END_ISLAND:
                return 0;
            default:
                break;
        }
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
/* Payload identity. Every table is POD, so byte hashing is exact and cheap
 * relative to a chunk's own work; the cache still verifies with a full compare. */
uint64_t hash_payload_bytes(uint64_t hash, const void* data, size_t length) noexcept {
    const uint8_t* bytes = static_cast<const uint8_t*>(data);
    for (size_t i = 0; i < length; ++i) {
        hash ^= bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

template <typename T>
uint64_t hash_vector(uint64_t hash, const std::vector<T>& values) noexcept {
    return hash_payload_bytes(hash, values.data(), values.size() * sizeof(T));
}

uint64_t hash_program_payload(const JpeGraphProgram& payload) noexcept {
    uint64_t hash = 1469598103934665603ULL;
    hash = hash_payload_bytes(hash, &payload.noise_plan_count, sizeof(uint32_t));
    if (payload.spline_count > 0) {
        hash = hash_payload_bytes(hash, payload.splines,
            static_cast<size_t>(payload.spline_count) * sizeof(JpeSplineNode));
    }
    if (payload.spline_point_count > 0) {
        hash = hash_payload_bytes(hash, payload.spline_points,
            static_cast<size_t>(payload.spline_point_count) * sizeof(JpeSplinePoint));
    }
    if (payload.interval_select_count > 0) {
        hash = hash_payload_bytes(hash, payload.interval_selects,
            static_cast<size_t>(payload.interval_select_count) * sizeof(JpeIntervalSelect));
    }
    if (payload.interval_threshold_count > 0) {
        hash = hash_payload_bytes(hash, payload.interval_thresholds,
            static_cast<size_t>(payload.interval_threshold_count) * sizeof(float));
    }
    if (payload.interval_branch_count > 0) {
        hash = hash_payload_bytes(hash, payload.interval_branches,
            static_cast<size_t>(payload.interval_branch_count) * sizeof(uint32_t));
    }
    if (payload.distance_point_count > 0) {
        hash = hash_payload_bytes(hash, payload.distance_points,
            static_cast<size_t>(payload.distance_point_count) * sizeof(JpeDistancePoint));
    }
    if (payload.end_island_permutation != nullptr) {
        hash = hash_payload_bytes(hash, payload.end_island_permutation, 256);
    }
    if (payload.blend != nullptr && payload.blend->active != 0) {
        const JpeBlendInput& blend = *payload.blend;
        hash = hash_payload_bytes(hash, &blend, sizeof(JpeBlendInput));
        const size_t cells = static_cast<size_t>(blend.cells_x) * blend.cells_y * blend.cells_z;
        hash = hash_payload_bytes(hash, blend.fixed_density, cells * sizeof(float));
        hash = hash_payload_bytes(hash, blend.density, cells * sizeof(float));
        hash = hash_payload_bytes(hash, blend.density_mask, cells);
    }
    return hash;
}

/* Exact cache verification. Comparing stored tables against the caller's tables
 * avoids re-copying a payload that turns out to be a duplicate. */
bool same_program_payload(const CompiledGraph& graph, const JpeGraphProgram& payload) noexcept {
    if (graph.spline_nodes.size() != payload.spline_count) return false;
    if (graph.spline_points.size() != payload.spline_point_count) return false;
    if (graph.interval_tables.size() != payload.interval_select_count) return false;
    if (graph.interval_thresholds.size() != payload.interval_threshold_count) return false;
    if (graph.interval_branches.size() != payload.interval_branch_count) return false;
    if (graph.distance_points.size() != payload.distance_point_count) return false;
    if (payload.spline_count > 0
        && std::memcmp(graph.spline_nodes.data(), payload.splines,
                       graph.spline_nodes.size() * sizeof(JpeSplineNode)) != 0) return false;
    if (payload.spline_point_count > 0
        && std::memcmp(graph.spline_points.data(), payload.spline_points,
                       graph.spline_points.size() * sizeof(JpeSplinePoint)) != 0) return false;
    if (payload.interval_select_count > 0
        && std::memcmp(graph.interval_tables.data(), payload.interval_selects,
                       graph.interval_tables.size() * sizeof(JpeIntervalSelect)) != 0) return false;
    if (payload.interval_threshold_count > 0
        && std::memcmp(graph.interval_thresholds.data(), payload.interval_thresholds,
                       graph.interval_thresholds.size() * sizeof(float)) != 0) return false;
    if (payload.interval_branch_count > 0
        && std::memcmp(graph.interval_branches.data(), payload.interval_branches,
                       graph.interval_branches.size() * sizeof(uint32_t)) != 0) return false;
    if (payload.distance_point_count > 0
        && std::memcmp(graph.distance_points.data(), payload.distance_points,
                       graph.distance_points.size() * sizeof(JpeDistancePoint)) != 0) return false;
    const bool has_permutation = !graph.end_island_permutation.empty();
    if (has_permutation != (payload.end_island_permutation != nullptr)) return false;
    if (has_permutation
        && std::memcmp(graph.end_island_permutation.data(), payload.end_island_permutation, 256) != 0) return false;
    const bool has_blend = graph.blend.active;
    const bool want_blend = payload.blend != nullptr && payload.blend->active != 0;
    if (has_blend != want_blend) return false;
    if (has_blend) {
        const JpeBlendInput& stored = *payload.blend;
        if (graph.blend.base_x != stored.base_cell_x || graph.blend.base_y != stored.base_cell_y
            || graph.blend.base_z != stored.base_cell_z
            || graph.blend.cells_x != stored.cells_x || graph.blend.cells_y != stored.cells_y
            || graph.blend.cells_z != stored.cells_z) return false;
        const size_t cells = graph.blend.fixed_density.size();
        if (std::memcmp(graph.blend.fixed_density.data(), stored.fixed_density,
                        cells * sizeof(float)) != 0) return false;
        if (std::memcmp(graph.blend.density.data(), stored.density, cells * sizeof(float)) != 0) return false;
        if (std::memcmp(graph.blend.density_mask.data(), stored.density_mask, cells) != 0) return false;
    }
    return true;
}

extern "C" uint64_t jpe_cpp_density_graph_compile_program(
    const JpeDensityNode* nodes, uint32_t node_count, uint32_t root_node,
    uint32_t ir_version, uint64_t configuration_fingerprint,
    const JpeGraphProgram* program
) try {
    if (ir_version != 10 || !valid_graph(nodes, node_count, root_node)) return 0;
    const JpeGraphProgram empty{};
    const JpeGraphProgram& payload = program != nullptr ? *program : empty;

    if (payload.noise_plan_count > kMaxNoisePlans) return 0;
    if (payload.noise_plan_count > 0 && payload.noise_plans == nullptr) return 0;
    if (payload.spline_count > kMaxSplineNodes || payload.spline_point_count > kMaxSplinePoints) return 0;
    if (payload.interval_select_count > kMaxIntervals) return 0;
    if (payload.interval_threshold_count > kMaxIntervalThresholds) return 0;
    if (payload.interval_branch_count > kMaxIntervalBranches) return 0;
    if (payload.distance_point_count > kMaxDistancePoints) return 0;
    if (payload.spline_count > 0 && payload.splines == nullptr) return 0;
    if (payload.spline_point_count > 0 && payload.spline_points == nullptr) return 0;
    if (payload.interval_select_count > 0 && payload.interval_selects == nullptr) return 0;
    if (payload.interval_threshold_count > 0 && payload.interval_thresholds == nullptr) return 0;
    if (payload.interval_branch_count > 0 && payload.interval_branches == nullptr) return 0;
    if (payload.distance_point_count > 0 && payload.distance_points == nullptr) return 0;

    // Noise slots: one staging buffer each, so a slot cannot serve two layouts.
    std::vector<int8_t> slot_layout(payload.noise_plan_count, -1);
    bool needs_end_island = false;
    for (uint32_t i = 0; i < node_count; ++i) {
        const JpeDensityNode& node = nodes[i];
        switch (node.opcode) {
            case JPE_DENSITY_NOISE:
            case JPE_DENSITY_NOISE_TRANSPOSED: {
                if (node.input_a >= payload.noise_plan_count) return 0;
                const int8_t layout = node.opcode == JPE_DENSITY_NOISE_TRANSPOSED ? 1 : 0;
                int8_t& existing = slot_layout[node.input_a];
                if (existing != -1 && existing != layout) return 0;
                existing = layout;
                break;
            }
            case JPE_DENSITY_SPLINE: {
                if (node.input_a >= payload.spline_count) return 0;
                const JpeSplineNode& root = payload.splines[node.input_a];
                if (root.kind != JPE_SPLINE_MULTIPOINT && root.kind != JPE_SPLINE_CONSTANT) return 0;
                if (root.kind == JPE_SPLINE_MULTIPOINT && root.coordinate >= i) return 0;
                break;
            }
            case JPE_DENSITY_INTERVAL_SELECT: {
                if (node.input_a >= payload.interval_select_count) return 0;
                const JpeIntervalSelect& table = payload.interval_selects[node.input_a];
                if (table.input_node >= i) return 0;
                if (static_cast<uint64_t>(table.first_threshold) + table.threshold_count
                    > payload.interval_threshold_count) return 0;
                if (static_cast<uint64_t>(table.first_branch) + table.threshold_count + 1
                    > payload.interval_branch_count) return 0;
                for (uint32_t k = 0; k < table.threshold_count; ++k) {
                    const float value = payload.interval_thresholds[table.first_threshold + k];
                    if (k > 0 && !(payload.interval_thresholds[table.first_threshold + k - 1] < value)) return 0;
                }
                for (uint32_t k = 0; k <= table.threshold_count; ++k) {
                    if (payload.interval_branches[table.first_branch + k] >= i) return 0;
                }
                break;
            }
            case JPE_DENSITY_DISTANCE_TO_POINT:
                if (node.input_a >= payload.distance_point_count) return 0;
                if (payload.distance_points[node.input_a].metric > 3) return 0;
                break;
            case JPE_DENSITY_END_ISLAND:
                needs_end_island = true;
                break;
            default:
                break;
        }
    }
    if (needs_end_island && payload.end_island_permutation == nullptr) return 0;

    // Spline tables: every point must name a real child and the locations must be
    // strictly ascending, because the sampler binary-searches them.
    for (uint32_t index = 0; index < payload.spline_count; ++index) {
        const JpeSplineNode& node = payload.splines[index];
        if (node.kind == JPE_SPLINE_CONSTANT) continue;
        if (node.kind != JPE_SPLINE_MULTIPOINT) return 0;
        if (node.point_count == 0) return 0;
        if (static_cast<uint64_t>(node.first_point) + node.point_count > payload.spline_point_count) return 0;
        for (uint32_t k = 0; k < node.point_count; ++k) {
            const JpeSplinePoint& point = payload.spline_points[node.first_point + k];
            if (point.child >= payload.spline_count) return 0;
            if (k > 0 && !(payload.spline_points[node.first_point + k - 1].location < point.location)) return 0;
        }
    }

    const uint64_t base = hash_graph(nodes, node_count, root_node, ir_version, configuration_fingerprint);
    const uint64_t noise_hash = payload.noise_plan_count > 0
        ? hash_noise_payload(payload.noise_plans, payload.noise_plan_count) : 0;
    const uint64_t program_hash = hash_program_payload(payload);
    // Boost-style mix keeps node identity and every payload table in one cache key.
    uint64_t fingerprint = base ^ (noise_hash + 0x9E3779B97F4A7C15ULL + (base << 6) + (base >> 2));
    fingerprint ^= program_hash + 0x9E3779B97F4A7C15ULL + (fingerprint << 6) + (fingerprint >> 2);

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
                && same_noise_payload(graph.noise_plans, payload.noise_plans, payload.noise_plan_count)
                && same_program_payload(graph, payload)) {
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

    graph->noise_plans.resize(payload.noise_plan_count);
    for (uint32_t slot = 0; slot < payload.noise_plan_count; ++slot) {
        const JpeNoisePlan& plan = payload.noise_plans[slot];
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

    for (const NoiseBinding& binding : graph->noises) {
        if (binding.plan_slot >= graph->noise_plans.size()) return 0;
    }
    for (const ShiftedNoiseBinding& binding : graph->shifted_noises) {
        if (binding.plan_slot >= graph->noise_plans.size()) return 0;
    }

    if (payload.spline_count > 0) {
        graph->spline_nodes.assign(payload.splines, payload.splines + payload.spline_count);
    }
    if (payload.spline_point_count > 0) {
        graph->spline_points.assign(payload.spline_points, payload.spline_points + payload.spline_point_count);
    }
    if (payload.interval_select_count > 0) {
        graph->interval_tables.assign(payload.interval_selects,
                                      payload.interval_selects + payload.interval_select_count);
    }
    if (payload.interval_threshold_count > 0) {
        graph->interval_thresholds.assign(payload.interval_thresholds,
                                          payload.interval_thresholds + payload.interval_threshold_count);
    }
    if (payload.interval_branch_count > 0) {
        graph->interval_branches.assign(payload.interval_branches,
                                        payload.interval_branches + payload.interval_branch_count);
    }
    if (payload.distance_point_count > 0) {
        graph->distance_points.assign(payload.distance_points,
                                      payload.distance_points + payload.distance_point_count);
    }
    if (payload.end_island_permutation != nullptr) {
        graph->end_island_permutation.assign(payload.end_island_permutation,
                                             payload.end_island_permutation + 256);
    }
    if (payload.blend != nullptr && payload.blend->active != 0) {
        const JpeBlendInput& source = *payload.blend;
        if (source.cells_x == 0 || source.cells_y == 0 || source.cells_z == 0) return 0;
        const uint64_t cells = static_cast<uint64_t>(source.cells_x) * source.cells_y * source.cells_z;
        if (cells > kMaxBlendCells) return 0;
        if (source.fixed_density == nullptr || source.density == nullptr
            || source.density_mask == nullptr) return 0;
        graph->blend.active = true;
        graph->blend.base_x = source.base_cell_x;
        graph->blend.base_y = source.base_cell_y;
        graph->blend.base_z = source.base_cell_z;
        graph->blend.cells_x = source.cells_x;
        graph->blend.cells_y = source.cells_y;
        graph->blend.cells_z = source.cells_z;
        graph->blend.fixed_density.assign(source.fixed_density,
                                          source.fixed_density + static_cast<size_t>(cells));
        graph->blend.density.assign(source.density, source.density + static_cast<size_t>(cells));
        graph->blend.density_mask.assign(source.density_mask,
                                         source.density_mask + static_cast<size_t>(cells));
    }

    build_execution_plan(*graph);
    std::lock_guard<std::mutex> lock(g_mutex);
    uint64_t handle = g_next_handle++;
    if (handle == 0) handle = g_next_handle++;
    g_handles.emplace(handle, std::move(graph));
    g_cache.emplace(fingerprint, handle);
    return handle;
} catch (...) { return 0; }

extern "C" uint64_t jpe_cpp_density_graph_compile_with_noise(
    const JpeDensityNode* nodes, uint32_t node_count, uint32_t root_node,
    uint32_t ir_version, uint64_t configuration_fingerprint,
    const JpeNoisePlan* noise_plans, uint32_t noise_plan_count
) {
    JpeGraphProgram program{};
    program.noise_plans = noise_plans;
    program.noise_plan_count = noise_plan_count;
    return jpe_cpp_density_graph_compile_program(
        nodes, node_count, root_node, ir_version, configuration_fingerprint, &program);
}


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

    // Reachability from the requested roots. Interpolation, slices and top-surface
    // probes own their children through a nested evaluation, so this walk stops at
    // those boundaries. Without it a chunk whose root is INTERPOLATED would run
    // every noise stack at full resolution and then again on the cell grid.
    const uint32_t graph_node_count = static_cast<uint32_t>(graph->nodes.size());
    std::vector<uint8_t> active(graph_node_count, 0);
    {
        std::vector<uint32_t> pending;
        pending.reserve(64);
        const auto mark = [&](uint32_t dep) {
            if (dep == kNoInput || dep >= graph_node_count || active[dep] != 0) return;
            active[dep] = 1;
            pending.push_back(dep);
        };
        for (uint32_t root : selected_roots) mark(root);
        while (!pending.empty()) {
            const uint32_t index = pending.back();
            pending.pop_back();
            const JpeDensityNode& node = graph->nodes[index];
            switch (node.opcode) {
                case JPE_DENSITY_CONST:
                case JPE_DENSITY_X:
                case JPE_DENSITY_Y:
                case JPE_DENSITY_Z:
                case JPE_DENSITY_INPUT:
                case JPE_DENSITY_OPAQUE:
                case JPE_DENSITY_NOISE:
                case JPE_DENSITY_NOISE_TRANSPOSED:
                case JPE_DENSITY_END_ISLAND:
                case JPE_DENSITY_DISTANCE_TO_POINT:
                case JPE_DENSITY_INTERPOLATED:
                case JPE_DENSITY_SLICE_X:
                case JPE_DENSITY_SLICE_Y:
                case JPE_DENSITY_SLICE_Z:
                case JPE_DENSITY_FIND_TOP_SURFACE:
                    // Leaf, or a node whose children are evaluated by its own stage.
                    break;
                case JPE_DENSITY_GRADIENT_CLAMPED_X:
                case JPE_DENSITY_GRADIENT_CLAMPED_Y:
                case JPE_DENSITY_GRADIENT_CLAMPED_Z:
                case JPE_DENSITY_NEGATE:
                case JPE_DENSITY_ABS:
                case JPE_DENSITY_SQUARE:
                case JPE_DENSITY_CUBE:
                case JPE_DENSITY_SQRT:
                case JPE_DENSITY_LOG:
                case JPE_DENSITY_RECIPROCAL:
                case JPE_DENSITY_SIGN:
                case JPE_DENSITY_SQUEEZE:
                case JPE_DENSITY_LEAKY_RELU:
                case JPE_DENSITY_CLAMP:
                case JPE_DENSITY_BLEND_DENSITY:
                    mark(node.input_a);
                    break;
                case JPE_DENSITY_ROUND:
                    mark(node.input_a);
                    mark(node.input_b);
                    break;
                case JPE_DENSITY_ADD:
                case JPE_DENSITY_MUL:
                case JPE_DENSITY_SUB:
                case JPE_DENSITY_DIV:
                case JPE_DENSITY_MIN:
                case JPE_DENSITY_MAX:
                case JPE_DENSITY_POW:
                    mark(node.input_a);
                    mark(node.input_b);
                    break;
                case JPE_DENSITY_RANGE_CHOICE:
                case JPE_DENSITY_LERP:
                    mark(node.input_a);
                    mark(node.input_b);
                    mark(node.reserved);
                    break;
                case JPE_DENSITY_INTERVAL_SELECT: {
                    const JpeIntervalSelect& table = graph->interval_tables[node.input_a];
                    mark(table.input_node);
                    for (uint32_t k = 0; k <= table.threshold_count; ++k) {
                        mark(graph->interval_branches[table.first_branch + k]);
                    }
                    break;
                }
                case JPE_DENSITY_SPLINE:
                    visit_spline_coordinates(*graph, graph->splines[graph->op_slot[index]].root, mark);
                    break;
                default:
                    break;
            }
        }
    }

    // Re-entrant child evaluation. Topological order guarantees a child's whole
    // dependency set sits below its index, so limiting the sweep to that prefix
    // is exact and prevents a nested call from redoing the whole graph.
    const auto evaluate_prefix = [&](uint32_t child_node,
                                     uint32_t nx, uint32_t ny, uint32_t nz,
                                     int32_t mx, int32_t my, int32_t mz,
                                     int32_t sx, int32_t sy, int32_t sz,
                                     float* target, uint64_t target_length) -> int32_t {
        if (g_reentry_depth >= kMaxTopSurfaceDepth) return 10;
        ++g_reentry_depth;
        const uint32_t saved_limit = g_node_limit;
        g_node_limit = child_node + 1;
        const int32_t status = jpe_cpp_density_graph_evaluate_multi_with_inputs(
            graph_handle, nx, ny, nz, mx, my, mz, sx, sy, sz,
            inputs, input_count, &child_node, 1, target, target_length);
        g_node_limit = saved_limit;
        --g_reentry_depth;
        return status;
    };

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
    // Per-spline interval hint. Advisory only: every use re-checks the bounds, so
    // a stale hint costs a binary search, never a wrong value.
    std::vector<int32_t> spline_hint(graph->spline_nodes.size(), -1);
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
    const auto in_prefix = [node_limit](uint32_t node) { return node < node_limit; };
    std::vector<float> noise_values;
    if (!graph->noises.empty() && in_prefix(graph->noises.front().node)) {
        if (graph->noise_plans.empty()) return 11;
        const uint64_t staged = static_cast<uint64_t>(graph->noise_plans.size()) * volume_length;
        if (staged > kMaxNoiseFloats) return 12;
        noise_values.resize(static_cast<size_t>(staged));
        for (const NoiseBinding& binding : graph->noises) {
            if (active[binding.node] == 0) continue;
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

    // Shifted noise needs its two shift volumes before the stack runs. Both are
    // ordinary subgraphs, so each is evaluated over this volume by re-entering the
    // evaluator with the node index as the sweep limit.
    std::vector<float> shifted_values;
    if (!graph->shifted_noises.empty() && in_prefix(graph->shifted_noises.front().node)) {
        const uint64_t staged = static_cast<uint64_t>(graph->shifted_noises.size()) * volume_length;
        if (staged > kMaxNoiseFloats) return 13;
        shifted_values.resize(static_cast<size_t>(staged));
        std::vector<float> shift_x(static_cast<size_t>(volume_length));
        std::vector<float> shift_z(static_cast<size_t>(volume_length));
        for (uint32_t index = 0; index < graph->shifted_noises.size(); ++index) {
            const ShiftedNoiseBinding& binding = graph->shifted_noises[index];
            if (binding.node >= node_limit || active[binding.node] == 0) continue;
            if (binding.plan_slot >= graph->noise_plans.size()) return 14;
            int32_t status = evaluate_prefix(binding.shift_x, size_x, size_y, size_z,
                min_x, min_y, min_z, step_x, step_y, step_z,
                shift_x.data(), volume_length);
            if (status != 0) return status;
            status = evaluate_prefix(binding.shift_z, size_x, size_y, size_z,
                min_x, min_y, min_z, step_x, step_y, step_z,
                shift_z.data(), volume_length);
            if (status != 0) return status;
            const NoisePlanData& plan = graph->noise_plans[binding.plan_slot];
            float* destination = shifted_values.data()
                + static_cast<uint64_t>(index) * volume_length;
            status = jpe_cpp_perlin_add_stack_shifted(
                plan.layers.data(), static_cast<uint32_t>(plan.layers.size()),
                size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z,
                shift_x.data(), shift_z.data(), destination, volume_length, plan.flags);
            if (status != 0) return 3;
        }
    }

    // INTERPOLATED evaluates its child on a coarse cell grid, then expands with the
    // same Z->X->Y order vanilla uses. The Y axis is a running sum, not a fresh lerp,
    // because vanilla accumulates valueStep; recomputing it would change the bits.
    std::vector<float> interpolated_values;
    if (!graph->interpolateds.empty() && in_prefix(graph->interpolateds.front().node)) {
        if (!graph->inputs.empty()) {
            // External volumes are staged at output resolution, so a coarser child
            // grid cannot index them. Refuse rather than silently read the wrong data.
            return 15;
        }
        const uint64_t staged = static_cast<uint64_t>(graph->interpolateds.size()) * volume_length;
        if (staged > kMaxNoiseFloats) return 16;
        interpolated_values.resize(static_cast<size_t>(staged));
        for (uint32_t index = 0; index < graph->interpolateds.size(); ++index) {
            const InterpolatedBinding& binding = graph->interpolateds[index];
            if (binding.node >= node_limit || active[binding.node] == 0) continue;
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
                if (g_interpolate_depth >= kMaxInterpolateDepth) return 17;
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
            if (step_x <= 0 || step_y <= 0 || step_z <= 0) return 18;

            if (step_x != 1 || step_y != 1 || step_z != 1) {
                // Re-expand to a unit-step block volume first, mirroring vanilla's
                // sampleWithNonBlockStep, then subsample at the original steps.
                const uint32_t bx = size_x * static_cast<uint32_t>(step_x);
                const uint32_t by = size_y * static_cast<uint32_t>(step_y);
                const uint32_t bz = size_z * static_cast<uint32_t>(step_z);
                const uint64_t block_length = static_cast<uint64_t>(bx) * by * bz;
                if (block_length > kMaxNoiseFloats) return 19;
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
            if (cell_length > kMaxNoiseFloats) return 20;
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

    // SLICE pins one axis: the child runs once on the reduced volume, then the
    // result is broadcast back along the pinned axis. Staging whole volumes keeps
    // the tile sweep a plain copy, exactly like noise.
    std::vector<float> slice_values;
    if (!graph->slices.empty() && in_prefix(graph->slices.front().node)) {
        // External input volumes are staged at output resolution, so a reduced
        // child grid could not index them. Refuse instead of reading wrong data.
        if (!graph->inputs.empty()) return 21;
        const uint64_t staged = static_cast<uint64_t>(graph->slices.size()) * volume_length;
        if (staged > kMaxNoiseFloats) return 22;
        slice_values.resize(static_cast<size_t>(staged));
        for (uint32_t index = 0; index < graph->slices.size(); ++index) {
            const SliceBinding& binding = graph->slices[index];
            if (active[binding.node] == 0) continue;
            uint32_t reduced_x = size_x, reduced_y = size_y, reduced_z = size_z;
            int32_t min_reduced_x = min_x, min_reduced_y = min_y, min_reduced_z = min_z;
            int32_t step_reduced_x = step_x, step_reduced_y = step_y, step_reduced_z = step_z;
            if (binding.axis == 0) {
                reduced_x = 1; min_reduced_x = binding.coordinate; step_reduced_x = 1;
            } else if (binding.axis == 1) {
                reduced_y = 1; min_reduced_y = binding.coordinate; step_reduced_y = 1;
            } else {
                reduced_z = 1; min_reduced_z = binding.coordinate; step_reduced_z = 1;
            }
            const uint64_t reduced_length = static_cast<uint64_t>(reduced_x) * reduced_y * reduced_z;
            std::vector<float> buffer(static_cast<size_t>(reduced_length));
            const int32_t status = evaluate_prefix(
                binding.input_node, reduced_x, reduced_y, reduced_z,
                min_reduced_x, min_reduced_y, min_reduced_z,
                step_reduced_x, step_reduced_y, step_reduced_z,
                buffer.data(), reduced_length);
            if (status != 0) return status;
            float* destination = slice_values.data()
                + static_cast<uint64_t>(index) * volume_length;
            for (uint32_t z = 0; z < size_z; ++z) {
                for (uint32_t x = 0; x < size_x; ++x) {
                    for (uint32_t y = 0; y < size_y; ++y) {
                        const uint64_t out = (static_cast<uint64_t>(z) * size_x + x) * size_y + y;
                        const uint64_t source = binding.axis == 0
                            ? static_cast<uint64_t>(z) * size_y + y
                            : binding.axis == 1
                                ? static_cast<uint64_t>(z) * size_x + x
                                : static_cast<uint64_t>(x) * size_y + y;
                        destination[out] = buffer[static_cast<size_t>(source)];
                    }
                }
            }
        }
    }

    // FIND_TOP_SURFACE scans downward from a per-column ceiling. One batched child
    // evaluation over the probe lattice replaces vanilla's per-probe sampleValue,
    // which is the whole reason this node is affordable at chunk scale.
    std::vector<float> top_surface_values;
    if (!graph->top_surfaces.empty() && in_prefix(graph->top_surfaces.front().node)) {
        // Vanilla rejects sizeY != 1 outright, because the result is one height
        // per column.
        if (size_y != 1) return 23;
        if (!graph->inputs.empty()) return 24;
        const uint64_t staged = static_cast<uint64_t>(graph->top_surfaces.size()) * volume_length;
        if (staged > kMaxNoiseFloats) return 25;
        top_surface_values.resize(static_cast<size_t>(staged));
        const uint64_t column_count = static_cast<uint64_t>(size_x) * size_z;
        std::vector<float> upper(static_cast<size_t>(volume_length));
        std::vector<int32_t> tops(static_cast<size_t>(column_count));
        for (uint32_t index = 0; index < graph->top_surfaces.size(); ++index) {
            const TopSurfaceBinding& binding = graph->top_surfaces[index];
            if (active[binding.node] == 0) continue;
            const int32_t status = evaluate_prefix(
                binding.upper_node, size_x, size_y, size_z,
                min_x, min_y, min_z, step_x, step_y, step_z,
                upper.data(), volume_length);
            if (status != 0) return status;

            const int32_t cell_height = binding.cell_height;
            const int32_t lower_bound = binding.lower_bound;
            int32_t highest_top = INT32_MIN;
            for (uint64_t column = 0; column < column_count; ++column) {
                const float value = upper[static_cast<size_t>(column)];
                const double scaled = std::floor(static_cast<double>(value / static_cast<float>(cell_height)));
                int32_t top = scaled <= -2147483648.0 ? INT32_MIN
                    : scaled >= 2147483647.0 ? INT32_MAX
                    : static_cast<int32_t>(scaled) * cell_height;
                tops[static_cast<size_t>(column)] = top;
                if (top > highest_top) highest_top = top;
            }

            float* destination = top_surface_values.data()
                + static_cast<uint64_t>(index) * volume_length;
            // Probe values are always multiples of cellHeight, so the lattice must
            // start at the first multiple at or above lowerBound.
            const int32_t base = (floor_div(lower_bound, cell_height)
                + (floor_mod(lower_bound, cell_height) != 0 ? 1 : 0)) * cell_height;
            const int64_t probe_count = highest_top < base ? 0
                : (static_cast<int64_t>(highest_top) - base) / cell_height + 1;
            if (probe_count == 0) {
                std::fill_n(destination, volume_length, static_cast<float>(lower_bound));
                continue;
            }
            if (probe_count > 65536) return 26;
            const uint32_t probe_height = static_cast<uint32_t>(probe_count);
            const uint64_t probe_length = static_cast<uint64_t>(size_x) * probe_height * size_z;
            if (probe_length > kMaxNoiseFloats) return 27;
            std::vector<float> probes(static_cast<size_t>(probe_length));
            const int32_t probe_status = evaluate_prefix(
                binding.density_node, size_x, probe_height, size_z,
                min_x, base, min_z, step_x, cell_height, step_z,
                probes.data(), probe_length);
            if (probe_status != 0) return probe_status;

            for (uint32_t z = 0; z < size_z; ++z) {
                for (uint32_t x = 0; x < size_x; ++x) {
                    const uint64_t column = static_cast<uint64_t>(z) * size_x + x;
                    const int32_t top = tops[static_cast<size_t>(column)];
                    float result = static_cast<float>(lower_bound);
                    if (top > lower_bound) {
                        const int64_t start_k = (static_cast<int64_t>(top) - base) / cell_height;
                        for (int64_t k = start_k; k >= 0; --k) {
                            const uint64_t probe = (static_cast<uint64_t>(z) * size_x + x)
                                * probe_height + static_cast<uint64_t>(k);
                            if (probes[static_cast<size_t>(probe)] > 0.0f) {
                                result = static_cast<float>(base + static_cast<int32_t>(k) * cell_height);
                                break;
                            }
                        }
                    }
                    destination[column] = result;
                }
            }
        }
    }

    // END_ISLAND is column-constant: one island height per (x,z), broadcast along Y.
    std::vector<float> end_island_values;
    if (!graph->end_island_nodes.empty() && in_prefix(graph->end_island_nodes.front())) {
        if (graph->end_island_permutation.size() != 256) return 28;
        const uint64_t staged = static_cast<uint64_t>(graph->end_island_nodes.size()) * volume_length;
        if (staged > kMaxNoiseFloats) return 29;
        end_island_values.resize(static_cast<size_t>(staged));
        const uint8_t* perms = graph->end_island_permutation.data();
        // Adjacent columns usually share (blockX/8)/2, so a tiny memo removes
        // almost all of the 25x25 island scan.
        std::unordered_map<int64_t, float> cache;
        cache.reserve(64);
        for (uint32_t index = 0; index < graph->end_island_nodes.size(); ++index) {
            if (active[graph->end_island_nodes[index]] == 0) continue;
            float* destination = end_island_values.data()
                + static_cast<uint64_t>(index) * volume_length;
            for (uint32_t z = 0; z < size_z; ++z) {
                const int32_t block_z = wrapped_coordinate(min_z, z, step_z);
                for (uint32_t x = 0; x < size_x; ++x) {
                    const int32_t block_x = wrapped_coordinate(min_x, x, step_x);
                    const int32_t section_x = block_x / 8;
                    const int32_t section_z = block_z / 8;
                    const int64_t key = (static_cast<int64_t>(section_x) << 32)
                        ^ static_cast<uint32_t>(section_z);
                    auto found = cache.find(key);
                    if (found == cache.end()) {
                        found = cache.emplace(key,
                            end_island_height(perms, section_x, section_z)).first;
                    }
                    const float value = (found->second - 8.0f) / 128.0f;
                    const uint64_t base_index = (static_cast<uint64_t>(z) * size_x + x) * size_y;
                    std::fill_n(destination + base_index, size_y, value);
                }
            }
        }
    }

    // Arithmetic runs from the ordered node sweep below, so it needs to be
    // callable per node rather than per binding list.
    const auto run_arithmetic = [&](const ArithmeticInstruction& instruction, uint32_t count) {
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
            } else if (instruction.opcode == JPE_DENSITY_LERP) {
                const float* alpha_lane = tile_values.data()
                    + static_cast<size_t>(instruction.input_a) * tile_capacity;
                const float* first_lane = tile_values.data()
                    + static_cast<size_t>(instruction.input_b) * tile_capacity;
                const float* second_lane = tile_values.data()
                    + static_cast<size_t>(instruction.input_c) * tile_capacity;
                for (uint32_t sample = 0; sample < count; ++sample) {
                    const float alpha = alpha_lane[sample];
                    // Vanilla returns first outright at alpha == 0 and never forms
                    // (second - first), so NaN in second cannot leak through.
                    //
                    // The alpha == 1 shortcut vanilla also has must NOT be added here.
                    // Measured in a live server: returning second outright keeps every
                    // endpoint within 1e-3 of the plain lerp (so the shortcut looks
                    // harmless sample by sample) yet the volume parity collapses from
                    // 1e-6 to 1.3 absolute on 6% of samples. Something downstream
                    // amplifies the endpoint change; until that is understood, plain
                    // lerp_f32 is what matches the live sampler.
                    destination[sample] = alpha == 0.0f
                        ? first_lane[sample]
                        : lerp_f32(alpha, first_lane[sample], second_lane[sample]);
                }
            } else if (instruction.opcode == JPE_DENSITY_POW) {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = static_cast<float>(std::pow(
                        static_cast<double>(left[sample]), static_cast<double>(right[sample])));
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
    };

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

        // Strict node order. Topological order is what makes a composite node
        // (spline, round, interval, blend) see its children already materialized;
        // grouping by binding kind would let arithmetic read a stale lane.
        for (uint32_t node_index = 0; node_index < node_limit; ++node_index) {
            if (active[node_index] == 0) continue;
            const JpeDensityNode& node = graph->nodes[node_index];
            const uint32_t slot = graph->op_slot[node_index];
            float* destination = tile_values.data()
                + static_cast<size_t>(node_index) * tile_capacity;
            switch (node.opcode) {
                case JPE_DENSITY_CONST:
                case JPE_DENSITY_OPAQUE:
                    // Static lanes are filled once per volume, not per tile.
                    break;
                case JPE_DENSITY_INPUT: {
                    const InputBinding& binding = graph->inputs[slot];
                    const float* source = inputs
                        + static_cast<uint64_t>(binding.input_index) * volume_length + tile_start;
                    std::copy_n(source, count, destination);
                    break;
                }
                case JPE_DENSITY_X:
                case JPE_DENSITY_Y:
                case JPE_DENSITY_Z: {
                    const uint32_t axis = graph->coordinates[slot].axis;
                    std::copy_n(axis == 0 ? x_values : axis == 1 ? y_values : z_values,
                                count, destination);
                    break;
                }
                case JPE_DENSITY_GRADIENT_CLAMPED_X:
                case JPE_DENSITY_GRADIENT_CLAMPED_Y:
                case JPE_DENSITY_GRADIENT_CLAMPED_Z: {
                    const GradientBinding& binding = graph->gradients[slot];
                    const int32_t* coordinates = binding.axis == 0 ? x_coordinates
                        : binding.axis == 1 ? y_coordinates : z_coordinates;
                    for (uint32_t sample = 0; sample < count; ++sample) {
                        const int32_t coordinate = coordinates[sample];
                        const int32_t clamped = coordinate < binding.min_coordinate ? binding.min_coordinate
                            : coordinate > binding.max_coordinate ? binding.max_coordinate : coordinate;
                        const uint32_t delta_bits = static_cast<uint32_t>(clamped)
                            - static_cast<uint32_t>(binding.from_coordinate);
                        const int32_t delta = signed_i32(delta_bits);
                        const float scaled = static_cast<float>(delta) * binding.coordinate_factor;
                        destination[sample] = binding.from_value + scaled;
                    }
                    break;
                }
                case JPE_DENSITY_NOISE:
                case JPE_DENSITY_NOISE_TRANSPOSED: {
                    const NoiseBinding& binding = graph->noises[slot];
                    const float* source = noise_values.data()
                        + static_cast<uint64_t>(binding.plan_slot) * volume_length + tile_start;
                    std::copy_n(source, count, destination);
                    break;
                }
                case JPE_DENSITY_NOISE_SHIFTED_XZ: {
                    const float* source = shifted_values.data()
                        + static_cast<uint64_t>(slot) * volume_length + tile_start;
                    std::copy_n(source, count, destination);
                    break;
                }
                case JPE_DENSITY_INTERPOLATED: {
                    const float* source = interpolated_values.data()
                        + static_cast<uint64_t>(slot) * volume_length + tile_start;
                    std::copy_n(source, count, destination);
                    break;
                }
                case JPE_DENSITY_SLICE_X:
                case JPE_DENSITY_SLICE_Y:
                case JPE_DENSITY_SLICE_Z: {
                    const float* source = slice_values.data()
                        + static_cast<uint64_t>(slot) * volume_length + tile_start;
                    std::copy_n(source, count, destination);
                    break;
                }
                case JPE_DENSITY_FIND_TOP_SURFACE: {
                    const float* source = top_surface_values.data()
                        + static_cast<uint64_t>(slot) * volume_length + tile_start;
                    std::copy_n(source, count, destination);
                    break;
                }
                case JPE_DENSITY_END_ISLAND: {
                    const float* source = end_island_values.data()
                        + static_cast<uint64_t>(slot) * volume_length + tile_start;
                    std::copy_n(source, count, destination);
                    break;
                }
                case JPE_DENSITY_SPLINE: {
                    const SplineBinding& binding = graph->splines[slot];
                    const float* lanes = tile_values.data();
                    for (uint32_t sample = 0; sample < count; ++sample) {
                        destination[sample] = spline_sample(
                            *graph, binding.root, lanes, tile_capacity, sample, 0,
                            spline_hint.data());
                    }
                    break;
                }
                case JPE_DENSITY_ROUND: {
                    const RoundBinding& binding = graph->rounds[slot];
                    const float* input_lane = tile_values.data()
                        + static_cast<size_t>(binding.input_node) * tile_capacity;
                    if (binding.multiple_node == kNoInput) {
                        for (uint32_t sample = 0; sample < count; ++sample) {
                            destination[sample] = round_to_integer(input_lane[sample], binding.type);
                        }
                        break;
                    }
                    const float* multiple_lane = tile_values.data()
                        + static_cast<size_t>(binding.multiple_node) * tile_capacity;
                    for (uint32_t sample = 0; sample < count; ++sample) {
                        const float multiple = multiple_lane[sample];
                        // Vanilla returns the input untouched at multiple == 1.
                        destination[sample] = multiple == 1.0f
                            ? input_lane[sample]
                            : round_to_integer(input_lane[sample] / multiple, binding.type) * multiple;
                    }
                    break;
                }
                case JPE_DENSITY_INTERVAL_SELECT: {
                    const IntervalBinding& binding = graph->intervals[slot];
                    const float* input_lane = tile_values.data()
                        + static_cast<size_t>(binding.input_node) * tile_capacity;
                    const float* thresholds = graph->interval_thresholds.data() + binding.first_threshold;
                    const uint32_t* branches = graph->interval_branches.data() + binding.first_branch;
                    for (uint32_t sample = 0; sample < count; ++sample) {
                        const float value = input_lane[sample];
                        // selectSamplerIndex: first threshold the value is below,
                        // otherwise the last branch. NaN lands on the last branch.
                        uint32_t chosen = binding.threshold_count;
                        for (uint32_t k = 0; k < binding.threshold_count; ++k) {
                            if (value < thresholds[k]) { chosen = k; break; }
                        }
                        destination[sample] = tile_values[
                            static_cast<size_t>(branches[chosen]) * tile_capacity + sample];
                    }
                    break;
                }
                case JPE_DENSITY_DISTANCE_TO_POINT: {
                    const JpeDistancePoint& point =
                        graph->distance_points[graph->distance_bindings[slot]];
                    for (uint32_t sample = 0; sample < count; ++sample) {
                        const int32_t delta_x = signed_i32(static_cast<uint32_t>(point.x)
                            - static_cast<uint32_t>(x_coordinates[sample]));
                        const int32_t delta_y = signed_i32(static_cast<uint32_t>(point.y)
                            - static_cast<uint32_t>(y_coordinates[sample]));
                        const int32_t delta_z = signed_i32(static_cast<uint32_t>(point.z)
                            - static_cast<uint32_t>(z_coordinates[sample]));
                        destination[sample] = distance_metric(point.metric,
                            static_cast<float>(delta_x), static_cast<float>(delta_y),
                            static_cast<float>(delta_z));
                    }
                    break;
                }
                case JPE_DENSITY_BLEND_DENSITY: {
                    const float* child_lane = tile_values.data()
                        + static_cast<size_t>(node.input_a) * tile_capacity;
                    if (!graph->blend.active) {
                        std::copy_n(child_lane, count, destination);
                        break;
                    }
                    const BlendRegion& region = graph->blend;
                    for (uint32_t sample = 0; sample < count; ++sample) {
                        destination[sample] = blend_density(region,
                            x_coordinates[sample], y_coordinates[sample], z_coordinates[sample],
                            child_lane[sample]);
                    }
                    break;
                }
                default:
                    run_arithmetic(graph->arithmetic[slot], count);
                    break;
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

extern "C" uint32_t jpe_cpp_density_graph_handle_count(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<uint32_t>(g_handles.size());
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
