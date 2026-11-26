#include "jpe_native_api.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdint>
#include <limits>
#include <unordered_map>
#include <vector>

namespace {
constexpr uint32_t kColumnCount = JPE_BLENDING_COLUMN_COUNT;
constexpr uint32_t kMaxDataViews = 256;
constexpr uint32_t kMaxQueries = 1U << 20;
constexpr uint32_t kMaxSections = 128;
constexpr float kNoValue = std::numeric_limits<float>::max();

struct Coord {
    int32_t x, y, z;
    bool operator==(const Coord& other) const noexcept {
        return x == other.x && y == other.y && z == other.z;
    }
};
struct CoordHash {
    size_t operator()(const Coord& p) const noexcept {
        uint64_t h = static_cast<uint32_t>(p.x);
        h = (h ^ (static_cast<uint64_t>(static_cast<uint32_t>(p.y)) + 0x9e3779b97f4a7c15ULL
                  + (h << 6) + (h >> 2)));
        h = (h ^ (static_cast<uint64_t>(static_cast<uint32_t>(p.z)) + 0x9e3779b97f4a7c15ULL
                  + (h << 6) + (h >> 2)));
        return static_cast<size_t>(h);
    }
};
uint64_t chunk_key(int32_t x, int32_t z) noexcept {
    return (static_cast<uint64_t>(static_cast<uint32_t>(x)) << 32)
        | static_cast<uint32_t>(z);
}
int32_t floor_div4(int32_t value) noexcept {
    // QuartPos.fromBlock uses arithmetic section coordinates, including negatives.
    return value >= 0 ? value / 4 : -static_cast<int32_t>((-static_cast<int64_t>(value) + 3) / 4);
}
int32_t from_section(int32_t section) noexcept {
    return static_cast<int32_t>(static_cast<uint32_t>(section) * 4U);
}
int32_t java_div8(int32_t value) noexcept { return value / 8; }

uint32_t inside_index(int32_t x, int32_t z) noexcept {
    return static_cast<uint32_t>(3 - x + z);
}
uint32_t outside_index(int32_t x, int32_t z) noexcept {
    return static_cast<uint32_t>(7 + x + 4 - z);
}
float density_at(const JpeBlendingDataView& view, int32_t cell_x,
                 int32_t cell_y, int32_t cell_z) noexcept {
    const int32_t min_y = static_cast<int32_t>(static_cast<uint32_t>(view.min_section_y) * 2U);
    if (cell_y == min_y) return 0.1F;
    const int32_t local_x = cell_x - from_section(view.chunk_x);
    const int32_t local_z = cell_z - from_section(view.chunk_z);
    uint32_t column = 0;
    if (local_x == 4 || local_z == 4) {
        if (local_x < 0 || local_x > 4 || local_z < 0 || local_z > 4) return kNoValue;
        column = outside_index(local_x, local_z);
    } else {
        if (local_x != 0 && local_z != 0) return kNoValue;
        if (local_x < 0 || local_x > 3 || local_z < 0 || local_z > 3) return kNoValue;
        column = inside_index(local_x, local_z);
    }
    if (column >= kColumnCount) return kNoValue;
    const float* values = view.density_columns[column];
    if (values == nullptr) return kNoValue;
    const int32_t column_min_y = min_y + 1;
    const int32_t y_index = cell_y - column_min_y;
    const uint32_t cell_count = view.section_count * 2U;
    if (y_index < 0 || static_cast<uint32_t>(y_index) >= cell_count) return kNoValue;
    return values[y_index] * 0.1F;
}
float fixed_density(const std::unordered_map<uint64_t, const JpeBlendingDataView*>& index,
                    int32_t cell_x, int32_t cell_y, int32_t cell_z) noexcept {
    const int32_t section_x = floor_div4(cell_x);
    const int32_t section_z = floor_div4(cell_z);
    const bool on_x_boundary = (cell_x & 3) == 0;
    const bool on_z_boundary = (cell_z & 3) == 0;
    const auto get = [&](int32_t sx, int32_t sz) noexcept {
        const auto it = index.find(chunk_key(sx, sz));
        if (it == index.end()) return kNoValue;
        return density_at(*it->second, cell_x, cell_y, cell_z);
    };
    float value = get(section_x, section_z);
    if (value != kNoValue) return value;
    if (on_x_boundary && on_z_boundary) {
        value = get(section_x - 1, section_z - 1);
        if (value != kNoValue) return value;
    }
    if (on_x_boundary) {
        value = get(section_x - 1, section_z);
        if (value != kNoValue) return value;
    }
    if (on_z_boundary) {
        value = get(section_x, section_z - 1);
        if (value != kNoValue) return value;
    }
    return kNoValue;
}

int32_t column_x(uint32_t column) noexcept {
    if (column < 7) return std::max(3 - static_cast<int32_t>(column), 0);
    const int32_t j = static_cast<int32_t>(column - 7);
    return 4 - std::max(4 - j, 0);
}
int32_t column_z(uint32_t column) noexcept {
    if (column < 7) return std::max(static_cast<int32_t>(column) - 3, 0);
    const int32_t j = static_cast<int32_t>(column - 7);
    return 4 - std::max(j - 4, 0);
}
struct DensityPoint { Coord coord; float density; };
void append_points(const JpeBlendingDataView* views, uint32_t count,
                   std::vector<DensityPoint>& points) {
    for (uint32_t v = 0; v < count; ++v) {
        const JpeBlendingDataView& view = views[v];
        const int32_t origin_x = from_section(view.chunk_x);
        const int32_t origin_z = from_section(view.chunk_z);
        const int32_t min_y = static_cast<int32_t>(static_cast<uint32_t>(view.min_section_y) * 2U);
        const int32_t column_min_y = min_y + 1;
        const uint32_t cell_count = view.section_count * 2U;
        for (uint32_t column = 0; column < kColumnCount; ++column) {
            const float* values = view.density_columns[column];
            if (values == nullptr) continue;
            const int32_t x = origin_x + column_x(column);
            const int32_t z = origin_z + column_z(column);
            for (uint32_t y = 0; y < cell_count; ++y) {
                points.push_back({{x, column_min_y + static_cast<int32_t>(y), z},
                                  values[y] * 0.1F});
            }
        }
    }
}
float blend_one(int32_t block_x, int32_t block_y, int32_t block_z, float noise,
                const std::unordered_map<uint64_t, const JpeBlendingDataView*>& height_index,
                const std::unordered_map<Coord, std::vector<uint32_t>, CoordHash>& spatial,
                const std::vector<DensityPoint>& points,
                std::vector<uint32_t>& candidates) {
    const int32_t cell_x = floor_div4(block_x);
    const int32_t cell_y = java_div8(block_y);
    const int32_t cell_z = floor_div4(block_z);
    const float fixed = fixed_density(height_index, cell_x, cell_y, cell_z);
    if (fixed != kNoValue) return fixed;

    // Vanilla iterateDensities(cellY - 1, cellY + 1) uses an exclusive upper bound.
    // The native spatial index gathers only possible radius-2 candidates, then sorts
    // their original forEach/column/Y ordinal to preserve Java's double accumulation order.
    candidates.clear();
    for (int32_t y = cell_y - 1; y < cell_y + 1; ++y) {
        for (int32_t dx = -2; dx <= 2; ++dx) {
            for (int32_t dz = -2; dz <= 2; ++dz) {
                const Coord key{cell_x + dx, y, cell_z + dz};
                const auto it = spatial.find(key);
                if (it != spatial.end()) candidates.insert(candidates.end(), it->second.begin(), it->second.end());
            }
        }
    }
    std::sort(candidates.begin(), candidates.end());
    double total_weight = 0.0;
    double weighted_density = 0.0;
    double closest_distance = std::numeric_limits<double>::infinity();
    for (uint32_t index : candidates) {
        const DensityPoint& point = points[index];
        // Minecraft calls Mth.length(float,float,float) here. Java performs
        // wrapped int subtraction, converts each delta to float32, evaluates
        // the squared length in float32, then Math.sqrt(double) and casts
        // back to float32. Only after that is the distance widened to double
        // for the inverse-fourth-power weighting and accumulation.
        const auto java_sub = [](int32_t left, int32_t right) noexcept {
            const uint32_t bits = static_cast<uint32_t>(left) - static_cast<uint32_t>(right);
            int32_t result;
            std::memcpy(&result, &bits, sizeof(result));
            return result;
        };
        const float dx = static_cast<float>(java_sub(cell_x, point.coord.x));
        const float dy = static_cast<float>(java_sub(cell_y, point.coord.y) * 2);
        const float dz = static_cast<float>(java_sub(cell_z, point.coord.z));
        const float squared = (dx * dx + dy * dy) + dz * dz;
        const float distance_f = static_cast<float>(std::sqrt(static_cast<double>(squared)));
        const double distance = static_cast<double>(distance_f);
        if (distance > 2.0) continue;
        if (distance < closest_distance) closest_distance = distance;
        const double weight = 1.0 / (distance * distance * distance * distance);
        weighted_density += static_cast<double>(point.density) * weight;
        total_weight += weight;
    }
    if (closest_distance == std::numeric_limits<double>::infinity()) return noise;
    const double average = weighted_density / total_weight;
    const double alpha = std::clamp(closest_distance / 3.0, 0.0, 1.0);
    return static_cast<float>(average + alpha * (static_cast<double>(noise) - average));
}
} // namespace

