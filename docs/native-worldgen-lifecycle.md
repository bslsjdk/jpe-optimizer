# Minecraft 26.3 worldgen integration boundary

This note records the exact lifecycle observed in the Mojang 26.3 release and
the NeoForge-patched artifact produced by the source-audit workflow. It is an
integration design constraint, not a claim that native world generation is
already wired in.

## Verified lifecycle

The decompiled `NoiseBasedChunkGenerator.buildTerrain` implementation:

1. Clamps the generator's `NoiseSettings` to the target chunk height accessor.
2. Schedules the terrain task with `CompletableFuture.supplyAsync`.
3. Inside that task, calls `createNoiseChunk(chunk, structureManager, blender,
   randomState, noiseSettings)`.
4. `createNoiseChunk` creates a chunk-local `Beardifier` from the structure
   manager and chunk position, then constructs `NoiseChunk` with the shared
   `RandomState`, chunk-local Beardifier, generator settings, fluid picker,
   Blender, and chunk volume.
5. The task obtains `noiseChunk.volume()`, acquires the affected chunk
   sections, calls `doFill(noiseChunk, chunk)`, releases the sections, and
   only then proceeds to surface building and carvers.
6. `NoiseChunk` is closed by try-with-resources when the terrain task ends.

Consequences:

- `buildTerrain` is not a safe place to perform expensive graph discovery for
  every chunk. It is an execution boundary and runs asynchronously.
- `RandomState` is shared across chunk generation and contains seed-derived
  noise state. It is the candidate owner for world-scoped prepared graph
  handles, provided the cache identity also includes the generator/noise
  configuration and relevant registry fingerprint.
- `NoiseChunk`, `Beardifier`, `Blender`, `DensityVolume`, and the bound
  sampler context are chunk/task-scoped. Their values must remain runtime
  inputs and must not be folded into a world-static graph cache.
- The earliest safe prepare point is after the world's actual
  `RandomState`, registries, and generator settings exist, but before the
  first terrain task consumes them. The audit has not yet identified a
  constructor or event hook that proves this timing; do not install a
  speculative hook.

## Correct native boundary

The intended execution split is:

- World scope: capture and compile the static `NoiseRouter.finalDensity`
  graph once; load its persisted native plan directly on later launches.
- Chunk/task scope: bind the current `NoiseChunk` context, volume origin,
  dimensions, strides, Blender/Beardifier-dependent inputs, and cache state.
- Batch scope: evaluate a whole compatible density volume with one native
  call, not one FFM call per graph node or sample.
- Java scope: preserve Minecraft's sampler cache behavior, dynamic context,
  block-state conversion, aquifer/material rules, heightmaps, and chunk writes
  until each operation has a separately verified native equivalent.

A static graph plan must not cache the sampled density volume. A density
volume depends on chunk coordinates and chunk-local context.

## Current implementation gap

The repository has a generic `DensityGraphCapture`, versioned IR, disk cache,
and native interpreter. It does not yet have a production adapter that walks
Minecraft's actual `DensityFunction` implementations and maps their
semantics into IR. Nor is `prepareDensityGraph` called from a verified
world-scoped lifecycle hook, and the `NoiseChunk`/sampler batch boundary is
not yet redirected to the native evaluator.

The current IR supports constants, coordinates, addition, multiplication,
and experimental OPAQUE sentinels. OPAQUE nodes preserve Java-side dependency
metadata and emit NaN in the experimental native evaluator. They are useful
for discovering unsupported graph regions, but they are not semantic
implementations of Minecraft nodes.

## Next integration gates

1. Extract exact constructor and method bodies for `RandomState`,
   `NoiseChunk`, `DensityFunctionCompiler`, `SamplerContext`,
   `DensitySampler`, and `DensityVolume` from both vanilla and patched
   artifacts. The source-audit report must show nonzero bodies for each
   required target; a successful class inventory alone is insufficient.
2. Identify the world-scoped owner and a hook that runs after registries and
   `RandomState` initialization but before first terrain sampling.
3. Implement a version-pinned adapter for the actual 26.3 density node types.
   Unknown nodes must be represented as OPAQUE in experimental mode, retaining
   all dependencies and source class/parameter diagnostics.
4. Keep chunk-local inputs outside the persisted graph identity unless they
   are intentionally part of a separate runtime binding block.
5. Add an opt-in shadow execution mode at the volume boundary: run Java as
   truth, run native on the same inputs, compare every float, record the first
   mismatch and bounded diagnostics, and never write experimental output into
   the live chunk.
6. Only after parity is demonstrated should a verified native subgraph be
   allowed to supply production volume values.

## Performance acceptance

Do not report a graph-interpreter microbenchmark as a Minecraft chunk speedup.
The acceptance benchmark must use the fixed seed and saved Java baseline,
measure full chunk throughput and latency, and include density-stage time,
allocation, GC, native calls, fallback counts, and the exact commit SHA.
