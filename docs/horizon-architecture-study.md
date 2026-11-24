# Horizon architecture study: lessons for JPE

Research target: `horizon-rust/horizon`, Minecraft Java Edition 26.3 reimplemented in Rust.
This is an architecture study, not a claim that JPE can reproduce Horizon's whole-engine results inside a Java mod.

## What Horizon actually optimizes

### 1. One process-wide worker pool

`crates/horizon-threads/src/jobs.rs` explicitly replaces several independent pools with one pool shared by chunk generation, lighting, meshing, far terrain and chunk encoding. The old arrangement could create four or five pools and roughly one hundred workers on a 32-thread machine, competing with the render and server threads.

Jobs are ranked by urgency and distance:
- `Urgent`: work the game is waiting for, such as saves and tick-side encoding.
- `Near`: nearby chunk generation, lighting and meshing.
- `Far`: distant terrain and viewing-only work.

Far jobs are capped so at least one worker remains available for near work. Each source owns its queue and can re-rank or discard jobs as the player moves. Workers take another job as soon as they finish, rather than waking on a fixed tick cadence.

### 2. Worker-local state, not per-call allocation

The shared pool exposes `PerWorker<T>`: each worker owns reusable generator/mesher scratch state. The worldgen worker is retained across jobs, so caches and buffers can be reused without one global mutable scratch area or repeated construction.

After an idle interval, a source can release its worker-local and shared caches. This avoids keeping every large cache forever while still preserving hot-path reuse during active generation. Cache census and per-job allocation tagging make memory costs observable.

### 3. A dependency-aware chunk pipeline

Horizon streams work through stages rather than treating a chunk as one giant opaque job:
- proto terrain generation (biomes, noise, surface, carvers);
- feature decoration over a 3x3 neighbourhood;
- finish/final column conversion;
- lighting;
- meshing and upload;
- save/eviction.

Neighbour dependencies are explicit. Decoration is order-dependent because features can write into adjacent chunks, so Horizon preserves a deterministic decoration order while allowing independent chunks to run concurrently. Its scheduler prioritizes the player-facing frontier and re-ranks work as the player moves.

### 4. Backpressure and bounded working sets

Pregeneration is split into strips and bands of roughly a thousand chunks. Once the leading row is lit and post-processed, the band advances and rows that leave the working range are saved/unloaded. This prevents a huge pregeneration request from retaining the entire generated area in memory.

### 5. Measurement discipline

Horizon's benchmark uses fresh worlds, identical seeds/settings/paths, serial runs on an otherwise idle machine, repeated trials, confidence intervals, frame-time percentiles, load times and process memory. It also separates startup, first terrain, chunk streaming, rendering and travel-induced regressions. Its own documentation cautions that matching terrain does not mean matching threading or per-chunk work.

## What transfers to JPE

JPE is a NeoForge mod inside the original Java engine. It cannot simply replace Minecraft's global chunk scheduler with Horizon's pool without creating major compatibility and lifecycle risks. The transferable ideas are narrower:

1. **Do not create a second pool for tiny kernels.** The current async Perlin bridge adds a Rust worker pool on top of Minecraft's worldgen workers. Every Perlin call also copies arrays, creates a tracked job, polls status and waits. The benchmark showed that most submissions fell back and Native throughput was below Scalar. First test a synchronous C++ call directly on the existing Minecraft worker.
2. **Make work coarse enough to amortize boundaries.** A native call should cover a substantial contiguous volume or a larger independent unit, not a tiny sample. Measure FFM copies and kernel time separately.
3. **Reuse scratch per existing worker.** Keep coordinate buffers and any future FFM scratch thread-confined. Never share mutable scratch between worldgen workers.
4. **Use explicit priority only where JPE owns scheduling.** JPE can rank optional precomputation or speculative jobs, but must not starve vanilla-required tasks or silently change chunk ordering.
5. **Keep dependencies and commit points explicit.** Workers may calculate immutable results off-thread; mutation of Minecraft-owned buffers/world state must occur only on the correct owning thread and at the correct chunk status.
6. **Bound memory and expose queue metrics.** Track queued, running, completed, rejected, cancelled and fallback counts, plus bytes retained. A larger queue is not automatically faster.
7. **Profile before porting more code.** Horizon's architecture suggests looking beyond Perlin at aquifer, climate lookup, palette/section work, lighting and meshing. JPE must use its own JFR and stage counters to prove which of those are hot in the Java/NeoForge path.

## First controlled experiment

Compare four fresh-server arms in ABBA order:
- Scalar Java Perlin A;
- synchronous C++ Perlin A;
- synchronous C++ Perlin B;
- Scalar Java Perlin B.

Keep the existing asynchronous Native ABBA as a separate diagnostic. Require bit-exact GameTest parity for both live modes. Record throughput, chunk latency, tick p95, CPU time, allocation, GC, native calls, synchronous calls, completions and fallbacks. Do not enable either Native mode by default unless the complete workload improves outside run-to-run noise.

## What “surpass Horizon” means here

A Java mod cannot honestly claim to beat a full Rust rewrite across startup, memory, rendering, worldgen and all game systems from a Perlin microbenchmark. JPE should target measurable wins on the original NeoForge/Java 26.3 stack, while retaining mod compatibility. The comparison must use the same machine, game build, seed, chunk frontier, settings and repeated fresh runs. Any claim should name the exact workload and metric.
