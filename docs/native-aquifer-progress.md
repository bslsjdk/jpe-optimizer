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

The Rust runtime exposes those APIs. Java's `NativeRuntime` has FFM entry points for grid-center cache prefill and nearest-four search. Per-worker FFM scratch buffers are reused for nearest-four arrays.

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
- FluidStatus construction and caching remain in Java.
- The 13 surface-level samples per cell and the surface, exclusion, floodedness, spread, and lava sampler evaluations are not yet moved into the native Aquifer volume pipeline.
- Global FluidPicker behavior, barrier-noise sampling, block-state selection, update scheduling, and modded fluid-state tokens are not yet fully integrated into a native full-volume contract.
- No full Aquifer speedup, reduction in total world-generation Java allocations, or complete world-generation parity is claimed.

## Required next implementation stages

1. Compile the six Aquifer density samplers into immutable native plans during world startup and retain their handles with the same world/RandomState fingerprint discipline as density graphs.
2. Add a native arbitrary-point/batch density evaluation path so C++ can sample the 13 surface positions and six FluidStatus inputs without Java producing per-block samples.
3. Implement per-volume Aquifer context preparation in C++: location cache, per-cell FluidStatus cache, quart-surface cache, lazy barrier-noise values, and global-fluid lookup tokens.
4. Add a complete full-volume API returning parallel outputs: keep-terrain/null marker, opaque block-state token, and fluid-update flag. Preserve the exact early-exit order and Java float/double conversion behavior.
5. Connect that output to the chunk fill once per volume. Java should only map opaque tokens to BlockState and commit block/heightmap writes; it must not run per-voxel nearest-neighbor search, status generation, pressure, or density sampling.
6. Validate raw Aquifer decisions and final chunk block-state hashes against vanilla across both RNG algorithms, negative coordinates, fluid interfaces, caves, debug-fluid mode, and modded registry states. Keep strict mode fail-closed until this passes.

Do not enable a production-native Aquifer path before the above parity checks pass.
