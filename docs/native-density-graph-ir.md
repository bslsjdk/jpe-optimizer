# Native density graph IR v6

Status: experimental shadow execution. Compiled-sampler arithmetic islands are evaluated by Native during real Minecraft world generation and compared bit-for-bit with Java. Native results are not committed to chunks; Java remains authoritative until broader exact-output and end-to-end performance gates pass.

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

## Versioned wire model

The transport is a flat topologically ordered node array. Every input index must refer to an earlier node; this makes cycles impossible and allows validation in one pass. The current IR version is passed explicitly through the C ABI and participates in the cache fingerprint. The Java FFM bridge now exposes compile, bulk volume evaluate, and reference-counted release. Native output is `float[]`-compatible to match Minecraft's `DensityBuffer`; intermediate prototype registers are `double` and are converted to float only at the volume boundary. Constants and parameters are stored as fixed-width numeric fields. The wire format contains no native pointers, Java object IDs, class names, or platform-sized integers.

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

These are a proposed allowlist, not a claim that every opcode is already implemented. The current native prototype implements `CONST`, `X`, `Y`, `Z`, `ADD`, `SUB`, and `MUL` in `native/cpp-kernels/src/density_graph.cpp`. Its C ABI supports versioned compile/cache, full-volume evaluation, and reference-counted release; Java 25 FFM wrappers are present in `NativeRuntime`. Rust unit tests exercise the arithmetic graph, cache reuse, malformed topological references, and invalid volume rejection. Java unit tests exercise IR validation. The prototype API is callable from Java, but no Minecraft `DensityFunction` extractor or `NoiseRouter` integration exists yet. It is not a terrain speedup.

The following require dedicated semantic work and must not be approximated as ordinary arithmetic:

- range-choice branches and their volume-specific branch evaluation
- cache nodes and cache lifetimes
- interpolated and cell-based density functions
- shifted noise and shared shift buffers
- blend alpha/offset and beardifier
- Y gradients with exact coordinate/endpoint behavior
- registry-backed or mod-provided custom density functions

Unknown nodes have two deliberately separate behaviors. STRICT capture rejects them and remains suitable for exact parity work. EXPERIMENTAL capture preserves each unknown node as an explicit OPAQUE node with a Java-side diagnostic sidecar (runtime class and reason). Native accepts that opcode only with its experimental marker, emits quiet NaN for that node, and continues evaluating known ancestors and independent subgraphs. The Java bridge exposes separate compileDensityGraph (strict) and compileDensityGraphExperimental entry points, so an experimental graph cannot accidentally enter the strict path. This is not an emulation of the unknown operation: NaN is a visible failure signal, not a guessed result. Experimental output must never be committed to a real chunk.


## Native Blender density kernel (experimental, not wired into Minecraft)

The C++ kernel now has a full-volume API for the two density paths in the exact 26.3 Blender implementation. It receives volume dimensions, axis origins/steps, and the original noise-value volume; C++ constructs every block coordinate and emits the blended volume in Minecraft's native `((z * sizeX + x) * sizeY + y)` order.

- `height_data` represents `heightAndBiomeBlendingData` and is used for the exact fixed-cell lookup, including the section-boundary fallback order.
- `density_data` represents `densityBlendingData` and is used for the radius-2 weighted neighborhood blend.
- Each `JpeBlendingDataView` carries the raw 16 density-column pointers, chunk coordinates, minimum section Y, and section count. A missing Java column is represented by a null pointer. Every non-null column contains `section_count * 2` raw floats.
- The caller must preserve the iteration order of `densityBlendingData.forEach`. C++ builds the spatial index and computes cell coordinates, column mapping, Y-window filtering, distance, inverse-fourth-power weights, closest-distance alpha, and final interpolation. It sorts candidate point ordinals before accumulation so the original Java iteration order is retained.
- The FFI call is synchronous, processes the entire density volume in one call, and does not retain Java pointers. A Java adapter may expose/copy raw primitive columns, but must not enumerate density points, build the spatial index, perform neighbor searches, or calculate any blend values.

The kernel is compiled directly into the C++ library and exposed through the Rust C ABI. Rust tests cover empty-data passthrough, fixed-density override, negative-coordinate boundary fallback, and weighted neighborhood interpolation.

