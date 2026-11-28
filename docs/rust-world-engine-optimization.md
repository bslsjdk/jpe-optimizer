# Rust World Engine — Optimization pass (Java-facing system)

Branch `work/rust-world-engine`, crate `native/rust-world/`, mod `jpe_optimizer`.
This is the optimization work on the Java-facing world engine described in
`rust-world-engine-stage6-minecraft.md`. The mandate was to keep driving the
engine against *real Minecraft* (C++ terrain still deferred — that remains the
`PushSource` follow-up) and to remove the per-frame and per-chunk hot-path costs.

Three constraints held throughout:

- **No ABI drift.** `JAVA_ABI_VERSION` stays `2`. The frozen struct layouts and
  offsets (`JpePipelineConfig` 32/4, `JpeFrameStats` 88, `JpePipelineStats` 128,
  `JpeRenderSection` 32, `JpeRenderPlanStats` 32) and every status code are
  unchanged. No new or removed FFI symbol. The C++ terrain ABI is untouched (v8).
- **Honest non-claims.** Anything not done is named below, not papered over.
- **Every batch verified.** `cargo test` green, and a live Minecraft 26.3
  GameTest run against the rebuilt `libjpe_world.so` (lavapipe software GPU) with
  identical correctness metrics before and after.

## What was optimized

### 1. GPU pixel readback is now opt-in (the big one)

The steady render path used to copy a **full RGBA frame into host memory on every
frame**: on Vulkan a `vkCmdCopyImageToBuffer` + `vkWaitForFences` + `vkMapMemory`
+ `slice.to_vec()` (≈ `width*height*4` bytes allocated per frame); on the GL/GLES
core a `glFinish` + `glReadPixels` + row-flip + two `Vec` allocations. A live host
reads pixels only occasionally (pixel checks), so this was almost pure waste on
the hot path.

The copy/map/allocate now lives in a separate transfer-only pass that runs **only
when `read_pixels` is called**, not in `end_frame`. The Vulkan colour attachment is
already left in the render pass's `TRANSFER_SRC` final layout, so the on-demand
copy needs no extra layout juggling; the GL FBO still holds the previous frame's
pixels so the on-demand read needs no redraw. Signatures are unchanged
(`Renderer::end_frame` / `Renderer::read_pixels`), and reading with no target
still maps to status `-6` so the existing renderer ABI test is preserved.

Files: `src/render/vulkan.rs` (new `capture_pixels`), `src/render/glcore.rs` (new
`capture_core`, shared by OpenGL and OpenGL ES), `src/render/mod.rs` (trait docs).

Verified: `render::tests::backends_agree_on_pixels_and_orientation` still passes —
all three native backends produce identical, correctly-oriented pixels — and the
live GameTest still reports `litPixels=1508 of=16384 awayLitPixels=0`.

### 2. Per-frame allocations cut (logic layer)

- `build_geometry` reuses a `thread_local` `MeshScratch` (vertex/index buffers)
  across sections on each worker instead of allocating fresh buffers per section.
- `registry.records()` reuses its cached `Vec` capacity on rebuild rather than
  reallocating via `collect()` every frame.

### 3. Counter correctness + telemetry

`frame()` now derives `mesh_jobs` / `remesh_chunks` directly from the accepted job
set, removing the old `accepted.len() - remesh_seen` form that could underflow
when a dequeued remesh was dropped for having no targets. Added Rust-only
per-phase timing (`pump / targets / build / publish / draw` ns) behind
`WorldPipeline::timing()` for profiling; it does **not** cross FFI and is not in
the ABI. Two new tests cover both.

Files: `src/pipeline.rs`, `src/render/registry.rs`.

### 4. Java transport: bulk copy in `submitChunk`

`pipelineSubmitChunk` copied each block id with `data.setAtIndex(...)` in a Java
loop — up to ~28k bounds-checked stores per submit (`sectionCount * 4096` ids), the
hot path while streaming chunks. Replaced with a single `MemorySegment.copy`
(short[] → segment), one memcpy-style native transfer. Same v2 contract and data
shape. File: `native/rust-world/java/src/dev/jpe/world/JpeWorldNative.java`.

### 5. MinecraftChunkReader: single pass

`read()` walked each section twice — once to fill the hand-off array, then a second
full 4096-element pass purely to recount that section's non-air blocks for the
palette cross-check. The per-section count now accumulates inside the fill loop.
File: `src/main/java/dev/jpe/optimizer/world/MinecraftChunkReader.java`.

### 6. remesh_budget is a real per-frame bound (follow-up)

The pump used to be `while terrain_done < terrain_budget || mesh_taken < mesh_budget`
over an undifferentiated `take_job()`. Two consequences, both fixed:

