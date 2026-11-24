# Native world-generation backend architecture

Status: foundation experiment only. This change does not redirect any Minecraft world-generation stage. Vanilla remains authoritative and is always the fallback.

## Intended division of responsibility

- Rust runtime: world-generation task orchestration, bounded worker scheduling, native buffer ownership, batch dispatch, cancellation and result lifecycle.
- C++ kernels: measured compute-heavy kernels such as noise and density evaluation, with SIMD backends selected only after device-specific measurements.
- Java compatibility layer: Minecraft and NeoForge lifecycle, registries, mod discovery, Java mod callbacks, configuration translation, event compatibility and safe commit of completed chunk data.

Java should not perform per-voxel orchestration. It should translate immutable generation inputs into compact batch data, submit large jobs, and receive results. Calls into custom Java mod code must remain possible. Unsupported custom logic must execute through an explicit Java callback boundary or the vanilla path.

## Safety and compatibility invariants

1. Native code must not retain references to mutable Minecraft objects.
2. Chunk writes and lifecycle-sensitive operations are committed on the Minecraft-owned executor/thread.
3. Every native job is tied to a world-generation identity, seed/configuration snapshot and cancellation token. Stale results are discarded.
4. Buffer ownership is explicit and bounded. Native memory is measured separately from JVM heap.
5. Rust panics and C++ exceptions must not cross the C ABI. Errors return status codes.
6. The ABI is versioned. Unknown ABI versions disable the native backend and select vanilla.
7. Native acceleration is opt-in until exact-output tests, mod compatibility tests and sustained device benchmarks pass.
8. Java mod callbacks are compatibility boundaries, not something the native engine may silently skip.

## Target execution pipeline

1. Java reads the active dimension's registry/configuration graph and builds an immutable, versioned snapshot.
2. A compatibility compiler lowers supported vanilla density/noise graph nodes into a compact native graph. Unsupported/custom Java nodes become explicit callback boundaries.
3. Rust schedules independent chunk/volume jobs and owns reusable buffers.
4. C++ executes bulk numerical kernels through a narrow C ABI. Cross-language calls are per batch, never per sample or per block.
5. Rust returns owned result buffers and status metadata.
6. Java validates world identity, chunk status and cancellation state, then commits results through the correct Minecraft lifecycle.
7. Unsupported features or validation/load failures use the vanilla Java implementation.

This is a staged target, not a claim that all Minecraft generation stages can already be lowered.

## First milestone

The initial foundation provides:
- a versioned C ABI header;
- a Rust cdylib/staticlib runtime boundary;
- a C++ scalar bulk-kernel probe called by Rust;
- a Java 25 FFM loader with ABI-version validation;
- Rust tests and a CI compile/test step.

The add-each-value-to-itself operation is only an ABI smoke test. It is not a world-generation optimization and is not called from Minecraft generation.

## Build and test

From the repository root:

```sh
cargo test --manifest-path native/rust-runtime/Cargo.toml
cargo build --release --manifest-path native/rust-runtime/Cargo.toml
gradle test
gradle build
```

On Linux x86-64 the library is written to native/rust-runtime/target/release/libjpe_runtime.so.

Java 25 FFM requires native access to be enabled for the calling module. For this class on the class path, launch with:

```text
--enable-native-access=ALL-UNNAMED
```

Then load the library explicitly with NativeRuntime.load(Path.of("/absolute/path/to/libjpe_runtime.so")). The library is never loaded automatically.

## Next milestones

1. Verify Java 25 FFM loading and calls on the exact ZL2 Android runtime (ARM64), not only desktop CI.
2. Add an immutable world-generation snapshot format and a versioned registry/configuration translation layer.
3. Port one small, precisely specified vanilla kernel and compare output bits against the exact 26.3 NeoForge-patched Java implementation.
4. Add batch density evaluation with exact float/double operation ordering.
5. Introduce Rust scheduling only after the single-batch kernel is correct and faster end-to-end.
6. Extend support to Aquifer, surface rules, carvers, structures and features incrementally, with explicit Java mod callback fallback at every unsupported boundary.

Do not enable native world generation by default until output equivalence and mod-compatibility coverage are demonstrated on the target device.


## First real kernel: Perlin volume accumulation

The first native worldgen candidate is the bulk equivalent of the existing Java Perlin volume vectorizer. The ABI accepts the 256-entry permutation table, block-coordinate axes, offsets/scales, amplitude and one contiguous output volume. It performs one call per volume, not one call per sample.

The C++ implementation deliberately starts scalar. It preserves the Java float expression order and disables compiler FP contraction/fast-math. The GameTest creates the same seeded Minecraft PerlinNoise instance, obtains its permutation/offset state through the existing Mixin accessor, computes a vanilla reference volume with the optimized path bypassed, and compares every output using raw float bits against the Rust/C++ result.

This test is a gate, not a claim of speed. The Java bridge currently copies arrays into a call-scoped native Arena; this is intentionally simple and correct first. If the kernel passes parity, the next optimization is reusable per-thread native buffers and then C++ SIMD, with end-to-end chunk benchmarks. Do not route production world generation to this kernel until both parity and throughput tests pass.