**Current limitation:** this is a standalone Native kernel plus Rust FFI, not yet connected to Minecraft's live Blender objects or density-volume execution. No actual chunk parity or world-generation speedup is claimed. The next integration must provide raw column views from the exact runtime BlendingData instances, then compare every result bit-for-bit against the Java implementation before enabling it.

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


### Trace overhead control

Observed caller/callee edges are deduplicated by object-identity node IDs, and branch outcomes are deduplicated by (node ID, explicit guard ID, outcome). Instrumentation must supply a stable guard ID for each branch site; an outcome is not itself a permanent fact and both outcomes may be present in the same snapshot. This prevents repeated voxel sampling from producing one identical edge/guard record per sample. The event budget still bounds unique records, and overflow is surfaced through droppedEvents.


### Semantic capture boundary

`RuntimeDensityTrace` is diagnostic evidence only. `DensityGraphCapture` is the separate, explicit lowering API: adapters must register constants, coordinates, and supported binary operations with their actual operands. A missing adapter or any reachable unsupported node rejects the entire graph, allowing the caller to retain the Java implementation. This prevents observed call order from being mistaken for executable semantics.


## Experimental native execution and extraction cost

The capture API now has STRICT and EXPERIMENTAL modes. STRICT is the default. EXPERIMENTAL converts missing adapters and explicitly unsupported nodes into OPAQUE nodes; the C++ evaluator propagates NaN through dependent arithmetic while still executing unrelated known nodes. The Java IR retains a diagnostic sidecar with the source class and reason, so the hot native node record stays compact. This is a deliberate failure-injection path, not a production fallback.

Extraction uses identity-based memoization to preserve shared subgraphs, pre-sizes its node/index containers from the captured object count, and performs iterative post-order traversal rather than recursive calls. This avoids repeated collection growth and stack overflow on deeply nested density graphs. DensityGraphCapture.lastStats() exposes conversion nanoseconds, source object count, emitted node count, opaque node count, and unreachable object count. Those metrics let us measure the conversion phase separately from native compile/cache and volume evaluation.

The fixed-size C++ OPAQUE node keeps at most two hot dependency slots, while the Java IR diagnostic sidecar and persistent Java cache retain the full child-index list for arbitrary arity. Runtime operands, branch guards, random state, and side-effect metadata are still not represented. Known child nodes remain in the experimental plan and execute; the opaque operation itself deliberately emits NaN. Do not interpret a NaN placeholder as a successful translation.

The FFM bridge also reuses a geometrically grown, per-worldgen-thread shared-arena node buffer for graph compilation. Repeated compilation no longer allocates and closes a fresh confined arena for every graph; scratch is leased during the synchronous native copy and released afterward, then closed with the runtime. This removes one allocation/arena lifecycle per compile, but the current path still encodes each node and the native side still hashes and validates the full payload on each compile request. Those costs remain measurable optimization targets.


## Startup prewarm and native-owned persistent plans

World-generation hot paths must not perform Java object traversal, graph lowering, node serialization, or per-node FFM calls. The intended lifecycle is:

1. During game bootstrap, after registries, mod extensions, dimension types, and world-generation settings are available but before a world starts generating chunks, construct a complete cache identity and call `NativeRuntime.prepareDensityGraph(...)`.
2. On the first run, the supplied Java capture callback builds the IR once. The Java IR cache is written atomically, the graph is transferred once to C++, and C++ writes its own portable binary plan cache.
3. On later launches, `prepareDensityGraph` first asks C++ to load the native plan file directly. A native cache hit does not invoke the Java capture callback, does not deserialize Java IR, and does not serialize individual nodes through FFM.
4. Keep the returned `PreparedDensityGraph` alive for the lifetime of the relevant world-generation configuration. Chunk generation should only invoke its already-loaded native executable in volume-sized batches. It must not call `prepareDensityGraph` or `compileDensityGraph` per chunk.

The cache identity must change whenever semantics can change: Minecraft and loader versions, native ABI/compiler version, IR version, mod set and relevant mod versions, dimension, world-generation settings, registry contents, and any graph-affecting configuration. Strict and experimental plans use separate cache identities. A stale or corrupt native cache is treated as a miss and rebuilt from the Java IR cache or capture callback.

