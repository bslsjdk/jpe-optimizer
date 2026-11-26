#include "jpe_native_api.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <mutex>
#include <unordered_map>
#include <vector>
#include <type_traits>

extern "C" uint64_t jpe_cpp_perlin_stack_retain(uint64_t) noexcept;
extern "C" int32_t jpe_cpp_perlin_stack_release(uint64_t) noexcept;
extern "C" int32_t jpe_cpp_perlin_stack_evaluate(
    uint64_t, double, double, float,
    uint32_t, uint32_t, uint32_t,
    int32_t, int32_t, int32_t, int32_t, int32_t, int32_t,
    float*, uint64_t, uint32_t) noexcept;
extern "C" int32_t jpe_cpp_perlin_stack_sample_warped(
    uint64_t, int32_t, int32_t, int32_t, double, double, double,
    double, double, float*) noexcept;
extern "C" int32_t jpe_cpp_perlin_stack_sample_warped_batch_f32(
    uint64_t, uint32_t, uint32_t, uint32_t,
    int32_t, int32_t, int32_t, int32_t, int32_t, int32_t,
    const float*, const float*, const float*, double, double, float*, uint64_t) noexcept;
extern "C" int32_t jpe_cpp_perlin_stack_sample_warped_batch_f64(
    uint64_t, uint32_t, uint32_t, uint32_t,
    int32_t, int32_t, int32_t, int32_t, int32_t, int32_t,
    const double*, const double*, const double*, double, double, float*, uint64_t) noexcept;

static_assert(sizeof(JpeDensityNode) == 40, "JpeDensityNode ABI layout mismatch");

namespace {
constexpr uint32_t kMaxNodes = 65536;
constexpr uint32_t kAllowZeroPerlinSteps = 1U << 31;
constexpr uint64_t kMaxVolumeElements = 1024ULL * 1024ULL;
constexpr uint32_t kNoInput = UINT32_MAX;
constexpr uint32_t kPerlinStack = 7;
constexpr uint32_t kSub = 8, kDiv = 9, kMin = 10, kMax = 11;
constexpr uint32_t kAbs = 12, kNegate = 13, kSquare = 14, kCube = 15;
constexpr uint32_t kSqrt = 16, kHalfNegative = 17, kQuarterNegative = 18;
constexpr uint32_t kReciprocal = 19, kSign = 20, kPow = 21, kClamp = 22;
constexpr uint32_t kSqueeze = 23, kLerp = 24, kGradient = 25, kInterpolated = 26, kRangeChoice = 27;
constexpr uint32_t kRound = 28, kLog = 29, kDistanceToPoint = 30, kPerlinStackWarp = 31, kSlice = 32;

struct CompiledGraph {
    uint64_t fingerprint = 0;
    uint64_t configuration_fingerprint = 0;
    uint32_t root = 0;
    uint32_t references = 1;
    std::vector<JpeDensityNode> nodes;
    std::vector<uint64_t> prepared_handles;
    // Immutable liveness plan, computed once at graph compile time.
    std::vector<uint32_t> node_slots;
    uint32_t slot_count = 0;
    bool has_perlin = false;

