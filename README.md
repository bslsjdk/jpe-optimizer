# JPE Optimizer

Experimental Minecraft Java Edition **26.3** world-generation optimization mod and benchmark laboratory.

The first target is fresh chunk generation, especially Density Function and Noise evaluation. Correctness is separate from speed: a candidate is not eligible for runtime use until it passes bit-level equivalence checks against the 26.3 float-precision reference.

## Toolchain
- Minecraft Java Edition 26.3
- NeoForge 26.3.0.115
- Java 25
- ModDevGradle 2.0.148
- Gradle 9.2.1

## Java runtime diagnostics
At mod initialization, JPE logs the actual runtime version, VM, vendor, CPU architecture, operating system, processor count, heap limit, compiler, active garbage collectors, JVM input arguments, and selected Java 25 module capabilities.

The Vector API checks distinguish:
- `vectorInImage`: the runtime image contains `jdk.incubator.vector`.
- `vectorResolved`: the module is resolved in the boot module layer and can be used by the application.

ZL2's Java 25 runtime image is expected to package the incubator module. To resolve it, add `--add-modules=jdk.incubator.vector` to the Minecraft profile's JVM arguments. The diagnostic reports the preferred FloatVector lane count only when the module is active. This is a capability probe, not proof that a particular world-generation loop was vectorized or accelerated.

The probe also reads `UseCompactObjectHeaders` when the running VM exposes that option. It does not enable the option or change garbage-collector settings. Runtime flags must be benchmarked on the actual device before being recommended.

## Test layers
1. Fast math tests: deterministic JUnit tests over one million float inputs.
2. Microbenchmarks: the `benchmark` task compares a reference expression graph with a common-subexpression-elimination candidate; reports ns/sample, samples/s and checksums.
3. Official in-game GameTests: `runGameTestServer` launches NeoForge GameTestServer and runs the registered `jpe_optimizer` namespace.
4. Worldgen integration: fixed-seed fresh-world chunk generation with generated chunk counts, elapsed time, CPU time, allocation and GC metrics is the next stage.
5. Device validation: sustained mobile thermal and power behavior requires real hardware; cloud CI cannot establish it.

## Correctness rule
Minecraft 26.3 evaluates Density Functions and noises with single-precision floating point, including intermediate operations. Reordering arithmetic can change rounding. Current code is a synthetic CSE experiment, not a replacement for vanilla worldgen.

## Commands
- `gradle test`
- `gradle benchmark`
- `gradle runGameTestServer`
- `gradle build`

## Benchmark protocol
Use the same Java version, CPU allocation, JVM flags, warmup, input corpus and sample count for each implementation. Run multiple forks and alternate baseline/candidate order to reduce thermal and JIT bias. Report median and p95, not only the best run. Java and Rust comparisons must use the same algorithm and inputs.

No performance improvement is claimed until measured against an unmodified Minecraft 26.3 baseline.