static int32_t wrapped_coordinate(int32_t minimum, uint32_t index, int32_t step) noexcept {
    const uint32_t bits = static_cast<uint32_t>(minimum)
        + index * static_cast<uint32_t>(step);
    int32_t result;
    std::memcpy(&result, &bits, sizeof(result));
    return result;
}

extern "C" int32_t jpe_cpp_blend_density_volume(
    const JpeBlendingDataView* height_data, uint32_t height_data_count,
    const JpeBlendingDataView* density_data, uint32_t density_data_count,
    uint32_t size_x, uint32_t size_y, uint32_t size_z,
    int32_t min_x, int32_t min_y, int32_t min_z,
    int32_t step_x, int32_t step_y, int32_t step_z,
    const float* noise_values, float* outputs, uint64_t output_count
) noexcept {
    if (height_data_count > kMaxDataViews || density_data_count > kMaxDataViews
        || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0) return 1;
    const uint64_t xy = static_cast<uint64_t>(size_x) * size_y;
    if (xy > kMaxQueries / size_z) return 1;
    const uint64_t volume_count = xy * size_z;
    if (volume_count != output_count || volume_count > kMaxQueries
        || (height_data_count > 0 && height_data == nullptr)
        || (density_data_count > 0 && density_data == nullptr)
        || (volume_count > 0 && (noise_values == nullptr || outputs == nullptr))) return 1;
    const auto valid_views = [](const JpeBlendingDataView* views, uint32_t count) noexcept {
        for (uint32_t i = 0; i < count; ++i) {
            if (views[i].section_count == 0 || views[i].section_count > kMaxSections) return false;
        }
        return true;
    };
    if (!valid_views(height_data, height_data_count)
        || !valid_views(density_data, density_data_count)) return 1;
    try {
        std::unordered_map<uint64_t, const JpeBlendingDataView*> height_index;
        height_index.reserve(height_data_count * 2U + 1U);
        for (uint32_t i = 0; i < height_data_count; ++i) {
            height_index.emplace(chunk_key(height_data[i].chunk_x, height_data[i].chunk_z), &height_data[i]);
        }
        std::vector<DensityPoint> points;
        uint64_t estimate = 0;
        for (uint32_t i = 0; i < density_data_count; ++i) {
            estimate += static_cast<uint64_t>(density_data[i].section_count) * 2U * kColumnCount;
        }
        if (estimate > 2'000'000ULL) return 1;
        points.reserve(static_cast<size_t>(estimate));
        append_points(density_data, density_data_count, points);
        std::unordered_map<Coord, std::vector<uint32_t>, CoordHash> spatial;
        spatial.reserve(points.size() * 2U + 1U);
        for (uint32_t i = 0; i < points.size(); ++i) spatial[points[i].coord].push_back(i);
        // Reuse candidate scratch across every voxel; no per-sample heap allocation.
        std::vector<uint32_t> candidates;
        candidates.reserve(96);

        // Match DensityVolume.indexUnchecked: ((z * sizeX + x) * sizeY + y).
        // All coordinate arithmetic is performed here, with Java int wrap semantics.
        for (uint32_t z = 0; z < size_z; ++z) {
            const int32_t block_z = wrapped_coordinate(min_z, z, step_z);
            for (uint32_t x = 0; x < size_x; ++x) {
                const int32_t block_x = wrapped_coordinate(min_x, x, step_x);
                uint64_t index = (static_cast<uint64_t>(z) * size_x + x) * size_y;
                for (uint32_t y = 0; y < size_y; ++y, ++index) {
                    const int32_t block_y = wrapped_coordinate(min_y, y, step_y);
                    outputs[index] = blend_one(block_x, block_y, block_z, noise_values[index],
                                                height_index, spatial, points, candidates);
                }
            }
        }
        return 0;
    } catch (...) {
        return 2;
    }
}
