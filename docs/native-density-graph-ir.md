# Native density graph IR v1

Status: an experimental, opt-in Minecraft 26.3 final-density path is integrated for a strict supported subset. It lowers the actual final-density object graph, evaluates complete density volumes through C++ F32, and falls back to vanilla for unsupported nodes or failures. It is not a complete terrain-generation takeover; exact parity across all shipped routers/modded graphs and end-to-end performance are not yet established.

## Prepared NoiseStack implementation slice

The experimental native branch now has an additive prepared-Perlin-stack primitive:

- `jpe_runtime_prepare_perlin_stack` copies the ordered 256-byte permutation tables, offsets, per-layer XZ/Y frequencies, and base amplitudes into an immutable native handle.
- `jpe_runtime_evaluate_prepared_perlin_stack` evaluates that handle with only sampler-level XZ/Y scales, amplitude, volume coordinates, and output storage. No per-layer descriptors or permutation arrays need to cross the FFM/Rust/C++ boundary for each volume.
- The C++ handle owns immutable layer data; evaluation retains shared ownership while running, so a concurrent release cannot invalidate an active call.
- Each calling thread reuses its descriptor scratch and the existing fused stack kernel remains the arithmetic implementation.
- A Rust test compares prepared evaluation with the existing direct stack API by exact `f32::to_bits()` equality across multiple sampler scale/amplitude combinations.

The Java `NativePerlinNoiseBackend` now keeps prepared handles in an identity-based weak cache keyed by `NoiseStack`; expired stack references release their native handles. The per-volume FFM call passes the prepared handle, sampler-level scales/amplitude, volume coordinates, and output storage, without re-reading the layer array or staging octave descriptors/permutations. `NativeRuntime.close()` releases remaining prepared handles. The prepared evaluator runs synchronously on the calling worldgen worker, avoiding a second queued worker-pool hop for this path.

This is now also used as a density-graph leaf for captured seed-bound `NoiseFunction` instances backed by supported Perlin stacks. The graph path bypasses the outer Java density sampler for supported roots. The prepared handle cache and compiled graph cache are process-local; they are not yet the requested persistent on-disk world-preparation cache.

## Implemented native IR prototype

The current branch has an executable C ABI/Rust/C++/Java path for a topologically ordered graph. It supports constants, X/Y/Z coordinates, ADD/MUL/SUB/DIV/MIN/MAX/POW, ABS/NEGATE/SQUARE/CUBE/SQRT/HALF_NEGATIVE/QUARTER_NEGATIVE/RECIPROCAL/SIGN/CLAMP/SQUEEZE/LERP/GRADIENT (clamp, repeat, mirrored repeat)/INTERPOLATED/RANGE_CHOICE/ROUND (including dynamic per-voxel multiples)/LOG, DISTANCE_TO_POINT (all four Minecraft 26.3 metrics), cubic spline trees (constant and multipoint forms), and prepared PERLIN_STACK nodes.

Execution is node-major over the complete requested volume. The C++ compiler precomputes a liveness-based slot assignment once, so each evaluation allocates only the maximum simultaneously-live volume buffers; dead nodes and nodes after their final consumer reuse slots. Compiled graphs retain prepared Perlin handles independently of their source handles.

The original graph API returns a double volume and remains available for compatibility/testing. A separate `jpe_runtime_density_graph_evaluate_f32` path now executes every graph node in `float`, including intermediate arithmetic, and writes directly to a float output buffer. Rust FFI tests distinguish the two modes with a value that rounds differently after an intermediate addition. This is necessary for Minecraft 26.3, whose density functions and noises use single-precision intermediates; it is not yet proof of parity for every operator or real worldgen. The Java FFM wrapper is called from the experimental `NoiseBasedChunkGenerator.doFill` path for complete `DensityVolume` requests when the actual final-density graph is fully supported. It writes directly into a pooled `ScopedDensityBuffer`. An optional `jpe.nativeDensityGraphVerify` mode compares raw float bits against vanilla and disables a mismatching graph for that `RandomState`. The current C++ cache is process-local and has no persistent on-disk generator cache.


Java FFM exposes `supportsDensityGraphF32()` and `evaluateDensityGraphF32(...)`; the mixin now calls the evaluator for supported final-density graphs.

