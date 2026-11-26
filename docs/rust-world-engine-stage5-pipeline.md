# Rust World Engine — Stage 5: GPU section registry and the per-frame pipeline

Branch `work/rust-world-engine`, crate `native/rust-world/`. Nothing in
`native/cpp-kernels` or `native/include` was touched, and the C++ terrain ABI stays
v8 — the Rust side declares it, it does not redefine or reimplement it.

Stage 4 ended with a real frustum planner and a real draw path, but with one
structural gap: **nobody fed it**. `jpe_world_renderer_draw_visible` took a
caller-owned section array, so Java still had to decide what was resident, own the
mesh handles, and know when to destroy them. That is world-engine work, and while
it lived in Java the Rust side could not be the layer that owns chunk lifecycle,
meshing, visibility and GPU resources.

This stage closes that gap inside Rust: `render::registry` (who owns which GPU
mesh, and for which section revision) and `pipeline` (the per-frame loop that turns
scheduler state into a draw list and pixels).

## What changed

- **`src/render/registry.rs`** — `SectionRegistry`: the GPU-side owner of resident
  sections. `publish(pos, mesh, index_count, revision)` inserts or replaces and
  hands back the superseded `MeshHandle`; `remove(pos)` and `clear()` return every
  handle the caller must destroy. `record_key(pos)` is `SectionPos::to_long()`, so
  a section key is the same packed long Java and the C++ side already use. Sections
  live in a `BTreeMap<SectionPos, _>`, which makes iteration order and therefore the
  draw list deterministic. `records(&mut self)` materialises the
  `render::plan::SectionRecord` slice lazily and only after a mutation — tests
  assert the slice address is unchanged while nothing published.
- **`src/task.rs`, `src/chunk.rs`, `src/scheduler.rs`** — a new `JobKind::RemeshMesh`
  (ABI job-kind code 4) plus two state edges, `MeshQueued -> TerrainReady` and
  `MeshQueued -> Uploaded`, and `SchedulerCore::{acquire_ticket, release_ticket,
  request_remesh}`. Remesh is a separate kind precisely so that *dropping* it rolls
  the chunk back to `Uploaded` (the old mesh is still resident and still correct to
  draw) instead of to `TerrainReady`, which would discard drawable geometry.
  `rollback_evicted` became a `(expected, previous)` table so each kind gets its own
  rollback target.
- **`src/pipeline.rs`** — `WorldPipeline<S: BlockSource>`, `PipelineConfig`,
  `PipelineStats`, `FrameOutcome`, `Camera`, and `PushSource` (block ids handed in
  by the caller). One `frame()` call runs the whole loop.
- **`src/mesh.rs`** — `ChunkSection::from_indices` / `indices`, the flat
  `(y<<8)|(z<<4)|x` hand-off shape, so a caller copies its buffer straight in.
- **`src/ffi.rs`** — Java ABI v2: the `jpe_pipeline_*` entry points and the
  `JpePipelineConfig` / `JpeFrameStats` / `JpePipelineStats` blocks.
- **`java/src/dev/jpe/world/`** — bindings for all of it and a harness section that
  drives a real backend through the pipeline.

## Registry: GPU ownership, not a side table

`SectionRecord` is what the planner consumes, so ownership of the records *is*
ownership of the GPU meshes. Three rules keep that honest:

1. Every mutation that replaces a section returns the old `MeshHandle`, and the
   pipeline destroys it on the render thread in the same call. Nothing is dropped
   silently: `RegistryStats` counts `published`, `replaced`, `removed`, `resident`,
   `resident_indices`.
2. A section is keyed by `SectionPos`, so a re-mesh of the same section replaces
   its own mesh rather than adding a second one.
3. The cached `records()` slice is invalidated by any mutation. A stable slice while
   the registry is clean is asserted by pointer identity, because the planner is
   called every frame and re-building the array per frame would be an avoidable
   allocation.

## Pipeline: one frame, four phases

```
retry deferred -> pump (terrain | mesh | remesh) -> build (workers) -> publish
                 under budgets                       parallel_map      create/replace/destroy meshes
                                                                  -> set camera -> begin_frame
                                                                     -> plan_draws -> draw* -> end_frame
```

- **Pump.** One loop over both budgets, because completing a terrain job queues a
  mesh job *during the same frame*; a separate terrain loop would let that newly
  queued work slip past the mesh budget. `terrain_budget`, `mesh_budget` and
  `remesh_budget` are hard upper bounds and the tests assert them.
- **Build.** `build_geometry` runs on `threads` workers through `parallel_map` and
  reads only cached block data (`self.blocks`), so the parallel phase needs no
  renderer and no render thread. Neighbour faces are culled against the cached
  neighbours, including across chunk borders.
