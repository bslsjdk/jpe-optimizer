# Native C++ world generator

`native/cpp-world` is a complete overworld generator written in C++17 with no
third-party dependencies. It owns the whole chain from seed to block ids:

```
seed -> named noise router -> 5x5 climate grid -> column pass (height, relief, biome)
     -> 4x8x4 density grid -> trilinear upsample + AVX2 row fill -> carvers
     -> surface rules -> 16x16x384 u16 chunk
```

No Java, no JVM, no Minecraft, no resource pack is involved at any point. The
only inputs are the seed and the chunk coordinate.

The Rust side (`native/rust-world`) links it through `build.rs` behind the
`cpp-bridge` feature and consumes the chunk in the same `y << 8 | z << 4 | x`
layout its mesher already used, so the generator is a drop-in replacement for
the Java terrain source rather than a parallel world.

## Files

| Path | Lines | Contents |
| --- | --- | --- |
| `include/jpe_terrain_api.h` | 159 | Stable C ABI v1. This is the only header a host needs. |
| `src/jpe_terrain_internal.h` | 483 | `Generator`, `Scratch`, `NormalNoise`, `ImprovedNoise`, cubic spline. |
| `src/generator.cpp` | 1008 | Column / density / carver / fill / surface passes. |
| `src/terrain_api.cpp` | 340 | ABI shim, handle table, chunk thread pool. |
| `bench/bench_terrain.cpp` | 156 | Throughput, histogram, heightmap, checksum. |
| `bench/dump_chunk.cpp` | 24 | One chunk to stdout, for diffing. |

## ABI

```c
JpeTerrainConfig cfg;
jpe_terrain_default_config(&cfg);          /* seed, min_y=-64, height=384, sea=63 */
cfg.seed = 11;
uint64_t gen = jpe_terrain_create(&cfg);

uint64_t per = jpe_terrain_chunk_blocks(gen);      /* 16*16*384 = 98304 */
uint16_t *chunk = malloc(per * sizeof(uint16_t));
jpe_terrain_generate_chunk(gen, 0, 0, chunk, per);
```

Batch generation (`jpe_terrain_generate_chunks`) uses the handle's own pool and
parallelises across chunks; a handle is safe from one thread at a time. Output
depends only on `(seed, config, chunk coordinate, stages)` — no time, thread or
order dependence, no fast-math, so a chunk generated alone is bit-identical to
the same chunk in a batch of 4096. `bench_terrain` verifies this every run.

`jpe_terrain_column_info` and `jpe_terrain_heightmap` are the cheap paths for
streaming and LOD decisions; `jpe_terrain_climate` exposes the five climate
parameters for tooling.

## Noise design

Every parameter is a `NormalNoise`: an octave stack with per-octave amplitudes,
like vanilla's `NormalNoise`. Two properties matter and both were bugs first:

**Normalisation.** `value()` divides by the sum of the amplitudes, so the field
is roughly `-1..1` whatever the array holds. An un-normalised `{1,1,1,1}` stack
has a standard deviation near 1 and puts as much energy in its finest octave as
in its coarsest. The finest octave of a 4-block-sampled field aliases, and the
symptom is a height field that steps 60 blocks between adjacent columns.

**Gain.** Normalisation equalises the *shape* of a noise, not its *spread*: a
three-octave stack lands on a different standard deviation than a five-octave
one. Each parameter is scaled by `0.5 / measured_sd` over a 2048x2048 block
area, so all five climate parameters have sd 0.5 and the thresholds in
`select_biome()` mean the same thing for every seed.

| Noise | First octave | Amplitudes | Gain | Role |
| --- | --- | --- | --- | --- |
| continentalness | -9 | 1, .62, .36, .19, .09 | 4.40 | ocean / shelf / inland |
| erosion | -8 | 1, .5, .22 | 2.15 | where ranges may exist |
| temperature | -8 | 1, .5, .25 | 2.65 | biome, snow cover |
| vegetation | -7 | 1, .5, .25 | 2.75 | biome |
| weirdness | -7 | 1, .5, .22 | 2.60 | massif height, surface tilt |
| hills | -6 | 1, .5, .22, .10 | 2.60 | 128-512 block undulation |
| ridges | -6 | 1, .45, .20 | 2.60 | crest lines |
| base_3d_noise | -8 | .47 ... .019 | 1.00 | 3D surface detail |

Carver noises (`cave_cheese`, `spaghetti_3d_*`) are deliberately **not**
normalised: their thresholds are absolute and vanilla's cheese cave expects a
field with a standard deviation well above 1.

## Height model

```
h = sea + continental(c) * inland
        + hills(x,z) * (4 + 26 * relief(e))
        + w * 4
        + uplift
```

`continental(c)` is a cubic spline from -34 (deep ocean floor) to +26 (inland
plateau), relative to sea level. It is deliberately shallow: height above the
plateau is the mountains' job, and a spline that reaches +66 makes every
continent a 130-block table with nothing left for uplift to add.

`relief(e)` maps erosion to a 0.16-1.25 multiplier on the hills term.

`uplift` is built from three smooth fields:

```
mask   = smoothstep(-0.05, -0.45, e) * smoothstep(-0.30, 0.02, c)   /* where */
massif = 0.5 + 0.5 * w                                              /* how high */
crest  = smoothstep(0.35, 0.95, 1 - |ridge(x,z)|)                   /* ridged */
uplift = mask * (22 + 78 * massif) * (0.78 + 0.44 * crest)
```