- Remeshes drew from the **mesh** budget, so the documented
  `mesh_budget + remesh_budget` bound on `PipelineConfig::remesh_budget` was not true.
- Because the loop continued while *either* budget had room, the mesh budget was only
  enforced once the terrain budget was exhausted; a frame with a spare terrain slot
  built every queued mesh.

The scheduler now exposes `take_job_where(admissible)`, backed by
`BoundedJobQueue::pop_where`: it returns the best job whose kind the caller still has
budget for. A declined head job is **neither popped nor transitioned** — the chunk
stays in its queued state (no in-progress transition to roll back), and the other
kinds behind it can still run, so an exhausted remesh budget never holds fresh meshes
hostage. Stale entries of the requested kind are discarded exactly as `take_job` did;
`take_job()` is now `take_job_where(|_| true)` and shares the same runnable check.
O(n) only when the head is declined; the queue is bounded, so that is cheap.

Files: `src/task.rs` (`pop_where`), `src/scheduler.rs` (`take_job_where`, shared
`runnable`), `src/pipeline.rs` (per-kind admissibility in `frame()`).
No FFI symbol, struct layout or ABI change (`JAVA_ABI_VERSION` stays `2`; the release
`.so` still exports the same 33 `jpe_world_*` symbols).

Verified: `cargo test` (lavapipe) **115 passed** (was 110). New:
`task::tests::pop_where_skips_declined_kinds_and_keeps_them_in_order`,
`pop_where_on_an_empty_queue_is_none`,
`scheduler::tests::take_job_where_leaves_a_declined_head_queued_and_untouched`,
`take_job_where_discards_stale_entries_of_the_requested_kind`, and
`pipeline::tests::remesh_budget_is_a_separate_hard_upper_bound_per_frame`, which
uploads a 3×3 block, loads nine more chunks beside it (3 remeshes + 9 fresh meshes
queued at once), and asserts each frame does ≤ `remesh_budget` remeshes and ≤
`mesh_budget` fresh meshes, that no declined job left a chunk in `Meshing`, and that the
work converges to `mesh_jobs == 18`, `remesh_chunks == 3` with an empty queue. A
mutation that lets remeshes draw from the mesh budget again makes that test fail.

### 7. Padded-grid mesher (2.28x meshing throughput)

The mesher tested every face with `(0..16).contains(&nx)` on three axes, then a
six-arm `match` to pick the neighbour section. It now copies the section and its
six neighbour boundary layers once into an 18³ padded grid held in `MeshScratch`
(reused per worker thread, zero per-section allocation), so every face test is one
indexed load at a precomputed ±1/±18/±324 offset. An interior block — enclosed on
all six sides — is rejected by a single six-load test instead of six separate face
tests, which is most of a solid section.

The vertex/index stream is unchanged byte for byte: the same corners, the same
addition order (`origin + block + corner`, so the f32 rounding is identical), the
same index pattern. `mesh::tests::padded_mesher_reproduces_the_reference_exactly`
keeps the old implementation in the test module and compares 640 cases (five
densities × 128 neighbour combinations) as raw vertex bytes and indices;
`reused_scratch_never_leaks_a_previous_section` covers the pad-reuse hazard.

Measured on this host (release, `cargo test -- --ignored --nocapture
mesh_throughput_report`), one half-solid section at the 16³ scale:

| implementation | ns / section |
|---|---|
| reference (per-face) | 40,481.7 |
| padded grid | 17,764.2 |
| **throughput** | **2.28x** |

That is meshing arithmetic only; the frame budget is spent mostly on upload and
draw, and this does not claim a frame-time win. Greedy merging would cut the quad
count itself (the next real win) but changes UV semantics, so it needs a texture
tiling decision first.

### 8. Section block arrays are shared, not copied (1.64x fill)

Every hand-off between ingest and the mesher wanted a `ChunkSection` of its own:
Java hands one in, `PushSource::put_chunk` stored a clone, `PushSource::section`
handed out another clone, and the pipeline cached that. With a plain `Vec<u16>`
inside the section that was **two 8 KiB allocations and two 8 KiB copies per
section**, with two live copies of every section at once — at view distance 8
(289 chunks x 8 sections) 37 MiB of block data where 18.5 MiB would do, and the
copies were the single largest phase of a fill.

The array now lives behind an `Arc<Vec<u16>>`, so a hand-off is a refcount bump
and all three owners share one allocation. `set` is copy-on-write
(`Arc::make_mut`): a section being filled is uniquely owned, so writes stay in
place; only a write to a section someone else still holds splits the buffer,
which is the same visible semantics as the `Vec` clone it replaced.
`fill_padded` hoists `section.indices()` out of its row loop so the padded grid
fill still chases one pointer per row rather than two.