- **Publish.** Zero-quad sections are **not** uploaded — an empty index buffer
  would allocate a GPU buffer that can never be drawn. Instead the section is
  removed from the registry and its previous mesh destroyed, so clearing blocks
  actually stops the geometry being drawn. Job completion and `mark_uploaded` are
  applied only after the uploads, and the outcome counters are filled after them,
  so an `Err` return cannot leave inflated counters.
- **Cull and draw.** The frame's camera is set once and shared by the planner and
  the backend, exactly as in stage 4.

Neighbour terrain arriving late is what makes remeshing necessary: a resident
chunk's boundary faces were built against air. `on_terrain_ready` therefore asks
the four horizontal neighbours for a `RemeshMesh` through the scheduler, and the
effect is measured rather than assumed — `neighbour_terrain_queues_a_remesh_that_shrinks_the_old_mesh`
loads chunk (1,0) after (0,0) and asserts the shared-boundary section's
`index_count` **drops** (the faces hidden by the new neighbour are gone), that a new
`MeshHandle` was allocated, that the superseded handle appears in the renderer's
destroyed list, and that the section revision advanced. A remesh the queue could not
accept returns to `Uploaded` instead of losing its drawable geometry.

`unload_outside(center, radius)` destroys every mesh on the render thread before
dropping the entries, skips ticketed chunks and chunks with work in flight, and
sweeps the block cache by position — not by registry membership, because sections
that meshed to nothing are cached but never resident.

## Java ABI v2

| Entry point | Purpose |
| --- | --- |
| `jpe_pipeline_create` | bind a pipeline to an existing renderer handle |
| `jpe_pipeline_destroy` | release every mesh the pipeline owns, then the handle |
| `jpe_pipeline_submit_chunk` | hand over `section_count * 4096` block ids |
| `jpe_pipeline_frame` | run one frame, write `JpeFrameStats` |
| `jpe_pipeline_unload_outside` | unload + destroy meshes + release block data |
| `jpe_pipeline_set_player` | move the priority reference point |
| `jpe_pipeline_ticket` | hold / release a chunk against unloading |
| `jpe_pipeline_stats` | lifetime counters and cache sizes |
| `jpe_pipeline_resident_keys` | resident `SectionPos` longs, ascending |
| `jpe_pipeline_reset` | drop GPU data, bump the world epoch |

Layouts are asserted in Rust (`pipeline_abi_structs_have_documented_layout`) and
read by constant offset in Java: `JpePipelineConfig` 32 bytes, `JpeFrameStats` 88
bytes, `JpePipelineStats` 128 bytes. Status codes are the existing set: 0 ok, -1
unknown handle, -2 null argument, -3 renderer gone, -4 invalid input, -5 API/device,
-6 unsupported, -7 wrong thread. Submit results are 0 new, 1 mesh queued, 2 remesh
queued, 3 stored without queueing.

`jpe_pipeline_create` takes the *renderer handle* rather than creating a renderer,
so pipeline calls reuse the renderer's thread check: `with_pipeline_renderer`
compares the caller's thread against the pipeline's creating thread and against the
renderer's owner, and refuses with -7 before touching the GPU. Lock order is always
pipeline -> renderer; no renderer entry point takes the pipeline lock.

## Bugs the stage-5 tests found

Not refactoring — real defects, each now pinned by a test:

1. **A rejected mesh request stranded the chunk forever.** `request_mesh` rolled
   back to `TerrainReady`, but that edge did not exist from `MeshQueued`, so the
   transition failed, the chunk stayed `MeshQueued`, and it was never scheduled
   again. Found while writing the rollback test; fixed by adding the edge
   (`chunk.rs`) and asserting the retry succeeds
   (`rejected_mesh_request_goes_back_to_terrain_ready`).
2. **A cancelled remesh dropped a drawable chunk to `TerrainReady`**, which would
   have thrown away resident geometry. `rollback_evicted` now maps
   `RemeshMesh -> Uploaded` (`an_evicted_remesh_job_leaves_the_old_mesh_resident`).
3. **Block cache leak.** `unload_outside` swept `self.blocks` by registry
   membership, so every all-air section — cached, meshed to nothing, never resident
   — leaked its block array. Test output was literally `left: 2, right: 0`
   (`cached_section_count()` vs expected 0). Now swept by position, counted in
   `sections_dropped`.
4. **A cleared section kept its mesh.** The empty-geometry path `continue`d without
   touching the registry, so removing blocks left the old mesh resident and drawn.
   Now it removes and destroys (`resubmitting_an_uploaded_chunk_remeshes_it_and_frees_the_old_mesh`).
