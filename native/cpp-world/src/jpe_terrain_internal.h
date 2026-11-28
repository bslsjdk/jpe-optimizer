#ifndef JPE_TERRAIN_INTERNAL_H
#define JPE_TERRAIN_INTERNAL_H

/*
 * Internals of the native terrain generator. Not part of the ABI.
 *
 * Everything here is float/double deterministic: no fast-math, no FMA
 * contraction, no random device state. Noise internals are double like
 * Minecraft's, density and block decisions are float32 like Minecraft's.
 */

#include <cstdint>
#include <cstring>
#include <cmath>

namespace jpe {

// ---------------------------------------------------------------------------
// Block and biome palette
// ---------------------------------------------------------------------------

enum BlockId : uint16_t {
    AIR = 0,
    STONE = 1,
    GRASS_BLOCK = 2,
    DIRT = 3,
    COARSE_DIRT = 4,
    PODZOL = 5,
    SAND = 6,
    RED_SAND = 7,
    SANDSTONE = 8,
    GRAVEL = 9,
    CLAY = 10,
    WATER = 11,
    LAVA = 12,
    BEDROCK = 13,
    DEEPSLATE = 14,
    SNOW_BLOCK = 15,
    ICE = 16,
    TERRACOTTA = 17,
    ANDESITE = 18,
    GRANITE = 19,
    CALCITE = 20,
    MOSS_BLOCK = 21,
    MUD = 22,
    PACKED_ICE = 23,
    MYCELIUM = 24,
    TUFF = 25,
    DRIPSTONE_BLOCK = 26,
    SNOW_LAYER = 27,
    BLOCK_COUNT = 28
};

enum BiomeId : int32_t {
    B_OCEAN = 0,
    B_DEEP_OCEAN,
    B_FROZEN_OCEAN,
    B_WARM_OCEAN,
    B_COLD_OCEAN,
    B_RIVER,
    B_BEACH,
    B_STONY_SHORE,
    B_PLAINS,
    B_FOREST,
    B_BIRCH_FOREST,
    B_DARK_FOREST,
    B_TAIGA,
    B_SNOWY_TAIGA,
    B_SNOWY_PLAINS,
    B_SWAMP,
    B_MANGROVE_SWAMP,
    B_JUNGLE,
    B_SAVANNA,
    B_DESERT,
    B_BADLANDS,
    B_WINDSWEPT_HILLS,
    B_MEADOW,
    B_GROVE,
    B_JAGGED_PEAKS,
    B_FROZEN_PEAKS,
    B_STONY_PEAKS,
    B_CHERRY_GROVE,
    B_LUSH_CAVES,
    B_DRIPSTONE_CAVES,
    B_DEEP_DARK,
    B_SNOWY_BEACH,
    BIOME_COUNT
};

/// Surface style a biome resolves to. Keeps the surface pass branch-light.
enum SurfaceStyle : uint8_t {
    SURF_GRASS,       // grass over dirt
    SURF_PODZOL,      // podzol over dirt (taiga / old growth)
    SURF_SAND,        // sand over sandstone
    SURF_RED_SAND,    // red sand over terracotta (badlands)
    SURF_GRAVEL,      // gravel shore / cold ocean floor
    SURF_STONE,       // bare stone (peaks, windswept)
    SURF_SNOW,        // snow over dirt / stone
    SURF_MYCELIUM,
    SURF_MUD,         // mangrove
    SURF_MOSS         // lush caves surface
};

struct BiomeDef {
    const char *name;
    float temperature;      // -1 cold .. 1 hot
    float humidity;         // -1 dry .. 1 wet
    float continentalness;  // -1 ocean .. 1 inland
    float erosion;          // -1 mountainous .. 1 flat
    float weirdness;        // -1 .. 1 (peaks/valleys)
    float depth;            // -1 surface .. 1 deep underground
    uint8_t style;
    uint8_t flags;          // bit0: cold (snow/ice), bit1: ocean, bit2: beach
};

enum BiomeFlags : uint8_t {
    BF_COLD = 1u << 0,
    BF_OCEAN = 1u << 1,
    BF_BEACH = 1u << 2
};

const BiomeDef &biome_def(int32_t id);

// ---------------------------------------------------------------------------
// Hashing and RNG
// ---------------------------------------------------------------------------

inline uint64_t mix_stafford13(uint64_t z) {
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

inline uint64_t splitmix64(uint64_t &state) {
    state += 0x9E3779B97F4A7C15ull;
    return mix_stafford13(state);
}

/// Minecraft's Mth.getSeed: mixes block coordinates with the world seed.
inline uint64_t position_seed(uint64_t world_seed, int32_t x, int32_t y, int32_t z) {
    uint64_t s = world_seed;
    s = s * 6364136223846793005ull + 1442695040888963407ull;
    s += static_cast<uint64_t>(static_cast<int64_t>(x) * 3129871ll) ^ (static_cast<uint64_t>(static_cast<int64_t>(z)) * 116129781ll + static_cast<uint64_t>(y));
    s = s * s * 42317861ull + s * 11ull;
    return s >> 16;
}

/// Xoroshiro128++ as used by Minecraft's positional random factories.
struct Xoroshiro {
    uint64_t s0 = 0, s1 = 0;

    static Xoroshiro from_seed(uint64_t seed) {
        uint64_t sm = seed;
        Xoroshiro r;
        r.s0 = splitmix64(sm);
        r.s1 = splitmix64(sm);
        if (r.s0 == 0 && r.s1 == 0) r.s0 = 1;
        return r;
    }

    static uint64_t rotl(uint64_t x, int k) { return (x << k) | (x >> (64 - k)); }

    uint64_t next() {
        const uint64_t result = rotl(s0 + s1, 17) + s0;
        s1 ^= s0;
        s0 = rotl(s0, 49) ^ s1 ^ (s1 << 21);
        s1 = rotl(s1, 28);
        return result;
    }

    /// Minecraft's nextDouble: 53 significant bits.
    double next_double() {
        return static_cast<double>(next() >> 11) * (1.0 / 9007199254740992.0);
    }

    /// Uniform in [0, bound) by rejection on 31 bits (java.util.Random's
    /// algorithm). Bias-free and branch-light for the bounds used here (<= 256).
    int32_t next_int(int32_t bound) {
        if (bound <= 0) return 0;
        const uint32_t b = static_cast<uint32_t>(bound);
        uint32_t bits = static_cast<uint32_t>(next() >> 33);
        uint32_t value = bits % b;
        while (bits - value + (b - 1) > 0x7FFFFFFFu) {
            bits = static_cast<uint32_t>(next() >> 33);
            value = bits % b;
        }
        return static_cast<int32_t>(value);
    }

    /// Uniform in [0, bound).
    int32_t next_int_inclusive(int32_t min, int32_t max) {
        return min + next_int(max - min + 1);
    }
};

// ---------------------------------------------------------------------------
// Perlin noise
// ---------------------------------------------------------------------------

inline int32_t ifloor(double v) {
    const int32_t i = static_cast<int32_t>(v);
    return (v < static_cast<double>(i)) ? i - 1 : i;
}

inline double fade(double t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }

inline double grad_dot(int32_t hash, double x, double y, double z) {
    static constexpr int8_t GX[16] = {1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0};
    static constexpr int8_t GY[16] = {1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, 0, -1, 0};
    static constexpr int8_t GZ[16] = {0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1};
    const int32_t i = hash & 15;
    return static_cast<double>(GX[i]) * x + static_cast<double>(GY[i]) * y +
           static_cast<double>(GZ[i]) * z;
}

/// One octave of Perlin noise, shaped exactly like Minecraft's ImprovedNoise:
/// a 256-entry permutation, gradients repeating on a 16^3 lattice, and a fixed
/// per-layer origin offset so octaves do not correlate.
struct ImprovedNoise {
    uint8_t p[256];
    double xo = 0.0, yo = 0.0, zo = 0.0;

    void init(Xoroshiro &rng, double offset_scale) {
        for (int i = 0; i < 256; ++i) p[i] = static_cast<uint8_t>(i);
        for (int i = 0; i < 256; ++i) {
            const int j = i + rng.next_int(256 - i);
            const uint8_t t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        xo = rng.next_double() * offset_scale;
        yo = rng.next_double() * offset_scale;
        zo = rng.next_double() * offset_scale;
    }

    double noise(double x, double y, double z) const {
        const double d = x + xo;
        const double e = y + yo;
        const double f = z + zo;
        const int32_t i = ifloor(d);
        const int32_t j = ifloor(e);
        const int32_t k = ifloor(f);
        const double g = d - static_cast<double>(i);
        const double h = e - static_cast<double>(j);
        const double l = f - static_cast<double>(k);

        const int32_t i0 = i & 15;
        const int32_t j0 = j & 15;
        const int32_t k0 = k & 15;

        const int32_t A = static_cast<int32_t>(p[i0]) + j0;
        const int32_t B = static_cast<int32_t>(p[i0 + 1]) + j0;
        const int32_t AA = static_cast<int32_t>(p[A & 255]) + k0;
        const int32_t AB = static_cast<int32_t>(p[(A + 1) & 255]) + k0;
        const int32_t BA = static_cast<int32_t>(p[B & 255]) + k0;
        const int32_t BB = static_cast<int32_t>(p[(B + 1) & 255]) + k0;

        const int32_t h000 = p[AA & 255];
        const int32_t h100 = p[BA & 255];
        const int32_t h001 = p[AB & 255];
        const int32_t h101 = p[BB & 255];
        const int32_t h010 = p[(AA + 1) & 255];
        const int32_t h110 = p[(BA + 1) & 255];
        const int32_t h011 = p[(AB + 1) & 255];
        const int32_t h111 = p[(BB + 1) & 255];

        const double u = fade(g);
        const double v = fade(h);
        const double w = fade(l);

        const double x0 = lerp(u, grad_dot(h000, g, h, l), grad_dot(h100, g - 1.0, h, l));
        const double x1 = lerp(u, grad_dot(h010, g, h - 1.0, l), grad_dot(h110, g - 1.0, h - 1.0, l));
        const double x2 = lerp(u, grad_dot(h001, g, h, l - 1.0), grad_dot(h101, g - 1.0, h, l - 1.0));
        const double x3 = lerp(u, grad_dot(h011, g, h - 1.0, l - 1.0), grad_dot(h111, g - 1.0, h - 1.0, l - 1.0));

        return lerp(w, lerp(v, x0, x1), lerp(v, x2, x3));
    }

    static double lerp(double t, double a, double b) { return a + t * (b - a); }
};

/// A named noise: an ordered octave stack with per-octave amplitudes, matching
/// Minecraft's NormalNoise. `first_octave` is the base frequency exponent.
struct NormalNoise {
    static constexpr int MAX_OCTAVES = 16;
    ImprovedNoise layers[MAX_OCTAVES];
    float amplitudes[MAX_OCTAVES] = {};
    int count = 0;
    int first_octave = 0;
    /// 1 / sum(|amplitudes|), or 1 when `normalize` was not requested.
    float norm = 1.0f;
    /// Output gain applied after the octave sum. Normalising gives every noise
    /// the same *shape* but not the same *spread*: a three-octave stack and a
    /// five-octave stack land on different standard deviations. The gain makes
    /// each parameter reach the range its spline and thresholds were written
    /// for, without having to re-derive those numbers per array.
    float gain = 1.0f;

    /// `params` is (first_octave, amplitudes[count]).
    ///
    /// `normalize` divides the octave sum by the sum of the amplitudes, so the
    /// result stays in roughly -1..1 whatever the array holds. Every noise that
    /// is scaled into blocks (heights, relief, climate) must use it: an
    /// un-normalised stack of `{1,1,1,1}` has a standard deviation near 1 and
    /// puts as much energy in its finest octave as in its coarsest, which turns
    /// a smooth height field into per-16-block spikes. The carvers deliberately
    /// do *not* normalise -- their thresholds are absolute.
    void init(uint64_t seed, int first_oct, const float *amps, int amp_count,
              double offset_scale = 256.0, bool normalize = false, float out_gain = 1.0f) {
        Xoroshiro rng = Xoroshiro::from_seed(seed);
        count = amp_count > MAX_OCTAVES ? MAX_OCTAVES : amp_count;
        first_octave = first_oct;
        float sum = 0.0f;
        for (int i = 0; i < count; ++i) {
            layers[i].init(rng, offset_scale);
            amplitudes[i] = amps[i];
            sum += amps[i] < 0.0f ? -amps[i] : amps[i];
        }
        norm = (normalize && sum > 0.0f) ? 1.0f / sum : 1.0f;
        gain = out_gain;
    }

    double value(double x, double y, double z) const {
        double sum = 0.0;
        double scale = ldexp(1.0, first_octave);
        for (int i = 0; i < count; ++i) {
            const float a = amplitudes[i];
            if (a != 0.0f) {
                sum += layers[i].noise(x * scale, y * scale, z * scale) * static_cast<double>(a);
            }
            scale *= 2.0;
        }
        return sum * static_cast<double>(norm * gain);
    }
};

// ---------------------------------------------------------------------------
// Cubic spline (Minecraft's CubicSplineFunction)
// ---------------------------------------------------------------------------

struct SplinePoint {
    float location;
    float value;
    float derivative;
};

/// Piecewise cubic Hermite interpolation with the same formula Minecraft uses:
/// linear interpolation plus a derivative term scaled by the segment width.
struct Spline {
    const SplinePoint *points = nullptr;
    int count = 0;

    float apply(float t) const {
        if (count == 0) return 0.0f;
        if (t <= points[0].location) return points[0].value;
        if (t >= points[count - 1].location) return points[count - 1].value;
        int lo = 0;
        int hi = count - 1;
        while (hi - lo > 1) {
            const int mid = (lo + hi) >> 1;
            if (points[mid].location <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        const SplinePoint &a = points[lo];
        const SplinePoint &b = points[hi];
        const float span = b.location - a.location;
        const float f = (t - a.location) / span;
        const float base = a.value + f * (b.value - a.value);
        const float curve = f * (1.0f - f) * ((1.0f - f) * a.derivative * span - f * b.derivative * span);
        return base + curve;
    }
};

// ---------------------------------------------------------------------------
// Generator
// ---------------------------------------------------------------------------

struct Config {
    int64_t seed = 0;
    int32_t min_y = -64;
    int32_t height = 384;
    int32_t sea_level = 63;
    int32_t lava_level = -54;
    uint32_t stages = 0x1Fu;
    uint32_t threads = 0;

    int32_t sections() const { return height / 16; }
    int32_t max_y() const { return min_y + height - 1; }
    uint64_t chunk_blocks() const {
        return static_cast<uint64_t>(16) * 16 * static_cast<uint64_t>(height);
    }
};

/// Per-thread scratch. Allocated once per worker; chunk generation allocates
/// nothing. Sized for the default 384-block column and grown on demand.
struct Scratch {
    static constexpr int GX = 5;          // 16 blocks / 4-block cells + 1
    static constexpr int GY_MAX = 97;     // up to 768 blocks / 8 + 1
    static constexpr int COLS = 17;       // 16 + 1 upsampled column grid

    float density[GX * GY_MAX * GX];
    float cave[GX * GY_MAX * GX];
    float height_col[COLS * COLS];
    float rough_col[COLS * COLS];
    float cont_col[COLS * COLS];
    float ero_col[COLS * COLS];
    float temp_col[COLS * COLS];
    int32_t biome_col[COLS * COLS];
    float plane0[COLS * COLS];
    float plane1[COLS * COLS];
    uint16_t column_top[256];
    uint8_t column_fluid[256];
    /// Highest density cell index that can still contain solid blocks, from the
    /// per-column surface estimate. Everything above it is air and is never
    /// sampled, filled or carved.
    int gy_limit = 0;
};

struct Stats {
    uint64_t chunks = 0;
    uint64_t blocks = 0;
    uint64_t density_samples = 0;
    uint64_t noise_samples = 0;
    uint64_t carve_cells = 0;
    uint64_t nanos = 0;
};

class Generator {
public:
    bool init(const Config &cfg);

    const Config &config() const { return cfg_; }

    /// Generate one chunk. `out` must hold config().chunk_blocks() u16.
    void generate_chunk(int32_t chunk_x, int32_t chunk_z, uint16_t *out, Scratch &scratch) const;

    /// The five climate parameters at a block column, in the order the biome
    /// search uses them: continentalness, erosion, temperature, vegetation,
    /// weirdness. Diagnostic only: the generator's own path bilinearly
    /// interpolates a 5x5 grid, so a direct sample can differ slightly from
    /// the value that actually selected the biome.
    void climate_at(int32_t block_x, int32_t block_z, float out[5]) const;

    /// Column summaries for one chunk (biome, surface, water).
    void column_info(int32_t chunk_x, int32_t chunk_z, int32_t *surface_y, int32_t *water_top,
                     int32_t *biome, uint32_t *solid, uint32_t *fluid, Scratch &scratch) const;

private:
    void build_columns(int32_t chunk_x, int32_t chunk_z, Scratch &scratch) const;
    void build_density(int32_t chunk_x, int32_t chunk_z, Scratch &scratch) const;
    void build_caves(int32_t chunk_x, int32_t chunk_z, Scratch &scratch) const;
    void fill_blocks(int32_t chunk_x, int32_t chunk_z, uint16_t *out, Scratch &scratch) const;
    void apply_surface(int32_t chunk_x, int32_t chunk_z, uint16_t *out, Scratch &scratch) const;

    Config cfg_{};
    uint64_t seed_ = 0;

    NormalNoise n_temperature_;
    NormalNoise n_vegetation_;
    NormalNoise n_continentalness_;
    NormalNoise n_erosion_;
    NormalNoise n_weirdness_;
    NormalNoise n_hills_;
    NormalNoise n_ridges_;
    NormalNoise n_base3d_;
    NormalNoise n_cave_cheese_;
    NormalNoise n_cave_spaghetti_a_;
    NormalNoise n_cave_spaghetti_b_;
    NormalNoise n_gravel_;
    NormalNoise n_surface_;

    Spline continental_height_{};
    Spline erosion_amplitude_{};
    SplinePoint continental_points_[8];
    SplinePoint erosion_points_[5];
};

}  // namespace jpe

#endif  // JPE_TERRAIN_INTERNAL_H
