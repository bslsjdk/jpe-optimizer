# Minecraft 26.3 world-generation source audit

Status: source inspection, not an optimization result.

## Source provenance

The initial audit used a decompiled Minecraft 26.3 source snapshot at
[`0khs/minecraft_source_26.3`](https://github.com/0khs/minecraft_source_26.3/tree/74720c2890f22942b72d7d84958eff1b3f72b95b).
It is a third-party CFR decompilation, not a Mojang-published source archive. It is used as a navigation/reference aid only; this repository does not copy or redistribute Minecraft source. Before changing runtime behavior, verify signatures and control flow against the exact NeoForge 26.3.0.115 development artifacts / generated sources used by this project.

## Verified generation path

Relevant classes:

- [NoiseBasedChunkGenerator](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java)
- [NoiseChunk](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/NoiseChunk.java)
- [DensitySampler](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/densityfunction/DensitySampler.java)
- [DensityFunctionCompiler](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler.java)

For a normal noise-based chunk, `buildTerrain` schedules work on the background executor named `buildTerrain`. The task creates a `NoiseChunk`, runs `doFill`, then `buildSurface`, then `generateCarvers`.

Inside `doFill`, the generator obtains the router's `finalDensity` sampler and calls `sampleVolume(volume)` once for the full chunk density volume. It then walks the volume in Z, X, Y order, reads each float density, asks the aquifer for the block substance, writes the block, and updates the two worldgen heightmaps. The volume is 16 x world-height x 16 for the normal chunk path.

This gives us an important separation: density/noise evaluation is a bulk volume operation; block placement, aquifer decisions, and heightmap updates are a second per-voxel pass. A profiler must measure these separately.

## Existing optimizations already present in vanilla

### Compiled samplers and graph rewrites

[`RandomState`](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/RandomState.java) owns a `DensityFunctionCompiler`. The compiler memoizes compiled samplers in a concurrent map, serializes compilation with a lock, inlines registry references, deduplicates explicit cache nodes, and applies `SLICE_UNIFORM_AXES`.

This means a generic "add common-subexpression elimination" patch would be premature. Vanilla already has graph compilation, sampler memoization, explicit cache preparation, and axis-slicing rewrites. We need to find what remains expensive after those passes.

### Context-scoped caches and buffer pools

[`SamplerContext`](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/densityfunction/SamplerContext.java) keeps cache cells by cache ID. A cached full-volume result is reused only when the requested `DensityVolume` matches; point sampling can reuse the last coordinate or read from a cached volume.

[`DensityBufferPool`](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/densityfunction/DensityBufferPool.java) rounds capacities to 16 elements and reuses buffers up to a 2x size window. `RandomState` retains up to 16 pools and protects pool acquire/release with a lock. These are candidates for measurement, not proven bottlenecks.

### Noise is already bulk-evaluated

[`NoiseFunction`](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/densityfunction/generator/NoiseFunction.java) has specialized volume samplers. Unshifted noise delegates to `Noise.addToVolume`; shifted XZ and shifted XYZ paths first fill shift buffers and then evaluate noise over the volume. The XYZ path uses temporary buffers for shift Y and shift Z, while the output buffer temporarily holds shift X.

[`NoiseStack`](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/synth/NoiseStack.java) iterates its octave/layer list and delegates each layer to its noise implementation.

[`PerlinNoise.addToVolume`](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/net/minecraft/world/level/levelgen/synth/PerlinNoise.java) is already a specialized Z/X/Y loop. It hoists X/Z work outside the Y loop and reuses gradient/permutation work while the sampled Y lattice cell remains unchanged. Replacing this with a naive scalar loop would likely be a regression.

## Overworld graph facts

Relevant data:

- [overworld noise settings](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/data/minecraft/worldgen/noise_settings/overworld.json)
- [overworld final density](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/data/minecraft/worldgen/density_function/overworld/final_density.json)
- [overworld sloped cheese](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/data/minecraft/worldgen/density_function/overworld/sloped_cheese.json)
- [overworld continents](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/data/minecraft/worldgen/density_function/overworld/continents.json)
- [overworld erosion](https://github.com/0khs/minecraft_source_26.3/blob/74720c2890f22942b72d7d84958eff1b3f72b95b/data/minecraft/worldgen/density_function/overworld/erosion.json)

The overworld final density combines:
1. A blended, interpolated terrain/cave density branch.
2. The noodle cave branch through a minimum operation.
3. The beardifier contribution.

The terrain/cave branch contains range choices, including a surface-vs-underground split. The underground branch includes cave-layer noise, cave-cheese noise, entrances, spaghetti 2D/roughness, and pillars. Several shared subgraphs are explicitly cached in the registered data (for example shift X/Z, continents, erosion, ridges, sloped cheese, entrances, and pillars).

The final-density JSON uses interpolation with 4-block X/Z cells and 8-block Y cells before the final squeeze. This is a major clue: much of the expensive density work is evaluated at coarse lattice points and interpolated, not independently recomputed for every output block.

## Numeric semantics

Density values and noise outputs are floats, but coordinates and frequency/scale calculations also use doubles. The implementation mixes both types. Any optimization must preserve the exact sequence of float operations and the double-to-float conversion points. "Algebraically equivalent" is not enough. Validate output bits over fixed seeds and coordinates before any runtime enablement.

## Initial hypotheses to test (not yet proven)

1. **Shifted-noise temporary-buffer traffic.** Measure the XYZ shifted sampler's two temporary volume buffers and the shift samplers' own cost. A fused kernel may reduce memory traffic, but must preserve exact evaluation and cache semantics.
2. **Octave accumulation and buffer traffic.** Measure the per-layer writes performed by `NoiseStack.addToVolume`. A fused multi-layer kernel may reduce repeated buffer passes, but must preserve layer order and float accumulation order.
3. **Cache/pool overhead.** Measure cache hit rates, buffer acquisition/reuse, pool lock wait, retained capacity, and allocations. Do not tune the pool based on source inspection alone.
4. **Block-fill pass.** Measure `doFill` separately from final-density sampling. Aquifer and heightmap work may be a substantial fraction after density evaluation is optimized.
5. **Branch behavior.** The volume `RangeChoiceFunction` implementation evaluates the in-range branch and out-of-range branch into buffers, then selects values. This differs from its point-sampling path, which evaluates only the selected branch. This is a concrete candidate for investigating masked/region-aware evaluation, but any change must account for cache correctness and the fraction of voxels in each branch.

## Next experimental sequence

1. Build a source-derived graph inventory for the default overworld: node count by type, explicit cache nodes, shared subgraphs, axis domains, noise layers/octaves, and interpolation cells.
2. Instrument the actual sampler implementations and the `doFill` stage. Record time, call counts, sampled volume sizes, temporary buffer bytes, allocations, and cache hit/miss counts.
3. Establish vanilla baseline with fixed MC/NeoForge/Java versions, seed, world settings, and chunk coordinates. Run repeated trials and report median and p95.
4. Prototype one change at a time behind a disabled-by-default configuration flag.
5. Compare final density bits and generated chunk block-state hashes against vanilla before performance comparisons.
6. Measure sustained throughput and thermal behavior on the target device separately from cloud CI.

No speedup is claimed by this audit. It identifies the real implementation and the first measurements needed to choose a safe optimization.
