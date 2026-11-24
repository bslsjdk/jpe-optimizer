# GPU-first world-generation backend

Status: architecture and implementation plan. This document does not claim that GPU world generation is implemented or enabled.

## Target responsibility split

The intended runtime is deliberately not a Java-to-native-to-Java per-sample pipeline.

- **Java 25 / NeoForge adapter:** owns Minecraft and mod lifecycle, reads registries and configuration, identifies which generator graph nodes are compatible with the native backend, submits compact chunk-job descriptors, executes required Java mod callbacks, validates completed results, and commits block/chunk data on Minecraft-owned executors. It must not calculate or relay every density sample.
- **Rust daemon:** a separate long-lived process. Owns the bounded job queue, shared-memory protocol, cancellation, world/dimension generation epochs, CPU fallback scheduling, GPU-device selection, memory budgets, and backend health. It stays resident between chunk requests.
- **C++ compute host:** compiled separately and loaded by the Rust daemon as a native library. Owns Vulkan instance/device/queue, descriptor sets, command buffers, pipeline cache, staging allocations, and GPU submission. Rust calls it once per large batch, not once per sample.
- **GPU kernels:** compute shaders compiled to SPIR-V. The first portable target is Vulkan compute, including Android Vulkan and desktop Vulkan. GLSL shader source is not C++; the C++ layer is the Vulkan host that dispatches it. CPU scalar kernels remain the correctness fallback.

C++ and Rust remain independently buildable and testable, but they should not be separate operating-system processes initially. A Rust process plus a C++ shared library avoids another IPC boundary between the scheduler and the GPU submitter.

## Data path

1. At world load or when generator settings change, Java creates an immutable, versioned generation snapshot. It contains the world seed, dimension identity, relevant registry IDs/configuration, noise parameters, and a compiled description of supported density graph nodes. It is not a copy of live Minecraft objects.
2. Java publishes a chunk-job descriptor into a bounded shared-memory ring. The descriptor identifies the generation snapshot, chunk coordinates, requested stage, input/output offsets, sizes, and cancellation generation. Large numeric inputs and result arrays live in the mapped region, not in per-call Java arrays copied through FFM.
3. The resident Rust daemon consumes ready descriptors, checks bounds and generation identity, batches compatible work, and dispatches it to the C++ Vulkan backend.
4. The GPU evaluates independent chunk/volume work in parallel. Results are written into output slots in the shared mapped region where the selected Vulkan memory type permits it. Otherwise the C++ backend uses an explicit staging copy. Zero-copy GPU access is an optional capability, never an assumption.
5. Rust validates completion and marks the slot complete. It never exposes native pointers as Minecraft objects.
6. Java receives a completion notification, checks that the world epoch and chunk status are still current, runs required Java-side mod callbacks, and commits the finished data on the correct Minecraft executor.
7. Unsupported nodes, unavailable Vulkan devices, driver failures, timeouts, or validation failures fall back to the vanilla Java implementation. A failed GPU job must never leave a partially committed chunk.

The notification channel should be a local Unix-domain socket on Android/Linux (and a platform equivalent on desktop). It carries only wakeups, health, and compact status messages. The shared mapping carries bulk job descriptors and arrays.

## Shared-memory protocol

Use a fixed-size, versioned ring rather than unbounded queues or arbitrary pointers.

The header should contain magic, protocol version, mapping size, slot count, producer/consumer sequence numbers, and backend health. Each slot should contain:

- atomic state: FREE, READY, RUNNING, COMPLETE, FAILED, or CANCELLED;
- job ID and world-generation epoch;
- dimension/generator snapshot ID and chunk coordinates;
- stage and kernel-graph ID;
- bounded input and output offsets/lengths;
- result status and diagnostic code.

Use release/acquire ordering when publishing and consuming slot states. Validate every offset, multiplication, alignment, length, and epoch before touching memory. No raw JVM address, Java object reference, Rust slice, or C++ pointer may be persisted in the protocol. The mapping size, number of in-flight jobs, and per-job output size must all have hard limits. Native memory and GPU memory are measured separately from JVM heap.

