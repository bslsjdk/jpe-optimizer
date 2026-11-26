# Rust World Engine — Stage 4: frustum extraction and the draw-list plan

Branch: `work/rust-world-engine`. Nothing is merged to `main`.

Stage 3 gave the three native backends a real camera. This stage makes that
camera do the World Engine's visibility job: build frustum planes from it, cull
sections against them, order what survives, and prove — on the GPU, not just in
a counter — that culled sections were never drawn.

## What changed

| File | Change |
| --- | --- |
| `src/render/mod.rs` | `frustum_from_view_proj`, `section_is_visible`, `pub mod plan` |
| `src/render/plan.rs` (new) | `SectionRecord`, `PlanStats`, `quads_of`, `plan_draws` |
| `src/ffi.rs` | `JpeRenderSection`, `JpeRenderPlanStats`, `jpe_world_renderer_draw_visible`, layout + behaviour tests |
| `java/.../JpeWorldNative.java` | `RenderSection`, `PlanStats`, `rendererDrawVisible`, `rendererTryDrawVisible` |
| `java/.../WorldEngineHarness.java` | `visibility(...)` section driving the plan through FFM |

Still zero dependencies; the planning code is pure Rust and touches no GPU, so
it is unit-testable with no driver at all.

## Frustum from the camera the renderer already has

`frustum_from_view_proj` implements Gribb-Hartmann over the shared column-major
`Mat4`: the six inward-facing planes come from rows of the combined matrix, in
the order `left, right, bottom, top, near, far`, and feed the existing
`mesh::Frustum::intersects` (positive-vertex test, unchanged since stage 1).

Two details that are easy to get wrong and are therefore written down:

- The **near plane is row 2 alone**, not `row3 + row2`, because the shared
  projection maps depth to `0..=1` (Vulkan range). The `-1..=1` form would put
  the near plane in the wrong place.
- A degenerate row yields a plane that rejects everything (`d = f32::MIN`)
  rather than silently accepting the world.

`flip_clip_y` (stage 3) does not affect visibility: negating the clip-Y row swaps
the top and bottom planes into each other, so the plane *set* is identical and one
camera serves both culling and rendering on every backend.

## The plan

```rust
pub fn plan_draws(records: &[SectionRecord], view_proj: &Mat4, eye: [f32; 3])
    -> (Vec<DrawBatch>, PlanStats)
```

- `index_count == 0` is culled, never queued — an empty mesh must not cost a draw
  call.
- Survivors are sorted **front to back** by squared distance from the eye to the
  section centre. The chunk pipeline is opaque geometry with depth writes on, so
  near-first lets the depth buffer reject what comes after; a transparent pass
  would need the reverse order and is not part of this contract yet.
- Ties break on the record key, so the order is a total order and the output is
  deterministic (the tests assert the exact sequence).
- `PlanStats` reports `considered / visible / culled / visible_quads /
  culled_quads` and the tests assert `considered == visible + culled` and that
  every quad is accounted for.

## C ABI

```c
typedef struct {           /* 32 bytes, 8-byte aligned */
    uint64_t key;          /* offset 0  */
    uint64_t mesh;         /* offset 8  */
    int32_t  origin_x;     /* offset 16 */
    int32_t  origin_y;     /* offset 20 */
    int32_t  origin_z;     /* offset 24 */
    uint32_t index_count;  /* offset 28 */
} JpeRenderSection;

typedef struct {           /* 32 bytes */
    uint32_t considered, visible, culled, reserved;
    uint64_t visible_quads, culled_quads;
} JpeRenderPlanStats;

int32_t jpe_world_renderer_draw_visible(uint64_t handle,
                                        const float *view_proj /* 16 */,
                                        const float *eye /* 3 */,
                                        const JpeRenderSection *sections, size_t count,
                                        JpeRenderPlanStats *out_stats /* optional */);
```

`ffi::tests::renderer_plan_struct_layout_is_stable` asserts every field offset and
both struct sizes, because the Java binding writes these offsets literally.

The call sets the same matrix on the backend *before* queuing anything, so the
transform used by the GPU and the frustum used for culling cannot disagree — the
caller no longer has to remember a separate `set_camera`. Status codes as
elsewhere: `-1` unknown handle, `-2` null pointer (null sections with a non-zero
count), `-4` non-finite matrix or no frame begun, `-7` wrong thread. A rejected
matrix returns before any `draw` is issued, which the test verifies by checking
that the frame afterwards still holds only the clear colour.

## Verification

Rust (`JPE_REQUIRE_RENDER_BACKENDS=1`, lavapipe + llvmpipe): **68 tests pass**,
including five new pure-logic plan tests — empty input, zero index count, behind
/ beyond / off-to-the-side rejection, exact front-to-back order, and quad
accounting over 64 sections.

The C ABI test uploads six real sections (one block each) on the best available
backend and checks the plan is observable in pixels:

```
culling over the C ABI: 2/6 visible -> 1024 lit, 0/6 -> 0 lit
```

`java/run_harness.sh` drives the same path through Java 25 FFM and reports
identical numbers:

```
[harness] visibility: 2/6 visible -> 1024 lit pixels, 0/6 visible -> 0 lit pixels
[harness] all 108 checks passed
```

The second half is the important assertion: when every section is behind the
camera, the plan queues nothing **and the readback frame is empty (0 lit pixels)**.
That rules out a culling path that only reports numbers while the GPU still draws
everything.

```sh
cd native/rust-world
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json
export XDG_RUNTIME_DIR=/tmp LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
export JPE_REQUIRE_RENDER_BACKENDS=1
cargo test -- --test-threads=1
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 java/run_harness.sh
```

## Explicit non-claims

- The caller still owns the section registry. `ChunkStore` / `SchedulerCore` are
  not wired to this plan yet: nothing automatically turns "chunk became
  MESH_READY/UPLOADED" into a `SectionRecord`, and there is no per-frame loop.
  That integration is the next step.
- Culling granularity is one AABB per 16x16x16 section. There is no per-face or
  per-quad culling, no occlusion queries, no hierarchical Z buffer, no portal/PVS
  system, and no sorting by material (the atlas is a single texture, so batching
  by texture is not needed yet).
- Single-threaded planning. The queue and `parallel_map` foundations exist, but
  `plan_draws` is not parallelised and has no benchmark; no throughput claims are
  made.
- Front-to-back ordering is by section centre distance, which is an approximation;
  a real implementation would use the nearest point of the box.
- Still no Minecraft window/context adoption and no presentation; the frame is an
  offscreen target read back on CPU.
