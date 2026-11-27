# Density graph server

Standalone service for the native density program: it compiles an IR graph,
evaluates chunk-sized volumes through the C++/Rust runtime, and compares the
result with a scalar Java reference. It needs no Minecraft client, no Gradle, and
no third-party jars — a JDK 22+ and the built `libjpe_runtime` are enough.

```sh
scripts/run-density-server.sh --port 8787
# open http://127.0.0.1:8787/
```

The script builds `native/rust-runtime` when the library is missing, compiles the
Minecraft-free classes into `build/density-server`, and starts the HTTP service.

## Why a separate service

The world-generation path cannot be exercised in this environment: it needs the
26.3 artifact, NeoForge, and a running game. Everything below the game boundary
can be exercised, and that is where the layout, table, and evaluator bugs live.
The server is the harness for that half: it drives the real cdylib through FFM,
so a wrong struct offset or a bad spline table fails immediately instead of in a
chunk.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | dashboard (canvas slice view, parity, benchmark) |
| GET | `/api/health` | library path, IR version, compiled plan count |
| GET | `/api/scenarios` | built-in graphs with their default geometry |
| POST | `/api/compile` | compile one scenario, return handle and fingerprint |
| POST | `/api/evaluate` | evaluate a volume, return floats and timing |
| POST | `/api/parity` | native vs Java reference, raw float bits |
| POST | `/api/bench` | repeated evaluation, p50/p95 |
| GET | `/api/selfcheck` | compile and parity-check every scenario |

```sh
curl -s -X POST localhost:8787/api/evaluate \
  -d '{"scenario":"ridge","size":[16,16,16],"min":[-8,-8,-8]}' | head -c 300

curl -s localhost:8787/api/selfcheck
```

## Built-in scenarios

| id | exercises |
|---|---|
| `terrain-spline` | four-point cubic spline, spline point table, edge extension |
| `ridge` | `sqrt(abs(x)) + z * 0.25 - y / 64` — unary and binary chain |
| `interval-bands` | interval select with two thresholds and three branches |
| `distance-field` | distance-to-point (Euclidean) and reciprocal |
| `clamped-gradient` | integer-clamped Y gradient with negative source coordinates |

All five are bit-exact against the Java reference (`/api/selfcheck` returns
`exact: true`). Measured on the CI host, 50 runs each, single thread:

| scenario | samples | p50 | ns/sample |
|---|---|---|---|
| terrain-spline | 2048 | 0.102 ms | 49.7 |
| ridge | 4096 | 0.056 ms | 13.7 |
| interval-bands | 1536 | 0.019 ms | 12.2 |
| distance-field | 4096 | 0.043 ms | 10.5 |
| clamped-gradient | 512 | 0.013 ms | 24.7 |

These are per-volume native evaluation times including the FFM staging copy.
They are not chunk-generation timings and say nothing about world throughput.

## Two integration pieces added with this service

### 1. Capture adapters for the full node set

`DensityGraphCapture` previously represented only constants, coordinates, and
add/multiply. Table-backed nodes could not be expressed at all.

- `DensityGraphCapture.emit(source, builder, children…)` registers an arbitrary
  node and resolves its child indices during the iterative post-order pass. The
  builder receives already-resolved indices, so a spline root, an interval table
  index, or a distance point can be attached without knowing final positions.
- `DensityGraphCapture.capture(root)` returns the resolved node array plus the
  object-to-index map; `Result.toIr(fingerprint, payload)` attaches the payload
  afterwards, which is required because the IR validates spline coordinates
  against node order.
- `DensityGraphPayloadCapture` builds the spline, interval, distance, blend, and
  end-island tables. Slots can be reserved before their coordinate node exists,
  which is how a bottom-up adapter handles a spline whose coordinate is a
  sibling node.
- `SamplerGraphProgramAdapter` lowers a whole captured sampler inventory into one
  native program: arithmetic, unary, clamp, and clamped-gradient nodes become
  native nodes, while cache/context boundaries and zero-short-circuit nodes stay
  explicit OPAQUE holes. In STRICT mode any hole rejects the graph; in
  EXPERIMENTAL mode the hole evaluates to NaN and its neighbours still run. A
  `PayloadProvider` lets a caller that holds live objects supply spline and
  interval tables.

### 2. Native final-density handoff at `doFill`

- `NativeFinalDensityPlan` compiles one graph and evaluates whole volumes. It
  refuses graphs containing OPAQUE nodes, because an experimental plan must never
  reach block placement.
- `NativeFinalDensityHooks.afterJavaSample` is called right after the vanilla
  sampler fills the buffer. Modes: `off` (default), `shadow` (compile, evaluate,
  compare every float bit), `commit` (also copy the native volume into the buffer,
  only when the comparison is exact).
- `NoiseBasedChunkGeneratorMixin` calls the hook at the final-density boundary.

A graph is compiled once per sampler identity. If the captured inventory contains
any node the adapter cannot lower, the hook logs the coverage and stays on Java.

**Not verified here:** the two Minecraft-facing files (`NativeFinalDensityHooks`
and the mixin call) import game classes and are not compiled in this sandbox. The
live-object field readers for spline points and interval tables are also still
missing, so `commit` currently stays inert for real overworld graphs — the hook
reports coverage instead of guessing.

## A bug this harness found

The first `clamped-gradient` run disagreed with the Java reference on every
sample. The node encoder mapped any negative `input_a`/`input_b` to the
`NO_INPUT` sentinel, but gradient nodes store raw signed coordinates there: a
`fromCoordinate` of `-8` arrived as `-1`, silently shifting the whole gradient.
`NO_INPUT` is already `-1` (`0xffffffff`), so the fix is to write the value
unchanged. `NativeRuntime` already special-cased this; the new plan encoder and
the test helper did not.

## Limits

- The Java reference covers the node families used by the built-in scenarios. It
  is not a full 26.3 sampler implementation; `/api/parity` returns 400 for
  unsupported opcodes rather than an approximate number.
- The service evaluates one volume per request. Batch scheduling, cache eviction,
  and worker policy remain Java-side concerns.
- Timings include the output copy out of native memory.
