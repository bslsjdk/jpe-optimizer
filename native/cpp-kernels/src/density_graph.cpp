#include "jpe_native_api.h"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {
constexpr uint32_t kMaxNodes = 65536;
constexpr uint64_t kMaxVolumeElements = 1024ULL * 1024ULL;
constexpr uint32_t kNoInput = UINT32_MAX;

struct CompiledGraph {
    uint64_t fingerprint = 0;
    uint64_t configuration_fingerprint = 0;
    uint32_t ir_version = 0;
    uint32_t root = 0;
    uint32_t references = 1;
    std::vector<JpeDensityNode> nodes;
};

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

bool valid_graph(const JpeDensityNode* nodes, uint32_t count, uint32_t root) noexcept {
    if (nodes == nullptr || count == 0 || count > kMaxNodes || root >= count) return false;
    for (uint32_t i = 0; i < count; ++i) {
        const JpeDensityNode& node = nodes[i];
        if (node.reserved != 0) return false;
        switch (node.opcode) {
            case JPE_DENSITY_CONST:
            case JPE_DENSITY_X:
            case JPE_DENSITY_Y:
            case JPE_DENSITY_Z:
                if (node.input_a != kNoInput || node.input_b != kNoInput) return false;
                break;
            case JPE_DENSITY_ADD:
            case JPE_DENSITY_MUL:
                if (node.input_a >= i || node.input_b >= i) return false;
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
                  uint64_t output_length) noexcept {
    if (sx == 0 || sy == 0 || sz == 0 || dx == 0 || dy == 0 || dz == 0) return false;
    const uint64_t xy = static_cast<uint64_t>(sx) * sy;
    if (xy > kMaxVolumeElements / sz) return false;
    return xy * sz == output_length && output_length <= kMaxVolumeElements;
}
} // namespace

extern "C" uint64_t jpe_runtime_density_graph_compile(
    const JpeDensityNode* nodes, uint32_t node_count, uint32_t root_node,
    uint32_t ir_version, uint64_t configuration_fingerprint
) try {
    if (ir_version != 1 || !valid_graph(nodes, node_count, root_node)) return 0;
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
    uint64_t handle = g_next_handle++;
    if (handle == 0) handle = g_next_handle++;
    g_handles.emplace(handle, std::move(graph));
    g_cache.emplace(fingerprint, handle);
    return handle;
} catch (...) { return 0; }

extern "C" int32_t jpe_runtime_density_graph_evaluate(
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

    std::vector<double> values(graph->nodes.size());
    uint64_t output_index = 0;
    for (uint32_t z = 0; z < size_z; ++z) {
        const double world_z = static_cast<double>(wrapped_coordinate(min_z, z, step_z));
        for (uint32_t x = 0; x < size_x; ++x) {
            const double world_x = static_cast<double>(wrapped_coordinate(min_x, x, step_x));
            for (uint32_t y = 0; y < size_y; ++y) {
                const double world_y = static_cast<double>(wrapped_coordinate(min_y, y, step_y));
                for (size_t n = 0; n < graph->nodes.size(); ++n) {
                    const JpeDensityNode& node = graph->nodes[n];
                    switch (node.opcode) {
                        case JPE_DENSITY_CONST: values[n] = node.immediate; break;
                        case JPE_DENSITY_X: values[n] = world_x; break;
                        case JPE_DENSITY_Y: values[n] = world_y; break;
                        case JPE_DENSITY_Z: values[n] = world_z; break;
                        case JPE_DENSITY_ADD:
                            values[n] = values[node.input_a] + values[node.input_b];
                            break;
                        case JPE_DENSITY_MUL:
                            values[n] = values[node.input_a] * values[node.input_b];
                            break;
                        default: return 1; // Defensive: compiled plans are immutable and validated.
                    }
                }
                output[output_index++] = static_cast<float>(values[graph->root]);
            }
        }
    }
    return 0;
} catch (...) { return 3; }

extern "C" int32_t jpe_runtime_density_graph_release(uint64_t graph_handle) try {
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
