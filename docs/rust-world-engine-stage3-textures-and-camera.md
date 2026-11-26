# Rust World Engine — Stage 3: textures, shading, depth and a real camera

Branch: `work/rust-world-engine`. Nothing is merged to `main`; all work stays on
this branch until the whole engine is finished.

This stage closes item 3 of the stage-2 next steps: the renderer no longer draws
a flat, fixed-transform quad. All three native backends now sample a texture
atlas, shade with per-face normals, run a depth test and use a real
view-projection matrix that Java can set per frame.

## What changed

| File | Change |
| --- | --- |
| `src/render/mod.rs` | Shared `Mat4` (column-major), `mat4_mul`, `ortho_look_at`, `flip_clip_y`, `Renderer::set_view_proj`, camera test helpers |
| `src/render/glcore.rs` | GL/GLES core now uploads normal + uv attributes, creates an 8×8 RGBA atlas, sets `uViewProj`/`uAtlas`, enables `GL_LESS` depth |
| `src/render/gl.rs`, `src/render/gles.rs` | `set_view_proj` delegates to the shared core; new shading tests |
| `src/render/vulkan.rs` | Sampler, descriptor set layout/pool/set, push constants, `D32_SFLOAT` depth attachment, atlas image upload, three vertex attributes, clip-Y flip |
| `src/render/headless.rs` | Records and validates the camera matrix |
| `src/render/shaders/chunk.{vert,frag}` (+ `.spv`) | Vulkan GLSL now takes normal/uv, a `sampler2D` and a push-constant matrix |
| `src/egl.rs` | The EGL framebuffer config requests 24 depth bits |
| `src/ffi.rs` | `jpe_world_renderer_set_camera`, `jpe_world_camera_ortho` |
| `java/.../JpeWorldNative.java` | `rendererSetCamera(long, float[])`, `cameraOrtho(...)` bindings |
| `java/.../WorldEngineHarness.java` | Camera checks plus a shaded-frame assertion instead of a flat colour |

No dependency was added. Everything is still hand-written `dlopen`/`dlsym`
binding to `libvulkan`, `libGL` and `libGLESv2`.

## One camera convention, three backends

`ortho_look_at` builds a column-major view-projection with the **Vulkan 0..1
depth range** (`z = -z_view/(far-near) - near/(far-near)`), which GL also accepts
directly, so one matrix serves all backends.

The Y convention genuinely differs: Vulkan's framebuffer origin is top-left, so
NDC `+y` points *down*, while GL/ES NDC `+y` points *up*. Feeding the same matrix
to Vulkan produced a vertically mirrored image. `flip_clip_y` negates the clip-Y
row (`indices 1, 5, 9, 13`) once, in `VulkanRenderer::set_view_proj`, so the
shared camera stays world-up oriented and every backend renders the same
direction. This is verified, not assumed — see the pixel-identity test below.

Two consequences worth remembering when culling is added:

- `flip_clip_y` reverses triangle winding. The Vulkan pipeline currently uses
  `CULL_MODE_NONE`; enabling back-face culling has to account for that (or bake
  the flip into the viewport instead).
- Vulkan also has a negative-height viewport trick, but it needs extra features;
  the matrix flip keeps the pipeline state simple.

## Vertex attributes actually reach the GPU

The mesher has emitted a 32-byte vertex (position, normal, uv) since stage 1, but
stage 2 only ever uploaded the position — `normal` and `uv` were dead bytes.
Now:

- GL/GLES: `vertexAttribPointer` for locations 0/1/2 at offsets 0/12/24.
- Vulkan: three `VertexInputAttributeDescription`s with formats
  `R32G32B32_SFLOAT`, `R32G32B32_SFLOAT`, `R32G32_SFLOAT`.

The fragment stage applies a single directional light
(`vec3(0.40824829, 0.81649658, 0.40824829)`, `shade = 0.5 + 0.5 * max(dot(N, L), 0)`)
to the sampled atlas texel, so a cube shows several tones at once.

## Atlas

`glcore::atlas_pixels()` generates an 8×8 RGBA checker (tones 235/175) used for
both GL backends (`GL_RGBA8`, `NEAREST`, `REPEAT`) and mirrored into the Vulkan
path (device-local `R8G8B8A8_UNORM` image, staged through a host-visible buffer,
with `UNDEFINED → TRANSFER_DST_OPTIMAL → SHADER_READ_ONLY_OPTIMAL` barriers, a
`VK_FILTER_NEAREST` sampler and a `COMBINED_IMAGE_SAMPLER` descriptor).

This is a *test* atlas. The real block-colour / resource-pack atlas and per-block
texture coordinates from the game are not wired yet, and no claim is made about
them.

## Depth

- Vulkan: second render-pass attachment `D32_SFLOAT`, cleared to 1.0, depth
  compare `VK_COMPARE_OP_LESS`, depth write on, aspect `IMAGE_ASPECT_DEPTH` view,
  attached to the framebuffer.
- GL/GLES: the offscreen FBO already attached a `GL_DEPTH_COMPONENT24`
  renderbuffer; `GL_DEPTH_TEST` is enabled with `glDepthFunc(GL_LESS)` and
  `glClear` clears `GL_DEPTH_BUFFER_BIT`. The EGL config now also asks for 24
  depth bits so a future host-window path has depth available.

Depth is needed rather than cosmetic: with a real camera a cube has faces that
overlap in screen space, and relying on draw order would show back faces.

## Two latent Vulkan bugs fixed

