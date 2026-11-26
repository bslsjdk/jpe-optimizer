# Rust World Engine — continuous Java client host

Branch `work/rust-world-engine`, crate `native/rust-world/`, mod `jpe_optimizer`.
This is the "complete system against Java" step: the bounded Stage 6 GameTest rig
(`rust-world-engine-stage6-minecraft.md`) is generalised into a host that runs
continuously, driven by real Minecraft client events, with the optimizations from
`rust-world-engine-optimization.md` already in place underneath it.

Nothing here changes the ABI (`JAVA_ABI_VERSION` stays `2`, layouts/status codes
frozen) and nothing connects the C++ terrain engine — that remains the deferred
`PushSource` step. The block source is still `MinecraftChunkReader`; vanilla
rendering is not taken over (`takeover=false`); the engine draws into its own
offscreen target.

## Pieces

### `WorldEngineHost` (client-only)
Attached from `JPEOptimizer` only under `Dist.CLIENT`, and only when the explicit
`-Djpe.worldHost=true` opt-in is set — two independent guards so neither a
dedicated server nor the validated `gameTestServer` path ever loads the class or
changes behaviour.

- **`ClientTickEvent.Post`** — reads the camera entity's chunk coordinate, calls
  `setPlayer`, streams every newly-`hasChunk` column in a working radius through
  `MinecraftChunkReader` → `submitChunk`, and on player-chunk change calls
  `unloadOutside` plus prunes its own tracked set so chunks that leave and return
  get resubmitted.
- **`RenderFrameEvent.Post`** — builds a real perspective view-projection from the
  camera entity's eye position and `getViewYRot`/`getViewXRot`, and runs one full
  engine frame (schedule → mesh → upload → cull → draw) per rendered frame. A
  periodic line logs resident/visible/culled/batches from the returned `FrameStats`.
- Every native call is wrapped; a fault sets `failed` and disables the host without
  ever throwing into the game.
- **`shutdown()`** uses a new `RustWorldBridge.releaseHandles()`: it destroys the
  pipeline + renderer on the engine thread but keeps `libjpe_world` mapped, because
  `dlclose` after a GPU backend was created leaves Mesa/glvnd teardown state and
  crashes the JVM on exit. Production keeps the library resident.

### `WorldCamera` (pure, no Minecraft dependency)
Column-major perspective `viewProj` built to the exact contract the Rust engine
consumes — the same `mat4_mul` / lookAt basis as `render/mod.rs`, and critically a
**Vulkan `0..=1` depth range** (the `z' = 0.5z + 0.5` remap is baked into the
projection, not applied later). `frustum_from_view_proj` extracts the near plane
from row 2 alone *because* it expects `0..=1`; feeding it a GL (`-1..=1`) matrix
culls incorrectly, so the host never does that. Being pure Java this is unit-test
material with no GPU or game.

### `build.gradle`
Adds a `client` run config that sets `jpe.worldLibrary` (to the crate's release
`.so`) and `jpe.worldHost=true`.

## Verification

- `gradle compileJava` succeeds against the real Minecraft 26.3 client classes
  (`net.minecraft.client.*`, the client tick/render events, `ChunkAccess`, etc.).
- `gradle test --tests WorldCameraTest`: **8/8 passed** — near→depth 0, far→depth 1,
  monotonic within `0..=1`, beyond-far culled, on-axis centred, behind-camera `w<0`,
  eye-translation consistency, invalid-parameter rejection.
- Live Minecraft 26.3 GameTest after the wiring is **unchanged** (`result=passed`,
  `paletteParity=true`, `litPixels=1508 of=16384`, `columnsSolid=64`,
  `takeover=false`, All 11 required tests passed), and its log shows the mod
  initialised without ever touching the host — confirming the server path is
  isolated from the client-only code.

## Limits (honest)

- **Not yet run on an on-screen client.** This environment is headless (no display),
  so live client rendering has not been exercised here; what is proven is that the
  host compiles against the real client API, its camera maths is unit-tested, and the
  server/GameTest paths are unaffected. Running it needs the `client` run config on a
  machine with a display.
- **Offscreen only.** No swapchain/surface/present and no sharing of Minecraft's
  window/context. Presentation + context sharing is the next stage.
- **Transitional camera/inputs.** Fixed FOV and `partialTick = 0` for now; reading the
  live FOV option and interpolated camera is part of the presentation stage.
- **New chunks only.** The host streams chunks on first sight; dirty-block resubmit
  on live block updates (via chunk-load/dirty tracking) is a follow-up.
- **Bounded radius.** A small working radius (default 4 chunks) rather than full
  render distance; the queue-capacity and budgets are sized for the transitional path.