The current native plan is a persisted, validated topological execution graph interpreted by C++; it is **not yet machine-code JIT output**. The native cache removes repeated Java extraction and cross-language node transfer, but native loading still reconstructs the in-memory plan at startup. The next optimization is to lower supported graphs into compact native execution blocks or generated machine code, then persist that executable representation where platform and ABI compatibility permit it.

Important integration boundary: the bootstrap API exists, but the Minecraft lifecycle hook that enumerates every active dimension/worldgen graph and prewarms all of them is not yet wired. Until that integration is complete, the API is a foundation, not a claim that world creation already runs exclusively through C++.

The runtime also reuses a per-thread native output buffer for density-volume evaluation. The C++ graph writes one contiguous volume, then Java performs one bulk copy into the Minecraft-owned float array; the per-evaluation confined Arena allocation has been removed. This is still one cross-language call and one output copy per volume, not zero-copy. Eliminating that final copy requires changing the density-buffer ownership/consumer boundary so Minecraft can consume native-backed memory safely; it must not be disguised as a completed optimization.

Seed handling must be split from graph identity. Do not put a world seed into the static graph cache key merely because it changes Perlin permutations or random offsets: those are per-world runtime parameter data and should be uploaded once when the world context is initialized, then reused by all chunks. If a captured operation actually embeds a seed-derived value as a graph constant, that dependency must either be externalized into the parameter block or included in the cache identity. Reusing a graph with the wrong seed state is a correctness bug, not a harmless cache hit.


### Minecraft 26.3 floating-point semantics

Minecraft 26.3 evaluates Density Functions and noises using single-precision
floating-point values, including intermediate operations. Native Density IR v3
therefore rounds constants, coordinates, and every arithmetic result to IEEE-754
float32 before downstream nodes consume them. The wire record still carries a
64-bit immediate field for ABI alignment, but constants are converted to float32
at evaluation. IR v1 plans are not compatible and must be rebuilt under the v2
cache identity. This is not yet a claim of full vanilla parity: each supported
operation and noise implementation still requires comparison against the exact
26.3 runtime.


### Opaque n-ary dependency preservation (IR v3)

Experimental OPAQUE nodes may represent runtime functions with more than two
children, such as composite or spline-backed functions. The fixed-size native
node record keeps only the first two dependency slots for ABI compatibility,
because the current OPAQUE evaluator returns a diagnostic NaN sentinel rather
than claiming to evaluate unknown semantics. The Java IR diagnostic sidecar now
retains every child node index in source order, including dependencies beyond
the two native slots. The persistent Java IR cache format is v2 and stores this
metadata; the IR version is v3 so old cache entries are invalidated. This
preserves the complete observed dependency list for later adapter development
without pretending the native sentinel evaluates the unknown operation.


IR v3 keeps the v2 float32 evaluation rules and extends the Java diagnostic
sidecar to retain every dependency index for n-ary OPAQUE nodes. The C++ hot
node record remains unchanged; the native plan version field is now 3 so old
IR-v2 plans cannot be mistaken for current plans.


### Exact Minecraft 26.3 worldgen lifecycle findings

The exact Mojang 26.3 server artifact and NeoForge-patched artifact were decompiled and
compared. The relevant method bodies show these lifecycle boundaries:

- `RandomState` is constructed with the world seed and `NoiseRouter`. Its constructor
  stores the router and builds a `DensityFunctionCompiler` whose compile context resolves
  noise samplers and random sources through this seed-bound `RandomState`. This is a
  candidate world-scoped prewarm point after construction and before the first chunk, but
  compiled sampler closures may still contain seed-specific resources. Cache graph
  topology separately from the per-world noise/random parameter block.
- `NoiseChunk` is created per chunk. Its constructor acquires a density buffer pool and
  builds a `SamplerContext` with the chunk's `Beardifier` and, when blending is active,
  `Blender` context fields. It then calls `RandomState.samplersWithContext(...)` with
  caching enabled. These fields are chunk-specific external inputs, not static graph
  constants.
- `NoiseBasedChunkGenerator.doFill` obtains the bound sampler for
  `NoiseRouter.finalDensity()` and calls `DensitySampler.Bound.sampleVolume(volume)`.
  The bound sampler delegates to its compiled sampler with the current
  `SamplerContext` and writes into a pooled `DensityBuffer`. This is the natural
  batch boundary for a native volume evaluation, rather than per-node FFM calls.
