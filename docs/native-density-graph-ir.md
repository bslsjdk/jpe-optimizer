# Native density graph IR v1

Status: design contract only. No Minecraft density graph is executed by this IR yet. The native backend must remain opt-in until the exact-output and real-worldgen gates below pass.

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

The transport is a flat topologically ordered node array. Every input index must refer to an earlier node; this makes cycles impossible and allows validation in one pass. IR version 1 is passed explicitly through the C ABI and participates in the cache fingerprint. The Java FFM bridge now exposes compile, bulk volume evaluate, and reference-counted release. Native output is `float[]`-compatible to match Minecraft's `DensityBuffer`; intermediate prototype registers are `double` and are converted to float only at the volume boundary. Constants and parameters are stored as fixed-width numeric fields. The wire format contains no native pointers, Java object IDs, class names, or platform-sized integers.

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
- add, multiply, min, max
- clamp and the simple unary transforms whose exact formulas are confirmed from the target artifact
- noise references only after permutation, octave order, coordinate wrapping, and float/double conversion points have parity tests

These are a proposed allowlist, not a claim that every opcode is already implemented. The first native prototype currently implements only `CONST`, `X`, `Y`, `Z`, `ADD`, and `MUL` in `native/cpp-kernels/src/density_graph.cpp`. Its C ABI supports versioned compile/cache, full-volume evaluation, and reference-counted release; Java 25 FFM wrappers are present in `NativeRuntime`. Rust unit tests exercise the arithmetic graph, cache reuse, malformed topological references, and invalid volume rejection. Java unit tests exercise IR validation. The prototype API is callable from Java, but no Minecraft `DensityFunction` extractor or `NoiseRouter` integration exists yet. It is not a terrain speedup.

The following require dedicated semantic work and must not be approximated as ordinary arithmetic:

- range-choice branches and their volume-specific branch evaluation
- cache nodes and cache lifetimes
- interpolated and cell-based density functions
- shifted noise and shared shift buffers
- blend alpha/offset and beardifier
- Y gradients with exact coordinate/endpoint behavior
- registry-backed or mod-provided custom density functions

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
2. Define the C ABI wire structs and strict validator; add native unit tests for malformed graphs and stable fingerprints.
3. Implement a tiny arithmetic-only graph evaluator and test it independently. This validates the transport/cache lifecycle, not Minecraft compatibility.
4. Add Java extraction for only the confirmed arithmetic nodes and compare complete volume outputs.
5. Add noise nodes using the existing verified Perlin kernel and descriptor representation.
6. Add special nodes (range choice, interpolation, shifts, blend, beardifier, caches) one family at a time.
7. Integrate behind a disabled-by-default graph backend flag. Keep the existing vanilla path as the correctness fallback.
8. Only after exact parity, run full-chunk benchmarks and compare the saved baseline.

## Explicit non-goals for v1

- Translating arbitrary Java bytecode or source code to C++.
- Moving BlockState, registries, chunk mutation, heightmaps, structures, carvers, or arbitrary mod callbacks into C++.
- Uploading generated terrain directly to the GPU. Terrain density evaluation is CPU worldgen; rendering uploads are a separate later pipeline.
- Claiming speedup before exact-output and full-chunk measurements.


## Runtime trace compiler experiment

The Java side now contains an opt-in `RuntimeDensityTrace` collector. Instrumentation can
open nested scopes around observed DensityFunction calls, record caller/callee identity
edges, and attach branch-outcome guards. Capture is bounded by a configurable event
budget and snapshots contain implementation class names and integer IDs only; they do
not retain Minecraft object references after the trace is stopped.

This collector is deliberately not wired into world generation yet. A generic call trace
does not recover arithmetic operands, exact float/double conversion points, random/noise
state, cache semantics, or side effects. It is evidence about an execution path, not a
native-executable graph. The next integration step must instrument only methods confirmed
from the exact 26.3 NeoForge-patched artifact, then add explicit semantic adapters for
supported node types. Any unknown stateful or branch-sensitive node keeps the complete
path on Java.

The collector's unit tests cover nested caller/callee edges, branch outcomes, disabled
capture, and event-budget overflow. No live DensityFunction trace or worldgen speedup is
claimed by this scaffolding.
