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
The `vectorBenchmark` Gradle task compiles a separate source set, packages it as a standalone JAR, and executes that exact JAR. The mod keeps all Vector API calls in a separately loadable implementation and activates it only when the module is resolved.

The JAR's entry class checks whether the Vector API module is actually resolved before loading the Vector-specific kernel. If the module is missing or unresolved, it prints the exact JVM option required instead of failing with an opaque `NoClassDefFoundError`.

The probe compares scalar and Vector API float multiply-plus-add, then benchmarks the same branch-heavy range-choice and min/max array kernels used by the mod. It checks bit-exact results and reports median and p95 ns/sample. These are isolated kernel timings, not full chunk-generation measurements.

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

The first desktop CI smoke test on 2026-11-23 used an x86-64 256-bit preferred species (8 float lanes). It measured 0.299 ns/sample for the scalar loop and 0.300 ns/sample for the Vector API loop (0.998x median speedup). The outputs were bit-exact, but this simple kernel showed no measurable speedup on that desktop host. This is not an ARM64 result.

This is an ARM64 runtime and arithmetic probe, not a Minecraft chunk-generation benchmark. A faster result for this one kernel does not establish faster Density Function evaluation, and it does not establish a world-generation improvement.

## Active density SIMD kernels

When `jdk.incubator.vector` is resolved, JPE reflectively loads Java 25 Vector API kernels and runs bit-exact startup self-tests before enabling them. If the module is missing, a self-test fails, or a kernel throws at runtime, the scalar implementation is used instead.

The first enabled operations are deliberately narrow:
- `RangeChoiceFunction.ConstSampler` and `RangeChoiceFunction.Sampler`: vectorized inclusive/exclusive range selection, including the full three-buffer path.
- `BinaryFunction.MinSampler` / `MaxSampler`, including constant-right variants: vectorized conditional replacement.

The full range-choice path keeps vanilla's branch-sampling order and pooled temporary-buffer lifecycle. The kernels preserve Minecraft's comparison semantics rather than using `Math.min`/`Math.max`, which can differ for NaN and signed zero. Loops stop at `DensityBuffer.size()`, never the pooled backing-array capacity. Devices without the Vector API retain the vanilla sampler path.

To force scalar execution for an A/B test, add `-Djpe.forceScalar=true` as a separate JVM argument. Remove it to allow automatic activation. The startup log line `JPE density kernels: active: ...` confirms bit-exact self-tests passed; it does not by itself prove faster world generation.

## World-generation stage metrics
The block-fill loop now processes one vertical chunk-section run at a time when the density volume has unit Y stride. That removes the section-boundary decision from every block iteration while preserving the original per-block aquifer, debug, block-state, heightmap, and post-processing operations. Non-unit Y volumes retain a defensive fallback.

For on-device diagnosis, add `-Djpe.worldgenMetrics=true` as one separate JVM argument. JPE then reports cumulative averages every 64 `doFill` calls, separating:
- `avgDensitySampleMs`: time spent evaluating the final-density volume.
- `avgBlockFillMs`: time spent converting sampled densities into blocks and updating heightmaps/post-processing.
- `avgSamplesPerCall`: number of density samples processed per call.

The metrics are disabled by default. They measure only these two `doFill` phases, not surface rules, carvers, lighting, chunk scheduling, or total world-generation time. Compare identical fresh-world runs and treat these timings as diagnostic data, not a complete benchmark.

## Test layers
1. Fast math tests: deterministic JUnit tests over one million float inputs.
2. Math microbenchmarks: the `benchmark` task compares a reference expression graph with a common-subexpression-elimination candidate; reports ns/sample, samples/s and checksums.
3. Vector probe: `vectorBenchmark` verifies arithmetic and the actual branch-heavy density kernels on the selected runtime.
4. Official in-game GameTests: `runGameTestServer` launches NeoForge GameTestServer and runs the registered `jpe_optimizer` namespace.
5. Worldgen integration: fixed-seed fresh-world chunk generation with generated chunk counts, elapsed time, CPU time, allocation and GC metrics is the next stage.
6. Device validation: sustained mobile thermal and power behavior requires real hardware; cloud CI cannot establish it.

## Correctness rule
Minecraft 26.3 evaluates Density Functions and noises with single-precision floating point, including intermediate operations. Reordering arithmetic can change rounding. Current code is a synthetic CSE experiment, not a replacement for vanilla worldgen.

## Standalone density graph server
`scripts/run-density-server.sh` starts a JDK-only HTTP service that compiles native density programs, evaluates chunk-sized volumes, and compares the result with a Java scalar reference. It needs neither Minecraft nor Gradle, only the built `libjpe_runtime`; the dashboard at `http://127.0.0.1:8787/` renders a volume slice and reports parity and p50/p95. See [docs/density-graph-server.md](docs/density-graph-server.md).

## Commands
- `scripts/run-density-server.sh`
- `gradle test`
- `gradle benchmark`
- `gradle vectorBenchmark`
- `gradle runGameTestServer`
- `gradle build`

## Benchmark protocol
Use the same Java version, CPU allocation, JVM flags, warmup, input corpus and sample count for each implementation. Run multiple forks and alternate baseline/candidate order to reduce thermal and JIT bias. Report median and p95, not only the best run. Java and Rust comparisons must use the same algorithm and inputs.

No performance improvement is claimed until measured against an unmodified Minecraft 26.3 baseline.
