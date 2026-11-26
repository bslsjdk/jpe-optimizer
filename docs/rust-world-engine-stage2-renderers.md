# Rust World Engine — Stage 2: native renderer backends

Branch: `work/rust-world-engine`. This stage adds the three native GPU backends
required by the task book — **Vulkan**, **OpenGL**, **OpenGL ES** — to
`native/rust-world/src/render/`. Nothing is merged to `main`; all work stays on
the branch.

## What was added

| File | Role |
| --- | --- |
| `src/render/mod.rs` | `Renderer` contract, `FramePixels` readback, `create_best_headless()` |
| `src/render/vulkan.rs` | Raw Vulkan 1.x backend (instance, device, render pass, pipeline, SPIR-V, image→buffer readback) |
| `src/render/egl.rs` | Minimal EGL bindings; surfaceless display + context for headless GL/GLES |
| `src/render/glcore.rs` | Shared GL/GLES core: entry-point table, VAO/VBO/IBO, shaders, FBO, readback |
| `src/render/gl.rs` | Desktop OpenGL backend (`Profile::Core`, GLSL 330, `libGL.so.1`) |
| `src/render/gles.rs` | OpenGL ES backend (`Profile::Es`, GLSL ES 300, `libGLESv2.so.2`) |

No third-party framework is used. Each backend talks to the native API through
hand-written `extern "C"` bindings loaded with `dlopen`/`dlsym`; Vulkan shaders
are SPIR-V compiled offline and embedded. There is no wgpu, gfx-hal, vulkano or
glium anywhere in the crate.

## Contract

Every backend implements the same [`render::Renderer`] trait and additionally
reads the completed frame back as tightly packed RGBA8 with a **top-left
origin**:

```rust
fn begin_frame(&mut self, extent: [u32; 2]) -> Result<(), RenderError>;
fn create_mesh(&mut self, mesh: &MeshData) -> Result<MeshHandle, RenderError>;
fn destroy_mesh(&mut self, handle: MeshHandle);
fn draw(&mut self, batch: DrawBatch) -> Result<(), RenderError>;
fn end_frame(&mut self) -> Result<(), RenderError>;
fn read_pixels(&mut self) -> Result<FramePixels, RenderError>;
```

`FramePixels::count_non_background` is what the tests use to prove real output:
a backend cannot "pass" by merely reporting a submitted draw.

## Per backend

### Vulkan

- Instance → first physical device → graphics queue → logical device.
- Colour target: `VK_FORMAT_R8G8B8A8_UNORM` image, render pass, framebuffer.
- Pipeline: one vertex buffer (32-byte vertex: position/normal/uv) and one
  uint32 index buffer, both host-visible + coherent.
- Frame: begin render pass (clear 0.05/0.05/0.1), draw indexed, barrier, copy
  image to a host-visible buffer, wait on a fence, map and copy out.
- No swapchain/presentation: this is an offscreen target by design; presentation
  belongs to the client integration.

### OpenGL / OpenGL ES

- Shared headless context from `egl.rs` using `EGL_PLATFORM_SURFACELESS_MESA`
  (no X/Wayland). Desktop requests a 3.3+ context via `EGL_OPENGL_API`; ES
  requests 3.0+ via `EGL_OPENGL_ES_API`.
- `glcore.rs` resolves GL entry points from `libGL.so.1` / `libGLESv2.so.2`
  with `eglGetProcAddress` as fallback, compiles the built-in shaders at
  runtime, and creates one VAO + VBO + IBO per mesh.
- Frame target is an FBO with an RGBA8 colour renderbuffer and a 24-bit depth
  renderbuffer; `glReadPixels` result is vertically flipped so the origin
  matches Vulkan.
- `gl.rs` and `gles.rs` are distinct renderer types with their own capabilities
  (backend tag, capabilities) so callers and diagnostics cannot confuse them.

## Java / C ABI access

The backends are reachable from Java through the same `jpe_world_*` surface used
for scheduling and meshing (`src/ffi.rs`, bound in `JpeWorldNative.java`):

| Function | Purpose |
| --- | --- |
| `jpe_world_renderer_create(backend)` | 0 = best available, 1 Vulkan, 2 GL, 3 GLES; returns a handle or 0 |
| `jpe_world_renderer_available(backend)` | probe without keeping anything alive |
| `jpe_world_renderer_capabilities(handle, out)` | backend code, API version, max texture size, compute |
| `jpe_world_renderer_begin_frame(handle, w, h)` | start an offscreen frame |
| `jpe_world_renderer_upload_section(handle, blocks, x, y, z, out_mesh, out_indices)` | mesh a 16³ section and upload it |
| `jpe_world_renderer_draw(handle, mesh, index_count, instances)` | queue an indexed draw |
| `jpe_world_renderer_end_frame(handle)` | draw the queued batches and capture the frame |
| `jpe_world_renderer_read_pixels(handle, dst, len, out_w, out_h)` | RGBA8 readback, top-left origin (null `dst` = size query only) |
| `jpe_world_renderer_destroy_mesh` / `jpe_world_renderer_destroy` | release GPU resources |