The implemented operator list is an execution substrate, not a claim that every 26.3 density function has been lowered. Cubic spline lowering was added experimentally: it handles constant and multipoint trees, nested values, Hermite segments, and linear endpoint extension, but exact float-bit parity has not yet been demonstrated against an in-game 26.3 sampler. DISTANCE_TO_POINT has an explicit opcode and unit coverage for all four metrics; its in-game bit parity is also still to be checked. Unsupported nodes still include shifted/domain-warped noise, non-empty legacy blending, non-empty beardifier, mod-defined/context-dependent functions. Cache/reference wrappers are lowered only where removing the cache wrapper preserves the pure function value. Unsupported roots fall back to vanilla. The compiler emits a debug-level rejection record containing the root implementation class and the first unsupported-lowering reason, so an actual game run can inventory which node families still prevent native execution without changing fallback behavior.

## Goal

Move the expensive, pure, volume-oriented part of Minecraft 26.3 terrain density evaluation behind one coarse Java-to-native boundary. Java extracts a stable description of the terrain density graph once per generator configuration. Native code validates, compiles, caches, and evaluates that description for many chunk volumes. Java retains Minecraft object ownership and commits block states.

This is not a Java-source-to-C++ transpiler. Java lambdas, arbitrary methods, object references, registries, and mod callbacks are not serializable native instructions. The bridge uses a versioned, pointer-free intermediate representation (IR).

## Important existing behavior

The project source audit records that Minecraft already has a `DensityFunctionCompiler` and compiled-sampler memoization in `RandomState`. The native project must not blindly duplicate that work or assume vanilla evaluates the whole graph naively for every voxel.

The actual implementation authority is the official Minecraft 26.3 artifact and the NeoForge-patched 26.3.0.115 artifact. Until the source-audit workflow verifies both, class-level details and node mappings remain provisional.

The extraction point should be the stable, inspectable DensityFunction graph and its configuration, not opaque compiled Java sampler lambdas. The native compiler may perform additional lowering only where profiling proves a benefit over vanilla's own compilation.

## Ownership split

### Java adapter

- Runs once when a world/generator configuration becomes available: traverses the relevant terrain graph and emits the IR.
- Supplies immutable noise parameters, seed-derived permutations, router/settings identity, dimension bounds, and supported extension metadata.
- Retains registry, BlockState, chunk, heightmap, random-state, mod callback, and lifecycle ownership.
- Sends a compiled graph handle plus chunk-volume coordinates for each job.
- Validates the result and commits Minecraft objects on the required executor.
- Invalidates native handles when the owning generator/configuration is disposed or reloaded.

### Native engine

- Validates IR version, node count, indices, types, arity, graph reachability, limits, and content hash.
- Lowers the graph to a compact execution plan and caches it by a content fingerprint plus semantic version.
- Evaluates a complete requested density volume, not one Java call per node or per octave.
- Reuses worker-local scratch and volume buffers within strict memory limits.
- Returns a status and result buffer; it never retains Java heap pointers or Minecraft object references.
- Does not silently emulate unsupported nodes.

## IR v1 wire model

The transport is a flat topologically ordered node array. Every input index must refer to an earlier node; this makes cycles impossible and allows validation in one pass. Constants and parameters are stored as fixed-width numeric fields. The wire format contains no native pointers, Java object IDs, class names, or platform-sized integers.

Each graph carries:
- `ir_version` and `semantic_version`
- `node_count` and `root_node`
- a stable 128-bit content fingerprint
- a generator/configuration fingerprint supplied by Java
- a bounded table of immutable noise descriptors
- a flat node array

Each node carries:
- opcode
- input indices (unused slots set to a defined sentinel)
- immediate operands
- optional descriptor index
- flags reserved for explicitly specified semantics

All floating-point operations must use the same precision and evaluation order as the target Java implementation. The native build keeps fast-math and implicit FMA contraction disabled unless a separately proven exact-parity mode permits them.

## Initial opcode families

The initial compiler should be deliberately narrow. Candidate pure operations include:

- constants and coordinate inputs
- add, subtract, multiply, divide, min, max, power
- clamp and implemented unary transforms (abs, negate, square, cube, sqrt, half-negative, quarter-negative, reciprocal, sign)
- prepared Perlin stack references

These are a proposed allowlist, not a claim that every opcode is already implemented.

The following still require dedicated semantic work and must not be approximated as ordinary arithmetic:

- spline and context-dependent nodes
- shifted noise and shared shift buffers
- non-empty blend alpha/offset and beardifier
- registry-backed or mod-provided custom density functions

Range-choice, interval-select, interpolation, all three gradient tiling modes, and empty blender/beardifier cases have initial native lowering, but remain gated by exact vanilla parity.

