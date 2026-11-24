# Native world-generation backend architecture

Status: foundation experiment. ABI v2 introduces a bounded asynchronous Rust worker pool for bulk native Perlin jobs. Minecraft production world generation is still vanilla Java; this change does not claim otherwise.

## Runtime responsibilities

- Rust owns the bounded task queue, worker scheduling, job state, copied input buffers, output buffers, cancellation state and result lifecycle.
- C++ owns the bulk numerical kernel implementation.
- Java 25 owns Minecraft/NeoForge lifecycle, registries, mod callbacks, configuration translation and safe result commit.

Java should submit large immutable batches, not orchestrate each voxel. Unsupported custom logic remains an explicit Java callback boundary or vanilla fallback.

## ABI v2 asynchronous task API

The asynchronous Perlin API copies all caller input arrays into Rust-owned vectors before returning. Worker threads never retain Java/FFM pointers or references to mutable Minecraft objects.

Resource bounds:
- Worker count: detected CPU parallelism, clamped to 1..8.
- Waiting task queue: 16 tasks.
- Tracked jobs: at most 32 pending, running or completed-but-not-released jobs.
- Per-job output: at most 1,048,576 floats (4 MiB).
- The theoretical retained output ceiling is therefore 128 MiB, excluding small axis and job metadata buffers.

The submit function returns a 64-bit job ID, or zero if validation fails or capacity is exhausted. Job status reports pending, running, complete, failed or cancelled. Completed results are copied into caller-owned memory. Callers must release every completed or abandoned job.

Cancellation prevents queued work from starting. A running C++ kernel is not interrupted mid-call; its result is discarded after the kernel returns. This limitation is explicit and must be improved before cancellation is used for rapidly changing chunk demand.

The scheduler is in-process. It is not a daemon, does not use shared-memory IPC, and does not use Vulkan or GPU compute.

## Safety and compatibility invariants

1. Native workers access only Rust-owned buffers.
2. Native code never retains a JVM pointer or mutable Minecraft object.
3. Chunk writes and lifecycle-sensitive operations must be committed on the Minecraft-owned executor/thread.
4. Future worldgen jobs must carry a world-generation epoch, seed/configuration snapshot and cancellation token; stale results must be discarded.
5. Rust panics and C++ exceptions must not cross the C ABI.
6. ABI mismatch disables native acceleration and selects vanilla.
7. Native acceleration remains opt-in until exact-output tests, mod compatibility tests and sustained device benchmarks pass.
8. Java mod callbacks are compatibility boundaries and cannot be silently skipped.

## Current implementation status

- Synchronous C++ Perlin volume call: present and used by the exact-parity GameTest.
- Rust asynchronous Perlin worker pool: added in ABI v2, with bounded queue, bounded job table, copied inputs, polling, result copy, cancellation state and release.
- Java FFM bridge: updated to accept ABI v2, but async job methods are not yet exposed.
- Minecraft production chunk generation: not redirected.
- Aquifer, density graph, surface rules, carvers, structures and features: not migrated.
- Persistent daemon, shared-memory ring and Vulkan backend: not implemented.

## Validation and next steps

Run cargo tests and release build, then Gradle unit tests, GameTests and the dedicated server benchmark. After CI, expose the async API through Java FFM and benchmark the complete input-copy + queue + compute + result-copy path against vanilla. Only a measured end-to-end win justifies connecting this kernel to a live worldgen stage.
