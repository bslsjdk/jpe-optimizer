# Rust World Engine — Stage 1 investigation and first implementation

Branch: `work/rust-world-engine` (based on `origin/work/native-density-graph-ir`).
Scope of this commit: Stage 1 audit against real sources, plus the first
verifiable Stage 2 slice (`native/rust-world/`). Nothing here claims that any
renderer or live world-logic replacement is finished.

## 1. Verified repository state

Facts below come from the cloned repository, not from the task description.

- Default branch `main`, HEAD `2e82e9f` ("更新 README.md"), repo size ~1.2 MB.
- `main` contains **5 Java files, 1 Python audit script, 1 doc**. Total source
  under `src/` is 187 lines. There is **no `native/` directory on `main`**:
  `git ls-tree -r main | grep ^native/` returns nothing.
- The build is a NeoForge mod (`net.neoforged.moddev` 2.0.148), Java 25 toolchain,
  `minecraft_version=26.3`, `neo_version=26.3.0.115`, Gradle 9.2.1 in CI.
  There is no Gradle wrapper committed.

### Native work lives on other branches

38 remote branches were fetched. The branches carrying native code are work
branches, not `main`. Highest-signal ones:

| Branch | Commits ahead of main | `native/` files |
|---|---|---|
| `work/native-density-graph-ir` | 920 | 10 |
| `work/effective-noise-router-digest` | 539 | 9 |
| `work/density-graph-world-fingerprint` | 537 | 9 |
| `feat/native-density-graph-ir` | 352 | 8 |
| `experiment/native-worldgen-foundation` | 162 | 6 |

`work/native-density-graph-ir` was chosen as the base because it contains the
complete current native stack **and** the source-audit docs:

```
native/cpp-kernels/src/{aquifer_random,blender,bulk_probe,density_graph,perlin_stack,perlin_volume}.cpp
native/include/jpe_native_api.h
native/rust-runtime/{Cargo.toml,build.rs,src/lib.rs}
benchmarks/density_batch_avx2.cpp
docs/{minecraft-26.3-worldgen-source-audit,native-worldgen-architecture,
      native-worldgen-lifecycle,gpu-worldgen-architecture,
      horizon-architecture-study,native-cpu-backend-audit,...}.md
```

## 2. What actually exists today (and is owned by which tier)

### C++ — Terrain Engine (exists, ABI v8)

`native/include/jpe_native_api.h` declares ABI v8 (`jpe_runtime_abi_version`
returns 8). Relevant surface:

- workers: `jpe_runtime_configure_workers`
- scalar/volume Perlin: `jpe_runtime_perlin_add_volume`,
  `jpe_runtime_perlin_add_stack`
- async jobs: `jpe_runtime_submit_perlin_stack` / `_volume`,
  `jpe_runtime_job_status` / `_wait_job` / `_cancel_job` / `_release_job`
- density-graph IR: `JpeDensityNode`, opcodes 1..26+, `..._density_graph_*`
- blender/aquifer helpers: `..._blend_density_volume`, `..._aquifer_*`

Coordinate convention is `min + index * step`; density output order is
`((z * size_x + x) * size_y + y)`. `native/rust-runtime/build.rs` compiles all
six kernels with `c++17`, `-ffp-contract=off`, `-fno-fast-math` — bit-exactness
is a deliberate constraint.

### Rust — existing `rust-runtime` (not the world engine)

`native/rust-runtime` is a `cdylib`/`staticlib` named `jpe_runtime`. It owns the
async job table and worker pool, and exports the C ABI above. It does **not**
contain chunk lifecycle, scheduling or meshing — that is what this branch adds.

### Java — compatibility layer (exists)

- `runtime/NativeRuntime.java` (~1941 lines): FFM downcall handles, leased
  output buffers, job lifecycle.
- `mixin/` — ~30 mixins touching worldgen internals (`NoiseStackPerlinMixin`,
  `NoiseStackSmearedPerlinMixin`, `NoiseBasedChunkGeneratorMixin`,
  `DensityBufferMetricsMixin`, `AquiferNoiseBasedMixin`, climate/biome zoom,
  structure index, palette experiments).
- `GameTests.java` — native parity GameTest marker.

### Verified Minecraft 26.3 call chain (from the committed audit)

