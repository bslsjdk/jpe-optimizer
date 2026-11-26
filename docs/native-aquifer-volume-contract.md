# Native Aquifer full-volume contract

Status: implementation contract; no production takeover is claimed by this document.

Target: Minecraft Java 26.3 with NeoForge 26.3.0.115, using the exact vanilla and patched artifacts selected by the source-audit workflow.

## Objective

Replace the production per-voxel Java `NoiseBasedAquifer.computeSubstance` work with one native C++ full-volume operation. The C++ operation must own all repeated numeric work for the requested chunk-fill volume:

- Aquifer cell-center lookup and nearest-four selection.
- FluidStatus cache misses and FluidStatus value calculation.
- The 13-sample surface schedule and quart-surface cache.
- The six Aquifer density samplers: surface, barrier, floodedness, spread, and lava-related sampler handles as represented by the audited 26.3 implementation. The exact field-to-handle mapping must come from the dual-artifact audit, not guessed names.
- Pressure, density barriers, fluid selection, debug-fluid behavior, and fluid-update decisions.
- Global FluidPicker lookup using immutable per-world tokens prepared before chunk generation.

Java may prepare immutable world-level inputs once, pass volume coordinates and handles, map returned opaque block-state tokens to registry-owned BlockState objects, and commit block/heightmap writes. Java must not perform any of the per-voxel calculations listed above.

## Call boundary

The production call is volume-oriented, not a per-block FFM loop. Inputs are validated completely before outputs are modified. A failed call returns an error and leaves caller output buffers unchanged.

The interface must carry:

1. An immutable Aquifer context handle, tied to the active world/RandomState fingerprint and native ABI/semantic version.
2. Volume geometry: origin, dimensions, and the exact traversal/layout metadata used by the audited chunk-fill caller.
3. The density volume or density sampler handle(s) required by the audited caller. Do not recompute density in Java and do not silently switch to a Java sampler.
4. The block-coordinate mapping and the immutable registry token table for air, water, lava, and any modded fluid states.
5. Debug-fluid mode and the global-fluid-below lookup data/handle.
6. Caller-owned output spans with explicit element counts and fixed-width ABI fields.

Outputs are parallel arrays in the caller's established voxel order:

- `keep_terrain`: whether the existing terrain state is retained (the inverse of a successful Aquifer replacement decision).
- `block_state_token`: opaque, world-context-scoped token; never a Java object pointer or registry numeric ID assumed stable across launches.
- `schedule_fluid_update`: exact vanilla update flag.

The ABI must use fixed-width integer fields, explicit structure sizes/offset assertions in C++ and Rust, and explicit capacity checks before any pointer arithmetic. Do not expose STL types, Rust types, Java references, or allocator-owned buffers across the ABI.

## Native context lifetime

Create the context during world startup after the effective NoiseRouter, dimension, seed, datapack/registry view, loader/mod versions, and all six required sampler plans are known. Reuse the repository's complete world fingerprint discipline. Cache entries must never be reused if any fingerprint component or semantic version differs.

The context owns:
- Retained immutable density graph handles.
- The native FluidStatus cache and quart-surface cache.
- Prepared global-fluid lookup tokens/data.
- Reusable scratch sized to the configured maximum volume.

Destroy the context only after all chunk tasks using it have completed. Concurrent calls may share immutable plans, but mutable cache/scratch state must be synchronized or worker-local. No process-local handle may be serialized into the persistent cache.

## Semantic requirements

The C++ implementation must preserve the exact audited 26.3 order of operations, including:
- Early exits and short-circuit order.
- X/Y/Z and quart-surface sample ordering.
- Java int32 overflow where vanilla performs int32 arithmetic.
- Java float/double conversions and operation order.
- Candidate tie ordering and positional RNG behavior already implemented.
- FluidStatus cache first-writer-wins behavior.
- Debug-fluid and global FluidPicker semantics.
- Modded fluid states through opaque tokens, without assuming only vanilla registry entries.

Do not "simplify" arithmetic or reorder samples until bitwise/decision parity has been established. Any unsupported sampler, state, or registry condition must fail closed in strict mode.

## Required tests before wiring production

1. C++ unit tests for every input validation and transactional-output guarantee, including null pointers, zero/oversized volumes, multiplication overflow, invalid handles, and undersized output spans.
2. Scalar-versus-volume parity for every voxel using the existing audited Java implementation as the oracle.
3. Both positional RNG algorithms, negative chunk coordinates, chunk-border cells, and extreme valid coordinates.
4. Fluid interfaces, caves, debug-fluid mode, global-fluid-below lava cases, and update scheduling.
5. Modded fluid tokens and registry remapping across separate world launches.
6. Full chunk block-state and fluid-update hashes against vanilla for a fixed multi-seed, multi-dimension corpus.
7. Strict native-only GameTest and Overworld generation with the Java Aquifer fallback deliberately disabled. Any native failure must fail the test; it must not silently execute Java.
8. End-to-end measurements: chunk latency, CPU time, Java allocation/GC, native allocation, and FFM call count. Report warm and cold startup separately.

## Integration sequence

1. Implement and test the C++ full-volume kernel and context lifecycle.
2. Add Rust FFI wrappers and ABI layout tests without changing the renderer/World Engine ownership boundary.
3. Add Java startup preparation and one-call-per-volume bridge.
4. Add strict native-only GameTests and exact block-state parity.
5. Only after all parity gates pass, enable the production path. Keep a diagnostic opt-out for bisecting regressions, but never use Java fallback to turn a strict test green.

## Non-goals

- No renderer work in this change. Rust's Vulkan, OpenGL, and OpenGL ES backends remain a separate World Engine tier.
- No third-party rendering framework.
- No Java per-voxel density or Aquifer computation.
- No claim of full native world generation, lower GC, or speedup until the strict end-to-end gates pass.