- `DensityVolume.indexUnchecked(x,y,z)` is
  `y + (x + z * sizeX) * sizeY`. The native output order z, then x, then y matches
  this layout, including non-unit positive axis steps.
- `CachingDensitySampler` delegates to `SamplerContext.sampleVolumeCached`, which
  may compute a cached subvolume once and copy it into later output buffers.
  `ContextBoundSampler` resolves its concrete sampler from the current context.
  A root-level native replacement that skips these wrappers would change cache
  population and chunk-context behavior. Preserve these operations explicitly in the IR,
  or limit native execution to proven pure, context-independent subgraphs until then.

The practical integration sequence is therefore: prepare the seed-independent router
topology from the world-scoped `RandomState` after its constructor has completed; bind
seed-derived noise/random parameters once for that world; bind `Beardifier` and
`Blender` values when each `NoiseChunk` context is created; then execute only a
semantically complete, cache-safe subset at the `Bound.sampleVolume` batch boundary.
The lifecycle hook and these adapters are not yet wired into Minecraft.

### Native density graph tiled execution

The C++ compiler now lowers the immutable node array into three compact lists:
static constant/OPAQUE values, coordinate bindings, and topologically ordered
ADD/MUL instructions. Evaluation processes bounded node-major tiles of up to 32
samples, with a scratch cap of 1 MiB. Constants are initialized once per tile,
coordinates are generated as contiguous arrays, and each arithmetic instruction
runs across the tile before the next instruction. This exposes contiguous loops
to compiler auto-vectorization while preserving instruction order and float32
rounding between dependent nodes. A large-volume parity test checks the
z/x/y ordering and tile tail. This is a SIMD-friendly execution layout, not yet
a measured Minecraft chunk speedup; benchmark results must be recorded separately.


## IR v4 subtraction opcode

IR v4 adds `SUB` (opcode 8) to the Java model, C ABI header, Rust bridge, C++ validator/lowering, tiled evaluator, and scalar reference evaluator. The operation is evaluated as ordered float32 `left - right`; it does not introduce reassociation or fused operations. The IR version is incremented so v3 persistent entries are rejected rather than misinterpreted. Rust tests cover a full volume with the established z/x/y output layout, and Java tests cover opcode construction and validation.

This addition makes subtraction representable by the native graph engine. It does not yet connect `CompiledSamplerArithmeticIslands` to native graph compilation, nor does it mean compiled Minecraft sampler graphs are being executed natively.


## IR v5 batched external volume inputs

IR v5 adds `INPUT` (opcode 9). Its immediate is an integer slot in `[0,255]`; it has no graph-node dependencies. The new `evaluate_with_inputs` ABI accepts an input-major matrix where `inputs[slot * volume_length + sample]` is the float32 value for that external volume sample. The evaluator copies only the current bounded tile into its node-major scratch and then executes the arithmetic island. This preserves a single coarse native call per island/volume rather than crossing the language boundary for each arithmetic node.

The original evaluate API remains available for graphs without external inputs. The input count is bounded at 256, every referenced slot must be supplied, and the input and output buffers are borrowed only for the synchronous call. Java's `NativeDensityGraph.evaluateWithInputs` currently stages Java float arrays into a reusable shared FFM arena, so this is one batched transfer per input matrix, not zero-copy. The Java caller still owns the source arrays and must keep them stable for the duration of the call.

This is now a native execution primitive and Java bridge API, not yet a Minecraft sampler integration. The diagnostic arithmetic-island planner does not automatically construct these wire graphs or populate input slots; that adapter and exact sampler parity tests remain required before worldgen can use the path.


## Compiled sampler arithmetic-island lowering

`CompiledSamplerArithmeticLowering` now converts an eligible ADD/SUB/CONSTANT island into the IR v5 wire graph. It deliberately reorders the root-first diagnostic sampler inventory into a stable child-before-parent topological order, creates BUFFER_INPUT slots for external sampler dependencies, and deduplicates an external source node shared by multiple consumers. The result retains the source-node-to-native-node mapping and the ordered list of source sampler IDs whose complete float32 volumes must be supplied by the caller.

