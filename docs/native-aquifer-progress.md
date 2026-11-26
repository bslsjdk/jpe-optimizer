# Native Aquifer execution status

Target runtime: Minecraft Java 26.3 with NeoForge 26.3.0.115.
Source authority: the exact Mojang 26.3 artifact and the generated NeoForge-patched artifact selected by this repository's source-audit workflow.

## Implemented native slice

The C++ runtime currently contains:

- XOROSHIRO positional offset generation with the audited Xoroshiro128++ state transition and bounded-integer rejection behavior.
- Legacy positional offset generation with the 48-bit LegacyRandomSource state transition.
- Minecraft-compatible `Mth.getSeed(x, y, z)` behavior, including intermediate int32 overflow and final 64-bit overflow.
- Batched 12-candidate nearest-four selection. It preserves the x=0..1, y=-1..1, z=0..1 visitation order, Java int32 squared-distance wrap, and later-equal-candidate insertion.
- Batched packed location-cache generation in vanilla x-fast, then z, then y order using the BlockPos long packing layout.
- Scalar Aquifer pressure/substance decision logic and a batch wrapper that validates the full batch before writing outputs.
- A per-Aquifer native `FluidStatus` cache keyed by packed `BlockPos.asLong` cell-center keys. It has fixed capacity, open-address lookup, per-cache locking, batch lookup/insert, first-writer-wins insertion semantics matching `computeIfAbsent`, and all-or-nothing capacity rejection for inserts.

The Rust runtime exposes those APIs, including native `FluidStatus` cache create/destroy/lookup/insert handles. The density-graph runtime exposes arbitrary non-grid-aligned point batches in `f64` and `f32`, plus a multi-sampler batch that resolves up to 64 immutable graph handles under one registry lock and evaluates sampler-major independent coordinate sets in one C ABI call. The current implementation deliberately reuses the complete single-point graph evaluator for each coordinate, preserving interpolation, range-choice, warped-Perlin, and numeric behavior while eliminating per-sample Java/FFM downcalls. Java's `NativeRuntime` has FFM entry points for grid-center cache prefill, nearest-four search, substance decisions, `FluidStatus` cache operations, arbitrary-point graph evaluation, and multi-sampler point batches. Per-worker FFM scratch buffers are reused for all these calls.

## Source-audit coverage

The 26.3 dual-artifact source-audit target list now includes `Aquifer` and its
`NoiseBasedAquifer` nested implementation, `RandomState`, `NoiseGeneratorSettings`,
both audited positional RNG implementations, `PositionalRandomFactory`,
`RandomSource`, and `Mth`. The audit requires the exact nested Aquifer implementation
in both source JARs, checks that `computeSubstance` exists, records Aquifer fluid-related
method bytecode lengths and SHA-256 fingerprints, and reports the field names,
descriptors, and access flags from both Mojang's vanilla artifact and the NeoForge-patched
artifact. That newly expanded audit remains subject to the latest CI result; adding
classes to the inventory is not itself proof that decompilation/parity has passed.

## Minecraft integration currently enabled only in CI

The `AquiferNoiseBasedMixin` constructor hook is guarded by
`-Djpe.nativeAquiferGridCentersEnabled=true`. It recognizes only the audited vanilla
XOROSHIRO and Legacy positional factory implementations, and writes only when every
entry in the original Aquifer location cache is still the vanilla sentinel. The native
result is first generated into a temporary array and copied into the original cache
only after the full C++ call succeeds.

The option is disabled by default for normal game launches. CI enables it for GameTest
and strict Overworld verification. The GameTest checks both RNG modes against the
actual Minecraft positional factories and checks nearest-four output against an
independent Java traversal of the same 12 candidate cells, including negative and
extreme signed-int coordinates.

## Explicitly not complete

This is not a complete native Aquifer or native chunk-fill implementation.

- The constructor still creates the Aquifer object and Java only hands the cache array and factory seed state to the bridge.
- The production per-voxel Aquifer `computeSubstance` path still runs in Java. The new FFM nearest-four method is currently a validated runtime API and GameTest target, not yet called from the production chunk-fill path.
- The FluidStatus **storage/cache infrastructure** now exists in C++, but Java has not yet been removed from producing FluidStatus values. The cache FFM API and Rust tests validate storage, first-writer-wins, missing-key lookup, and capacity behavior; the 13-surface-sample FluidStatus computation itself is not yet native.
- The arbitrary-point and multi-sampler density APIs are foundational plumbing only. The 13 surface-level sample schedule and the surface, exclusion, floodedness, spread, and lava sampler handles have not yet been bound into the Aquifer C++ context, so those values are not yet being produced by the native Aquifer path.
- Global FluidPicker behavior, barrier-noise sampling, block-state selection, update scheduling, and modded fluid-state tokens are not yet fully integrated into a native full-volume contract.
- No full Aquifer speedup, reduction in total world-generation Java allocations, or complete world-generation parity is claimed.

## Required next implementation stages

1. Compile the six Aquifer density samplers into immutable native plans during world startup and retain their handles with the same world/RandomState fingerprint discipline as density graphs.
2. Bind the new native arbitrary-point density API to the six audited Aquifer sampler plans. The current point implementation is correctness-first and evaluates one point at a time inside a single native call; optimize across points only after parity is established.
3. Implement per-volume Aquifer context preparation in C++: fill the native per-cell FluidStatus cache from native sampler evaluations, add the quart-surface cache, lazy barrier-noise values, and global-fluid lookup tokens.
4. Add a complete full-volume API returning parallel outputs: keep-terrain/null marker, opaque block-state token, and fluid-update flag. Preserve the exact early-exit order and Java float/double conversion behavior.
5. Connect that output to the chunk fill once per volume. Java should only map opaque tokens to BlockState and commit block/heightmap writes; it must not run per-voxel nearest-neighbor search, status generation, pressure, or density sampling.
6. Validate raw Aquifer decisions and final chunk block-state hashes against vanilla across both RNG algorithms, negative coordinates, fluid interfaces, caves, debug-fluid mode, and modded registry states. Keep strict mode fail-closed until this passes.

Do not enable a production-native Aquifer path before the above parity checks pass.
