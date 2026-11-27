# Running the mod on a real Minecraft server

This is the end-to-end check that the pipeline works outside a unit test: a
NeoForge 26.3 dedicated server loads the built jar, unpacks the native runtime
that ships inside it, and generates a world.

## Prerequisites

| Component | Version | Notes |
|---|---|---|
| JDK | 25 | Minecraft 26.3 and the mod's `--release 25` both require it |
| Gradle | 9.1+ | Gradle 8 cannot run on Java 25 (`class file major version 69`) |
| NeoForge | 26.3.0.115 | the installer is downloaded by the script |

The build machine needs roughly 8 GB of free disk for the Minecraft artifacts
and about 8 GB of addressable memory. On a container with a CPU-count-derived
heap limit, NeoForm sizes Vineflower at `256 MB × availableProcessors`, so a
32-core container asks for an 8 GB heap and gets killed. Cap the perception:

```bash
JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=8" \
JAVA_HOME=/path/to/jdk-25 \
gradle --no-daemon -Dorg.gradle.java.installations.paths=/path/to/jdk-25 jar
```

The resulting `build/libs/jpe_optimizer-0.1.0.jar` contains
`META-INF/natives/linux-x86_64/libjpe_runtime.so`. `NativeLibraryLoader`
unpacks it to `$TMPDIR/jpe-optimizer-natives/<platform>/` on first use, so the
server does not need a checkout, a `target/` directory, or `-Djpe.nativeLibraryPath`.

## One command

```bash
JAVA_HOME=/path/to/jdk-25 scripts/run-mc-server.sh
```

The script installs NeoForge into `.mc-server/` (once), writes `eula.txt` and
`server.properties`, copies the jar into `mods/`, starts the server, waits for
`Done (`, force-loads a 16×16 chunk square, and prints the evidence lines.

`--no-start` only prepares the directory; `--stop` shuts the server down through
RCON. The server directory, jar, memory, and seed are overridable with
`JPE_SERVER_DIR`, `JPE_MOD_JAR`, `JPE_SERVER_MEMORY`, and `JPE_SEED`.

## What a successful run looks like

```
Native runtime ready: path=/tmp/jpe-optimizer-natives/linux-x86_64/libjpe_runtime.so
  platform=linux-x86_64 perlinStack=true blender=true aquiferXoroshiro=true
Done (1.437s)! For help, type "help"
```

`Native runtime ready` is emitted by `NativeRuntimeSelfCheck` at mod
construction. It is the proof that the jar's own payload was unpacked and
linked: world generation only reaches the library when the final-density hook
is enabled, and that hook refuses graphs it cannot lower completely.

World files land under `world/dimensions/minecraft/overworld/region/` in 26.3,
not `world/region/`. A normal spawn generates four region files; the script's
force-load adds more.

## Measured on this host (2026-11-27)

| Check | Result |
|---|---|
| Mod load | `JPE Optimizer 0.1.0 (jpe_optimizer)` on NeoForge 26.3.0.115 |
| Bundled native | unpacked to `/tmp/jpe-optimizer-natives/linux-x86_64/libjpe_runtime.so`, ABI probe passed |
| First boot | `Done (9.845s)` |
| Chunks generated | 2219 overworld chunks across 6 region files, 14 MiB |
| Final-density capture | `nodes=191 native=116 opaque=75` on the first new chunk |

The chunk count comes from the region headers (non-zero offset entries), not from
the log. Force-loading is capped at 256 chunks per command by vanilla, so the
script marks one 16×16 square; the table above includes a second square added
manually.

## Current native coverage on a vanilla world

With `-Djpe.nativeFinalDensity=shadow`, the hook captures the real final-density
sampler on the first chunk and reports:

```
Native final-density coverage: nodes=191 native=116 opaque=75 ...; staying on Java
```

116 of 191 nodes lower to native arithmetic, but 75 remain opaque, so the plan is
refused and generation stays on Java. The opaque families are, in order of
frequency:

| Family | Why it is not lowered yet |
|---|---|
| `NoiseFunction$Sampler` | needs a noise plan (octaves, amplitudes, seed); the plan encoder has no noise table yet |
| `SliceFunction$YSampler` | IR has `SLICE_Y`, but the adapter does not read the pinned coordinate |
| `CachingDensitySampler` | pure cache; can be passed through once the adapter treats it as a wrapper |
| `SplineFunction$Sampler`, `IntervalSelectFunction$Sampler` | need live spline/interval tables from the sampler object |
| `LerpFunction$Sampler`, `RangeChoiceFunction$Sampler`, `InterpolatedFunction$Sampler` | IR supports them; adapter still classifies them as opaque |
| `ContextBoundSampler` | resolves through `SamplerContext`; must use the bound sampler, not the fallback |
| `BlendDensityFunction$Sampler` | needs blend data from the world |

The hook therefore behaves as designed: it reports coverage and leaves the
authoritative Java path untouched. No block is ever placed from an unverified
native volume.

## GameTest on a dedicated server

`RegisterGameTestsEvent` is not fired on a normal dedicated server, even with
`-Dneoforge.enableGameTest=true`; the test registry stays empty and `/test run`
reports no matches. Use the `gameTestServer` run type (Gradle) for the test
suite. The server-side check in this document is deliberately independent of it:
it exercises mod loading, native unpacking, and chunk generation.

## Next step to make `commit` real

Lower the remaining families, starting with the three that need no payload
(`CachingDensitySampler`, `SliceFunction$YSampler`, `LerpFunction$Sampler`),
then add a noise plan table for `NoiseFunction$Sampler`. Only when `opaque=0`
will the hook compile a plan and compare every float; `commit` then copies the
native volume only after an exact match.