Measured with the new whole-frame report (`cargo test --release
frame_throughput_report -- --ignored --nocapture`, 289 chunks / 2312 sections,
8 sections per column):

| phase | before | after |
|---|---|---|
| targets (ingest + neighbour gather) | 11.04 ms | 2.59 ms |
| build (meshing, 4 workers) | 9.77 ms | 8.78 ms |
| publish (upload) | 1.76 ms | 2.19 ms |
| draw (cull + order + submit) | 0.07 ms | 0.08 ms |
| **fill total** | **23.1 ms** | **14.1 ms** |
| per chunk | 80.0 µs | 48.7 µs |

Tests: `mesh::tests::clone_shares_the_block_buffer_until_written` proves the
clone is a refcount bump (same buffer pointer) and that a write splits it without
disturbing the peer; `pipeline::push_source_tests::
pushed_sections_reach_the_mesher_without_being_copied` proves the caller's
buffer, the source's copy and the pipeline's block cache are the same allocation
end to end.

### Measurement first: a whole-frame report

The two reports above each measure one function. Nothing measured the loop a host
drives, so "what is a frame made of" was guesswork — and the first guess was
wrong: with `FlatSource` as the source, the *mock generator* (4096 `set` calls per
section) was 44% of the fill and hid the pipeline behind it. The benchmark now
uses `CachedFlatSource` (pre-built sections, one hand-off per section, the same
order as the real `PushSource` ingest path) and reports the phase split both as
fill totals and per busy frame, sampled before the idle frames rather than
diluted by them.

## Verification

- `cargo test` (in `native/rust-world`): **119 passed, 3 ignored** (the throughput
  reports), warning-free `cargo build` and `cargo build --release`; the release
  `.so` still exports the same 33 `jpe_world_*` symbols.
- Live Minecraft 26.3 GameTest (`native/rust-world/java/run_minecraft_gametest.sh`,
  lavapipe), run after the Java batches, identical metrics before/after:
  `result=passed backend=vulkan litPixels=1508 of=16384 awayLitPixels=0
  columnsSolid=64 cellsChecked=448 distinctBlockStates=5 paletteParity=true
  blocksScanned=20480 nonAirBlocks=5123 takeover=false` and
  `All 11 required tests passed`.

## Explicitly NOT done (and why)

- **Persistent mesh worker pool.** `parallel_map` still uses `std::thread::scope`
  per burst. Replacing it with a long-lived pool is genuinely awkward in safe
  Rust: workers must run closures that borrow per-frame data (the `&Target` list
  and the pipeline's block source), and a pool thread cannot hold non-`'static`
  borrows — which is exactly why `thread::scope` is used. A correct pool needs a
  lifetime-erasing arena (unsafe) or restructuring meshing to own its inputs. It is
  also bounded to frames that actually have mesh work: an idle frame takes the
  `n == 0` early return and spawns nothing. Left as-is rather than traded for unsafe
  complexity of unproven benefit.
- **Typed `invokeExact` for the FFM downcalls.** The binding still uses
  `invokeWithArguments` (boxes each primitive). It was deliberately *not* changed:
  the boxing of a handful of scalar args is tens of nanoseconds, dwarfed by the
  native work behind each call (a whole scheduled/meshed/submitted frame) and by the
  bulk copy that was the real cost (fixed in #4). The benefit is marginal and the
  per-call-site exact-signature rewrite is broad and runtime-fragile; deferred.
- **remesh_budget enforcement.** Done — see section 6 above. (It was an honest TODO
  in `pipeline.rs` for a while: remeshes drew from the mesh budget, and the mesh
  budget itself only applied once the terrain budget was exhausted.)
- **CPU culling is O(resident) per frame** (`plan_draws` over `registry.records()`).
  It is also *not* where the frame time is: the whole-frame report puts cull +
  order + submit for 834 resident sections at 0.08 ms of a 14 ms fill, and 8 µs on
  an idle frame. Making it sublinear (column-level hierarchy, or HZB occlusion)
  would be optimising 0.6% of the fill, so it stays as it is until the meshing and
  upload paths below are done.
- **Presentation / swapchain / context sharing.** Still none across the three
  backends; output is offscreen. Adopting Minecraft's own window/context and present
  is the next stage and needs a real (non-headless) GPU host.
- **The data source is still `MinecraftChunkReader`**, not the C++ terrain engine;
  `takeover=false`. Connecting C++ (`cpp-bridge` `PushSource`) is the deferred step.
