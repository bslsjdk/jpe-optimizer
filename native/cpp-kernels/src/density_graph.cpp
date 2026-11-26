#include "jpe_native_api.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

extern "C" uint64_t jpe_cpp_perlin_stack_retain(uint64_t) noexcept;
extern "C" int32_t jpe_cpp_perlin_stack_release(uint64_t) noexcept;
extern "C" int32_t jpe_cpp_perlin_stack_evaluate(
    uint64_t, double, double, float,
    uint32_t, uint32_t, uint32_t,
    int32_t, int32_t, int32_t, int32_t, int32_t, int32_t,
    float*, uint64_t, uint32_t) noexcept;

namespace {
constexpr uint32_t kMaxNodes = 65536;
constexpr uint64_t kMaxVolumeElements = 1024ULL * 1024ULL;
constexpr uint32_t kNoInput = UINT32_MAX;
constexpr uint32_t kPerlinStack = 7;
constexpr uint32_t kSub = 8, kDiv = 9, kMin = 10, kMax = 11;
constexpr uint32_t kAbs = 12, kNegate = 13, kSquare = 14, kCube = 15;
constexpr uint32_t kSqrt = 16, kHalfNegative = 17, kQuarterNegative = 18;
constexpr uint32_t kReciprocal = 19, kSign = 20, kPow = 21, kClamp = 22;

struct CompiledGraph {
    uint64_t fingerprint = 0;
    uint64_t configuration_fingerprint = 0;
    uint32_t root = 0;
    uint32_t references = 1;
    std::vector<JpeDensityNode> nodes;
    std::vector<uint64_t> prepared_handles;

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
    }
    return hash;
}