Three rules came out of failed renders:

- **No `clamp()` in a blend.** A clamp leaves a crease in the height field, and
  a crease in a voxel height field is a cliff. Every blend is a `smoothstep`.
- **A range is a massif with ridges on it, not a ridge.** Using `1 - |n|` as the
  height itself gives knife-edge lines: 0.5% of the map above y=96 and nothing
  in between.
- **No saturating smoothstep on the massif.** `smoothstep(-0.3, 0.6, w)` is
  exactly 1.0 for a quarter of the world, and every saturated column gets the
  same uplift, so the top of a range comes out as a flat white mesa with a
  ruler edge. The linear map keeps `w`'s variation.

Measured over 4096x4096 blocks (seed 11): p50 = 54, p75 = 79, p90 = 117,
p95 = 147, p99 = 192, max 243; 40% of the area is below sea level.

## Density and fill

`build_columns` writes a 5x5 climate grid at 4-block steps and bilinearly
upsamples it to 17x17, so a chunk edge column equals the neighbouring chunk's
first column by construction. `build_density` samples one 3D noise per 4x8x4
cell into `d = (h + n * rough - y) / 12`, and `fill_blocks` upsamples each
density plane to 17x17 and walks the eight block rows inside the cell with a
lerp plus a sign test.

Two thirds of the column never needs a noise sample: 48 blocks under the local
surface is solid by definition and everything above `h + 1.2 * rough + 4` is
air. `Scratch::gy_limit` is derived per chunk from the tallest column and every
pass stops there, which removes about 40% of the column volume.

The inner loop (`fill_row`) has an AVX2 implementation and a scalar reference.
They are bit-identical: the translation unit is compiled with
`-ffp-contract=off -fno-fast-math`, and `JPE_TERRAIN_NO_SIMD=1` switches to the
scalar path for verification. `bench_terrain` prints a checksum, and both paths
must produce `e1bb60b999e78408` for seed 11 / 1024 chunks.

## Materials

Surface material is decided by three signals, in this order:

1. **Submerged** — sand near the shore, gravel and clay in deep or cold water.
2. **Altitude** — bare rock above `132 + 14 * noise`, snow above that plus
   30. Snow cover (the thin `SNOW_LAYER` on grass) is decided by the column's
   own temperature, not by the biome's cold flag: the flag is true for every
   peak biome, so a 180-block summit in a warm range used to get a snow layer
   at y=90 while the valley below stayed green.
3. **Slope** — a face steeper than 1.3 blocks per block is bare rock at any
   altitude, and snow does not settle on it. Without this the materials form
   horizontal bands, which on a smooth cone reads as a contour map.

Biome edges are dithered by a smooth 64-block noise on the decision inputs.
A per-column hash dither is cheaper but produces an 8-block checkerboard of two
biomes, which is more obviously wrong than the straight line it replaces.

## Performance

`g++ -O3 -mavx2 -ffp-contract=off -fno-fast-math`, seed 11, 384-block columns,
sandbox with a 4-core quota:

| Threads | Chunks | Wall | Per chunk | Chunks/s | Mblocks/s |
| --- | --- | --- | --- | --- | --- |
| 1 | 1024 | 438.6 ms | 0.43 ms | 2334 | 229.5 |
| 4 | 4096 | 407.6 ms | 0.10 ms | 10049 | 987.9 |
| 1, no SIMD | 1024 | 449.6 ms | 0.44 ms | 2278 | 223.9 |

The AVX2 row fill is worth 2.4%: the pass is dominated by noise evaluation, not
by the lerp. It is kept because it is free and because the scalar reference
gives the determinism test something to compare against.

Scaling from 1 to 4 threads is 4.3x. Beyond that the sandbox quota binds.

## Build and run

```sh
# standalone, no Rust
cd native/cpp-world
g++ -O3 -std=c++17 -mavx2 -Iinclude -Isrc bench/bench_terrain.cpp src/*.cpp -o bench_terrain
./bench_terrain 11 1024 1

# through the Rust pipeline: C++ generates, Rust meshes, GL/Vulkan draws
cd native/rust-world
cargo run --release --features cpp-bridge --example native_world -- 11 14 /tmp/world.ppm
```

`native_world` writes a 1280x720 frame and a top-down `_map.ppm`. No display is
needed: the GL backend runs on headless EGL and the Vulkan backend uses
`OffscreenOnly`.

## Known limitations

- Carvers are cheese caves plus two spaghetti noises, not vanilla's full
  `cave`/`canyon`/`aquifer` graph. Underground water is a 1-in-5 pocket per
  carved cell rather than a real aquifer.
- Structure generation (villages, ores, trees) is out of scope; the generator
  produces terrain, not features.
- Biomes are selected by a decision tree over the five climate parameters, not
  by vanilla's 60-entry nearest-point search. Shares are close to vanilla's on
  the seeds measured, but the boundaries will not line up with a vanilla world.
- The surface is a smooth analytic function sampled on a 4x8x4 grid, so long
  slopes show the 1-block terracing that any voxel terrain has. Adding surface
  detail means a finer density grid, which costs roughly linearly.
