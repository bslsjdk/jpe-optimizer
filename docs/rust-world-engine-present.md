# Stage A (start): presentation contract, external-GL path, and the C++ terrain seam

This stage *begins* the present/swapchain work and, as asked, **leaves the C++ engine
interface ready** — without connecting it and without touching ABI v8 or the frozen
Java↔Rust ABI v2. Everything here is additive and verified at unit + C-ABI + live
GameTest level; true on-screen output still needs a display and a render-thread
ownership change (explained below).

## 1. Presentation contract (Rust `Renderer`)

`render/mod.rs` gained four default methods plus three small types, so **every existing
backend and test keeps working unchanged**:

- `present_support() -> PresentSupport` — `OffscreenOnly | ExternalGl | OwnerSwapchain`
- `attach_surface(SurfaceDesc)` — `ExternalGlCurrent { w, h }` or `NativeWindow { .. }`
- `resize_surface([w, h])`
- `present() -> PresentStatus` — `Presented | Suboptimal | Nothing`

All default to `RendererError::Unsupported`, matching the existing `set_view_proj` /
`read_pixels` opt-in pattern.

### What each backend does

- **OpenGL / OpenGL ES** (`glcore.rs`, shared): implement `ExternalGl`. Attaching an
  `ExternalGlCurrent` surface drops the owned FBO and makes `begin/end_frame` render into
  the **default framebuffer (`0`)** of the context already current on the thread;
  `present()` is a `glFlush` and the context owner (Minecraft) swaps. `present_support()`
  returns `ExternalGl`. A `NativeWindow` attach is rejected with `Unsupported`, because
  desktop GL here uses the host's current context, not an owned window.
- **Vulkan / headless**: `OffscreenOnly` (default). A Vulkan owner-swapchain needs a
  platform `VkSurfaceKHR` (Xlib/Wayland/Win32) which this host cannot create without a
  display; it is left unimplemented and reported honestly, never faked.

## 2. Additive FFI (ABI stays v2)

Four new exported symbols, no struct or existing-symbol change, so a caller that ignores
them behaves exactly as before:

| symbol | returns |
|---|---|
| `jpe_world_renderer_present_support(handle)` | 0 offscreen / 1 external-GL / 2 owner-swapchain (or -1/-7) |
| `jpe_world_renderer_attach_external_gl(handle, w, h)` | 0 ok, -4 bad extent, -6 unsupported, -7 wrong thread |
| `jpe_world_renderer_resize_surface(handle, w, h)` | 0 ok, -4, -6 not attached, -7 |
| `jpe_world_renderer_present(handle, out_status)` | 0 ok (writes 0/1/2), -6 unsupported/not attached, -7 |

The Java binding (`JpeWorldNative`) and `RustWorldBridge` wrap all four. Because the live
GameTest constructs `JpeWorldNative` (which resolves every symbol eagerly), **the GameTest
passing proves the new binding matches the Rust exports** — a missing symbol would throw at
construction.

## 3. The remaining wiring to real on-screen output (not faked)

The bridge marshals every native call onto a private `jpe-world-engine` daemon thread,
and GL contexts are thread-affine. So an `ExternalGlCurrent` attach only shows up on screen
if the **host's GL context is current on the renderer's thread**. In Minecraft that means
the renderer must live on (or be re-homed to) Minecraft's render thread — the same thread
that owns the window context and performs the swap. That ownership change is the last mile
of Stage A and is a *wiring* step now that the contract, the backends and the FFI exist.
This document does not claim it is done.

## 4. C++ terrain-engine seam (ready, not connected)

The C++ side owns terrain **math** behind ABI v8, reachable from Rust only through
`terrain::TerrainEngine` (mock now, `NativeTerrainEngine` under `cpp-bridge`). The world
pipeline consumes **blocks** through `pipeline::BlockSource`. New module
`src/terrain_source.rs` adds the one small adapter that joins them:

```rust
DensityBlockSource<E: TerrainEngine>   // impl BlockSource
```

For each requested section it asks the engine for that section's `16³` region
(`VolumeDesc`, ABI v8 order `((z*sx+x)*sy+y)`, step 1) and thresholds density →
`ChunkSection`. No terrain math is reimplemented or duplicated.

Connecting the C++ engine later is a one-liner that changes no pipeline or renderer code:

```rust
let source = native_density_block_source(layers);   // cfg(feature = "cpp-bridge")
```

Tested against `MockTerrainEngine`: dense fill, threshold monotonicity, determinism, and a
non-empty mesh fed to the mesher.

## Verification

- `cargo test` (lavapipe): **108 passed** (was 103). New: 4 `terrain_source` tests + the
  `renderer_presentation_over_c_abi` test, which drives the C ABI end to end on desktop GL
  (present-support = ExternalGl, attach 64×48 ok, resize ok/­invalid, present → Presented).
- `gradle compileJava` / `gradle test`: BUILD SUCCESSFUL, **121 tests, 0 failures**.
- Live `runGameTestServer` via `run_minecraft_gametest.sh` after rebuilding the release
  `.so`: **All 11 required tests passed**, `result=passed backend=vulkan ... takeover=false`,
  no `world host` line (host stays gated). `nm -D` confirms the four new symbols are
  exported from `libjpe_world.so`.

## Explicitly NOT done / limits

- **No on-screen verification.** This sandbox has no display; the external-GL path is
  exercised on a headless EGL context (attach/resize/present status codes), not against a
  real window. Presenting into Minecraft's window needs the render-thread re-homing above.
- **Vulkan owner-swapchain** (platform surface + `vkQueuePresentKHR`) is unimplemented and
  reported as `OffscreenOnly`. Wiring it needs a real windowing host.
- GLES `ExternalGl` is only meaningful when the host owns the ES context; on desktop the GL
  backend is the shareable one.
- The C++ engine is **not** connected (`PushSource` still feeds blocks); only the adapter and
  `NativeTerrainEngine` drop-in path exist. ABI v8 unchanged; `takeover` stays `false`.

## Files

- `native/rust-world/src/render/mod.rs` — presentation types + trait defaults
- `native/rust-world/src/render/glcore.rs`, `gl.rs`, `gles.rs` — external-GL present
- `native/rust-world/src/ffi.rs` — 4 additive symbols + C-ABI test
- `native/rust-world/src/terrain_source.rs` (new) — C++ seam adapter
- `native/rust-world/src/lib.rs` — module + re-exports
- `native/rust-world/java/src/dev/jpe/world/JpeWorldNative.java` — binding
- `src/main/java/dev/jpe/optimizer/world/RustWorldBridge.java` — bridge wrappers