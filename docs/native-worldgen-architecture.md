# Native world-generation backend architecture

Status: foundation experiment. ABI v6 provides a bounded asynchronous Rust worker pool for bulk native Perlin jobs, configurable against process-visible CPU parallelism. Minecraft production world generation remains opt-in and is not yet a wholesale replacement for vanilla Java.

## Runtime responsibilities

- Rust owns the bounded task queue, worker scheduling, job state, copied permutation input, cancellation state and result lifecycle.
- Java owns a leased shared FFM output buffer; the native worker borrows it only until terminal job status.
- C++ owns the bulk numerical kernel implementation.
- Java 25 owns Minecraft/NeoForge lifecycle, registries, mod callbacks, configuration translation and safe result commit.

Java should submit large immutable batches, not orchestrate each voxel. Unsupported custom logic remains an explicit Java callback boundary or vanilla fallback.

## ABI v6 asynchronous task API

The asynchronous Perlin API copies the 256-byte permutation into Rust-owned memory and borrows a writable shared FFM output buffer until terminal job status. Java keeps that buffer leased and exclusively reserved until the job is released. The native worker writes only into this temporary buffer; Java copies it into the Minecraft density array only after successful completion. Axis coordinates are represented by min/step metadata and reconstructed inside C++. Worker threads never retain Java/FFM pointers or references to mutable Minecraft objects.

Resource bounds:
- Worker count: configured before the scheduler starts. The Java bridge defaults to `max(1, availableProcessors - jpe.nativeWorkerReserve)` with a default reserve of one core. `-Djpe.nativeWorkers=N` selects an explicit count; requests are capped to process-visible CPU parallelism. `auto` is the default. The old fixed eight-worker cap has been removed.
- Waiting task queue: 16 tasks.
- Tracked jobs: at most 32 pending, running or completed-but-not-released jobs.
- Per-job output: at most 1,048,576 floats (4 MiB), held in the Java-owned FFM scratch arena.
- Rust no longer allocates or retains a second output vector per job, reducing native heap copies and retained memory.

The submit function returns a 64-bit job ID, or zero if validation fails or capacity is exhausted. Job status reports pending, running, complete, failed or cancelled. Java can wait on a native condition variable with a bounded timeout instead of polling at a fixed 1 ms interval. Completed results are already in the leased FFM buffer; Java commits them to the target array only after success. Callers must release every completed or abandoned job.

Cancellation prevents queued work from starting. A running C++ kernel is not interrupted mid-call; cancellation is therefore advisory once execution begins, and Java waits for a terminal state before releasing the borrowed buffer.

The scheduler is in-process. It is not a daemon, does not use shared-memory IPC, and does not use Vulkan or GPU compute.

## Safety and compatibility invariants

1. Native workers access only the copied permutation and the explicitly borrowed shared FFM output buffer; the borrow is bounded by the job lifecycle.
2. Native code never retains a Java heap pointer or mutable Minecraft object.
3. Chunk writes and lifecycle-sensitive operations must be committed on the Minecraft-owned executor/thread.
4. Future worldgen jobs must carry a world-generation epoch, seed/configuration snapshot and cancellation token; stale results must be discarded.
5. Rust panics and C++ exceptions must not cross the C ABI.
6. ABI mismatch disables native acceleration and selects vanilla.
7. Native acceleration remains opt-in until exact-output tests, mod compatibility tests and sustained device benchmarks pass.
8. Java mod callbacks are compatibility boundaries and cannot be silently skipped.

## Current implementation status

- Synchronous C++ Perlin volume call: present and used by the exact-parity GameTest.
- Rust asynchronous Perlin worker pool: bounded queue and job table, copied permutation, borrowed FFM output buffer, configurable worker count (same-setting reinitialization is idempotent), condition-variable completion wait, queued-job cancellation and release.
- Java FFM bridge: exposes synchronous and asynchronous Perlin calls, condition-variable waits, job lifecycle management and per-calling-thread reusable scratch arenas. Scratch arenas are no longer capped at eight threads.

- Minecraft production chunk generation: not redirected.
- Aquifer, density graph, surface rules, carvers, structures and features: not migrated.
- Persistent daemon, shared-memory ring and Vulkan backend: not implemented.

## Validation and next steps

Run cargo tests and release build, then Gradle unit tests, GameTests and the dedicated server benchmark. After CI, compare one-worker and auto-sized worker configurations on fresh servers, then benchmark the complete input-copy + queue + compute + result-copy path against vanilla. Only a measured end-to-end win justifies connecting this kernel to a live worldgen stage.


## Palette hash experiment

`-Djpe.paletteHashEnabled=true` replaces only the 3-bit and 4-bit linear palette configurations with Minecraft's existing HashMap palette factory. The default remains vanilla. This is an isolated experiment, not an assumed optimization: GameTest checks that the requested Mixin actually applied, and CI compares fresh same-seed servers in off/on/on/off order. Keep the option disabled unless the full benchmark shows a repeatable end-to-end gain without changing generated chunk contents.

## Required end state: closed native terrain execution

The current Perlin volume bridge and compiled-sampler arithmetic-island path are transitional experiments, not the target architecture. In particular, `CompiledSamplerExternalInputs` calls Minecraft's `DensitySampler.sampleVolume` for every opaque boundary. That path is allowed only for shadow diagnostics and must never become the production density execution path. Its per-chunk Java sampling and input staging costs are not a tuning target; production must remove that work from the chunk hot path.

The production density plan must be a **closed native graph** for every supported world configuration:

1. At world preparation time, inspect the actual compiled density graph and the exact registered worldgen configuration. Resolve all static sampler structure, noise parameters, octave/layer lists, interpolation rules, and supported cache semantics into a versioned native plan.
2. Persist the plan under a cache identity that includes Minecraft/NeoForge versions, native IR/ABI versions, registry/configuration fingerprints, and relevant mod identities. Reuse it on later launches. Do not rediscover or translate the graph per chunk.
3. At chunk execution time, pass only genuinely dynamic chunk inputs that cannot be known at world preparation (coordinates, chunk-local Blender/Beardifier state, seed-derived runtime handles, and bounded scratch/output memory). C++ evaluates the complete final-density graph and writes the density volume directly into the chunk task's native output buffer.
4. No Java `sampleVolume()` calls may be used to resolve density graph nodes on the production path. No per-node Java callbacks, per-node FFM calls, or Java-generated external-input float volumes are permitted for built-in terrain nodes.
5. Every built-in density/noise operation needed by a selected generator must have a native semantic implementation. Mod-provided density/noise operations require an explicit adapter/compiler contract; if a graph contains an unsupported operation, native authoritative mode must reject that plan rather than silently sampling it in Java or silently dropping it. A compatibility mode may keep vanilla Java generation for the whole affected plan, clearly reported as a fallback, but must not claim native execution.
6. After density is closed and bit-exact, migrate aquifer substance selection, surface rules, carvers, and other terrain stages into native execution in separately validated slices. Keep registry registration and genuinely game-owned lifecycle callbacks on Java where required.

The success criterion is not a faster arithmetic island. It is that a generated chunk's terrain density is evaluated by one prepared C++ plan without Java doing the density math or staging opaque sampler volumes during that chunk. Shadow parity must compare the complete final-density volume, then same-seed block-state hashes, before production handoff is enabled.
