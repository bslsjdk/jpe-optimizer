# Native CPU backend audit: Perlin-stack batching

Date: 2026-11-24

Status: repository code-path audit and implementation proposal. This document makes no performance claim. The current source-audit workflow for the pinned Minecraft 26.3 / NeoForge artifact is still required before relying on third-party decompilation details.

## Executive finding

The repository already has a native hook on the real PerlinNoise.addToVolume call site. When explicitly enabled and when the native library loads, PerlinNoiseVolumeMixin can replace that Perlin volume operation with the Rust/C++ backend. This is narrower than replacing the final density sampler or the whole chunk-generation stage.

The next useful optimization is not another Java micro-optimization and not a wholesale rewrite of the density graph. It is to move the boundary outward from one Perlin octave to one complete octave stack / noise-volume operation, then benchmark that design against the current per-octave bridge.

## Current call and data path

The repository implementation currently has these layers:

1. PerlinNoiseVolumeMixin injects at PerlinNoise.addToVolume HEAD.
2. NativePerlinNoiseBackend.tryAddToVolume checks the opt-in property and validates volume dimensions.
3. NativeRuntime copies the 256-byte permutation and the existing density output into a reusable shared FFM scratch arena.
4. Rust accepts the job, copies the permutation into Rust-owned memory, and queues a task.
5. A Rust worker calls the C++ jpe_cpp_perlin_add_volume kernel using the leased FFM output buffer.
6. The Java caller waits for a terminal job status, copies the output back into the Minecraft density buffer, and releases the job/scratch lease.

Relevant implementation files:

- src/main/java/dev/jpe/optimizer/mixin/PerlinNoiseVolumeMixin.java
- src/main/java/dev/jpe/optimizer/runtime/NativePerlinNoiseBackend.java
- src/main/java/dev/jpe/optimizer/runtime/NativeRuntime.java
- native/rust-runtime/src/lib.rs
- native/include/jpe_native_api.h
- native/cpp-kernels/src/perlin_volume.cpp

The hook is opt-in: jpe.nativePerlinEnabled=true and a valid jpe.nativeLibraryPath are both required. Otherwise the vanilla Java path remains active.

## Important costs and limits found in the code

### 1. The current async API is a worker hand-off, not a non-blocking chunk pipeline

NativePerlinNoiseBackend.tryAddToVolume submits a job and then waits in a loop until the job is complete, failed, or cancelled. This is necessary for the current synchronous addToVolume contract: the density buffer must be ready before its caller continues.

Consequently, one volume call does not overlap later Minecraft work on the same caller thread. Multiple independent worldgen callers can still have jobs executing concurrently on Rust workers, but this competes with Minecraft's existing chunk-generation executor. The worker count must therefore be benchmarked rather than assumed to help.

### 2. The bridge copies the full output volume for every intercepted Perlin call

NativeRuntime.submitPerlinVolume copies the existing Java float array into the FFM output segment before submission, because the C++ kernel adds its result to the existing values. After completion, Java copies the full segment back into the target array.

That is two full-volume transfers per intercepted call, in addition to the native calculation. The permutation is copied into FFM and then copied again into a Rust Vec<u8>. These are bounded copies, but repeating them for every octave is avoidable overhead.

### 3. The hook boundary is probably too narrow

The source audit describes NoiseStack as iterating its octave/layer list and delegating each layer to its noise implementation. If the official 26.3 artifact confirms that call structure, the current hook pays the FFM/Rust job lifecycle and full output copy once per layer.

A stack-level batch operation can instead:
- submit all eligible layer descriptors in one native call/job;
- copy the initial output volume into native scratch once;
- accumulate layers in the same order as vanilla;
- copy the final volume back once;
- amortize queue, condition-variable, FFM and job-table overhead across the stack.

Do not assume the number of layers or exact class/method layout until the official artifact audit confirms it.

### 4. The current C++ kernel is scalar CPU code

perlin_volume.cpp uses scalar loops. It already hoists and reuses some coordinate and gradient work, including per-thread Y-coordinate scratch, but it is not a SIMD kernel and it is not a GPU kernel. Any SIMD implementation must preserve the exact operation order and floating-point behavior required by the pinned game version.

### 5. The float contract is version-sensitive

Minecraft 26.3 changed density-function and noise evaluation to single-precision floating point, including intermediate operations. The native path must be checked against the official 26.3 artifact for every conversion and accumulation point. Algebraic equivalence or visually similar terrain is not a parity test.

## Proposed C++ / Rust boundary

Prefer a new stack-level ABI rather than adding more per-sample calls.

Conceptual input:
- volume dimensions and axis min/step metadata;
- ordered array of layer descriptors;
- each layer's 256-entry permutation table and exact offsets/scales/amplitude fields;
- existing output values in one leased output segment;
- versioned flags for coordinate wrapping and numeric mode.

Execution:
- Java extracts immutable numeric descriptors at the supported boundary;
- Rust validates dimensions, descriptor count, output length and resource limits, then owns the job lifecycle;
- C++ evaluates layers in the same order as vanilla and accumulates into one output volume;
- Java waits for completion and commits the final volume only after success;
- unsupported or modded noise implementations continue through vanilla.

The first implementation should support only the exact vanilla layer type whose fields and behavior are verified. Unknown custom implementations must fall back rather than being silently treated as vanilla.

## Required experiments before enabling this path

Run the same fixed-seed, fresh-world benchmark in these modes:

1. Vanilla Java.
2. Current native synchronous Perlin call.
3. Current Rust async per-Perlin call.
4. New Rust/C++ batched stack call.

Record:
- completed fresh chunks per second;
- full-chunk p50 and p95 latency;
- final-density sample time separately from block fill;
- native calls/jobs per chunk and queue rejection count;
- bytes copied between Java and FFM per chunk;
- CPU utilization and thread count;
- JVM heap, native memory and GC;
- exact final-density float bits and final block-state hashes.

Use repeated ABBA or randomized order on fresh servers. Report median and spread. A faster isolated C++ kernel is not sufficient if bridge copies, queueing or block fill erase the gain.

## Immediate next implementation steps

1. Complete the official Minecraft 26.3 and NeoForge-patched artifact audit. Record artifact hashes and verify the actual NoiseStack, PerlinNoise, and density-volume call chain.
2. Add a native stack-batch kernel and Rust ABI behind a separate opt-in flag. Keep the existing per-Perlin ABI as the reference/fallback.
3. Add C++ unit tests comparing stack batching with repeated calls to the existing scalar kernel, including non-zero initial output and multiple layers.
4. Add a Java GameTest that compares vanilla, current native and batched-native output over fixed seeds and volumes, checking float bits.
5. Only after parity passes, benchmark complete fresh chunk generation and tune worker count.

## Explicit non-goals for this experiment

- No Java-only micro-optimization as the main deliverable.
- No migration of aquifers, surface rules, carvers, structures or features yet.
- No GPU/Vulkan work until the CPU batch path has exact parity and measured end-to-end value.
- No default enablement and no claim of speedup before complete-server measurements.