Source: `docs/minecraft-26.3-worldgen-source-audit.md` and
`docs/native-worldgen-lifecycle.md`, checked against Mojang's official 26.3
artifact (SHA-1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`) and the NeoForge
patched artifact (SHA-256 `8ba1bd3c…`; 657 classes present, 628 differ).

1. `NoiseBasedChunkGenerator.buildTerrain` clamps `NoiseSettings`, schedules a
   task with `CompletableFuture.supplyAsync`.
2. Task calls `createNoiseChunk` → chunk-local `Beardifier` → `NoiseChunk`.
3. `noiseChunk.volume()`, acquire sections, `doFill`, release, `buildSurface`,
   `generateCarvers`.
4. In `doFill`, `finalDensity` is sampled **once per full volume**
   (16 x world-height x 16) via `DensitySampler.sampleVolume`; then a second
   per-voxel pass does aquifer substance + block write + heightmaps.
5. `NoiseStack.addToVolume` iterates ordered `Layer[]`; the existing mixins
   intercept `NoiseStack$Perlin` / `$SmearedPerlin` and can route the whole
   stack to the C++ kernel.

Caveat recorded in the repo: source notes were first derived from a third-party
CFR dump and are only authoritative where the CI bytecode audit re-confirmed
them. The **render path is not audited at all** (see §4).

## 3. What does not exist anywhere in the repo

- **No renderer of any kind.** A search across all 38 branches for
  `render|vulkan|/gl/|gles|opengl|shader|.glsl|.vert|.frag` returns nothing.
  There is no window/context ownership code, no surface handling, no GPU
  resource manager.
- **No Rust world logic.** No chunk lifecycle, scheduler, queue, mesher or
  culling exists before this branch.
- **`main` has none of the native stack**, so a PR against `main` would either
  be empty of native dependencies or would have to pull in 920 commits.
- **No Minecraft render audit.** The task book requires Vulkan/GL/GLES on
  Minecraft's real context; nothing in the repo documents how 26.3 owns its
  window, GL context, or render thread.

## 4. Environment constraints in this sandbox

Measured, not assumed:

- **No GPU**: no `nvidia-smi`, no `/dev/dri`. Real Vulkan/GL/GLES initialization
  and frame validation cannot run here. Backend code must be validated on real
  hardware/CI with a device.
- **No Java 25**: sandbox has Temurin 21; the project requires 25. No Gradle
  wrapper is committed, and the NeoForge `moddev` plugin downloads Minecraft
  artifacts. The Java/NeoForge build was therefore **not** executed here; it
  runs in the GitHub Actions workflow that installs Java 25 and Gradle 9.2.1.
- 4 vCPU / 7.8 GB RAM, Rust 1.98, g++ 13.4, cmake/ninja present.
- `rustfmt`/`clippy` components are not downloadable from this sandbox's rustup
  mirror (404), so formatting/lint checks are advisory in CI rather than
  locally verified.

## 5. Gap analysis against the task book

| Requirement | Current state |
|---|---|
| C++ owns terrain math | Exists (ABI v8). Untouched here. |
| Rust owns chunk lifecycle/scheduling | **New in this branch** (core, tested; not yet wired to Java) |
| Rust owns meshing/visibility/culling | **New in this branch** (CPU mesher + frustum culling; not greedy/AO/lighting) |
| Three native GPU backends | **Not implemented**. Interface + honest probes only. |
| Unified renderer interface | Defined (`render::Renderer`) |
| No third-party GPU frameworks | Enforced by construction: zero dependencies |
| Renderer reuses Minecraft context | **Not started**; needs a render audit first |
| Migrate Java world logic | Not started; audit lists the classes/mixins |
| Stale-epoch / mod-compat safety | Epoch + ticket rules implemented and tested |
| Real chunk-level benchmarks | Not yet; needs live integration |

## 6. Decisions needed from the owner

1. **PR base branch.** `work/rust-world-engine` is based on
   `work/native-density-graph-ir` because that is where the C++ ABI and docs
   live. Options:
   - (a) draft PR against `work/native-density-graph-ir` (small, honest diff);
   - (b) merge the native stack to `main` first, then PR against `main`;
   - (c) PR against `main` and accept a 920-commit diff.
   **Default taken:** push the branch only, open no PR until confirmed.
2. **Render-step audit** of Minecraft 26.3 (window/GL context/render thread)
   before any backend work, plus a machine with a real GPU for validation.
3. Confirm target platforms (Linux desktop Vulkan first? Android GLES?) so the
   first backend's surface integration is scoped correctly.

## 7. First implementation delivered in this commit

`native/rust-world/` — crate `jpe-world-engine` (lib `jpe_world`), **zero
dependencies**, 44 unit tests passing locally, plus a Java 25 FFM bridge harness
(55 checks) passing against the built `libjpe_world`.

- `coords` — `ChunkPos`/`SectionPos`/`BlockPos` with vanilla `toLong` packing
  (X|Z|Y layout), arithmetic-shift chunk mapping, Y bounds for the default
  384-high overworld.
- `chunk` — lifecycle state machine (`Empty`→`TerrainQueued`→…→`Uploaded`),
  ticket accounting, world-epoch invalidation; illegal transitions rejected.
- `task` — bounded priority queue (Urgent/Near/Far), distance tie-break,
  deterministic FIFO ties, worst-job eviction with explicit outcome.
- `scheduler` — `SchedulerCore` with submit/take/complete, stale-result
  rejection, eviction rollback so a dropped job never leaves a chunk stuck,
  unload rules, statistics; `parallel_map` scoped-thread foundation.
- `mesh` — 16³ section, culled mesher (shared 32-byte vertex format), optional
  neighbour boundary culling, AABB/frustum culling.
- `render` — `Renderer` trait, draw batches, and **honest** Vulkan/GL/GLES
  probes reporting `BackendUnavailable`, plus `HeadlessRenderer` used to test
  the contract without a GPU.
- `terrain` — FFI declaration of the existing ABI v8 (`cpp-bridge` feature,
  not enabled by default), `JpePerlinLayer` layout guarded by a size/alignment
  test (72/8 bytes), and a deterministic mock for tests.
- `ffi` — a stable C ABI (`jpe_world_*`) over `SchedulerCore` and the section
  mesher, with a handle registry, typed status codes and versioned layout.

### Temporary Java integration path (per owner direction)

The owner directed that C++ not be connected yet (it is still in progress) and
that the engine instead be wired to Java and tested on this branch. So this
commit adds a **direct Java↔Rust** path, C++-free:

- `src/ffi.rs` — 15 exported `jpe_world_*` symbols (create/destroy, player,
  terrain/mesh requests, take/complete job, upload, unload, state, stats, reset,
  mesh-a-section, ABI/max-quads), each with explicit status codes.
- `java/src/dev/jpe/world/JpeWorldNative.java` — Java 25 FFM binding (Arena,
  `SymbolLookup.libraryLookup`, `Linker` downcall handles, typed `MethodHandle`s).
- `java/src/dev/jpe/world/WorldEngineHarness.java` — end-to-end harness:
  priority ordering (Urgent/Near/Far), lifecycle transitions
  (queued→generating→ready→meshed→uploaded→unload), stats accounting, single /
  empty / solid-layer meshing, and epoch invalidation rejecting stale results.
- `java/run_harness.sh` — builds the cdylib, compiles the harness, runs it under
  JDK 25 with `--enable-native-access=ALL-UNNAMED`.
- CI job `java-bridge` in `.github/workflows/rust-world.yml` runs the harness on
  every push to this branch.

The bridge itself also includes a `jpe_world_mesh_section` downcall that returns
real interleaved vertex bytes, so the same ABI will carry mesh data to whichever
GPU backend is added later.

### Explicit non-claims

- No Vulkan/GL/GLES device is created; no triangle is drawn.
- No Minecraft/NeoForge integration; no Java source in the mod was changed. The
  bridge was proven with a standalone harness, not a running client.
- No C++ library is loaded; the terrain bridge is declared but not exercised.
- No performance numbers are reported; there is no live workload yet.

## 8. Next steps, in order

1. Confirm PR base branch (§6.1).
2. Audit Minecraft 26.3's render ownership (window, GL context, render thread)
   and record it here with artifact hashes.
3. Replace the standalone harness with a GameTest / client-thread hook that
   drives the same `jpe_world_*` ABI from inside a dev run, proving lifecycle and
   epoch behaviour with real chunk coordinates.
4. Implement the first backend's device probe on a GPU machine (Vulkan first is
   the cleanest to test headless), then the shared mesh upload path.
5. Only after parity + measurement: connect the density volume from C++ into the
   Rust chunk pipeline (feature `cpp-bridge`).
