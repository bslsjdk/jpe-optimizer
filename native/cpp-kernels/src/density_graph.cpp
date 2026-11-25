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

namespace {
constexpr uint32_t kMaxNodes = 65536;
constexpr uint64_t kMaxVolumeElements = 1024ULL * 1024ULL;
constexpr uint32_t kNoInput = UINT32_MAX;

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

struct ArithmeticInstruction {
    uint32_t opcode;
    uint32_t input_a;
    uint32_t input_b;
    uint32_t output;
};

struct StaticBinding {
    uint32_t node;
    float value;
};

struct InputBinding { uint32_t node; uint32_t input_index; };

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
    std::vector<ArithmeticInstruction> arithmetic;
};

void build_execution_plan(CompiledGraph& graph) {
    graph.static_values.clear();
    graph.inputs.clear();
    graph.coordinates.clear();
    graph.arithmetic.clear();
    graph.static_values.reserve(graph.nodes.size());
    graph.inputs.reserve(graph.nodes.size());
    graph.coordinates.reserve(graph.nodes.size());
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
            case JPE_DENSITY_OPAQUE:
                graph.static_values.push_back({i, std::numeric_limits<float>::quiet_NaN()});
                break;
            case JPE_DENSITY_ADD:
            case JPE_DENSITY_MUL:
            case JPE_DENSITY_SUB:
                graph.arithmetic.push_back({node.opcode, node.input_a, node.input_b, i});
                break;
            default:
                // valid_graph() runs before this lowering step.
                throw std::invalid_argument("Cannot lower an unknown density opcode");
        }
    }
}

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
        switch (node.opcode) {
            case JPE_DENSITY_CONST:
            case JPE_DENSITY_X:
            case JPE_DENSITY_Y:
            case JPE_DENSITY_Z:
                if (node.input_a != kNoInput || node.input_b != kNoInput || node.reserved != 0) return false;
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
                if (node.reserved != 0 || node.input_a >= i || node.input_b >= i) return false;
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

extern "C" uint64_t jpe_cpp_density_graph_compile(
    const JpeDensityNode* nodes, uint32_t node_count, uint32_t root_node,
    uint32_t ir_version, uint64_t configuration_fingerprint
) try {
    if (ir_version != 5 || !valid_graph(nodes, node_count, root_node)) return 0;
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
    if (offset != payload_length || ir_version != 5 || !valid_graph(nodes.data(), count, root)) return 0;
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
    if (volume_length == 0
        || static_cast<uint64_t>(input_count) > 16777216ULL / volume_length) return 1;

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
        || static_cast<uint64_t>(selected_roots.size()) > 16777216ULL / volume_length
        || output_length != volume_length * selected_roots.size()) return 1;

    // Evaluate a bounded tile of samples at once. Node-major storage lets the
    // compiler vectorize each ADD/MUL across contiguous samples while preserving
    // the topological order (and therefore every float32 rounding point).
    constexpr uint32_t kMaxTileSamples = 32;
    constexpr size_t kMaxTileValues = 262144; // <= 1 MiB of float scratch.
    const size_t node_count = graph->nodes.size();
    const uint32_t tile_capacity = static_cast<uint32_t>(
        std::max<size_t>(1, std::min<size_t>(kMaxTileSamples, kMaxTileValues / node_count)));
    // The FFM call is synchronous and this evaluator never calls back into Java,
    // so one bounded scratch vector can be safely reused by each worldgen worker.
    // Capacity is capped at 1 MiB and retained per native worker thread.
    thread_local std::vector<float> tile_scratch;
    tile_scratch.resize(node_count * tile_capacity);
    std::vector<float>& tile_values = tile_scratch;
    std::array<float, kMaxTileSamples> x_values{};
    std::array<float, kMaxTileSamples> y_values{};
    std::array<float, kMaxTileSamples> z_values{};
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

    for (uint64_t tile_start = 0; tile_start < output_length; tile_start += tile_capacity) {
        const uint32_t count = static_cast<uint32_t>(
            std::min<uint64_t>(tile_capacity, output_length - tile_start));

        const uint32_t z_start = static_cast<uint32_t>(tile_start / plane_size);
        const uint64_t within_plane = tile_start - static_cast<uint64_t>(z_start) * plane_size;
        uint32_t x_index = static_cast<uint32_t>(within_plane / size_y);
        uint32_t y_index = static_cast<uint32_t>(within_plane - static_cast<uint64_t>(x_index) * size_y);
        uint32_t z_index = z_start;

        for (uint32_t sample = 0; sample < count; ++sample) {
            x_values[sample] = static_cast<float>(wrapped_coordinate(min_x, x_index, step_x));
            y_values[sample] = static_cast<float>(wrapped_coordinate(min_y, y_index, step_y));
            z_values[sample] = static_cast<float>(wrapped_coordinate(min_z, z_index, step_z));
            if (++y_index == size_y) {
                y_index = 0;
                if (++x_index == size_x) {
                    x_index = 0;
                    ++z_index;
                }
            }
        }

        for (const InputBinding& binding : graph->inputs) {
            const float* source = inputs + static_cast<uint64_t>(binding.input_index) * output_length + tile_start;
            float* destination = tile_values.data() + static_cast<size_t>(binding.node) * tile_capacity;
            std::copy_n(source, count, destination);
        }

        for (const CoordinateBinding& binding : graph->coordinates) {
            float* destination = tile_values.data() + static_cast<size_t>(binding.node) * tile_capacity;
            const float* source = binding.axis == 0 ? x_values.data()
                : binding.axis == 1 ? y_values.data() : z_values.data();
            std::copy_n(source, count, destination);
        }

        for (const ArithmeticInstruction& instruction : graph->arithmetic) {
            const float* left = tile_values.data()
                + static_cast<size_t>(instruction.input_a) * tile_capacity;
            const float* right = tile_values.data()
                + static_cast<size_t>(instruction.input_b) * tile_capacity;
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
            } else {
                for (uint32_t sample = 0; sample < count; ++sample) {
                    destination[sample] = left[sample] - right[sample];
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
                        default: return 1;
                    }
                }
                output[output_index++] = values[graph->root];
            }
        }
    }
    return 0;
} catch (...) { return 3; }

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