float decode_float(uint32_t bits) noexcept {
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
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
                if (node.reserved != 0 || node.input_a != kNoInput || node.input_b != kNoInput) return false;
                break;
            case JPE_DENSITY_ADD: case JPE_DENSITY_MUL:
            case kSub: case kDiv: case kMin: case kMax: case kPow:
                if (node.reserved != 0 || node.input_a >= i || node.input_b >= i) return false;
                break;
            case kAbs: case kNegate: case kSquare: case kCube: case kSqrt:
            case kHalfNegative: case kQuarterNegative: case kReciprocal: case kSign:
                if (node.input_a >= i || node.input_b != kNoInput || node.reserved != 0) return false;
                break;
            case kClamp:
                if (node.input_a >= i || !std::isfinite(decode_float(node.input_b))
                    || !std::isfinite(decode_float(node.reserved))
                    || decode_float(node.input_b) > decode_float(node.reserved)) return false;
                break;
            case kPerlinStack: {
                const double handle = node.immediate;
                const float xz_scale = decode_float(node.input_a);
                const float y_scale = decode_float(node.input_b);
                const float amplitude = decode_float(node.reserved);
                if (!std::isfinite(handle) || handle < 1.0 || handle > 9007199254740991.0
                    || std::floor(handle) != handle
                    || !std::isfinite(xz_scale) || !std::isfinite(y_scale)
                    || !std::isfinite(amplitude)) return false;
                break;
            }
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
                  uint64_t output_length) noexcept {
    if (sx == 0 || sy == 0 || sz == 0 || dx == 0 || dy == 0 || dz == 0) return false;
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
            if (nodes[i].opcode != kPerlinStack) continue;
            const uint64_t source_handle = static_cast<uint64_t>(nodes[i].immediate);
            const uint64_t retained = jpe_cpp_perlin_stack_retain(source_handle);
            if (retained == 0) return 0;
            graph->prepared_handles[i] = retained;
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

extern "C" int32_t jpe_runtime_density_graph_evaluate(
    uint64_t graph_handle,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    double* output, uint64_t output_length
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

    try {
        const size_t volume = static_cast<size_t>(output_length);
        std::vector<uint32_t> consumers(graph->nodes.size(), 0);
        for (const auto& node : graph->nodes) {
            if (node.opcode == JPE_DENSITY_ADD || node.opcode == JPE_DENSITY_MUL
                || node.opcode == kSub || node.opcode == kDiv || node.opcode == kMin
                || node.opcode == kMax || node.opcode == kPow) {
                ++consumers[node.input_a]; ++consumers[node.input_b];
            } else if ((node.opcode >= kAbs && node.opcode <= kSign) || node.opcode == kClamp) {
                ++consumers[node.input_a];
            }
        }

        // Node-major execution: each DAG node is evaluated once over the entire
        // chunk volume. Slots are recycled as soon as their final consumer runs.
        std::vector<std::vector<double>> slots;
        std::vector<uint32_t> free_slots;
        std::vector<uint32_t> node_slots(graph->nodes.size(), kNoInput);
        std::vector<float> perlin_scratch(volume);

        for (uint32_t i = 0; i < graph->nodes.size(); ++i) {
            const JpeDensityNode& node = graph->nodes[i];
            uint32_t slot;
            if (free_slots.empty()) {
                slot = static_cast<uint32_t>(slots.size());
                slots.emplace_back();
            } else {
                slot = free_slots.back();
                free_slots.pop_back();
            }
            node_slots[i] = slot;
            auto& values = slots[slot];
            values.resize(volume);

            switch (node.opcode) {
                case JPE_DENSITY_CONST:
                    std::fill(values.begin(), values.end(), node.immediate);
                    break;
                case JPE_DENSITY_X:
                case JPE_DENSITY_Y:
                case JPE_DENSITY_Z: {
                    size_t out = 0;
                    for (uint32_t z = 0; z < size_z; ++z) {
                        const double wz = static_cast<double>(wrapped_coordinate(min_z, z, step_z));
                        for (uint32_t x = 0; x < size_x; ++x) {
                            const double wx = static_cast<double>(wrapped_coordinate(min_x, x, step_x));
                            for (uint32_t y = 0; y < size_y; ++y) {
                                const double wy = static_cast<double>(wrapped_coordinate(min_y, y, step_y));
                                values[out++] = node.opcode == JPE_DENSITY_X ? wx
                                    : node.opcode == JPE_DENSITY_Y ? wy : wz;
                            }
                        }
                    }
                    break;
                }
                case JPE_DENSITY_ADD: case JPE_DENSITY_MUL:
                case kSub: case kDiv: case kMin: case kMax: case kPow: {
                    const auto& a = slots[node_slots[node.input_a]];
                    const auto& b = slots[node_slots[node.input_b]];
                    for (size_t j = 0; j < volume; ++j) {
                        switch (node.opcode) {
                            case JPE_DENSITY_ADD: values[j] = a[j] + b[j]; break;
                            case JPE_DENSITY_MUL: values[j] = a[j] * b[j]; break;
                            case kSub: values[j] = a[j] - b[j]; break;
                            case kDiv: values[j] = a[j] / b[j]; break;
                            case kMin: values[j] = std::min(a[j], b[j]); break;
                            case kMax: values[j] = std::max(a[j], b[j]); break;
                            case kPow: values[j] = std::pow(a[j], b[j]); break;
                        }
                    }
                    break;
                }
                case kAbs: case kNegate: case kSquare: case kCube: case kSqrt:
                case kHalfNegative: case kQuarterNegative: case kReciprocal: case kSign: {
                    const auto& a = slots[node_slots[node.input_a]];
                    for (size_t j = 0; j < volume; ++j) {
                        const double v = a[j];
                        switch (node.opcode) {
                            case kAbs: values[j] = std::abs(v); break;
                            case kNegate: values[j] = -v; break;
                            case kSquare: values[j] = v * v; break;
                            case kCube: values[j] = v * v * v; break;
                            case kSqrt: values[j] = std::sqrt(v); break;
                            case kHalfNegative: values[j] = v < 0.0 ? v * 0.5 : v; break;
                            case kQuarterNegative: values[j] = v < 0.0 ? v * 0.25 : v; break;
                            case kReciprocal: values[j] = 1.0 / v; break;
                            case kSign: values[j] = (v > 0.0) - (v < 0.0); break;
                        }
                    }
                    break;
                }
                case kClamp: {
                    const auto& a = slots[node_slots[node.input_a]];
                    const double lo = decode_float(node.input_b), hi = decode_float(node.reserved);
                    for (size_t j = 0; j < volume; ++j) values[j] = std::clamp(a[j], lo, hi);
                    break;
                }
                case kPerlinStack: {
                    std::fill(perlin_scratch.begin(), perlin_scratch.end(), 0.0F);
                    const int32_t status = jpe_cpp_perlin_stack_evaluate(
                        graph->prepared_handles[i], decode_float(node.input_a),
                        decode_float(node.input_b), decode_float(node.reserved),
                        size_x, size_y, size_z, min_x, min_y, min_z,
                        step_x, step_y, step_z, perlin_scratch.data(), output_length, 0);
                    if (status != 0) return status;
                    for (size_t j = 0; j < volume; ++j) values[j] = perlin_scratch[j];
                    break;
                }
                default:
                    return 1;
            }

            const auto release_input = [&](uint32_t input) {
                if (input != graph->root && --consumers[input] == 0) free_slots.push_back(node_slots[input]);
            };
            if (node.opcode == JPE_DENSITY_ADD || node.opcode == JPE_DENSITY_MUL
                || node.opcode == kSub || node.opcode == kDiv || node.opcode == kMin
                || node.opcode == kMax || node.opcode == kPow) {
                release_input(node.input_a); release_input(node.input_b);
            } else if ((node.opcode >= kAbs && node.opcode <= kSign) || node.opcode == kClamp) {
                release_input(node.input_a);
            }
            // Nodes with no consumers are dead as soon as they finish. Recycle
            // their slot immediately instead of retaining one volume per dead node.
            if (i != graph->root && consumers[i] == 0) {
                free_slots.push_back(slot);
            }
        }

        const auto& root_values = slots[node_slots[graph->root]];
        std::copy(root_values.begin(), root_values.end(), output);
        return 0;
    } catch (...) {
        return 3;
    }
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