Status codes: 0 ok, -1 unknown handle, -2 bad argument, -3 backend unavailable,
-4 invalid mesh, -5 API/device error, -6 no completed frame, -7 wrong thread.

Handles are **thread-affine**. GL/EGL contexts are thread-local, so the C ABI
records the creating thread and rejects any other with -7 before touching the
backend. This is enforced (and tested) rather than documented and hoped for.

The harness now drives a real backend: it uploads a one-block section, draws,
reads the frame back and asserts both the clear colour and the shader colour.
That is 71 checks in total, and the renderer section fails instead of skipping
when `JPE_REQUIRE_RENDER_BACKENDS=1`.

## Verification

Software Mesa is enough to exercise all three paths (`lavapipe` for Vulkan,
`llvmpipe` for GL/GLES). Observed on this sandbox:

```
vulkan device: llvmpipe (LLVM 15.0.7, 256 bits), api=(1, 3) maxTex=16384
gl      renderer: llvmpipe (LLVM 15.0.7, 256 bits) (4.5 (Core Profile) Mesa 23.2.1)
gles    renderer: llvmpipe (LLVM 15.0.7, 256 bits) (OpenGL ES 3.2 Mesa 23.2.1)
```

Each backend's test renders a single block mesh into a 128×128 offscreen target,
reads the frame back and asserts the frame is the expected size, that a
meaningful number of pixels differ from the cleared background (>100), and that
the shader colour (1.0, 0.5, 0.25) actually covers geometry.

The C ABI path is tested as well: `ffi::tests::renderer_over_c_abi_*` creates
each backend through `jpe_world_renderer_create(1|2|3)`, checks the reported
capabilities, renders and reads a frame, verifies the wrong-thread rejection and
the read-before-frame error. The whole crate is 52 tests; the Java harness is 71
checks.

Run locally:

```sh
cd native/rust-world
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json
export XDG_RUNTIME_DIR=/tmp
cargo test render:: -- --nocapture --test-threads=1
```

To turn a missing driver into a failure (used by CI so a backend cannot be
silently skipped):

```sh
JPE_REQUIRE_RENDER_BACKENDS=1 cargo test render:: -- --test-threads=1
```

The `gpu-backends` CI job installs the Mesa software drivers and runs exactly
that, so every push proves Vulkan, GL and GLES each created a real device or
context and produced pixels.

## Notes and lessons

- `#![deny(unsafe_op_in_unsafe_fn)]` is on: every unsafe call inside an `unsafe
  fn` is wrapped in an explicit `unsafe {}` block, and the entry-point table
  records the signature it transmutes to.
- The GL/EGL loader handles are intentionally **not** `dlclose`d. Closing
  `libGL.so.1` while glvnd/Mesa still hold process state segfaulted at exit
  during development; leaving one handle per renderer open is harmless and
  avoids unloading a vendor driver out from under the context.
- EGL contexts are thread-affine; the renderer types are deliberately `!Send`,
  and the C ABI enforces same-thread use.
- Neither the GL/EGL loader handles nor the Vulkan loader handle are `dlclose`d
  for the same reason: unloading a driver while its teardown state is live
  crashes the process. The Java side must also keep `libjpe_world` loaded once a
  backend has been created — closing the FFM arena unloads the module and the
  JVM then crashes on exit (reproduced and documented; production loads the
  library once for the whole game).

## Explicit non-claims

- No Minecraft window/context is adopted yet; the backends render to an
  offscreen target. Host-context/surface adoption is part of the client
  integration and is not faked here.
- No swapchain/presentation, textures, lighting, or greedy meshing.
- Renderer handles are exposed over the C ABI and exercised by the Java
  harness, but nothing in the NeoForge mod calls them yet: Minecraft's window,
  context and render thread are not wired, so there is no in-game rendering.
- No performance claims; there is no live workload.

## Next steps

1. Add a host-context adoption constructor per backend once Minecraft's render
   ownership (window, GL context, render thread) is audited.
2. Call the renderer ABI from the NeoForge mod on the render thread (the C ABI
   and Java binding are already there).
3. Add textures and a real camera/projection to replace the fixed offscreen
   transform, including the per-backend Y convention (Vulkan framebuffer origin
   is top-left, GL is bottom-left).
