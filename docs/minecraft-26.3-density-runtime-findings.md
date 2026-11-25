# Minecraft 26.3 density runtime findings

This note records facts verified from the Mojang vanilla and NeoForge-patched 26.3 bytecode audit. It is not a claim that the native density graph is integrated into live world generation.

## Density volume layout

The audited `DensityVolume.indexUnchecked(x, y, z)` bytecode computes:

```text
index = y + (x + z * sizeX) * sizeY
```

The naive sampler traverses axes in this order:

1. z from `0 .. sizeZ-1`
2. x from `0 .. sizeX-1`
3. y from `0 .. sizeY-1`

It obtains block coordinates through `blockZ(z)`, `blockX(x)`, and `blockY(y)`, then writes each sample at the next sequential buffer index. The native graph volume evaluator's output order `((z * sizeX + x) * sizeY + y)` matches this traversal and index formula.

The block-coordinate methods are affine mappings:

```text
blockX(i) = minBlockX + i * stepBlockX
blockY(i) = minBlockY + i * stepBlockY
blockZ(i) = minBlockZ + i * stepBlockZ
```

These values are runtime volume metadata. They must not be baked into the persistent graph topology.

## Sampler and cache boundary

The audited `DensitySampler` interface exposes:

- `sampleValue(SamplerContext, int, int, int)`
- `sampleVolume(SamplerContext, DensityBuffer, DensityVolume)`
- `bind(SamplerContext)`, which creates a `DensitySampler.Bound`

The bound record retains both the sampler and its `SamplerContext`. This is evidence that the sampler context is a runtime binding, not a graph constant.

The audited `SamplerContext.sampleVolumeCached` path behaves as follows:

- If there is no cache cell, it calls the sampler's `sampleVolume` directly.
- If the cache cell has no buffer or its cached `DensityVolume` differs, it closes the old scoped buffer when present, stores the new volume, acquires a buffer, and samples into that buffer.
- It then calls `DensityBuffer.copyFrom` from the cached buffer into the caller's output buffer.

Therefore a cache hit can still incur a complete buffer copy. Replacing the sampler's compute step with Native does not by itself remove the copy or the Java-side buffer boundary.

## NoiseChunk lifecycle and per-world state

The audited `NoiseChunk` constructor receives `RandomState`, `Beardifier`, `NoiseGeneratorSettings`, `Aquifer.FluidPicker`, `Blender`, and `DensityVolume`. It acquires a density buffer pool from `RandomState`, builds a `SamplerContext` with user fields and a buffer arena, enables caches, and asks `RandomState` for samplers bound to that context.

This confirms the split required by the native design:

- Persistent cache: immutable graph topology and compiled execution plan, keyed by graph/configuration identity and relevant engine/mod versions.
- World/runtime state: seed-derived noise state, samplers, context-bound inputs, and per-volume coordinates.
- Chunk/runtime buffers: volume dimensions, origin, steps, and temporary cache buffers.

Do not serialize a context-bound sampler or a particular chunk's coordinates as if they were reusable graph structure.

## Current limitations

- The existing native graph IR supports only constants, X/Y/Z, ADD, MUL, and experimental OPAQUE sentinels.
- OPAQUE currently evaluates to NaN; it preserves diagnostic/dependency metadata but does not reproduce unknown Minecraft semantics.
- No verified adapter yet captures real `DensityFunction` instances into this IR.
- The current FFM evaluate path copies the native output into a Java `float[]`; it is not a zero-copy write into Minecraft's `DensityBuffer`.
- No full-chunk Native speedup has been demonstrated. Existing full-chunk measurements show the current Native path is slower than the Java baseline.

## Next integration targets

1. Audit the actual `DensityFunction` interface and representative node implementations before writing adapters.
2. Inspect `DensityBuffer` and `ScopedDensityBuffer` storage to determine whether a safe bulk/direct-memory bridge is possible.
3. Capture immutable graph topology separately from `SamplerContext`, cache cells, seed-derived state, and chunk volume metadata.
4. Add a first exact adapter only after its operation semantics and inputs are verified from source/bytecode.
5. Keep unknown nodes on the experimental OPAQUE path and compare against Java; never substitute a first-observed output as a permanent constant.