5. **Mesh budget bypass** (two loops), **`FrameOutcome.terrain_jobs` never
   assigned**, and **remesh counted twice** — all counter bugs caught by the budget
   and convergence tests.
6. **`FlatSource::section` read the wrong tuple element** (`origin_block()` returns
   `(x, y, z)`; the code used `x` as the row height), which made every row at or
   below the surface solid and produced full-height columns instead of ground.
   Caught by an exact quad-count assertion.

## Verification

```sh
cd native/rust-world
export VK_DRIVER_FILES="$(bash ci/vulkan-icd.sh)"
export VK_ICD_FILENAMES="$VK_DRIVER_FILES"
export JPE_REQUIRE_RENDER_BACKENDS=1

cargo test -- --test-threads=1              # 101 passed, 0 failed (debug)
cargo test --release -- --test-threads=1    # 101 passed, 0 failed
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 java/run_harness.sh
```

Harness output on lavapipe/llvmpipe (software Mesa), release `libjpe_world.so`:

```
[harness] renderer backend=1 api=1.3 maxTex=16384
[harness] frame: 8626 lit pixels, 4 tones
[harness] visibility: 2/6 visible -> 1024 lit pixels, 0/6 visible -> 0 lit pixels
[harness] pipeline: 5 frames, 2 published, 2 remeshed, 2 meshes destroyed, 1296 lit pixels
[harness] all 164 checks passed
```

The pipeline numbers are end-to-end over the C ABI from Java-supplied block ids:
one submitted chunk publishes one resident section that covers 1296 pixels; the
same registry under a turned-away camera reports `visible = 0`, `culled = 1`,
`batches = 0` and reads back **zero** lit pixels; submitting the section as air
reports `destroyed = 1`, `resident = 0` and again zero lit pixels. Residency,
culling and mesh destruction are therefore observed in the frame, not only in a
counter. The same assertions run in Rust on the headless reference renderer
(`ffi::tests::pipeline_over_c_abi_on_every_backend` loops Vulkan, OpenGL and
OpenGL ES, skipping a backend only if it cannot start here, and
`JPE_REQUIRE_RENDER_BACKENDS=1` turns such a skip into a failure in CI).

**33 tests are new in this stage: 8 in `render::registry`, 16 in `pipeline` (12
lifecycle plus 4 `push_source_tests`), 5 in `scheduler` (rollback, remesh legality,
eviction, tickets), 3 in `ffi` (struct layout, contract guards, end-to-end
pipeline), 1 in `mesh` (index hand-off layout).** The crate total went from 68 tests
at the previous commit to 101, and the Java harness gained 56 checks (4 `check` plus
52 `eq`) in a new pipeline section, for 164 total on this machine — the renderer and
visibility sections skip when no GPU backend can start, so the total is machine
dependent.

## Explicit non-claims

- **No C++ terrain and no Minecraft integration.** `PushSource` receives block ids
  from Java. Nothing calls Minecraft's chunk loader, so this does not yet replace
  vanilla rendering, and the C++ engine is still not the data source.
- **Offscreen only.** The pipeline draws into the renderer's offscreen target;
  presentation/swapchain and adopting Minecraft's live window/context are still
  unwritten.
- **No block light, skylight, biome colour or real atlas**; the test atlas and the
  flat directional shading from stage 3 are still what is drawn.
- **Culling is frustum + residency only.** No HZB/occlusion queries, no section
  sort beyond the front-to-back plan order, no LOD, no greedy meshing, no AO.
- **Only the meshed `section_y` range is cached.** Sections outside the configured
  range are treated as air for boundary culling, so the column height must be set
  per config; a full vanilla 24-section column is not exercised by any test yet.
- **A block update reaches five columns only.** Chunks are columns, so a
  re-submitted chunk remeshes itself plus its four horizontal neighbours; nothing
  beyond that is invalidated, and there is no dirty-rect or per-section propagation.
- **Section-level meshing only.** Faces hidden by a newly arrived neighbour are
  removed correctly, but there is no merging of coplanar quads across sections and
  no per-face UV atlas packing beyond the stage-3 test atlas.
- **The CPU reference renderer is not exposed over the Java ABI.** `AUTO` means
  "best native backend", so on a machine with no driver the `jpe_pipeline_*` tests
  and the harness pipeline section skip (and fail, not skip, whenever
  `JPE_REQUIRE_RENDER_BACKENDS=1`, which the two GPU jobs set). The equivalent
  pipeline coverage without a GPU runs at Rust level against
  `render::headless::HeadlessRenderer`, which is where the lifecycle assertions
  actually live.
- `cargo fmt --check` and `cargo clippy` could not be run in this sandbox (the
  rustup mirror returns 404 for the `rustfmt`/`clippy` components), so they remain
  advisory CI steps here; nothing in this document claims they passed.