For Android, begin with an app-private mapped file shared by the Java process and its child daemon under the same app UID. Use a file-backed mapping as the portable baseline. Add Android shared-memory/FD passing and Vulkan external-memory import only after device/driver capability checks. Desktop can use the corresponding file mapping or OS shared-memory primitive.

## GPU constraints that affect the design

- A GPU cannot consume Minecraft Java objects, registries, block states, or arbitrary mod callbacks directly. Inputs must be flattened into compact numeric data and stable registry IDs first.
- A GPU dispatch is not automatically faster. Small jobs can lose to command submission, synchronization, upload/download, and shader launch overhead. Batch multiple compatible chunk/volume jobs and keep intermediate density data on the GPU across adjacent stages where possible.
- Integrated Android GPUs often share physical DRAM with the CPU, but that does not guarantee a Vulkan buffer is directly importable from the shared mapping or coherent without explicit synchronization.
- Discrete desktop GPUs commonly require staging transfers. Benchmark the complete path, not shader execution time alone.
- Floating-point contraction, denormals, rounding, integer overflow, and shader precision can differ from Java. Exact parity must be specified per kernel. If a kernel cannot meet required semantics, it must not replace that vanilla stage.
- Driver/device loss and memory pressure must trigger clean cancellation and vanilla fallback. Never block the Minecraft main thread waiting for a GPU fence.

## Mod compatibility boundary

The native compiler may lower only graph nodes whose behavior is fully understood. Unknown custom density functions, custom biome sources, modded surface rules, structure hooks, feature callbacks, and other Java extension points remain explicit Java callback/fallback boundaries. Do not silently skip a mod callback to keep a chunk on the GPU. A chunk may use a mixed pipeline, but every boundary and synchronization cost must be measured.

## Implementation stages

1. **Protocol first:** implement and unit-test the versioned shared-memory ring, bounded slot lifecycle, cancellation, stale-epoch rejection, and process restart behavior without a GPU.
2. **Resident process:** launch one Rust daemon per game instance, health-check it, restart safely, and prove that repeated jobs do not restart the process or allocate unbounded memory.
3. **Vulkan probe:** C++ enumerates the physical device, queue families, extensions, memory types, subgroup limits, and compute capabilities. Run a trivial shader and report the exact device/driver. If Vulkan is unavailable, keep CPU fallback.
4. **First GPU kernel:** port one simple, high-volume numeric kernel with a precisely defined input/output contract. Compare CPU C++, GPU shader, and the exact Minecraft 26.3 Java implementation. Perlin is a candidate, but its current native C++ implementation is scalar CPU code, not a GPU kernel.
5. **End-to-end benchmark:** compare vanilla Java, Rust/C++ CPU, GPU-only, and complete Java -> shared memory -> Rust -> C++/Vulkan -> shared memory -> Java commit. Include throughput, p50/p95 latency, CPU/GPU utilization, process/JVM/native/GPU memory, energy/thermal behavior on Android, and failed/cancelled jobs.
6. **Incremental worldgen lowering:** only after parity and end-to-end wins, extend the compiled graph to aquifers, density functions, surface rules, and other stages. Preserve Java mod callbacks and vanilla fallback.

## Acceptance gates

- No GPU path is enabled by default until exact output behavior or an explicitly approved numerical tolerance is demonstrated for the relevant stage.
- A speed claim must include transfer, scheduling, synchronization, and Java commit costs.
- The Android target must be tested on the actual ZL2 Java 25 runtime and physical GPU. Desktop CI cannot establish Android driver compatibility.
- If GPU is slower for a workload, the scheduler chooses the CPU backend for that workload.
- The Minecraft main thread must never wait synchronously for GPU completion.

## Current implementation status

The repository currently has a scalar C++ Perlin volume function behind a Rust ABI and a Java 25 FFM parity GameTest. It is not a daemon, does not use shared memory, does not use Vulkan, and is not connected to production chunk generation. The CI workflow now preserves GameTest logs separately so the native parity marker can be inspected rather than being erased by later benchmark runs.