The lowering now retains every output root in an eligible island. The native multi-output ABI evaluates all roots in one pass, sharing intermediate nodes rather than recompiling or recomputing the island per output. The diagnostic capture path attempts this lowering and reports how many islands are lowerable. It does not compile or execute them; configuration fingerprint zero is a diagnostic placeholder and is not a valid persistent worldgen cache identity.

The adapter still requires a runtime provider for each external sampler volume, exact matching of Minecraft's sampler volume/context/cache lifetime, and Java-vs-native bitwise comparison before worldgen execution can be enabled.


## Multi-output arithmetic-island execution

The native density graph bridge now exposes an additive multi-output entry point. It accepts an ordered list of graph node indices and writes output-major volumes in one evaluation pass. The graph's shared intermediate nodes are evaluated once per tile, even when several output roots depend on them. The legacy single-root entry points remain available and delegate to the same evaluator.

The Java lowering result retains every source output in stable island order and maps each output to its lowered native node index. Its batch execution helper passes all roots to `NativeDensityGraph.evaluateMultiWithInputs`, so callers can obtain all island outputs without recompiling or evaluating the graph separately for each root.

The bridge limits each volume to 1,048,576 samples, each input/output count to 256, and combined external-input plus output staging to 16,777,216 float values (64 MiB). Java validates array lengths and the same combined budget before copying; Rust and C++ validate the ABI arguments again. Output layout is output-major: `outputs[rootIndex * volumeLength + sampleIndex]`. This is still a synchronous FFM staging path, not zero-copy access to Java heap arrays.

This primitive and its tests do not yet prove that Minecraft's live `DensitySampler` graph is executing natively. The sampler boundary providers, cache-cell semantics, context binding, and exact Java-vs-native volume parity still need to be connected and verified before world generation can use this path.


## Live sampler boundary resolution

A short-lived `CompiledSamplerGraphTrace.CapturedGraph` now retains the exact source sampler objects alongside the diagnostic node inventory. This is deliberately an ephemeral capture API; callers must not store it in static fields or persistent graph caches because sampler instances can retain world- and chunk-scoped context.

`CompiledSamplerExternalInputs` resolves each lowered island input by source node ID and invokes the original `DensitySampler.sampleVolume(bound.context(), buffer, volume)` API. This is important for two special boundaries:

- `ContextBoundSampler` selects its actual sampler through the supplied `SamplerContext`; the helper does not substitute the fallback sampler or inspect context internals.
- `CachingDensitySampler` continues through `SamplerContext.sampleVolumeCached`, preserving Minecraft's cache-cell identity and volume lifetime behavior.

The helper samples each distinct external sampler identity at most once for its lifetime, shares the resulting float array among island consumers, and enforces a 64 MiB input staging cap. A new helper must be created for each exact `DensitySampler.Bound` and `DensityVolume` pair. The convenience `evaluate` method then submits those full-volume inputs and all island roots through the multi-output native API.

This is the first real bridge from captured compiled-sampler nodes to live Minecraft-produced input volumes. It is not yet wired into `NoiseBasedChunkGenerator.doFill`; doing so safely still requires a runtime library lifecycle, a nonzero world/settings graph fingerprint, output parity checks against the original Java samplers, and a fail-closed experimental switch before any block placement consumes native results.


## Full-volume sampler parity verifier

`CompiledSamplerNativeParity.verify` is an opt-in diagnostic harness for a lowered arithmetic island. It resolves all external inputs through the live `DensitySampler.Bound` context, executes every native output in one call, then evaluates each original Java output sampler into an unpooled `DensityBuffer`. It compares every sample with `Float.floatToRawIntBits`, reporting the first mismatch and total mismatch count.

The verifier refuses combined input/output staging above 64 MiB and never changes block placement or the final-density buffer. A successful result proves bitwise parity only for the specific sampler instances, context, volume, and inputs tested. It does not prove parity for other seeds, dimensions, mods, or branches, and it does not itself enable native world generation.


### Exact sampler compilation and context-wrapper findings (Minecraft 26.3)

The 2026-11-25 javap -p -c -s audit of both the official and NeoForge-patched 26.3 artifacts confirms an important cache boundary:

