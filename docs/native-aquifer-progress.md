# Native Aquifer execution status

Target runtime: Minecraft Java 26.3 with NeoForge 26.3.0.115. Source authority remains the exact Mojang 26.3 artifact and the generated NeoForge-patched artifact selected by this repository's source-audit workflow.

## Implemented native slice

The current native path includes:

- C++ positional RNG and the vanilla 12-cell nearest-four selection, preserving the audited RNG and tie-order behavior.
- Native compilation and retained handles for the six Aquifer density samplers, cached by the owning `RandomState`.
- A C++ full-volume `computeSubstance` path for the complete `DensityVolume`: coordinate derivation, positive-density/global-picker shortcuts, nearest-four selection, FluidStatus generation and cache lookup, barrier sampling, pressure decisions, state tokens, and fluid-update flags.
- The audited vanilla 13-probe FluidStatus sampler path, including quart surface sampling, surface-level cache, floodedness/exclusion sampling, negative-coordinate quantization, and lava selection.
- A Java 25 FFM bridge that calls the C++ Aquifer kernel once per chunk volume. The native result view holds the runtime read lease and worker scratch lease until Java has finished consuming its outputs.
- A world context keyed by weak `RandomState` identity. It retains the six immutable graph handles and global FluidPicker settings across chunk fills. Each chunk obtains and releases a short-lived context lease; world-context destruction is deferred until the `RandomState` is reclaimed.
- Bounded native caches for long-lived contexts: FluidStatus entries use round-robin replacement at a fixed 65,536-entry capacity, while the quart-surface cache is capped at 65,536 entries. The standalone cache API keeps its original transactional capacity-rejection behavior.
- Strict mode fails closed when the native path is unavailable or when per-voxel comparison with the vanilla Aquifer oracle finds a mismatch. Java fallback remains available only in non-strict mode.

## Current integration boundary

This is a meaningful native takeover of the repeated density/Aquifer calculations in the chunk-fill path, but it is not complete native world generation.

- The world context is currently created lazily on the first chunk fill for a `RandomState`, then reused. It is not yet prepared by the launcher during startup.
- Final-density evaluation still writes through a Java-owned `ScopedDensityBuffer`; the buffer is passed to C++ as one bulk volume input. Fusing final-density graph evaluation and Aquifer decisions into one native entry point remains a follow-up optimization.
- Java still owns the `NoiseBasedChunkGenerator.doFill` traversal and Minecraft section writes, BlockState placement, heightmaps, debug-surface handling, and post-processing marks.
- The native density-graph compiler supports an audited subset. Shifted/domain-warped graphs, non-empty blending, non-empty Beardifier, and unsupported mod-defined/context-dependent graphs are not claimed as native-supported.
- Surface Rules, carvers, structure placement, feature placement, and the broader biome/climate/world-generation orchestration have not been fully moved into C++.
- Arbitrary modded global FluidPicker callbacks are not supported by the current kernel. The preflight declines obvious non-vanilla pickers rather than approximating them.
- Current state tokens cover air, water, lava, and the dimension's default fluid. A general registry-backed opaque token table for arbitrary modded states remains future work.
- No launcher-time persistent conversion cache of the effective post-NeoForge/post-mod world-generation graph has been implemented yet.

## Validation and performance claims

Native build, ABI, unit, and GameTest results must be read from the latest CI run for the exact branch head. The strict Overworld gate must complete with zero failed chunks and no density/Aquifer parity mismatch before the path is treated as validated.

Do not claim a speedup, reduced total Java allocation, or reduced GC from microbenchmarks alone. Required end-to-end evidence includes full chunk generation, cold/warm startup, chunk latency distribution, Java allocation/GC, native memory, FFM call counts, and a vanilla comparison over multiple seeds and dimensions.

The strict test configuration must keep Java Aquifer fallback disabled. Non-strict compatibility fallback is not evidence of a successful native takeover.

## Separate Rust World Engine

The Rust World Engine and its raw Vulkan, OpenGL, and OpenGL ES renderer backends remain a separate layer and separate pull request. This C++ Aquifer implementation does not add a rendering framework or alter renderer ownership.