While wiring push constants I re-derived the constants I had hand-written in
stage 2 against the Vulkan 1.0 enum numbering and found two mistakes that only
escaped because lavapipe is forgiving and the old tests did not check depth or
winding:

- `VK_BUFFER_USAGE_VERTEX_BIT` and `VK_BUFFER_USAGE_INDEX_BIT` were swapped
  (correct: INDEX `0x40`, VERTEX `0x80`).
- `VK_INDEX_TYPE_UINT32` was `1` — that is `UINT16`. Correct value is `3`. The old
  code "worked" only because little-endian u32 indices read as u16 pairs happen to
  yield the same values for this tiny mesh.

Verified-correct values now in use include: `VK_STRUCTURE_TYPE_*`
(DEPTH_STENCIL_ATTACHMENT 25, SAMPLER 31, DESCRIPTOR_SET_LAYOUT 32,
DESCRIPTOR_POOL 33, DESCRIPTOR_SET_ALLOCATE 34, WRITE_DESCRIPTOR_SET 35,
FRAMEBUFFER 37, RENDER_PASS 38, COMMAND_POOL 39), stage flags
(TOP_OF_PIPE 0x1, FRAGMENT_SHADER 0x80, EARLY_FRAGMENT_TESTS 0x100,
COLOR_ATTACHMENT_OUTPUT 0x400, TRANSFER 0x1000), access flags
(SHADER_READ 0x20, COLOR_ATTACHMENT_WRITE 0x100, DEPTH_ATTACHMENT_WRITE 0x400,
TRANSFER_READ 0x800, TRANSFER_WRITE 0x1000), image layouts
(COLOR_ATTACHMENT_OPTIMAL 2, DEPTH_STENCIL_ATTACHMENT_OPTIMAL 3,
SHADER_READ_ONLY_OPTIMAL 5, TRANSFER_SRC_OPTIMAL 6, TRANSFER_DST_OPTIMAL 7),
`VK_COMPARE_OP_LESS = 1`, `VK_FORMAT_D32_SFLOAT = 126`.

## C ABI and Java

```
jpe_world_renderer_set_camera(u64 handle, const float* view_proj /* 16 */) -> i32
jpe_world_camera_ortho(const float* eye, const float* target, const float* up,
                       float half_w, float half_h, float near, float far,
                       float* out /* 16 */) -> i32
```

`camera_ortho` keeps the projection maths single-sourced in Rust; Java passes
plain vectors. Status codes follow the existing renderer ABI: `-2` null pointer,
`-4` non-finite matrix / degenerate camera, `-1` unknown handle, `-7` wrong
thread, `-6` backend without camera support. `set_camera` is subject to the same
thread affinity as the rest of a renderer handle.

Java side: `JpeWorldNative.rendererSetCamera(long, float[])` (64-byte segment)
and `JpeWorldNative.cameraOrtho(...)` returning `float[16]` or `null` for a
degenerate camera. The harness builds a camera, rejects a NaN matrix, rejects a
degenerate look-at, calls the camera from the wrong thread, and then asserts the
readback contains several distinct tones instead of one flat fill colour.

## Verification

Same software Mesa setup as stage 2.

```
vulkan device: llvmpipe (LLVM 15.0.7, 256 bits), api=(1, 3) maxTex=16384
gl      renderer: llvmpipe (LLVM 15.0.7, 256 bits) (4.5 (Core Profile) Mesa 23.2.1)
gles    renderer: llvmpipe (LLVM 15.0.7, 256 bits) (OpenGL ES 3.2 Mesa 23.2.1)
```

Shading evidence — each backend draws one block with the sample camera into a
128×128 target:

```
gl shading:     8626 lit pixels, 4 distinct colours
gles shading:   8626 lit pixels, 4 distinct colours
vulkan shading: 8626 lit pixels, 4 distinct colours
```

The C ABI path reports the same numbers through `jpe_world_renderer_*`, i.e.
Java/FFM gets the identical frame as the Rust-native tests.

`render::tests::backends_agree_on_pixels_and_orientation` is the strong check:
it renders a block placed near the top of a section with a world-up camera and
asserts

- the lit-pixel centre row is in the **upper half** of the frame for every
  backend (observed: row 10 of 128 on all three), which is only true if the
  top-left-origin contract and the Vulkan clip-Y flip are both right;
- every backend pair has a **pixel difference of 0** over all 16384 pixels.

Test/check counts: 61 Rust unit tests (`cargo test -- --test-threads=1`), 77 Java
harness checks.

```sh
cd native/rust-world
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json
export XDG_RUNTIME_DIR=/tmp
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
JPE_REQUIRE_RENDER_BACKENDS=1 cargo test -- --test-threads=1
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 java/run_harness.sh
```

## Explicit non-claims

- Still no Minecraft window/context adoption and no presentation. The Vulkan
  image is an offscreen colour attachment; there is no swapchain.
- The atlas is synthetic. Real block colours, the resource pack, baked lighting,
  skylight/block-light arrays, biome tint and fog are not implemented.
- Vertex formats are per-vertex float32 with no compression, one draw per mesh,
  no instancing, no batch/section caching beyond the resident mesh map, no
  frustum-culling integration into the draw loop (the culling code exists in the
  world engine but the renderer is not fed by it yet).
- The Vulkan backend picks the first suitable physical device and a single queue;
  there is no device selection policy, no multi-queue use, and the readback path
  is a blocking fence wait intended for tests, not for a frame loop.
- `cargo fmt`/`cargo clippy` remain advisory in CI: this sandbox cannot install
  those components from the configured rustup mirror (404), so line lengths are
  not auto-normalised.