Unknown nodes make the graph unsupported as a whole for that execution path unless a future explicit hybrid plan proves that a whole batch can safely cross back to Java. The first implementation should prefer full Java fallback over per-voxel Java callbacks.

## Compilation and cache lifecycle

1. Java computes a canonical serialization of the supported graph and its immutable descriptors.
2. Native validates the complete payload before allocating long-lived resources.
3. Native computes or verifies the content fingerprint; Java-provided IDs alone are not trusted as cache keys.
4. If the fingerprint and semantic version match an existing entry, native returns its handle without recompiling.
5. Otherwise native lowers the graph and stores an immutable compiled plan.
6. Each density-volume request supplies only the handle, volume dimensions/axis metadata, and required per-job inputs.
7. The handle is reference-counted or explicitly released. World reload invalidates stale handles.
8. Cache size is bounded. Eviction is allowed only when no active job references an entry.

The cache key must include every value that changes graph semantics: Minecraft/NeoForge compatibility version, IR semantic version, noise/settings data, seed-derived noise state, dimension/generator configuration, and mod extension identity. Do not key only by chunk coordinates or registry names.

## Batch execution and memory contract

- One request evaluates the full density volume for one chunk or a deliberately larger safe batch.
- Inputs are immutable for the job lifetime.
- Results use a leased native/shared buffer or a single bulk copy; no per-node or per-voxel FFM calls.
- A job may not access a Java heap pointer from a worker thread.
- Queue depth, graph count, node count, volume size, scratch bytes, and in-flight output bytes are bounded.
- Cancellation and world-reload epochs prevent stale results from being committed.
- Do not create a second unbounded native worker pool if Minecraft's worldgen executor already provides parallelism. Compare same-thread native execution with bounded native workers before selecting a scheduler.

## Correctness gates

Before enabling a node family:
1. Compare its scalar output against the exact Java 26.3 implementation over fixed seeds, coordinates, negative coordinates, extreme supported Y values, and edge cases.
2. Compare complete density-volume float bit patterns, not just approximate values.
3. Compare final generated block-state hashes and relevant heightmap/post-processing results.
4. Test a vanilla overworld and dimensions/configurations that use different routers.
5. Test unsupported custom nodes and verify deterministic Java fallback.
6. Test world reload, cancellation, concurrent chunks, cache eviction, and ABI mismatch.

A graph is eligible for native execution only if every reachable node has a verified implementation and every required cache/context semantic is represented. Otherwise the entire graph falls back to vanilla.

## Performance gates

Measure separately:
- graph extraction and serialization (one-time)
- native validation/lowering/compile (one-time)
- cache hit/miss and retained bytes
- per-volume submission and wait cost
- density evaluation time
- result transfer/commit time
- full chunk throughput and p50/p95 latency

The existing Java scalar baseline is already recorded in the project. Do not rerun it just to tune this prototype. Compare native graph execution against that saved baseline and use same-seed, fresh-world native-only experiments for internal variants.

A successful arithmetic microbenchmark is not evidence of a Minecraft worldgen speedup. The acceptance criterion is bit-identical terrain plus repeatable end-to-end improvement.

## Implementation sequence

1. Confirm the official and NeoForge-patched 26.3 source audit and inventory concrete DensityFunction implementations reachable from the default overworld router.
2. Extend and validate the C ABI wire structs; add native unit tests for malformed graphs, shared subgraphs, dead-node slot reuse, and stable fingerprints.
3. Implement and test the arithmetic and prepared-Perlin volume evaluator independently. This validates transport/cache/liveness, not Minecraft compatibility.
4. Expand parity coverage over the shipped 26.3 overworld graph and all other vanilla dimension routers, then verify against the exact NeoForge-patched artifact.
5. Add remaining vanilla noise, spline, shift, and context nodes using exact target-artifact semantics.
6. Add safe persistent world-preparation cache identity and lifecycle invalidation.
7. Keep the graph backend opt-in and preserve deterministic vanilla fallback for every unsupported graph.
8. Only after exact parity, run full-chunk benchmarks and compare the saved baseline.

## Explicit non-goals for v1

- Translating arbitrary Java bytecode or source code to C++.
- Moving BlockState, registries, chunk mutation, heightmaps, structures, carvers, or arbitrary mod callbacks into C++.
- Uploading generated terrain directly to the GPU. Terrain density evaluation is CPU worldgen; rendering uploads are a separate later pipeline.
- Claiming speedup before exact-output and full-chunk measurements.
