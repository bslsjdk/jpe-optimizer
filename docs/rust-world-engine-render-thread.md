# Render-thread presentation — the last mile of Stage A

This note covers the step that connects the renderer to the real screen: creating the
native renderer on **Minecraft's render thread** over **Minecraft's already-current GL
context**, and presenting into that context's default framebuffer.

It builds directly on Stage A's presentation contract (see
`docs/rust-world-engine-present.md`): `PresentSupport::ExternalGl`, `attach_surface`,
`resize_surface`, `present`. Stage A made those callable over the C ABI; this step makes
them usable against a live game context by adding a way to *reuse* an existing context
instead of owning a private headless one.

## The problem this solves

The engine's headless backends (`create_headless`) call `EglContext::create`, which makes
a brand-new surfaceless context current and **owns** it: on drop it makes that context
not-current and destroys it. Two consequences blocked on-screen present:

1. Building a renderer on Minecraft's render thread would *replace* Minecraft's current
   context with the engine's own — corrupting the game's rendering.
2. The bridge always ran native calls on a private `jpe-world-engine` daemon thread, but
   a GL context is only usable on the thread it is current on. The renderer therefore
   could never touch the game's context, which lives on the render thread.

## What changed

### Rust — wrap, don't own

- `render/egl.rs`: `PfnGetProcAddress` is now public, `EglContext::get_proc_fn()` exposes
  the loader, and `load_egl_get_proc()` borrows `eglGetProcAddress` from a freshly opened
  `libEGL` for the no-owned-context path (`eglGetProcAddress` needs no current context).
- `render/glcore.rs`: `GlCore._egl` is now `Option<EglContext>`. `Some` = a headless
  renderer that owns and destroys its context; `None` = the renderer **wraps** a context
  the host already made current and must never make-current or destroy it. `GlCore::drop`
  only releases the GPU objects it created; it never touches the borrowed context.
- `GlCore::create_external(profile, width, height)` wraps the current context, verifies
  the GL version, and `attach_surface(ExternalGlCurrent)` so `begin/end_frame` draw into
  framebuffer `0`. `GlFns::load` now takes an optional proc loader so it works with or
  without an owned `EglContext`.
- `GlRenderer::create_external` / `GlesRenderer::create_external` expose it per backend.
- `draw_core` now accepts a draw when either an owned target or an attached external
  surface exists (previously it required an owned FBO, which the external path never
  creates) — this was a real bug the new test caught.
- FFI: additive `jpe_world_renderer_create_external(backend, width, height) -> u64`.
  `AUTO` tries GL then GLES; Vulkan returns `0` honestly (no owner-swapchain path yet).
  No struct layout or ABI version change.

### Java — keep the calls on the owning thread

- `CallerThreadExecutor` (new): an `AbstractExecutorService` that runs each task inline on
  the submitting thread. Substituting it for the private engine thread makes the existing
  `submit(...).get(...)` marshalling execute synchronously on the caller — so every native
  call stays on the render thread that owns the context.
- `JpeWorldNative.rendererCreateExternal(backend, width, height)` — binding for the new
  symbol.
- `RustWorldBridge.open(library, options, shareCurrentContext)` — when sharing, uses
  `CallerThreadExecutor` and creates the renderer with `rendererCreateExternal`; the
  existing `open(library, options)` delegates with `shareCurrentContext = false`.
- `WorldEngineHost` — opt-in `-Djpe.worldPresent=true` opens the bridge in shared mode and,
  each render frame after `bridge.frame(...)`, calls `bridge.present()` (a flush; Minecraft
  swaps). If the backend cannot share a context, the host logs and stays offscreen-only; a
  later present failure stops presenting instead of failing the host.

## Verification

- `cargo test` (lavapipe): **110 passed**. New `render::gl::tests::wrap_current_context_does_not_own_it`
  creates a headless owner, wraps its live context, draws+resizes+presents through the
  wrapper, drops the wrapper, then proves the owner's context is still current and its
  offscreen path still renders. New `ffi::tests::create_external_requires_a_current_context`
  proves the ABI symbol returns `0` (never a fake renderer) with no current context, for a
  zero extent, and for Vulkan/AUTO-without-context.
- Release `.so` exports `jpe_world_renderer_create_external` (`nm -D`).
- `gradle test`: **BUILD SUCCESSFUL** (includes `CallerThreadExecutorTest`).
- `runGameTestServer`: **11/11 passed** against the freshly built `.so`, `takeover=false`,
  backend Vulkan — the new Java binding loads and the gated host path is never triggered.

## Explicitly NOT done

- **No on-screen output has been verified.** This sandbox has no display, so the shared
  context path was proven only as far as "wrap a current context, draw, flush" on
  lavapipe; whether Minecraft's main target is framebuffer `0` at `RenderFrameEvent.Post`,
  and the visual result, are unverified.
- The present path is a **takeover demo**, not an overlay: the engine clears the
  framebuffer it draws into, so enabling `jpe.worldPresent` replaces the frame rather than
  compositing over the vanilla world. Off by default.
- Vulkan has **no** context-sharing/owner-swapchain path yet; it stays `OffscreenOnly`.
- The C++ terrain engine is still **not connected** (`PushSource` remains the block
  source); the seam (`DensityBlockSource`) is unchanged and ready.
- `takeover` remains `false`.