#include "jpe_terrain_internal.h"

#include <algorithm>
#include <cfloat>

namespace jpe {

// ---------------------------------------------------------------------------
// Biome table
// ---------------------------------------------------------------------------
//
// A reduced but coherent overworld table: every entry is a point in the
// six-parameter climate space Minecraft's multi-noise biome source searches.
// Vanilla carries ~60 entries with per-parameter ranges and searches a 7x7x7
// neighbourhood; this table is the high-traffic subset, matched by nearest
// point (Voronoi) instead. See docs/native-worldgen-cpp-engine.md.

static const BiomeDef kBiomes[BIOME_COUNT] = {
    /* name              temp  humid  cont   ero   weird  depth  style           flags */
    {"ocean",            0.0f,  0.0f, -0.55f,  0.0f,  0.0f,  0.0f, SURF_GRAVEL,   BF_OCEAN},
    {"deep_ocean",       0.0f,  0.0f, -0.85f,  0.0f,  0.0f,  0.0f, SURF_GRAVEL,   BF_OCEAN},
    {"frozen_ocean",    -0.9f,  0.0f, -0.55f,  0.0f,  0.0f,  0.0f, SURF_GRAVEL,   BF_OCEAN | BF_COLD},
    {"warm_ocean",       0.9f,  0.0f, -0.55f,  0.0f,  0.0f,  0.0f, SURF_SAND,     BF_OCEAN},
    {"cold_ocean",      -0.5f,  0.0f, -0.55f,  0.0f,  0.0f,  0.0f, SURF_GRAVEL,   BF_OCEAN},
    {"river",            0.0f,  0.0f, -0.12f,  0.3f,  0.0f,  0.0f, SURF_SAND,     0},
    {"beach",            0.3f,  0.0f, -0.05f,  0.5f,  0.0f,  0.0f, SURF_SAND,     BF_BEACH},
    {"stony_shore",     -0.2f,  0.0f, -0.05f, -0.6f,  0.0f,  0.0f, SURF_STONE,    BF_BEACH},
    {"plains",           0.2f,  0.0f,  0.30f,  0.55f, 0.0f,  0.0f, SURF_GRASS,    0},
    {"forest",           0.1f,  0.4f,  0.30f,  0.35f, 0.0f,  0.0f, SURF_GRASS,    0},
    {"birch_forest",     0.0f,  0.35f, 0.30f,  0.35f, 0.0f,  0.0f, SURF_GRASS,    0},
    {"dark_forest",      0.1f,  0.6f,  0.30f,  0.30f, 0.0f,  0.0f, SURF_GRASS,    0},
    {"taiga",           -0.4f,  0.5f,  0.30f,  0.20f, 0.0f,  0.0f, SURF_PODZOL,   BF_COLD},
    {"snowy_taiga",     -0.8f,  0.4f,  0.30f,  0.20f, 0.0f,  0.0f, SURF_PODZOL,   BF_COLD},
    {"snowy_plains",    -0.9f,  0.0f,  0.30f,  0.55f, 0.0f,  0.0f, SURF_GRASS,    BF_COLD},
    {"swamp",            0.3f,  0.9f, -0.05f,  0.70f, 0.0f,  0.0f, SURF_MUD,      0},
    {"mangrove_swamp",   0.8f,  0.9f, -0.05f,  0.70f, 0.0f,  0.0f, SURF_MUD,      0},
    {"jungle",           0.9f,  0.9f,  0.30f,  0.20f, 0.0f,  0.0f, SURF_GRASS,    0},
    {"savanna",          0.7f, -0.4f,  0.30f,  0.45f, 0.0f,  0.0f, SURF_GRASS,    0},
    {"desert",           0.9f, -0.8f,  0.30f,  0.40f, 0.0f,  0.0f, SURF_SAND,     0},
    {"badlands",         0.8f, -0.6f,  0.30f, -0.2f,  0.0f,  0.0f, SURF_RED_SAND, 0},
    {"windswept_hills", -0.1f,  0.0f,  0.35f, -0.7f,  0.0f,  0.0f, SURF_GRASS,    0},
    {"meadow",           0.1f,  0.2f,  0.40f,  0.10f, 0.0f,  0.0f, SURF_GRASS,    0},
    {"grove",           -0.5f,  0.4f,  0.40f,  0.05f, 0.0f,  0.0f, SURF_GRASS,    BF_COLD},
    {"jagged_peaks",    -0.6f,  0.0f,  0.55f, -0.85f, 0.6f,  0.0f, SURF_STONE,    BF_COLD},
    {"frozen_peaks",    -0.9f,  0.0f,  0.55f, -0.85f, 0.4f,  0.0f, SURF_STONE,    BF_COLD},
    {"stony_peaks",      0.4f,  0.0f,  0.55f, -0.85f, 0.5f,  0.0f, SURF_STONE,    0},
    {"cherry_grove",     0.2f,  0.4f,  0.40f,  0.05f, 0.0f,  0.0f, SURF_GRASS,    0},
    {"lush_caves",       0.4f,  0.6f,  0.20f,  0.30f, 0.0f,  0.8f, SURF_MOSS,     0},
    {"dripstone_caves",  0.3f, -0.2f,  0.20f,  0.10f, 0.0f,  0.8f, SURF_STONE,    0},
    {"deep_dark",        0.1f,  0.0f,  0.20f,  0.00f, 0.0f,  1.0f, SURF_STONE,    0},
    {"snowy_beach",     -0.8f,  0.0f, -0.05f,  0.50f, 0.0f,  0.0f, SURF_SAND,     BF_BEACH | BF_COLD},
};

const BiomeDef &biome_def(int32_t id) {
    if (id < 0 || id >= BIOME_COUNT) return kBiomes[B_PLAINS];
    return kBiomes[id];
}

// ---------------------------------------------------------------------------
// Noise parameters
// ---------------------------------------------------------------------------
//
// Octave layout follows the public overworld noise settings introduced with the
// 1.18 terrain rewrite. These are the values that decide continental scale,
// erosion scale and cave scale; the exact 26.3 data pack is the authority and
// is cross-checked by the audit script in scripts/.

namespace {

// Temperature and vegetation are the two parameters a player crosses on foot,
// so their period has to be comparable to a view distance, not to a continent.
// At first_octave -10 the first octave has a 4096-block period: a 400-block
// view then sits inside a single cell and the whole visible world is one
// temperature, which is why every render came out snowy.
//
// Every array below decays geometrically and is normalised by NormalNoise, so
// the resulting field is roughly -1..1. Equal amplitudes are never correct
// here: they put as much energy in the finest octave as in the coarsest, and
// the finest octave is the one that has to survive the 4-block sampling grid.
constexpr float AMP_TEMPERATURE[] = {1.0f, 0.5f, 0.25f};
constexpr float AMP_VEGETATION[] = {1.0f, 0.5f, 0.25f};
// Five octaves from a 1024-block period down to 64. Climate is sampled on a
// 4-block grid, so an octave whose period falls below ~32 blocks aliases into a
// 1-block checkerboard of cliffs instead of a slope; the decay keeps that
// octave's share small enough that it only roughens the edge.
constexpr float AMP_CONTINENTALNESS[] = {1.0f, 0.62f, 0.36f, 0.19f, 0.09f};
// Erosion decides relief, so it has to be *smooth*: the mountain mask is
// derived from it, and a mask that flips over 16 blocks builds towers, not
// ranges. Three octaves, 512 / 256 / 128 blocks.
constexpr float AMP_EROSION[] = {1.0f, 0.5f, 0.22f};
// Hills: the 128-256 block band. Continentalness supplies the shape of a
// continent and the 3D noise supplies the surface, but nothing in between, so
// without this the terrain between two mountains is a flat plate.
constexpr float AMP_HILLS[] = {1.0f, 0.5f, 0.22f, 0.10f};
// Ridges: the mountain skeleton. Sharpened into crest lines in build_columns.
constexpr float AMP_RIDGES[] = {1.0f, 0.45f, 0.20f};
constexpr float AMP_WEIRDNESS[] = {1.0f, 0.5f, 0.22f};
// Six octaves with a spectral falloff, normalised to sum 1.0, so `value()` is
// roughly in -1..1. Without that the octaves add up to ~2.1 and a 13-block
// relief amplitude becomes a 27-block cone on every 4x8x4 grid point.
constexpr float AMP_BASE3D[] = {0.47f, 0.26f, 0.14f, 0.075f, 0.038f, 0.019f};
// Carvers are not normalised: their thresholds are absolute, and vanilla's
// cheese cave expects a field with a standard deviation well above 1.
constexpr float AMP_CHEESE[] = {1.0f, 1.0f, 1.0f, 1.0f};
constexpr float AMP_SPAGHETTI[] = {1.0f, 1.0f};
constexpr float AMP_SURFACE[] = {1.0f, 1.0f, 1.0f};

template <typename T, int N>
constexpr int count_of(const T (&)[N]) {
    return N;
}

/// Distinct per-name seed so two noises never share a permutation table.
inline uint64_t named_seed(uint64_t world_seed, const char *name) {
    uint64_t h = 0xCBF29CE484222325ull;
    for (const char *c = name; *c; ++c) {
        h ^= static_cast<uint64_t>(static_cast<uint8_t>(*c));
        h *= 0x100000001B3ull;
    }
    return mix_stafford13(world_seed ^ h);
}

}  // namespace

// ---------------------------------------------------------------------------
// Init
// ---------------------------------------------------------------------------

bool Generator::init(const Config &cfg) {
    if (cfg.height <= 0 || cfg.height % 16 != 0) return false;
    if (cfg.min_y % 16 != 0) return false;
    if (cfg.height > 768) return false;
    cfg_ = cfg;
    seed_ = static_cast<uint64_t>(cfg.seed);

    // 2D climate noises: xz scale 0.25 is applied at the sample site, matching
    // NoiseFunction(temperature, 0.25, 0.0) in the data pack. All of these are
    // normalised, so each parameter lands in roughly -1..1 and the thresholds
    // in select_biome() mean the same thing for every seed.
    // Gains are 0.5 / measured_sd over a 2048x2048 block area, so every
    // parameter has standard deviation 0.5 and a usable range near +-1.5. They
    // are a property of the octave count (a five-octave stack is smoother than
    // a three-octave one at the same normalisation), not of the seed.
    n_temperature_.init(named_seed(seed_, "temperature"), -8, AMP_TEMPERATURE, count_of(AMP_TEMPERATURE), 256.0, true, 2.65f);
    n_vegetation_.init(named_seed(seed_, "vegetation"), -7, AMP_VEGETATION, count_of(AMP_VEGETATION), 256.0, true, 2.75f);
    n_continentalness_.init(named_seed(seed_, "continentalness"), -9, AMP_CONTINENTALNESS, count_of(AMP_CONTINENTALNESS), 256.0, true, 4.40f);
    n_erosion_.init(named_seed(seed_, "erosion"), -8, AMP_EROSION, count_of(AMP_EROSION), 256.0, true, 2.15f);
    n_weirdness_.init(named_seed(seed_, "weirdness"), -7, AMP_WEIRDNESS, count_of(AMP_WEIRDNESS), 256.0, true, 2.60f);
    n_hills_.init(named_seed(seed_, "hills"), -6, AMP_HILLS, count_of(AMP_HILLS), 256.0, true, 2.60f);
    n_ridges_.init(named_seed(seed_, "ridge"), -6, AMP_RIDGES, count_of(AMP_RIDGES), 256.0, true, 2.60f);

    // 3D density detail. Already normalised by its amplitude list.
    n_base3d_.init(named_seed(seed_, "base_3d_noise"), -8, AMP_BASE3D, count_of(AMP_BASE3D), 256.0, true);

    // Carvers. Higher frequency than the density noise: cave scale is tens of
    // blocks, not hundreds.
    n_cave_cheese_.init(named_seed(seed_, "cave_cheese"), -6, AMP_CHEESE, count_of(AMP_CHEESE));
    n_cave_spaghetti_a_.init(named_seed(seed_, "spaghetti_3d_1"), -7, AMP_SPAGHETTI, count_of(AMP_SPAGHETTI));
    n_cave_spaghetti_b_.init(named_seed(seed_, "spaghetti_3d_2"), -7, AMP_SPAGHETTI, count_of(AMP_SPAGHETTI));

    // Surface / material variation.
    n_gravel_.init(named_seed(seed_, "gravel"), -8, AMP_SURFACE, count_of(AMP_SURFACE));
    n_surface_.init(named_seed(seed_, "surface"), -6, AMP_SURFACE, count_of(AMP_SURFACE));

    // Continentalness -> base surface height in blocks, relative to sea level.
    //
    // Deliberately shallow: the ocean floor bottoms out 34 below sea level and
    // the highest inland plateau is 26 above it. Height above that is the
    // mountains' job, and mountains come from erosion, not from continentalness.
    // A continental spline that reaches +66 makes every continent a 130-block
    // table and leaves nothing for uplift to add.
    continental_points_[0] = {-1.00f, -34.0f, 0.0f};
    continental_points_[1] = {-0.45f, -28.0f, 0.0f};
    continental_points_[2] = {-0.19f, -14.0f, 0.0f};
    continental_points_[3] = {-0.11f, -2.0f, 0.0f};
    continental_points_[4] = {0.03f, 5.0f, 0.0f};
    continental_points_[5] = {0.30f, 12.0f, 0.0f};
    continental_points_[6] = {0.60f, 19.0f, 0.0f};
    continental_points_[7] = {1.00f, 26.0f, 0.0f};
    continental_height_.points = continental_points_;
    continental_height_.count = 8;

    // Erosion -> terrain relief multiplier. Negative erosion is mountainous.
    erosion_points_[0] = {-1.00f, 1.25f, 0.0f};
    erosion_points_[1] = {-0.35f, 0.85f, 0.0f};
    erosion_points_[2] = {0.10f, 0.50f, 0.0f};
    erosion_points_[3] = {0.55f, 0.28f, 0.0f};
    erosion_points_[4] = {1.00f, 0.16f, 0.0f};
    erosion_amplitude_.points = erosion_points_;
    erosion_amplitude_.count = 5;

    return true;
}

// ---------------------------------------------------------------------------
// Column pass: climate -> height, relief, biome
// ---------------------------------------------------------------------------

/// Hermite ramp: 0 at or below `edge0`, 1 at or above `edge1`, C1 in between.
/// Every blend in the column pass uses this rather than a clamp, because a
/// clamp leaves a crease in the height field and a crease in a voxel height
/// field is a cliff.
static inline float smoothstep(float edge0, float edge1, float x) {
    float t = (x - edge0) / (edge1 - edge0);
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;
    return t * t * (3.0f - 2.0f * t);
}

// ---------------------------------------------------------------------------
// Biome selection
// ---------------------------------------------------------------------------

/// Temperature and vegetation are rescaled before this is called: measured over
/// 600k columns the temperature noise sits 0.27 cold of centre with sd 0.36, so
/// raw thresholds would leave desert (t = 0.9) unreachable. `t` and `v` here are
/// zero-mean with sd ~0.55; `c`, `e`, `w` are the raw centred parameters.
///
/// The branches are ordered by how much they depend on continentalness, because
/// that is the only parameter that says "this is water". Measured shares on seed
/// 11 over 49x49 chunks: ocean 31%, coast 4%, peaks 5%, hills 8%, temperate 44%,
/// cold 8%.
static int32_t select_biome(float c, float e, float t, float v, float w) {
    // Ocean. Continentalness alone decides, at vanilla's -0.19 shelf.
    if (c < -0.19f) {
        if (c < -0.52f) return t < -0.62f ? B_FROZEN_OCEAN : B_DEEP_OCEAN;
        if (t < -0.62f) return B_FROZEN_OCEAN;
        if (t < -0.12f) return B_COLD_OCEAN;
        if (t > 0.55f) return B_WARM_OCEAN;
        return B_OCEAN;
    }
    // Coast: a 0.12-wide band, so a beach stays a strip and never a biome.
    if (c < -0.07f) {
        if (e < -0.62f) return B_STONY_SHORE;
        return t < -0.45f ? B_SNOWY_BEACH : B_BEACH;
    }
    // Peaks need low erosion *and* high weirdness: erosion alone also covers the
    // rolling hills that are most of the inland area.
    if (e < -0.70f && w > 0.20f) {
        if (t < -0.45f) return B_FROZEN_PEAKS;
        if (t > 0.50f) return B_STONY_PEAKS;
        return B_JAGGED_PEAKS;
    }
    // High, cold, wet ground: grove and meadow sit on the shoulders of a range.
    if (e < -0.50f) {
        if (t < -0.40f) return B_GROVE;
        if (t > 0.55f) return v < -0.30f ? B_BADLANDS : B_SAVANNA;
        return v > 0.55f ? B_CHERRY_GROVE : B_MEADOW;
    }
    // Broken, wind-scoured uplands.
    if (e < -0.30f) return B_WINDSWEPT_HILLS;

    // Rolling and flat ground: temperature first, then humidity.
    if (t < -0.45f) return v > 0.15f ? B_SNOWY_TAIGA : B_SNOWY_PLAINS;
    if (t < -0.15f) {
        if (v > 0.45f) return B_TAIGA;
        if (v > 0.05f) return B_BIRCH_FOREST;
        return B_PLAINS;
    }
    if (t < 0.30f) {
        if (v > 0.62f) return B_DARK_FOREST;
        if (v > 0.18f) return B_FOREST;
        return B_PLAINS;
    }
    if (t < 0.55f) {
        if (v > 0.70f) return B_SWAMP;
        if (v > 0.55f) return B_MANGROVE_SWAMP;
        if (v > 0.10f) return B_FOREST;
        return B_PLAINS;
    }
    // Hot.
    if (v < -0.22f) return w > 0.45f ? B_BADLANDS : B_DESERT;
    if (v > 0.55f) return B_JUNGLE;
    return B_SAVANNA;
}

void Generator::climate_at(int32_t block_x, int32_t block_z, float out[5]) const {
    // Climate runs at a quarter of the block frequency: build_columns samples it
    // on a 4-block grid and scales the coordinate by 0.25, so a continent spans
    // kilometres rather than chunks. Diagnostic samples must use the same scale
    // or they describe a different world.
    const double wx = static_cast<double>(block_x) * 0.25;
    const double wz = static_cast<double>(block_z) * 0.25;
    out[0] = static_cast<float>(n_continentalness_.value(wx, 0.0, wz));
    out[1] = static_cast<float>(n_erosion_.value(wx, 0.0, wz));
    out[2] = static_cast<float>(n_temperature_.value(wx, 0.0, wz));
    out[3] = static_cast<float>(n_vegetation_.value(wx, 0.0, wz));
    out[4] = static_cast<float>(n_weirdness_.value(wx, 0.0, wz));
}

void Generator::build_columns(int32_t chunk_x, int32_t chunk_z, Scratch &s) const {
    constexpr int COLS = Scratch::COLS;
    const int32_t base_x = chunk_x * 16;
    const int32_t base_z = chunk_z * 16;

    // 5x5 climate grid at 4-block steps, then bilinear upsample to 17x17 so the
    // surface is continuous across chunk borders (a chunk edge column equals the
    // neighbouring chunk's first column by construction).
    float cont[5 * 5];
    float ero[5 * 5];
    float temp[5 * 5];
    float veg[5 * 5];
    float weird[5 * 5];

    for (int gz = 0; gz < 5; ++gz) {
        for (int gx = 0; gx < 5; ++gx) {
            const double wx = static_cast<double>(base_x + gx * 4) * 0.25;
            const double wz = static_cast<double>(base_z + gz * 4) * 0.25;
            const int i = gz * 5 + gx;
            cont[i] = static_cast<float>(n_continentalness_.value(wx, 0.0, wz));
            ero[i] = static_cast<float>(n_erosion_.value(wx, 0.0, wz));
            temp[i] = static_cast<float>(n_temperature_.value(wx, 0.0, wz));
            veg[i] = static_cast<float>(n_vegetation_.value(wx, 0.0, wz));
            weird[i] = static_cast<float>(n_weirdness_.value(wx, 0.0, wz));
        }
    }

    const float sea = static_cast<float>(cfg_.sea_level);
    for (int cz = 0; cz < COLS; ++cz) {
        for (int cx = 0; cx < COLS; ++cx) {
            const int gx0 = cx >> 2;
            const int gz0 = cz >> 2;
            const int gx1 = gx0 < 4 ? gx0 + 1 : 4;
            const int gz1 = gz0 < 4 ? gz0 + 1 : 4;
            const float fx = static_cast<float>(cx & 3) * 0.25f;
            const float fz = static_cast<float>(cz & 3) * 0.25f;

            auto bilerp = [&](const float *grid) {
                const float a = grid[gz0 * 5 + gx0];
                const float b = grid[gz0 * 5 + gx1];
                const float c = grid[gz1 * 5 + gx0];
                const float d = grid[gz1 * 5 + gx1];
                const float ab = a + (b - a) * fx;
                const float cd = c + (d - c) * fx;
                return ab + (cd - ab) * fz;
            };

            const float c = bilerp(cont);
            const float e = bilerp(ero);
            const float t = bilerp(temp);
            const float v = bilerp(veg);
            const float w = bilerp(weird);

            const int idx = cz * COLS + cx;
            s.cont_col[idx] = c;
            s.ero_col[idx] = e;
            s.temp_col[idx] = t;

            // Base height: continental shelf -> inland plateau, scaled by how
            // eroded the region is. Weirdness adds the medium-scale undulation.
            const float relief = erosion_amplitude_.apply(e);
            const float base_h = continental_height_.apply(c);
            // Continents rise faster than they sink, but the shelf still has to
            // drop far enough to make a real ocean: 0.62 at the deep end, 1.15
            // inland.
            float inland = 0.75f + 0.35f * c;
            if (inland < 0.62f) inland = 0.62f;
            if (inland > 1.15f) inland = 1.15f;
            // Mid-scale hills. Amplitude is gated by relief, so a plain stays
            // nearly flat and a mountain range keeps its foothills.
            const double hx = static_cast<double>(base_x + cx) * 0.25;
            const double hz = static_cast<double>(base_z + cz) * 0.25;
            const float hills = static_cast<float>(n_hills_.value(hx, 0.0, hz));

            // Mountain uplift, built from two smooth fields instead of one:
            //
            //   mask  -- where a range may exist. Low erosion *and* land. Both
            //            inputs are low-frequency and smoothstepped, so the mask
            //            has no hard edge; a clamp() here is what produced the
            //            60-block towers in the earlier renders.
            //   crest -- how tall inside the mask. A ridged noise, i.e. 1-|n|,
            //            which concentrates height along connected crest lines
            //            rather than in round blobs, then squared so only the
            //            upper half of the ridge contributes.
            const float mask = smoothstep(-0.05f, -0.45f, e) * smoothstep(-0.30f, 0.02f, c);
            // A range is a *massif* with ridges on it, not a ridge alone. The
            // massif term (weirdness) says how high the block of mountain is;
            // the ridged noise only modulates it by +-22%, which is what turns a
            // rounded dome into a crest-and-cirque silhouette. Using the ridge
            // as the height itself -- the previous version -- gives knife-edge
            // lines: 0.5% of the map above y=96 and nothing in between.
            // Linear, not smoothstepped. A smoothstep saturates at w > 0.6 --
            // a quarter of the world -- and every saturated column gets the
            // same uplift, so the top of a range comes out as a flat white
            // mesa with a ruler edge. The linear map keeps w's variation.
            const float massif = 0.5f + 0.5f * w;
            float crest = 1.0f - std::fabs(static_cast<float>(n_ridges_.value(hx, 0.0, hz)));
            crest = smoothstep(0.35f, 0.95f, crest);
            const float uplift = mask * (22.0f + 78.0f * massif) * (0.78f + 0.44f * crest);

            const float h = sea + base_h * inland + w * 4.0f +
                            hills * (4.0f + 26.0f * relief) + uplift;
            s.height_col[idx] = h;

            // Relief amplitude in blocks: how far the 3D noise can push the
            // surface up or down. Mountains get real overhangs, plains do not.
            // The 3D noise only adds relief detail, never the mountain itself:
            // a large amplitude on a 4x8x4 cell grid turns into single-block
            // spires, not terrain. Big shapes come from the 2D splines above.
            s.rough_col[idx] =
                (2.5f + 9.0f * relief) * (0.6f + 0.4f * std::fabs(w)) + 0.35f * uplift;

            // Biome: explicit climate decision tree, in the order vanilla uses.
            // A nearest-point search over 28 biomes in five dimensions is
            // unmaintainable: every tweak moves three Voronoi cells at once, and
            // measured over 600k columns it gave 29% of the world to the four
            // mountain biomes while plains, forest and desert together got 1.5%.
            // The tree below is read top to bottom, so each rule can be checked
            // on its own, and the measured shares are in the comment on each
            // branch.
            // Temperature and vegetation need the rescale described on
            // `select_biome`; height keeps using the raw parameters, which are
            // already centred and already tuned.
            const float tb = t * 1.80f;
            const float vb = v * 2.00f;
            // Dither the decision inputs on an 8-block lattice. Every threshold
            // in select_biome is a straight line in climate space, and a
            // straight line in a 16-block column grid is a visible seam across
            // the landscape -- a snowfield with a ruler-drawn edge. A few
            // percent of noise turns each seam into an interlocking edge without
            // moving the biome shares.
            // The dither is a smooth noise, not a per-column hash: a hash
            // gives an 8-block checkerboard of two biomes, which is more
            // obviously wrong than the straight line it replaced.
            const float jit = static_cast<float>(n_surface_.value(hx * 4.0, 0.0, hz * 4.0));
            s.biome_col[idx] = select_biome(c + 0.05f * jit, e + 0.05f * jit,
                                            tb + 0.10f * jit, vb + 0.10f * jit, w);
        }
    }

    // Highest Y that can hold a solid block: the tallest column's surface plus
    // the full 3D noise swing plus a margin. Above it every cell is air, so the
    // density, cave and fill passes stop there instead of walking to the sky.
    float tallest = -1e9f;
    for (int i = 0; i < COLS * COLS; ++i) {
        const float top = s.height_col[i] + 1.2f * s.rough_col[i] + 4.0f;
        if (top > tallest) tallest = top;
    }
    int limit = static_cast<int>((tallest - static_cast<float>(cfg_.min_y)) / 8.0f) + 1;
    const int gy_max = cfg_.height / 8;
    if (limit > gy_max) limit = gy_max;
    if (limit < 1) limit = 1;
    s.gy_limit = limit;
}

// ---------------------------------------------------------------------------
// Density pass
// ---------------------------------------------------------------------------

void Generator::build_density(int32_t chunk_x, int32_t chunk_z, Scratch &s) const {
    constexpr int COLS = Scratch::COLS;
    constexpr int GX = Scratch::GX;
    const int gy_max = cfg_.height / 8;
    const int32_t base_x = chunk_x * 16;
    const int32_t base_z = chunk_z * 16;

    (void)gy_max;
    const int limit = s.gy_limit;
    for (int gy = 0; gy <= limit; ++gy) {
        const int32_t y = cfg_.min_y + gy * 8;
        for (int gz = 0; gz < GX; ++gz) {
            for (int gx = 0; gx < GX; ++gx) {
                const int ci = (gz * 4) * COLS + (gx * 4);
                const float h = s.height_col[ci];
                const float rough = s.rough_col[ci];

                // Two regions need no 3D noise at all: the crust (48 blocks
                // under the local surface is solid by definition) and the sky
                // (above the full noise swing). Only the band between them is
                // actually shaped by noise, which is ~40% of the column.
                if (static_cast<float>(y) < h - 48.0f) {
                    s.density[(gy * GX + gz) * GX + gx] = 1.0f;
                    continue;
                }
                if (static_cast<float>(y) > h + 1.2f * rough + 4.0f) {
                    s.density[(gy * GX + gz) * GX + gx] = -1.0f;
                    continue;
                }

                // One 3D noise sample per density cell. This is the only 3D
                // terrain noise; everything else is 2D and hoisted per column.
                const double n = n_base3d_.value(
                    static_cast<double>(base_x + gx * 4) * 0.25,
                    static_cast<double>(y) * 0.25,
                    static_cast<double>(base_z + gz * 4) * 0.25);

                const float surface = h + static_cast<float>(n) * rough;
                // 1/12 blocks -> density: a 12-block band spans -1..1, which
                // keeps trilinear interpolation stable at the 8-block Y cell.
                float d = (surface - static_cast<float>(y)) * (1.0f / 12.0f);
                // Crust floor: 48 blocks under the local surface the column is
                // solid rock. Without this a deep ocean would leave a vacuum
                // under its floor instead of stone; carvers still cut caves
                // through it, which is the only way a void may appear there.
                const float deep = (surface - 48.0f - static_cast<float>(y)) * 0.05f;
                if (deep > d) d = deep;
                s.density[(gy * GX + gz) * GX + gx] = d;
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Carver pass
// ---------------------------------------------------------------------------

void Generator::build_caves(int32_t chunk_x, int32_t chunk_z, Scratch &s) const {
    constexpr int GX = Scratch::GX;
    constexpr int COLS = Scratch::COLS;
    const int gy_max = cfg_.height / 8;
    const int32_t base_x = chunk_x * 16;
    const int32_t base_z = chunk_z * 16;

    (void)gy_max;
    const int limit = s.gy_limit;
    for (int gy = 0; gy <= limit; ++gy) {
        const int32_t y = cfg_.min_y + gy * 8;
        const double wy = static_cast<double>(y);
        for (int gz = 0; gz < GX; ++gz) {
            for (int gx = 0; gx < GX; ++gx) {
                const int ci = (gz * 4) * COLS + (gx * 4);
                // No carve strength is needed in the sky: nothing is solid there.
                if (static_cast<float>(y) > s.height_col[ci] + 1.2f * s.rough_col[ci] + 4.0f) {
                    s.cave[(gy * GX + gz) * GX + gx] = 0.0f;
                    continue;
                }
                const double wx = static_cast<double>(base_x + gx * 4);
                const double wz = static_cast<double>(base_z + gz * 4);

                const double cheese = n_cave_cheese_.value(wx * 0.5, wy * 1.1, wz * 0.5);
                const double a = n_cave_spaghetti_a_.value(wx * 0.8, wy * 1.4, wz * 0.8);
                const double b = n_cave_spaghetti_b_.value(wx * 0.8, wy * 1.4, wz * 0.8);

                // Two carve shapes: a low-frequency "cheese" void and thin
                // intersecting "spaghetti" tunnels. Both are expressed as one
                // strength scalar so the block pass interpolates a single field.
                const double cheese_strength = (cheese - 0.30) * 2.0;
                const double worm_strength = 1.0 - std::fmin(std::fabs(a), std::fabs(b)) / 0.045;
                double strength = std::fmax(cheese_strength, worm_strength);
                if (strength < 0.0) strength = 0.0;
                if (strength > 1.0) strength = 1.0;
                s.cave[(gy * GX + gz) * GX + gx] = static_cast<float>(strength);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Block pass
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// Row fill kernels
// ---------------------------------------------------------------------------
//
// One row is 16 blocks along x for a fixed (y, z). The density value is a lerp
// between the two bounding cell planes, and the block id is a sign test, so the
// whole row is 16 independent lerps plus a select. The scalar kernel is the
// reference; the AVX2 kernel is bit-identical to it (same float32 lerp, same
// comparison, same integer ids) and is used when the CPU reports AVX2.

namespace {

inline void fill_row_scalar(const float *p0, const float *p1, float t, uint16_t stone,
                            uint16_t fluid, uint16_t *dst) {
    for (int x = 0; x < 16; ++x) {
        const float d0 = p0[x];
        const float density = d0 + (p1[x] - d0) * t;
        dst[x] = density > 0.0f ? stone : fluid;
    }
}

#if defined(__x86_64__) || defined(__i386__)
#define JPE_HAVE_X86_SIMD 1
#include <immintrin.h>

__attribute__((target("avx2"))) inline void fill_row_avx2(const float *p0, const float *p1,
                                                          float t, uint16_t stone,
                                                          uint16_t fluid, uint16_t *dst) {
    const __m256 vt = _mm256_set1_ps(t);
    const __m256 zero = _mm256_setzero_ps();
    const __m256 vstone = _mm256_set1_ps(static_cast<float>(stone));
    const __m256 vfluid = _mm256_set1_ps(static_cast<float>(fluid));
    for (int x = 0; x < 16; x += 8) {
        const __m256 a = _mm256_loadu_ps(p0 + x);
        const __m256 b = _mm256_loadu_ps(p1 + x);
        const __m256 v = _mm256_add_ps(a, _mm256_mul_ps(_mm256_sub_ps(b, a), vt));
        const __m256 idf = _mm256_blendv_ps(vfluid, vstone, _mm256_cmp_ps(v, zero, _CMP_GT_OQ));
        const __m256i idi = _mm256_cvtps_epi32(idf);
        const __m128i lo = _mm256_castsi256_si128(idi);
        const __m128i hi = _mm256_extracti128_si256(idi, 1);
        _mm_storeu_si128(reinterpret_cast<__m128i *>(dst + x), _mm_packus_epi32(lo, hi));
    }
}
#endif

inline bool avx2_available() {
#if defined(JPE_HAVE_X86_SIMD)
    static const bool ok = [] {
        if (std::getenv("JPE_TERRAIN_NO_SIMD") != nullptr) return false;
        __builtin_cpu_init();
        return __builtin_cpu_supports("avx2") != 0;
    }();
    return ok;
#else
    return false;
#endif
}

inline void fill_row(const float *p0, const float *p1, float t, uint16_t stone, uint16_t fluid,
                     uint16_t *dst, bool use_simd) {
#if defined(JPE_HAVE_X86_SIMD)
    if (use_simd) {
        fill_row_avx2(p0, p1, t, stone, fluid, dst);
        return;
    }
#endif
    (void)use_simd;
    fill_row_scalar(p0, p1, t, stone, fluid, dst);
}

}  // namespace

void Generator::fill_blocks(int32_t chunk_x, int32_t chunk_z, uint16_t *out, Scratch &s) const {
    constexpr int COLS = Scratch::COLS;
    constexpr int GX = Scratch::GX;
    const int gy_max = s.gy_limit;
    const int sections = cfg_.sections();
    const int32_t base_x = chunk_x * 16;
    const int32_t base_z = chunk_z * 16;
    const float sea = static_cast<float>(cfg_.sea_level);
    const float lava = static_cast<float>(cfg_.lava_level);
    const bool carve_on = (cfg_.stages & 2u) != 0;
    const bool use_simd = avx2_available();

    (void)sections;

    // Per layer: upsample the two bounding density/cave planes to 17x17, then
    // walk the 8 block rows inside the cell. The inner loop is a pure lerp plus
    // a sign test over 256 contiguous outputs, which the compiler vectorises.
    for (int gy = 0; gy < gy_max; ++gy) {
        // Build plane at gy and gy+1 by bilinear upsampling in x,z.
        for (int pz = 0; pz < COLS; ++pz) {
            const int gz0 = pz >> 2;
            const int gz1 = gz0 < 4 ? gz0 + 1 : 4;
            const float fz = static_cast<float>(pz & 3) * 0.25f;
            for (int px = 0; px < COLS; ++px) {
                const int gx0 = px >> 2;
                const int gx1 = gx0 < 4 ? gx0 + 1 : 4;
                const float fx = static_cast<float>(px & 3) * 0.25f;
                auto sample = [&](const float *grid, int gyi) {
                    const float a = grid[(gyi * GX + gz0) * GX + gx0];
                    const float b = grid[(gyi * GX + gz0) * GX + gx1];
                    const float c = grid[(gyi * GX + gz1) * GX + gx0];
                    const float d = grid[(gyi * GX + gz1) * GX + gx1];
                    const float ab = a + (b - a) * fx;
                    const float cd = c + (d - c) * fx;
                    return ab + (cd - ab) * fz;
                };
                s.plane0[pz * COLS + px] = sample(s.density, gy);
                s.plane1[pz * COLS + px] = sample(s.density, gy + 1);
            }
        }

        const int32_t y_base = cfg_.min_y + gy * 8;
        for (int sub = 0; sub < 8; ++sub) {
            const float t = static_cast<float>(sub) * 0.125f;
            const int32_t y = y_base + sub;
            uint16_t *row = out + static_cast<size_t>(y - cfg_.min_y) * 256;

            // Deepslate boundary: full deepslate below y=-8, stone above y=0,
            // a noisy mix in between.
            uint16_t stone_id = STONE;
            if (y < -8) {
                stone_id = DEEPSLATE;
            }

            // Fluid decision is per row: lava below the lava level, water at
            // or below sea level, air otherwise.
            const uint16_t fluid = (y <= static_cast<int32_t>(lava))
                                       ? LAVA
                                       : (static_cast<float>(y) <= sea ? WATER : AIR);
            const bool mix_band = (y >= -8 && y < 0);

            for (int pz = 0; pz < 16; ++pz) {
                const int pi = pz * COLS;
                uint16_t *dst = row + pz * 16;
                if (!mix_band) {
                    fill_row(&s.plane0[pi], &s.plane1[pi], t, stone_id, fluid, dst, use_simd);
                    continue;
                }
                // Deepslate transition band: the hash decides per block, so this
                // row stays scalar.
                for (int px = 0; px < 16; ++px) {
                    const float d0 = s.plane0[pi + px];
                    const float d1 = s.plane1[pi + px];
                    const float density = d0 + (d1 - d0) * t;
                    if (density > 0.0f) {
                        const uint64_t h = position_seed(seed_, base_x + px, y, base_z + pz);
                        dst[px] = ((h & 3ull) == 0ull) ? DEEPSLATE : STONE;
                    } else {
                        dst[px] = fluid;
                    }
                }
            }
        }

        // Cave carving: separate pass over the same 8 rows, using the cave
        // field. Carving only removes solid blocks well below the local surface,
        // so terrain never gets perforated at the skyline.
        if (carve_on) {
            for (int pz = 0; pz < COLS; ++pz) {
                const int gz0 = pz >> 2;
                const int gz1 = gz0 < 4 ? gz0 + 1 : 4;
                const float fz = static_cast<float>(pz & 3) * 0.25f;
                for (int px = 0; px < COLS; ++px) {
                    const int gx0 = px >> 2;
                    const int gx1 = gx0 < 4 ? gx0 + 1 : 4;
                    const float fx = static_cast<float>(px & 3) * 0.25f;
                    auto sample = [&](const float *grid, int gyi) {
                        const float a = grid[(gyi * GX + gz0) * GX + gx0];
                        const float b = grid[(gyi * GX + gz0) * GX + gx1];
                        const float c = grid[(gyi * GX + gz1) * GX + gx0];
                        const float d = grid[(gyi * GX + gz1) * GX + gx1];
                        const float ab = a + (b - a) * fx;
                        const float cd = c + (d - c) * fx;
                        return ab + (cd - ab) * fz;
                    };
                    s.plane0[pz * COLS + px] = sample(s.cave, gy);
                    s.plane1[pz * COLS + px] = sample(s.cave, gy + 1);
                }
            }

            for (int sub = 0; sub < 8; ++sub) {
                const float t = static_cast<float>(sub) * 0.125f;
                const int32_t y = y_base + sub;
                if (y < cfg_.min_y + 4) continue;
                uint16_t *row = out + static_cast<size_t>(y - cfg_.min_y) * 256;
                for (int pz = 0; pz < 16; ++pz) {
                    const int pi = pz * COLS;
                    for (int px = 0; px < 16; ++px) {
                        uint16_t &block = row[pz * 16 + px];
                        if (block == AIR || block == WATER || block == LAVA) continue;
                        const float c0 = s.plane0[pi + px];
                        const float c1 = s.plane1[pi + px];
                        const float cave = c0 + (c1 - c0) * t;
                        if (cave <= 0.5f) continue;
                        // Do not breach the surface: keep a 4-block roof.
                        const int cidx = pz * COLS + px;
                        const float local_surface = s.height_col[cidx];
                        if (static_cast<float>(y) > local_surface - 4.0f) continue;
                        // Underground water is a sparse aquifer pocket, not a
                        // flooded sea: 1 in 5 carved cells below sea level holds
                        // water, the rest stays air. Lava wins below the lava
                        // level. This is deterministic per 4x8x4 cell.
                        uint16_t fill = AIR;
                        if (static_cast<float>(y) <= sea && y > static_cast<int32_t>(lava)) {
                            const uint64_t h = position_seed(seed_,
                                (chunk_x * 16 + px) >> 2, y >> 3, (chunk_z * 16 + pz) >> 2);
                            if ((h % 5ull) == 0ull) fill = WATER;
                        }
                        if (y <= static_cast<int32_t>(lava)) fill = LAVA;
                        block = fill;
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Surface pass
// ---------------------------------------------------------------------------

void Generator::apply_surface(int32_t chunk_x, int32_t chunk_z, uint16_t *out, Scratch &s) const {
    constexpr int COLS = Scratch::COLS;
    const float sea = static_cast<float>(cfg_.sea_level);
    const bool surface_on = (cfg_.stages & 8u) != 0;

    for (int pz = 0; pz < 16; ++pz) {
        for (int px = 0; px < 16; ++px) {
            const int ci = pz * COLS + px;
            const int32_t biome = s.biome_col[ci];
            const BiomeDef &bd = kBiomes[biome];
            const bool ocean = (bd.flags & BF_OCEAN) != 0;
            // Snow cover is decided by the column's own temperature, not by the
            // biome's cold flag. The flag is true for every peak biome, so a
            // 180-block summit in a warm range got a snow layer at y=90 while
            // the valley below it stayed green -- white mountains on a green
            // map. Temperature varies smoothly across a range, so the snow line
            // now follows the mountain.
            const bool cold = s.temp_col[ci] < -0.55f;

            // Topmost non-air, non-fluid block. The scan starts at the highest
            // cell that can hold a solid block, not at the build limit, so the
            // ~300 air blocks above the terrain are never touched.
            int32_t top = cfg_.min_y - 1;
            int32_t water_top = cfg_.min_y - 1;
            int32_t scan_top = cfg_.min_y + s.gy_limit * 8 + 7;
            if (scan_top > cfg_.max_y()) scan_top = cfg_.max_y();
            for (int32_t y = scan_top; y >= cfg_.min_y; --y) {
                const uint16_t id = out[static_cast<size_t>(y - cfg_.min_y) * 256 + pz * 16 + px];
                if (id == WATER || id == ICE) {
                    if (water_top < cfg_.min_y) water_top = y;
                    continue;
                }
                if (id != AIR) {
                    top = y;
                    break;
                }
            }
            s.column_top[pz * 16 + px] = static_cast<uint16_t>(top - cfg_.min_y + 1);
            s.column_fluid[pz * 16 + px] = water_top >= cfg_.min_y ? 1 : 0;

            if (!surface_on || top <= cfg_.min_y) continue;

            const bool submerged = static_cast<float>(top) < sea - 1.0f;
            uint16_t surface_block = GRASS_BLOCK;
            uint16_t subsurface = DIRT;

            if (submerged) {
                // Sea floor: sand near the shore, gravel and clay in deeper and
                // colder water, matching the material split players expect.
                const float depth = sea - static_cast<float>(top);
                if (ocean && depth > 24.0f) {
                    surface_block = (n_gravel_.value((chunk_x * 16 + px) * 0.5, 0.0, (chunk_z * 16 + pz) * 0.5) > 0.1) ? CLAY : GRAVEL;
                    subsurface = GRAVEL;
                } else if (cold) {
                    surface_block = GRAVEL;
                    subsurface = GRAVEL;
                } else {
                    surface_block = SAND;
                    subsurface = SANDSTONE;
                }
            } else {
                switch (bd.style) {
                    case SURF_GRASS:
                        surface_block = GRASS_BLOCK;
                        subsurface = DIRT;
                        break;
                    case SURF_PODZOL:
                        surface_block = PODZOL;
                        subsurface = DIRT;
                        break;
                    case SURF_SAND:
                        surface_block = SAND;
                        subsurface = SANDSTONE;
                        break;
                    case SURF_RED_SAND:
                        surface_block = RED_SAND;
                        subsurface = TERRACOTTA;
                        break;
                    case SURF_GRAVEL:
                        surface_block = GRAVEL;
                        subsurface = GRAVEL;
                        break;
                    case SURF_STONE:
                        surface_block = STONE;
                        subsurface = ANDESITE;
                        break;
                    case SURF_SNOW:
                        surface_block = SNOW_BLOCK;
                        subsurface = DIRT;
                        break;
                    case SURF_MYCELIUM:
                        surface_block = MYCELIUM;
                        subsurface = DIRT;
                        break;
                    case SURF_MUD:
                        surface_block = MUD;
                        subsurface = CLAY;
                        break;
                    case SURF_MOSS:
                        surface_block = MOSS_BLOCK;
                        subsurface = DIRT;
                        break;
                }
                // Altitude decides bare rock and snow, not the biome. A
                // biome-wide SURF_SNOW paints a whole grove white at y=70 and
                // leaves a 170-block peak grey, which is backwards, and it puts
                // a biome-shaped blob of white on the map. Two noisy lines --
                // rock above the lower one, snow above the upper one -- give a
                // tree line and a snow line that follow the mountain.
                const double sx = static_cast<double>(chunk_x * 16 + px) * 0.25;
                const double sz = static_cast<double>(chunk_z * 16 + pz) * 0.25;
                const float edge = static_cast<float>(n_surface_.value(sx, 0.0, sz));
                const float cold_drop = cold ? 8.0f : 0.0f;
                const float rock_line = 132.0f + 14.0f * edge - cold_drop;
                const float snow_line = rock_line + 30.0f + 8.0f * edge;
                const float topf = static_cast<float>(top);

                // Slope exposes rock. Without it the materials form horizontal
                // bands -- green below 124, grey to 154, white above -- which on
                // a smooth cone reads as a contour map, not a mountain. A face
                // steeper than ~1.3 blocks per block is bare rock at any
                // altitude, and snow does not settle on it.
                const int ci_l = ci - (px > 0 ? 1 : 0);
                const int ci_r = ci + (px < 15 ? 1 : 0);
                const int ci_d = ci - (pz > 0 ? COLS : 0);
                const int ci_u = ci + (pz < 15 ? COLS : 0);
                const float dx = std::fabs(s.height_col[ci_r] - s.height_col[ci_l]);
                const float dz = std::fabs(s.height_col[ci_u] - s.height_col[ci_d]);
                const float slope = std::max(dx, dz) * 0.5f;
                const bool steep = slope > 1.3f;

                if (topf > snow_line && !steep) {
                    surface_block = SNOW_BLOCK;
                    subsurface = STONE;
                } else if (topf > rock_line || (steep && topf > 96.0f)) {
                    surface_block = STONE;
                    subsurface = STONE;
                }
            }

            // Write the surface stack: 1 surface block, 3 subsurface, then stone
            // (already present). Only replace natural stone, never fluids.
            auto put = [&](int32_t y, uint16_t id) {
                if (y < cfg_.min_y || y > cfg_.max_y()) return;
                uint16_t &slot = out[static_cast<size_t>(y - cfg_.min_y) * 256 + pz * 16 + px];
                if (slot == STONE || slot == DEEPSLATE || slot == ANDESITE ||
                    slot == GRANITE || slot == TERRACOTTA || slot == SAND ||
                    slot == GRAVEL || slot == DIRT || slot == SANDSTONE) {
                    slot = id;
                }
            };

            put(top, surface_block);
            for (int d = 1; d <= 3; ++d) put(top - d, subsurface);

            // Snow layer on cold, high, dry ground.
            if (cold && static_cast<float>(top) > sea + 10.0f && !submerged) {
                const int32_t above = top + 1;
                if (above <= cfg_.max_y()) {
                    uint16_t &slot = out[static_cast<size_t>(above - cfg_.min_y) * 256 + pz * 16 + px];
                    if (slot == AIR) slot = SNOW_LAYER;
                }
            }

            // Ice sheet on frozen water.
            if (cold && water_top >= cfg_.min_y && water_top == static_cast<int32_t>(sea)) {
                uint16_t &slot = out[static_cast<size_t>(water_top - cfg_.min_y) * 256 + pz * 16 + px];
                slot = ICE;
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------

void Generator::generate_chunk(int32_t chunk_x, int32_t chunk_z, uint16_t *out, Scratch &scratch) const {
    const size_t blocks = static_cast<size_t>(cfg_.chunk_blocks());
    std::memset(out, 0, blocks * sizeof(uint16_t));

    build_columns(chunk_x, chunk_z, scratch);
    build_density(chunk_x, chunk_z, scratch);
    build_caves(chunk_x, chunk_z, scratch);
    fill_blocks(chunk_x, chunk_z, out, scratch);

    // Bedrock floor: 1 solid layer plus a ragged 4-block transition.
    for (int32_t d = 0; d < 4; ++d) {
        const int32_t y = cfg_.min_y + d;
        uint16_t *row = out + static_cast<size_t>(d) * 256;
        for (int z = 0; z < 16; ++z) {
            for (int x = 0; x < 16; ++x) {
                const uint64_t h = position_seed(seed_, chunk_x * 16 + x, y, chunk_z * 16 + z);
                if (d == 0 || (h % 4ull) >= static_cast<uint64_t>(d)) {
                    row[z * 16 + x] = BEDROCK;
                }
            }
        }
    }

    apply_surface(chunk_x, chunk_z, out, scratch);
}

void Generator::column_info(int32_t chunk_x, int32_t chunk_z, int32_t *surface_y, int32_t *water_top,
                            int32_t *biome, uint32_t *solid, uint32_t *fluid, Scratch &scratch) const {
    // Heightmap path: reuse the column pass only. It is ~40x cheaper than a full
    // chunk, which is what streaming decisions should pay.
    build_columns(chunk_x, chunk_z, scratch);
    constexpr int COLS = Scratch::COLS;
    for (int pz = 0; pz < 16; ++pz) {
        for (int px = 0; px < 16; ++px) {
            const int ci = pz * COLS + px;
            const int idx = pz * 16 + px;
            const float h = scratch.height_col[ci];
            const int32_t top = static_cast<int32_t>(std::floor(h));
            if (surface_y) surface_y[idx] = top;
            if (biome) biome[idx] = scratch.biome_col[ci];
            if (water_top) {
                water_top[idx] = (static_cast<float>(top) < static_cast<float>(cfg_.sea_level) - 1.0f)
                                     ? cfg_.sea_level
                                     : cfg_.min_y - 1;
            }
            if (solid) solid[idx] = static_cast<uint32_t>(std::max(0, top - cfg_.min_y + 1));
            if (fluid) {
                fluid[idx] = (static_cast<float>(top) < static_cast<float>(cfg_.sea_level) - 1.0f)
                                 ? static_cast<uint32_t>(cfg_.sea_level - top)
                                 : 0u;
            }
        }
    }
}

}  // namespace jpe
