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

## Verification

- `cargo test` (in `native/rust-world`): **103 passed, 0 failed**, warning-free
  `cargo build` and `cargo build --release`.
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
- **remesh_budget enforcement.** Still an honest TODO in `pipeline.rs` — remeshes
  draw from the mesh budget; enforcing a separate remesh budget needs the queue to
  defer an already-dequeued job without a spurious state transition (scheduler change).
- **CPU culling is O(resident) per frame** (`plan_draws` over `registry.records()`).
  Reducing this needs GPU-side occlusion (HZB), which is a later stage, not a tuning
  change.
- **Presentation / swapchain / context sharing.** Still none across the three
  backends; output is offscreen. Adopting Minecraft's own window/context and present
  is the next stage and needs a real (non-headless) GPU host.
- **The data source is still `MinecraftChunkReader`**, not the C++ terrain engine;
  `takeover=false`. Connecting C++ (`cpp-bridge` `PushSource`) is the deferred step.