    ~CompiledGraph() {
        for (uint64_t handle : prepared_handles) {
            if (handle != 0) jpe_cpp_perlin_stack_release(handle);
        }
    }
};

std::mutex g_mutex;
uint64_t g_next_handle = 1;
std::unordered_map<uint64_t, std::shared_ptr<CompiledGraph>> g_handles;
std::unordered_multimap<uint64_t, uint64_t> g_cache;

uint64_t hash_graph(const JpeDensityNode* nodes, uint32_t count,
                    uint32_t root, uint64_t configuration) noexcept {
    uint64_t hash = 1469598103934665603ULL;
    const auto mix = [&hash](const uint8_t* bytes, size_t length) {
        for (size_t i = 0; i < length; ++i) {
            hash ^= bytes[i];
            hash *= 1099511628211ULL;
        }
    };
    mix(reinterpret_cast<const uint8_t*>(&configuration), sizeof(configuration));
    mix(reinterpret_cast<const uint8_t*>(&count), sizeof(count));
    mix(reinterpret_cast<const uint8_t*>(&root), sizeof(root));
    for (uint32_t i = 0; i < count; ++i) {
        mix(reinterpret_cast<const uint8_t*>(&nodes[i].opcode), sizeof(nodes[i].opcode));
        mix(reinterpret_cast<const uint8_t*>(&nodes[i].input_a), sizeof(nodes[i].input_a));
        mix(reinterpret_cast<const uint8_t*>(&nodes[i].input_b), sizeof(nodes[i].input_b));
        mix(reinterpret_cast<const uint8_t*>(&nodes[i].reserved), sizeof(nodes[i].reserved));
        uint64_t bits;
        std::memcpy(&bits, &nodes[i].immediate, sizeof(bits));
        mix(reinterpret_cast<const uint8_t*>(&bits), sizeof(bits));
        mix(reinterpret_cast<const uint8_t*>(&nodes[i].auxiliary), sizeof(nodes[i].auxiliary));
        mix(reinterpret_cast<const uint8_t*>(&nodes[i].tertiary), sizeof(nodes[i].tertiary));
    }
    return hash;
}

float decode_float(uint32_t bits) noexcept {
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

double java_min(double a, double b) noexcept {
    if (std::isnan(a)) return a;
    if (std::isnan(b)) return b;
    if (a == b) {
        if (a == 0.0) return std::signbit(a) || std::signbit(b) ? -0.0 : 0.0;
        return a;
    }
    return a < b ? a : b;
}

double java_max(double a, double b) noexcept {
    if (std::isnan(a)) return a;
    if (std::isnan(b)) return b;
    if (a == b) {
        if (a == 0.0) return std::signbit(a) && std::signbit(b) ? -0.0 : 0.0;
        return a;
    }
    return a > b ? a : b;
}

double java_signum(double value) noexcept {
    if (std::isnan(value) || value == 0.0) return value;
    return std::signbit(value) ? -1.0 : 1.0;
}

bool valid_graph(const JpeDensityNode* nodes, uint32_t count, uint32_t root) noexcept {
    if (nodes == nullptr || count == 0 || count > kMaxNodes || root >= count) return false;
    for (uint32_t i = 0; i < count; ++i) {
        const JpeDensityNode& node = nodes[i];
        switch (node.opcode) {
            case JPE_DENSITY_CONST:
            case JPE_DENSITY_X:
            case JPE_DENSITY_Y:
            case JPE_DENSITY_Z:
                if (node.reserved != 0 || node.input_a != kNoInput || node.input_b != kNoInput
                    || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case JPE_DENSITY_ADD: case JPE_DENSITY_MUL:
            case kSub: case kDiv: case kMin: case kMax: case kPow:
                if (node.reserved != 0 || node.input_a >= i || node.input_b >= i
                    || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case kAbs: case kNegate: case kSquare: case kCube: case kSqrt:
            case kHalfNegative: case kQuarterNegative: case kReciprocal: case kSign:
                if (node.input_a >= i || node.input_b != kNoInput || node.reserved != 0
                    || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case kClamp:
                if (node.input_a >= i || !std::isfinite(decode_float(node.input_b))
                    || !std::isfinite(decode_float(node.reserved))
                    || decode_float(node.input_b) > decode_float(node.reserved)
                    || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case kSqueeze:
                if (node.input_a >= i || node.input_b != kNoInput || node.reserved != 0
                    || node.immediate != 0.0 || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case kLerp:
                if (node.input_a >= i || node.input_b >= i || node.reserved >= i
                    || node.immediate != 0.0 || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case kGradient:
                if (node.input_a > 2 || node.input_b > 2
                    || !std::isfinite(decode_float(node.reserved))
                    || !std::isfinite(node.immediate) || !std::isfinite(node.auxiliary)
                    || !std::isfinite(node.tertiary)
                    || std::floor(node.immediate) != node.immediate
                    || std::floor(node.auxiliary) != node.auxiliary
                    || node.immediate < static_cast<double>(std::numeric_limits<int32_t>::min())
                    || node.immediate > static_cast<double>(std::numeric_limits<int32_t>::max())
                    || node.auxiliary < static_cast<double>(std::numeric_limits<int32_t>::min())
                    || node.auxiliary > static_cast<double>(std::numeric_limits<int32_t>::max())
                    || node.immediate == node.auxiliary) return false;
                break;
            case kInterpolated:
                if (node.input_a >= i || node.input_b == 0 || node.reserved == 0
                    || node.input_b > static_cast<uint32_t>(INT32_MAX)
                    || node.reserved > static_cast<uint32_t>(INT32_MAX)
                    || node.immediate != 0.0 || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case kRangeChoice:
                if (node.input_a >= i || node.input_b >= i || node.reserved >= i
                    || (!std::isfinite(node.immediate)
                        && node.immediate != -std::numeric_limits<double>::infinity())
                    || !std::isfinite(node.auxiliary)
                    || node.immediate >= node.auxiliary || node.tertiary != 0.0) return false;
                break;
            case kRound:
                if (node.input_a >= i || node.input_b >= i || node.reserved > 3
                    || node.immediate != 0.0 || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case kLog:
                if (node.input_a >= i || node.input_b != kNoInput || node.reserved != 0
                    || node.immediate != 0.0 || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            case kDistanceToPoint:
                if (node.input_a > 3 || node.input_b != kNoInput || node.reserved != 0
                    || !std::isfinite(node.immediate) || !std::isfinite(node.auxiliary)
                    || !std::isfinite(node.tertiary)
                    || std::floor(node.immediate) != node.immediate
                    || std::floor(node.auxiliary) != node.auxiliary
                    || std::floor(node.tertiary) != node.tertiary
                    || node.immediate < static_cast<double>(INT32_MIN)
                    || node.immediate > static_cast<double>(INT32_MAX)
                    || node.auxiliary < static_cast<double>(INT32_MIN)
                    || node.auxiliary > static_cast<double>(INT32_MAX)
                    || node.tertiary < static_cast<double>(INT32_MIN)
                    || node.tertiary > static_cast<double>(INT32_MAX)) return false;
                break;
            case kPerlinStack: {
                const double handle = node.immediate;
                const double xz_scale = node.auxiliary;
                const double y_scale = node.tertiary;
                const float amplitude = decode_float(node.reserved);
                if (node.input_a != kNoInput || node.input_b != kNoInput
                    || !std::isfinite(handle) || handle < 1.0 || handle > 9007199254740991.0
                    || std::floor(handle) != handle
                    || !std::isfinite(xz_scale) || !std::isfinite(y_scale)
                    || !std::isfinite(amplitude)) return false;
                break;
            }
            case kPerlinStackWarp: {
                const double handle = node.immediate;
                if (node.input_a >= i || node.input_b >= i || node.reserved >= i
                    || !std::isfinite(handle) || handle < 1.0 || handle > 9007199254740991.0
                    || std::floor(handle) != handle
                    || !std::isfinite(node.auxiliary) || !std::isfinite(node.tertiary)) return false;
                break;
            }
            case kSlice:
                if (node.input_a >= i || node.input_b > 2 || node.reserved != 0
                    || !std::isfinite(node.immediate) || std::floor(node.immediate) != node.immediate
                    || node.immediate < static_cast<double>(INT32_MIN)
                    || node.immediate > static_cast<double>(INT32_MAX)
                    || node.auxiliary != 0.0 || node.tertiary != 0.0) return false;
                break;
            default:
                return false;
        }
    }
    return true;
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
                  uint64_t output_length, bool allow_zero_steps = false) noexcept {
    if (sx == 0 || sy == 0 || sz == 0
        || (!allow_zero_steps && (dx == 0 || dy == 0 || dz == 0))) return false;
    const uint64_t xy = static_cast<uint64_t>(sx) * sy;
    if (xy > kMaxVolumeElements / sz) return false;
    return xy * sz == output_length && output_length <= kMaxVolumeElements;
}
} // namespace

extern "C" uint64_t jpe_runtime_density_graph_compile(
    const JpeDensityNode* nodes, uint32_t node_count, uint32_t root_node,
    uint64_t configuration_fingerprint
) {
    if (!valid_graph(nodes, node_count, root_node)) return 0;
    const uint64_t fingerprint = hash_graph(nodes, node_count, root_node, configuration_fingerprint);
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto range = g_cache.equal_range(fingerprint);
    for (auto it = range.first; it != range.second; ++it) {
        const auto found = g_handles.find(it->second);
        if (found == g_handles.end()) continue;
        auto& graph = *found->second;
        if (graph.configuration_fingerprint == configuration_fingerprint
            && graph.root == root_node && graph.nodes.size() == node_count
            && std::memcmp(graph.nodes.data(), nodes,
                           static_cast<size_t>(node_count) * sizeof(JpeDensityNode)) == 0) {
            ++graph.references;
            return found->first;
        }
    }

    try {
        auto graph = std::make_shared<CompiledGraph>();
        graph->fingerprint = fingerprint;
        graph->configuration_fingerprint = configuration_fingerprint;
        graph->root = root_node;
        graph->nodes.assign(nodes, nodes + node_count);
        graph->prepared_handles.assign(node_count, 0);
        for (uint32_t i = 0; i < node_count; ++i) {
            if (nodes[i].opcode != kPerlinStack && nodes[i].opcode != kPerlinStackWarp) continue;
            const uint64_t source_handle = static_cast<uint64_t>(nodes[i].immediate);
            const uint64_t retained = jpe_cpp_perlin_stack_retain(source_handle);
            if (retained == 0) return 0;
            graph->prepared_handles[i] = retained;
            graph->has_perlin = true;
        }

        // Build a deterministic register-allocation schedule once. Evaluation
        // then needs no per-node liveness scan or free-list manipulation.
        std::vector<uint32_t> consumers(node_count, 0);
        for (const auto& node : graph->nodes) {
            if (node.opcode == JPE_DENSITY_ADD || node.opcode == JPE_DENSITY_MUL
                || node.opcode == kSub || node.opcode == kDiv || node.opcode == kMin
                || node.opcode == kMax || node.opcode == kPow) {
                ++consumers[node.input_a]; ++consumers[node.input_b];
            } else if ((node.opcode >= kAbs && node.opcode <= kSign)
                || node.opcode == kClamp || node.opcode == kSqueeze || node.opcode == kInterpolated || node.opcode == kLog) {
                ++consumers[node.input_a];
            } else if (node.opcode == kLerp || node.opcode == kRangeChoice) {
                ++consumers[node.input_a]; ++consumers[node.input_b]; ++consumers[node.reserved];
            } else if (node.opcode == kRound) {
                ++consumers[node.input_a]; ++consumers[node.input_b];
            } else if (node.opcode == kPerlinStackWarp) {
                ++consumers[node.input_a]; ++consumers[node.input_b]; ++consumers[node.reserved];
            } else if (node.opcode == kSlice) {
                ++consumers[node.input_a];
            }
        }
        graph->node_slots.resize(node_count, kNoInput);
        std::vector<uint32_t> free_slots;
        for (uint32_t i = 0; i < node_count; ++i) {
            uint32_t slot;
            if (free_slots.empty()) slot = graph->slot_count++;
            else { slot = free_slots.back(); free_slots.pop_back(); }
            graph->node_slots[i] = slot;
            const auto release_input = [&](uint32_t input) {
                if (input != root_node && --consumers[input] == 0) {
                    free_slots.push_back(graph->node_slots[input]);
                }
            };
            const auto& node = graph->nodes[i];
            if (node.opcode == JPE_DENSITY_ADD || node.opcode == JPE_DENSITY_MUL
                || node.opcode == kSub || node.opcode == kDiv || node.opcode == kMin
                || node.opcode == kMax || node.opcode == kPow) {
                release_input(node.input_a); release_input(node.input_b);
            } else if ((node.opcode >= kAbs && node.opcode <= kSign)
                || node.opcode == kClamp || node.opcode == kSqueeze || node.opcode == kInterpolated || node.opcode == kLog) {
                release_input(node.input_a);
            } else if (node.opcode == kLerp || node.opcode == kRangeChoice) {
                release_input(node.input_a);
                release_input(node.input_b);
                release_input(node.reserved);
            } else if (node.opcode == kRound) {
                release_input(node.input_a);
                release_input(node.input_b);
            } else if (node.opcode == kPerlinStackWarp) {
                release_input(node.input_a);
                release_input(node.input_b);
                release_input(node.reserved);
            } else if (node.opcode == kSlice) {
                release_input(node.input_a);
            }
            if (i != root_node && consumers[i] == 0) free_slots.push_back(slot);
        }
        uint64_t handle = g_next_handle++;
        if (handle == 0) handle = g_next_handle++;
        g_handles.emplace(handle, graph);
        g_cache.emplace(fingerprint, handle);
        return handle;
    } catch (...) {
        return 0;
    }
}

template <typename Scalar>
Scalar java_min_scalar(Scalar a, Scalar b) noexcept {
    if (std::isnan(a)) return a;
    if (std::isnan(b)) return b;
    if (a == b) {
        if (a == Scalar(0)) return std::signbit(a) || std::signbit(b) ? -Scalar(0) : Scalar(0);
        return a;
    }
    return a < b ? a : b;
}

template <typename Scalar>
Scalar java_max_scalar(Scalar a, Scalar b) noexcept {
    if (std::isnan(a)) return a;
    if (std::isnan(b)) return b;
    if (a == b) {
        if (a == Scalar(0)) return std::signbit(a) && std::signbit(b) ? -Scalar(0) : Scalar(0);
        return a;
    }
    return a > b ? a : b;
}

template <typename Scalar>
Scalar java_signum_scalar(Scalar value) noexcept {
    if (std::isnan(value) || value == Scalar(0)) return value;
    return std::signbit(value) ? Scalar(-1) : Scalar(1);
}

template <typename Scalar>
struct DensitySlotView {
    Scalar* pointer = nullptr;
    size_t length = 0;

    Scalar& operator[](size_t index) noexcept { return pointer[index]; }
    const Scalar& operator[](size_t index) const noexcept { return pointer[index]; }
    Scalar* data() noexcept { return pointer; }
    const Scalar* data() const noexcept { return pointer; }
    Scalar* begin() noexcept { return pointer; }
    Scalar* end() noexcept { return pointer + length; }
    const Scalar* begin() const noexcept { return pointer; }
    const Scalar* end() const noexcept { return pointer + length; }
};

template <typename Scalar>
struct DensityEvaluationScratch {
    // All DAG registers live in one contiguous allocation. Per-register vectors
    // used to cause one heap allocation per slot and fragmented working sets.
    std::vector<Scalar> slot_storage;
    std::vector<DensitySlotView<Scalar>> slots;
    std::vector<float> perlin;
    std::vector<Scalar> corner_values;
    std::vector<Scalar> sliced_values;

    void prepare(uint32_t slot_count, size_t volume, bool has_perlin) {
        if (slot_count != 0 && volume > slot_storage.max_size() / slot_count) {
            throw std::length_error("density graph scratch size overflow");
        }
        slot_storage.resize(static_cast<size_t>(slot_count) * volume);
        slots.resize(slot_count);
        for (uint32_t i = 0; i < slot_count; ++i) {
            Scalar* slot_data = slot_storage.empty()
                    ? nullptr
                    : slot_storage.data() + static_cast<size_t>(i) * volume;
            slots[i] = DensitySlotView<Scalar>{slot_data, volume};
        }
        if (has_perlin) perlin.resize(volume);
    }
};

template <typename Scalar>
DensityEvaluationScratch<Scalar>& density_evaluation_scratch(uint32_t depth) {
    // One independent frame per recursive graph evaluation level. A fixed array
    // keeps parent references stable while INTERPOLATED/SLICE recurse.
    thread_local std::array<DensityEvaluationScratch<Scalar>, 33> frames;
    return frames[depth];
}

template <typename Scalar>
int32_t evaluate_density_graph_impl(
    const std::shared_ptr<CompiledGraph>& graph,
    uint32_t root_node,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    Scalar* output, uint64_t output_length,
    uint32_t recursion_depth
) {
    if (output == nullptr || root_node >= graph->nodes.size()
        || recursion_depth > 32
        || !valid_volume(size_x, size_y, size_z, step_x, step_y, step_z, output_length, true)) return 1;

    try {
        const size_t volume = static_cast<size_t>(output_length);
        auto& scratch = density_evaluation_scratch<Scalar>(recursion_depth);
        scratch.prepare(graph->slot_count, volume, graph->has_perlin);
        auto& slots = scratch.slots;
        auto& perlin_scratch = scratch.perlin;

        for (uint32_t i = 0; i <= root_node; ++i) {
            const JpeDensityNode& node = graph->nodes[i];
            const uint32_t slot = graph->node_slots[i];
            auto& values = slots[slot];

            switch (node.opcode) {
                case JPE_DENSITY_CONST:
                    std::fill(values.begin(), values.end(), static_cast<Scalar>(node.immediate));
                    break;
                case JPE_DENSITY_X:
                case JPE_DENSITY_Y:
                case JPE_DENSITY_Z: {
                    size_t out = 0;
                    for (uint32_t z = 0; z < size_z; ++z) {
                        const Scalar wz = static_cast<Scalar>(wrapped_coordinate(min_z, z, step_z));
                        for (uint32_t x = 0; x < size_x; ++x) {
                            const Scalar wx = static_cast<Scalar>(wrapped_coordinate(min_x, x, step_x));
                            for (uint32_t y = 0; y < size_y; ++y) {
                                const Scalar wy = static_cast<Scalar>(wrapped_coordinate(min_y, y, step_y));
                                values[out++] = node.opcode == JPE_DENSITY_X ? wx
                                    : node.opcode == JPE_DENSITY_Y ? wy : wz;
                            }
                        }
                    }
                    break;
                }
                case JPE_DENSITY_ADD: case JPE_DENSITY_MUL:
                case kSub: case kDiv: case kMin: case kMax: case kPow: {
                    const auto& a = slots[graph->node_slots[node.input_a]];
                    const auto& b = slots[graph->node_slots[node.input_b]];
                    for (size_t j = 0; j < volume; ++j) {
                        switch (node.opcode) {
                            case JPE_DENSITY_ADD: values[j] = a[j] + b[j]; break;
                            case JPE_DENSITY_MUL: values[j] = a[j] * b[j]; break;
                            case kSub: values[j] = a[j] - b[j]; break;
                            case kDiv: values[j] = a[j] / b[j]; break;
                            case kMin: values[j] = java_min_scalar(a[j], b[j]); break;
                            case kMax: values[j] = java_max_scalar(a[j], b[j]); break;
                            case kPow: values[j] = static_cast<Scalar>(std::pow(a[j], b[j])); break;
                        }
                    }
                    break;
                }
                case kAbs: case kNegate: case kSquare: case kCube: case kSqrt:
                case kHalfNegative: case kQuarterNegative: case kReciprocal: case kSign: {
                    const auto& a = slots[graph->node_slots[node.input_a]];
                    for (size_t j = 0; j < volume; ++j) {
                        const Scalar v = a[j];
                        switch (node.opcode) {
                            case kAbs: values[j] = std::abs(v); break;
                            case kNegate: values[j] = -v; break;
                            case kSquare: values[j] = v * v; break;
                            case kCube: values[j] = v * v * v; break;
                            case kSqrt: values[j] = static_cast<Scalar>(std::sqrt(v)); break;
                            case kHalfNegative: values[j] = v < Scalar(0) ? v * Scalar(0.5) : v; break;
                            case kQuarterNegative: values[j] = v < Scalar(0) ? v * Scalar(0.25) : v; break;
                            case kReciprocal: values[j] = Scalar(1) / v; break;
                            case kSign: values[j] = java_signum_scalar(v); break;
                        }
                    }
                    break;
                }
                case kClamp: {
                    const auto& a = slots[graph->node_slots[node.input_a]];
                    const Scalar lo = static_cast<Scalar>(decode_float(node.input_b));
                    const Scalar hi = static_cast<Scalar>(decode_float(node.reserved));
                    for (size_t j = 0; j < volume; ++j) {
                        values[j] = std::min(std::max(a[j], lo), hi);
                    }
                    break;
                }
                case kSqueeze: {
                    const auto& a = slots[graph->node_slots[node.input_a]];
                    for (size_t j = 0; j < volume; ++j) {
                        const Scalar v = a[j];
                        const Scalar half = v * Scalar(0.5);
                        const Scalar square = v * v;
                        const Scalar cube = square * v;
                        const Scalar cubic = cube / Scalar(24.0);
                        const Scalar squeezed = half - cubic;
                        values[j] = std::min(std::max(squeezed, Scalar(-1)), Scalar(1));
                    }
                    break;
                }
                case kLerp: {
                    const auto& alpha = slots[graph->node_slots[node.input_a]];
                    const auto& first = slots[graph->node_slots[node.input_b]];
                    const auto& second = slots[graph->node_slots[node.reserved]];
                    for (size_t j = 0; j < volume; ++j) {
                        const Scalar delta = second[j] - first[j];
                        volatile Scalar scaled = alpha[j] * delta;
                        values[j] = first[j] + scaled;
                    }
                    break;
                }
                case kGradient: {
                    const int32_t from_coordinate = static_cast<int32_t>(node.immediate);
                    const int32_t to_coordinate = static_cast<int32_t>(node.auxiliary);
                    const int32_t coordinate_range = static_cast<int32_t>(
                        static_cast<uint32_t>(to_coordinate) - static_cast<uint32_t>(from_coordinate));
                    const Scalar from_value = static_cast<Scalar>(decode_float(node.reserved));
                    const Scalar to_value = static_cast<Scalar>(node.tertiary);
                    const Scalar coordinate_factor =
                        (to_value - from_value) / static_cast<Scalar>(coordinate_range);
                    const auto wrapped_subtract = [](int32_t left, int32_t right) {
                        const uint32_t bits = static_cast<uint32_t>(left) - static_cast<uint32_t>(right);
                        int32_t result;
                        std::memcpy(&result, &bits, sizeof(result));
                        return result;
                    };
                    const auto floor_div = [](int64_t value, int64_t divisor) {
                        int64_t quotient = value / divisor;
                        const int64_t remainder = value % divisor;
                        if (remainder != 0 && ((remainder < 0) != (divisor < 0))) --quotient;
                        return quotient;
                    };
                    const auto floor_mod = [&](int64_t value, int64_t divisor) {
                        return value - floor_div(value, divisor) * divisor;
                    };
                    const int32_t min_coordinate = std::min(from_coordinate, to_coordinate);
                    const int32_t max_coordinate = std::max(from_coordinate, to_coordinate);
                    size_t out = 0;
                    for (uint32_t z = 0; z < size_z; ++z) {
                        const int32_t wz = wrapped_coordinate(min_z, z, step_z);
                        for (uint32_t x = 0; x < size_x; ++x) {
                            const int32_t wx = wrapped_coordinate(min_x, x, step_x);
                            for (uint32_t y = 0; y < size_y; ++y) {
                                const int32_t wy = wrapped_coordinate(min_y, y, step_y);
                                const int32_t coordinate = node.input_a == 0 ? wx
                                    : node.input_a == 1 ? wy : wz;
                                const int32_t relative = wrapped_subtract(coordinate, from_coordinate);
                                int32_t sample_offset;
                                if (node.input_b == 0) {
                                    const int32_t clamped = std::min(
                                        std::max(coordinate, min_coordinate), max_coordinate);
                                    sample_offset = wrapped_subtract(clamped, from_coordinate);
                                } else if (node.input_b == 1) {
                                    sample_offset = static_cast<int32_t>(
                                        floor_mod(relative, coordinate_range));
                                } else {
                                    const int64_t tile_index = floor_div(relative, coordinate_range);
                                    const int64_t local_coordinate =
                                        static_cast<int64_t>(relative) - tile_index * coordinate_range;
                                    sample_offset = static_cast<int32_t>(
                                        (tile_index & 1) == 0
                                            ? local_coordinate
                                            : static_cast<int64_t>(coordinate_range) - local_coordinate);
                                }
                                volatile Scalar scaled =
                                    static_cast<Scalar>(sample_offset) * coordinate_factor;
                                values[out++] = from_value + scaled;
                            }
                        }
                    }
                    break;
                }
                case kRound: {
                    const auto& input = slots[graph->node_slots[node.input_a]];
                    const auto& multiple = slots[graph->node_slots[node.input_b]];
                    for (size_t j = 0; j < volume; ++j) {
                        const Scalar ratio_value = input[j] / multiple[j];
                        const double ratio = static_cast<double>(ratio_value);
                        double rounded;
                        switch (node.reserved) {
                            case 0: rounded = std::floor(ratio); break;
                            case 1: rounded = std::floor(ratio + 0.5); break;
                            case 2: rounded = std::ceil(ratio); break;
                            case 3: rounded = std::trunc(ratio); break;
                            default: return 1;
                        }
                        values[j] = static_cast<Scalar>(rounded) * multiple[j];
                    }
                    break;
                }
                case kLog: {
                    const auto& input = slots[graph->node_slots[node.input_a]];
                    for (size_t j = 0; j < volume; ++j) {
                        values[j] = static_cast<Scalar>(std::log(static_cast<double>(input[j])));
                    }
                    break;
                }
                case kDistanceToPoint: {
                    const float point_x = static_cast<float>(node.immediate);
                    const float point_y = static_cast<float>(node.auxiliary);
                    const float point_z = static_cast<float>(node.tertiary);
                    size_t out = 0;
                    for (uint32_t z = 0; z < size_z; ++z) {
                        const float dz = static_cast<float>(wrapped_coordinate(min_z, z, step_z)) - point_z;
                        for (uint32_t x = 0; x < size_x; ++x) {
                            const float dx = static_cast<float>(wrapped_coordinate(min_x, x, step_x)) - point_x;
                            for (uint32_t y = 0; y < size_y; ++y) {
                                const float dy = static_cast<float>(wrapped_coordinate(min_y, y, step_y)) - point_y;
                                const float ax = std::abs(dx), ay = std::abs(dy), az = std::abs(dz);
                                float distance;
                                switch (node.input_a) {
                                    case 0: distance = std::max(ax, std::max(ay, az)); break; // CHEBYSHEV
                                    case 1: distance = std::sqrt(dx * dx + dy * dy + dz * dz); break; // EUCLIDEAN
                                    case 2: distance = dx * dx + dy * dy + dz * dz; break; // EUCLIDEAN_SQUARED
                                    case 3: distance = (ax + ay) + az; break; // MANHATTAN
                                    default: return 1;
                                }
                                values[out++] = static_cast<Scalar>(distance);
                            }
                        }
                    }
                    break;
                }
                case kRangeChoice: {
                    const auto& input = slots[graph->node_slots[node.input_a]];
                    const auto& when_in_range = slots[graph->node_slots[node.input_b]];
                    const auto& when_out_of_range = slots[graph->node_slots[node.reserved]];
                    const Scalar minimum = static_cast<Scalar>(node.immediate);
                    const Scalar maximum = static_cast<Scalar>(node.auxiliary);
                    for (size_t j = 0; j < volume; ++j) {
                        values[j] = input[j] >= minimum && input[j] < maximum
                            ? when_in_range[j] : when_out_of_range[j];
                    }
                    break;
                }
                case kInterpolated: {
                    const int64_t cell_xz = static_cast<int64_t>(node.input_b);
                    const int64_t cell_y = static_cast<int64_t>(node.reserved);
                    const auto floor_div = [](int64_t value, int64_t divisor) {
                        int64_t quotient = value / divisor;
                        const int64_t remainder = value % divisor;
                        if (remainder < 0) --quotient;
                        return quotient;
                    };
                    const auto ceil_div = [&](int64_t value, int64_t divisor) {
                        return -floor_div(-value, divisor);
                    };
                    const int64_t max_x = static_cast<int64_t>(min_x)
                        + static_cast<int64_t>(size_x - 1) * step_x;
                    const int64_t max_y = static_cast<int64_t>(min_y)
                        + static_cast<int64_t>(size_y - 1) * step_y;
                    const int64_t max_z = static_cast<int64_t>(min_z)
                        + static_cast<int64_t>(size_z - 1) * step_z;
                    const int64_t corner_min_x = floor_div(min_x, cell_xz) * cell_xz;
                    const int64_t corner_min_y = floor_div(min_y, cell_y) * cell_y;
                    const int64_t corner_min_z = floor_div(min_z, cell_xz) * cell_xz;
                    const int64_t corner_max_x = (floor_div(max_x, cell_xz) + 1) * cell_xz;
                    const int64_t corner_max_y = (floor_div(max_y, cell_y) + 1) * cell_y;
                    const int64_t corner_max_z = (floor_div(max_z, cell_xz) + 1) * cell_xz;
                    if (corner_min_x < INT32_MIN || corner_min_y < INT32_MIN || corner_min_z < INT32_MIN
                        || corner_max_x > INT32_MAX || corner_max_y > INT32_MAX || corner_max_z > INT32_MAX) {
                        return 1;
                    }
                    const uint64_t corner_x = static_cast<uint64_t>((corner_max_x - corner_min_x) / cell_xz + 1);
                    const uint64_t corner_y = static_cast<uint64_t>((corner_max_y - corner_min_y) / cell_y + 1);
                    const uint64_t corner_z = static_cast<uint64_t>((corner_max_z - corner_min_z) / cell_xz + 1);
                    if (corner_x > UINT32_MAX || corner_y > UINT32_MAX || corner_z > UINT32_MAX
                        || corner_x * corner_y > kMaxVolumeElements
                        || corner_x * corner_y * corner_z > kMaxVolumeElements) return 1;
                    const uint64_t corner_length = corner_x * corner_y * corner_z;
                    auto& corner_values = scratch.corner_values;
                    corner_values.resize(static_cast<size_t>(corner_length));
                    const int32_t corner_status = evaluate_density_graph_impl<Scalar>(
                        graph, node.input_a,
                        static_cast<uint32_t>(corner_x), static_cast<uint32_t>(corner_y),
                        static_cast<uint32_t>(corner_z),
                        static_cast<int32_t>(corner_min_x), static_cast<int32_t>(corner_min_y),
                        static_cast<int32_t>(corner_min_z),
                        static_cast<int32_t>(cell_xz), static_cast<int32_t>(cell_y),
                        static_cast<int32_t>(cell_xz),
                        corner_values.data(), corner_length, recursion_depth + 1);
                    if (corner_status != 0) return corner_status;

                    const Scalar inv_xz = static_cast<Scalar>(1.0F / static_cast<float>(cell_xz));
                    const Scalar inv_y = static_cast<Scalar>(1.0F / static_cast<float>(cell_y));
                    const auto corner_at = [&](uint64_t x, uint64_t y, uint64_t z) -> Scalar {
                        return corner_values[static_cast<size_t>((z * corner_x + x) * corner_y + y)];
                    };
                    const auto lerp = [](Scalar a, Scalar b, Scalar t) -> Scalar {
                        const Scalar delta = b - a;
                        volatile Scalar scaled = delta * t;
                        return a + scaled;
                    };
                    size_t out = 0;
                    for (uint32_t z = 0; z < size_z; ++z) {
                        const int64_t block_z = wrapped_coordinate(min_z, z, step_z);
                        const int64_t cell_z = floor_div(block_z, cell_xz) * cell_xz;
                        const uint64_t cz = static_cast<uint64_t>((cell_z - corner_min_z) / cell_xz);
                        const Scalar tz = static_cast<Scalar>(block_z - cell_z) * inv_xz;
                        for (uint32_t x = 0; x < size_x; ++x) {
                            const int64_t block_x = wrapped_coordinate(min_x, x, step_x);
                            const int64_t cell_x = floor_div(block_x, cell_xz) * cell_xz;
                            const uint64_t cx = static_cast<uint64_t>((cell_x - corner_min_x) / cell_xz);
                            const Scalar tx = static_cast<Scalar>(block_x - cell_x) * inv_xz;
                            for (uint32_t y = 0; y < size_y; ++y) {
                                const int64_t block_y = wrapped_coordinate(min_y, y, step_y);
                                const int64_t cell_y0 = floor_div(block_y, cell_y) * cell_y;
                                const uint64_t cy = static_cast<uint64_t>((cell_y0 - corner_min_y) / cell_y);
                                const Scalar ty = static_cast<Scalar>(block_y - cell_y0) * inv_y;

                                const Scalar z00 = lerp(corner_at(cx, cy, cz), corner_at(cx, cy, cz + 1), tz);
                                const Scalar z10 = lerp(corner_at(cx + 1, cy, cz), corner_at(cx + 1, cy, cz + 1), tz);
                                const Scalar z01 = lerp(corner_at(cx, cy + 1, cz), corner_at(cx, cy + 1, cz + 1), tz);
                                const Scalar z11 = lerp(corner_at(cx + 1, cy + 1, cz), corner_at(cx + 1, cy + 1, cz + 1), tz);
                                const Scalar y0 = lerp(z00, z10, tx);
                                const Scalar y1 = lerp(z01, z11, tx);
                                values[out++] = lerp(y0, y1, ty);
                            }
                        }
                    }
                    break;
                }
                case kSlice: {
                    auto& sliced_values = scratch.sliced_values;
                    sliced_values.resize(volume);
                    int32_t sliced_min_x = min_x, sliced_min_y = min_y, sliced_min_z = min_z;
                    int32_t sliced_step_x = step_x, sliced_step_y = step_y, sliced_step_z = step_z;
                    const int32_t coordinate = static_cast<int32_t>(node.immediate);
                    if (node.input_b == 0) {
                        sliced_min_x = coordinate;
                        sliced_step_x = 0;
                    } else if (node.input_b == 1) {
                        sliced_min_y = coordinate;
                        sliced_step_y = 0;
                    } else {
                        sliced_min_z = coordinate;
                        sliced_step_z = 0;
                    }
                    const int32_t status = evaluate_density_graph_impl<Scalar>(
                        graph, node.input_a, size_x, size_y, size_z,
                        sliced_min_x, sliced_min_y, sliced_min_z,
                        sliced_step_x, sliced_step_y, sliced_step_z,
                        sliced_values.data(), output_length, recursion_depth + 1);
                    if (status != 0) return status;
                    std::copy(sliced_values.begin(), sliced_values.end(), values.begin());
                    break;
                }
                case kPerlinStack: {
                    std::fill(perlin_scratch.begin(), perlin_scratch.end(), 0.0F);
                    const int32_t status = jpe_cpp_perlin_stack_evaluate(
                        graph->prepared_handles[i], node.auxiliary,
                        node.tertiary, decode_float(node.reserved),
                        size_x, size_y, size_z, min_x, min_y, min_z,
                        step_x, step_y, step_z, perlin_scratch.data(), output_length,
                        (step_x == 0 || step_y == 0 || step_z == 0) ? kAllowZeroPerlinSteps : 0U);
                    if (status != 0) return status;
                    for (size_t j = 0; j < volume; ++j) values[j] = static_cast<Scalar>(perlin_scratch[j]);
                    break;
                }
                case kPerlinStackWarp: {
                    const auto& shift_x = slots[graph->node_slots[node.input_a]];
                    const auto& shift_y = slots[graph->node_slots[node.input_b]];
                    const auto& shift_z = slots[graph->node_slots[node.reserved]];
                    // Pass the live DAG slots directly. The batch sampler widens
                    // F32 shifts at use time, avoiding three temporary heap buffers
                    // and a full-volume conversion/copy before Perlin evaluation.
                    const int32_t status = [&]() noexcept {
                        if constexpr (std::is_same_v<Scalar, float>) {
                            return jpe_cpp_perlin_stack_sample_warped_batch_f32(
                                graph->prepared_handles[i], size_x, size_y, size_z,
                                min_x, min_y, min_z, step_x, step_y, step_z,
                                shift_x.data(), shift_y.data(), shift_z.data(),
                                node.auxiliary, node.tertiary, perlin_scratch.data(), output_length);
                        } else {
                            return jpe_cpp_perlin_stack_sample_warped_batch_f64(
                                graph->prepared_handles[i], size_x, size_y, size_z,
                                min_x, min_y, min_z, step_x, step_y, step_z,
                                shift_x.data(), shift_y.data(), shift_z.data(),
                                node.auxiliary, node.tertiary, perlin_scratch.data(), output_length);
                        }
                    }();
                    if (status != 0) return status;
                    for (size_t j = 0; j < volume; ++j) {
                        values[j] = static_cast<Scalar>(perlin_scratch[j]);
                    }
                    break;
                }
                default:
                    return 1;
            }
        }

        const auto& root_values = slots[graph->node_slots[root_node]];
        std::copy(root_values.begin(), root_values.end(), output);
        return 0;
    } catch (...) {
        return 3;
    }
}

template <typename Scalar>
int32_t evaluate_density_graph(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    Scalar* output, uint64_t output_length
) {
    if (output == nullptr || !valid_volume(size_x, size_y, size_z,
            step_x, step_y, step_z, output_length)) return 1;
    std::shared_ptr<CompiledGraph> graph;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto found = g_handles.find(graph_handle);
        if (found == g_handles.end()) return 2;
        graph = found->second;
    }
    return evaluate_density_graph_impl<Scalar>(
        graph, graph->root, size_x, size_y, size_z,
        min_x, min_y, min_z, step_x, step_y, step_z,
        output, output_length, 0);
}

extern "C" int32_t jpe_runtime_density_graph_evaluate(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double* output, uint64_t output_length
) {
    return evaluate_density_graph<double>(graph_handle, size_x, size_y, size_z,
        min_x, min_y, min_z, step_x, step_y, step_z, output, output_length);
}

extern "C" int32_t jpe_runtime_density_graph_evaluate_f32(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    float* output, uint64_t output_length
) {
    return evaluate_density_graph<float>(graph_handle, size_x, size_y, size_z,
        min_x, min_y, min_z, step_x, step_y, step_z, output, output_length);
}

extern "C" int32_t jpe_runtime_density_graph_release(uint64_t graph_handle) {
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
}