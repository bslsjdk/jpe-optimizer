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

## Generic child discovery through rewriteChildren

The `DensityFunction` interface exposes `rewriteChildren(DfRewriteRule)`. The audited implementations call the supplied rule on their direct child functions and return the original node when the rule returns those same child objects. For example, `BinaryFunction` passes both `left` and `right`; `NoiseFunction` passes `shiftX`, `shiftY`, and `shiftZ`.

This gives the capture adapter a better discovery mechanism than reflecting over arbitrary fields:

1. Supply an identity rewrite rule that records each child and returns that exact child unchanged.
2. Traverse the discovered children iteratively with identity-based cycle/shared-node tracking.
3. Lower known node classes only when their operation and parameters have exact adapters.
4. Preserve every discovered child of unknown nodes in the experimental OPAQUE diagnostic sidecar.

This is especially useful for modded/custom density nodes: if their `rewriteChildren` implementation correctly reports dependencies, the experiment can still preserve their graph structure even when their evaluation semantics are unknown. A node that hides dependencies or violates this contract remains a known capture limitation and must be diagnosed.

## Density node layouts verified from bytecode

The audited concrete node records expose these fields:

- `BinaryFunction`: `type`, `left`, and `right`. The presence of a type enum means the adapter must inspect the exact operation type; it must not assume every binary node is ADD or MUL.
- `ClampFunction`: `input`, `min`, and `max`.
- `RangeChoiceFunction`: `input`, `minInclusive`, `maxExclusive`, `whenInRange`, and `whenOutOfRange`. This is a true data-dependent branch and cannot be lowered by recording only the branch taken during one trace.
- `NoiseFunction`: a `Holder<NormalNoise>`, `xzScale`, `yScale`, and three shift density dependencies (`shiftX/Y/Z`). The Holder resolves seed/world-specific noise state, so it cannot be replaced by a seed-independent constant in a persistent topology cache.
- `CacheFunction`: wraps an `input`; its `compileSampler` method explicitly throws if called before deduplication. Cache wrappers therefore need to preserve Minecraft's dedup/cache semantics rather than being treated as an ordinary arithmetic node.

These field layouts are confirmed from the Mojang 26.3 bytecode report. The operation bodies and enum values still require dedicated inspection before any exact lowering is claimed.

## DensityBuffer storage and copy cost

The audited `DensityBuffer` stores values in a protected final Java `float[] values`, with a separate logical `size`. Its `copyFrom` method checks the logical sizes and then invokes `System.arraycopy` over the active range. `ScopedDensityBuffer` extends this class and returns its storage to a `DensityBufferArena` when its scope closes.

This has two consequences:

1. The current FFM route's native output staging buffer followed by a Java array copy is a real avoidable transfer.
2. The backing storage is a heap array, not a direct/native buffer. FFM cannot simply treat the Java array as an ordinary native address. A JNI experiment using primitive-array access or a different buffer integration could avoid the explicit staging copy, but pinning/copy behavior is JVM-dependent and a long critical region can interfere with garbage collection. It must be benchmarked and have a safe fallback before being adopted.

A suitable experiment should write into the actual target array only while the owning `DensityBuffer` is exclusively in use, respect its logical size, and never retain a pointer after the JNI call or after a `ScopedDensityBuffer` is closed.

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
