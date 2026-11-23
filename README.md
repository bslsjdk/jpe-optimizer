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

ZL2's Java 25 runtime image is expected to package the incubator module. To resolve it, add `--add-modules=jdk.incubator.vector` to the JVM arguments used by the Java process. The diagnostic reports the preferred FloatVector lane count only when the module is active. This is a capability probe, not proof that a particular world-generation loop was vectorized or accelerated.

The probe also reads `UseCompactObjectHeaders` when the running VM exposes that option. It does not enable the option or change garbage-collector settings. Runtime flags must be benchmarked on the actual device before being recommended.

## Vector API probe
The `vectorBenchmark` Gradle task compiles a separate source set, packages it as a standalone JAR, and executes that exact JAR. The shipped mod therefore has no compile-time dependency on the incubator module.

The probe compares scalar and Vector API float multiply-plus-add over identical deterministic arrays, verifies bit-exact results, alternates execution order, and reports median and p95 ns/sample together with the runtime architecture and preferred vector species.

Build and run on a desktop:
```sh
gradle vectorBenchmark
```

The standalone file is written to:
```
build/vector-probe/jpe-vector-probe.jar
```

### Running on ZL2 / Android
1. Download `jpe-vector-probe.jar` from the `jpe-optimizer-ci` artifact attached to a successful GitHub Actions run.
2. In ZL2, open Java Management and use the **Execute JAR** import action. Long-press that action to choose the runtime, then select `Internal-25` and the downloaded JAR.
3. Ensure the JVM arguments used by the JAR process include this exact argument as one separate argument: `--add-modules=jdk.incubator.vector`. ZL2's JAR launcher reuses the configured user JVM arguments; do not concatenate this flag with another argument.
4. Read the output in ZL2's Java execution log. Save the full output, including the reported `os.arch`, `java.version`, preferred species and scalar/vector timings.

If the Vector API module is not resolved, the program will say so and exit before the benchmark. Do not interpret the scalar fallback as a Vector API result.

This is an ARM64 runtime and arithmetic probe, not a Minecraft chunk-generation benchmark. A faster result for this one kernel does not establish faster Density Function evaluation, and it does not establish a world-generation improvement.

## Test layers
1. Fast math tests: deterministic JUnit tests over one million float inputs.
2. Math microbenchmarks: the `benchmark` task compares a reference expression graph with a common-subexpression-elimination candidate; reports ns/sample, samples/s and checksums.
3. Vector probe: the standalone `vectorBenchmark` JAR checks scalar/vector arithmetic equivalence and reports a controlled microbenchmark.
4. Official in-game GameTests: `runGameTestServer` launches NeoForge GameTestServer and runs the registered `jpe_optimizer` namespace.
5. Worldgen integration: fixed-seed fresh-world chunk generation with generated chunk counts, elapsed time, CPU time, allocation and GC metrics is the next stage.
6. Device validation: sustained mobile thermal and power behavior requires real hardware; cloud CI cannot establish it.

## Correctness rule
Minecraft 26.3 evaluates Density Functions and noises with single-precision floating point, including intermediate operations. Reordering arithmetic can change rounding. Current code is a synthetic CSE experiment, not a replacement for vanilla worldgen.

## Commands
- `gradle test`
- `gradle benchmark`
- `gradle vectorBenchmark`
- `gradle runGameTestServer`
- `gradle build`

## Benchmark protocol
Use the same Java version, CPU allocation, JVM flags, warmup, input corpus and sample count for each implementation. Run multiple forks and alternate baseline/candidate order to reduce thermal and JIT bias. Report median and p95, not only the best run. Java and Rust comparisons must use the same algorithm and inputs.

No performance improvement is claimed until measured against an unmodified Minecraft 26.3 baseline.