- RandomState constructs one DensityFunctionCompiler in its constructor. That compiler owns a ConcurrentHashMap<DensityFunction, DensitySampler> and getSampler(function) calls samplers.computeIfAbsent(function, optimizeAndCompile). The key is the DensityFunction object under its Java equals/hashCode contract, not an identity-keyed map. On a cache miss, the compiler applies its optimizer rewrite and calls compileSampler(context) while holding its compile lock. Thus vanilla already memoizes compiled samplers for the lifetime of a RandomState; a native layer must not recompile the same source graph independently for every chunk.
- RandomState.samplersWithContext(context) creates a new anonymous RandomState$2 wrapper on every invocation. That wrapper stores the current SamplerContext; its get(function) calls the owning RandomState.getSampler(function) and then bind(context), while sampleValue(...) delegates to the same cached sampler with the current context. The wrapper itself is therefore context-scoped and must not be used as a persistent cache key.
- NoiseChunk is constructed per chunk and creates its own SamplerContext (including its buffer arena, enabled caches, and chunk user fields) before calling randomState.samplersWithContext(...). The per-chunk wrapper and DensitySampler.Bound are ephemeral, but the underlying compiled sampler is obtained from the world-scoped RandomState compiler cache.
- RandomState also creates its DensityFunctionCompiler with a compile context that resolves Noise and random sources through that RandomState. These compiled sampler closures can therefore retain seed-derived resources. A serialized arithmetic topology may be reusable across compatible worlds only after all seed-derived noise/random state is externalized; otherwise the cache identity must include the relevant seed-derived semantics.

Consequences for the native design:

1. Do not cache RandomState$2, SamplerContext, DensitySampler.Bound, NoiseChunk, DensityBuffer, or captured sampler object references across chunks.
2. Reuse the vanilla DensityFunctionCompiler result as the source of compiled sampler topology. Extract/lower only after RandomState has initialized its compiler and before the first chunk needs the graph; avoid repeating traversal in each doFill.
3. Cache the pointer-free native arithmetic plan by a canonical semantic graph fingerprint plus Minecraft/loader, IR, adapter, and relevant mod/config versions. Keep world-seed-derived noise state in a per-world parameter binding unless the compiled node semantics force it into the key.
4. Bind current chunk context and volume at execution time. Existing CachingDensitySampler, ContextBoundSampler, buffer-arena and cache-cell behavior remain Java-owned boundaries until explicitly represented and parity-tested.
5. Because vanilla's compiler cache is a ConcurrentHashMap, source-function key equality follows equals/hashCode; do not assume distinct but equal DensityFunction instances compile into distinct samplers, or assume object identity alone is the vanilla cache contract.

The audit artifact for commit 00e443900923c72da73ddc2e04fb3f835a88cd82 confirms these method bodies in both artifacts. This is a bytecode/lifecycle finding, not evidence that the native graph is integrated into world generation or that it is faster.


### Constant-specialized compiled arithmetic lowering

The exact 26.3 sampler bytecode confirms that vanilla's optimizer emits record samplers for constant-specialized arithmetic. The native island planner now admits three exact forms in addition to generic ADD/SUB/CONSTANT:

- ConstAddSampler(leftSampler, rightFloat) lowers to left + rightConstant.
- ConstSubSampler(leftFloat, rightSampler) lowers to leftConstant - right.
- ConstMulSampler(leftSampler, rightFloat) lowers to left * rightConstant.

The constant fields are read as Java float record components and materialized as float32 constant nodes. Subtraction preserves the constant as the left operand; it is not rewritten as right - constant or as a negation plus addition. Multiplication uses the existing native float32 MUL opcode. No general MulSampler or DivSampler is admitted: their left-zero short-circuit behavior must remain explicit, and generic DIV is not implemented. Constant division, min, and max remain boundaries until their exact operation semantics are represented.

The new unit fixture verifies that a shared arithmetic island containing these three constant-specialized samplers lowers to one graph, keeps the external inputs deduplicated, and preserves operand order. This expands the set of candidate islands but does not claim live Minecraft parity or worldgen execution; the full-volume parity verifier must pass on actual sampler instances before integration.


### Follow-up bytecode detail: cache wrappers are traversable records

The exact 26.3 audit confirms that CachingDensitySampler and ContextBoundSampler are Java records, not opaque non-record wrappers. CachingDensitySampler exposes id and input record components; its sampleVolume and sampleValue delegate through SamplerContext.sampleVolumeCached and sampleValueCached. ContextBoundSampler exposes its context key and fallback sampler as record components, but the fallback is not necessarily the active sampler: evaluation resolves the current context field first. The trace can therefore preserve these wrapper nodes and their structural dependencies without inspecting arbitrary fields. The planner still treats them as hard execution boundaries and must not inline through them.

The bytecode also shows DensityFunctionCompiler.samplers is declared as Map but initialized with ConcurrentHashMap; getSampler uses computeIfAbsent, and optimizeAndCompile is serialized by a ReentrantLock. The compiler cache is world-scoped through RandomState, while SamplerContext and its cache cells remain chunk-scoped. These details were checked against both official and NeoForge-patched 26.3 artifacts.

The focused unit fixture models the CachingDensitySampler and ContextBoundSampler nodes with their recorded child edges and confirms that both remain hard boundaries. The exact Minecraft record-component structure itself is independently confirmed by the 26.3 javap audit; the focused pure-Java test source intentionally does not depend on Minecraft classes.


### Canonical sampler graph fingerprints for persistent cache keys

Compiled sampler inventories now have a deterministic SHA-256 semantic fingerprint. The encoding is length-prefixed and includes the IR semantic version, root id, node order and ids, exact operation kind, source implementation class, ordered child ids, named child edges, and scalar record components sorted by component name. It deliberately excludes diagnostic prose and map insertion order. Ordered operands remain significant, so subtraction and other non-commutative operations cannot collide after child reordering.

Incomplete captures with dropped nodes and graphs with missing/duplicate node references are rejected rather than fingerprinted. The cache helper combines this digest with the existing environment identity (Minecraft, loader, native build, mod set, dimension, worldgen settings, and registry fingerprints). This is a key-construction primitive only: no live sampler object or world/chunk context is serialized, and the cache is not yet wired into the worldgen execution path.


### Opt-in live Native sampler parity hook

The final-density fill boundary has a separate shadow-only parity hook. Set both a valid `jpe.nativeLibraryPath` and `-Djpe.verifyCompiledSamplerParity=true` to enable it. The hook loads the shared Native runtime without enabling Perlin interception, captures the real bound sampler graph once, lowers every eligible non-constant arithmetic island, and compares every island output float bit-for-bit with its original Java sampler. One per-volume boundary resolver is shared across islands so repeated external sampler identities are sampled once. Aggregate input and output staging is capped at 64 MiB.

A missing library, unsupported island, oversized staging request, Native error, or parity mismatch is logged and does not affect block placement; the original Java density sampler remains authoritative. The hook is disabled by default and does not change `jpe.nativePerlinEnabled`. This is still diagnostic shadow execution, not a production Native worldgen replacement.


### Float32 division and multi-island parity (IR v6)

The compiled sampler `ConstDivSampler` is classified as `CONST_DIV_LEFT` and may join pure arithmetic islands. Its constant numerator is emitted before the external sampler denominator, and the native `DIV` opcode performs IEEE-754 float32 division in that exact operand order. Unlike ordinary `DivSampler`, this constant-specialized node has no left-zero short-circuit. Native tests cover finite and negative results, division by positive zero, and `0/0` NaN.

The real-worldgen shadow verifier now visits every lowerable non-constant arithmetic island, not only the largest island. A single per-volume boundary-input resolver is shared across those islands so an identical external sampler is sampled only once. Aggregate input and output staging remains capped at 64 MiB. Each island is compared bit-for-bit against its original Java sampler; failures are isolated and never affect authoritative Java block placement.


### Batched multi-island execution

The shadow parity path validates candidate islands independently, then combines all lowerable disjoint islands into one Native graph with multiple output roots. External boundary samplers are deduplicated across the combined graph, so a shared source is sampled once and all island outputs are evaluated in one FFM/native dispatch. Opaque, cache, context, and short-circuit nodes remain boundaries; batching does not inline or reinterpret them. If combined lowering or execution fails, the verifier retries islands independently to retain fault isolation. The same 64 MiB aggregate input/output staging budget applies.
