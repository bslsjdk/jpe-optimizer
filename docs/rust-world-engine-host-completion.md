# Completing the Java world host (Stage 4b): dirty resubmit, full-distance streaming, live camera

This stage finishes the continuously-running Java `WorldEngineHost` begun in Stage 4. It
does **not** touch the C++ terrain engine (still deferred), the frozen Java↔Rust ABI
v2, or the bounded GameTest rig. Everything here is Java-side and verifiable at
compile/unit level plus the existing live GameTest; on-screen presentation is still the
unimplemented stage.

## What changed

### 1. Full render-distance streaming

`WorldEngineHost.Config.streamRadius <= 0` now means "follow the live
`Options.renderDistance()`". The streaming radius is recomputed every tick through
`WorldHostMath.effectiveStreamRadius(configured, renderDistance, cap)`, so raising or
lowering Minecraft's render distance re-streams (or prunes) to match without a restart.
A hard `radiusCap` (default **8** chunks) bounds memory: at 8 the ring is 17×17 columns
≈ 56 MB of `u16` block data plus the Rust mirror, so a 12–32-chunk client does not
balloon the heap. Raise `radiusCap` where memory allows.

The far plane is derived from the effective radius via `WorldHostMath.farPlane`, with a
floor (`minFarBlocks`, default 128) so distant terrain is not clipped.

### 2. Block-edit ("dirty chunk") resubmit — bounded cost

Re-submitting an already-uploaded chunk makes Rust replace the stored sections and
remesh that chunk plus its loaded horizontal neighbours (`Submit::RemeshRequested`,
`pipeline.rs`). The host needs to know *when* a client chunk changed. Rather than
re-reading every chunk every tick, it keeps a **content signature** per resident chunk:

- `BlockSignature` is a 64-bit FNV-1a fold over the `u16` ids in the reader's exact
  `(section, y, z, x)` order. All-air sections fold via one multiply
  (`foldAirSection`, `PRIME^4096`), not 4096 steps.
- `MinecraftChunkReader.signature(chunk, base, count)` computes that fold **without
  allocating** the `short[]` the `read` path builds; it shares the same `idOf` mapping,
  so it equals `BlockSignature.of(read(...))` for identical content.
- Each tick the host re-signatures at most `Config.dirtyBudget()` resident chunks
  (default 8), round-robin via `dirtyCursor`. Only a chunk whose signature moved pays for
  a fresh `read` on the resubmit path; unchanged chunks cost one section walk and no
  allocation.

Cost is therefore **O(dirtyBudget) per tick regardless of ring size**, not O(resident).
Signature collisions merely miss a remesh; they can never produce wrong geometry.

A second correctness win falls out of this: the host now uses **one**
`MinecraftChunkReader` for its whole lifetime. The `BlockState → id` map is therefore
globally consistent, so the Rust mesher culls shared faces at chunk borders instead of
treating the same block in two chunks as different ids (the previous per-chunk reader
gave each column its own local numbering).

### 3. Interpolated camera + live FOV

`onRenderFrame` now:

- reads the partial tick from `Minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true)`
  and builds the eye from `Entity.getEyePosition(partialTick)` and the look angles from
  `getViewYRot/getViewXRot(partialTick)`, so motion is smooth at render rate instead of
  snapping to the 20 Hz tick;
- reads the **live** `Options.fov()` (clamped by `WorldHostMath.clampFov` to 30–130°)
  instead of the previous fixed 70°.

The view-projection maths are unchanged (`WorldCamera`, Vulkan `0..=1` depth — the near
plane must stay on `0`, see `frustum_from_view_proj`).

## Verification

- `gradle compileJava compileTestJava` — BUILD SUCCESSFUL (against the real
  Minecraft 26.3 client classes; the `client` run config compiles the host).
- `gradle test` — **121 tests, 0 failures** (was 105). New: `BlockSignatureTest`
  (10: stability, single-id edit, reorder, length, unsigned-u16, air fast-path parity,
  range checks, collision sanity) and `WorldHostMathTest` (6: radius follow/cap/override/
  floor, FOV clamp, far plane). `WorldCameraTest` (8) still green.
- Live `runGameTestServer` via `native/rust-world/java/run_minecraft_gametest.sh` —
  `All 11 required tests passed`, `JPE_RUST_WORLD_GAME_TEST result=passed backend=vulkan
  ... takeover=false`. The script sets `jpe.worldLibrary` and `jpe.worldRequired=true`
  but **not** `jpe.worldHost`, and the log contains no "world host" line: the new host
  code is never loaded on the validated GameTest path.

## Explicitly NOT done / limits

- **No on-screen rendering.** The engine still draws into its own offscreen target; it
  does not present to Minecraft's window/swapchain or share its GL/Vulkan context. That
  is the next stage and needs a real GPU host with a display — unavailable here.
- The dirty detector is signature-based, not event-driven. It catches edits within
  `ceil(resident/dirtyBudget)` ticks (latency, not correctness). A future Mixin/event on
  client block updates could make it immediate, but that is intentionally avoided.
- FOV uses the **base** option, not the per-frame dynamic FOV (sprint/bow/underwater
  modifiers applied in `GameRenderer`); matching those exactly is part of the
  present/context stage.
- Streaming still reads whole columns from Minecraft; terrain generation stays in
  Minecraft and the C++ terrain engine remains unwired (`PushSource` unchanged).
- `jpe.worldHost` stays opt-in; nothing in this stage changes default server or GameTest
  behaviour, and `takeover` stays `false`.

## Files

- `src/main/java/dev/jpe/optimizer/world/BlockSignature.java` (new)
- `src/main/java/dev/jpe/optimizer/world/WorldHostMath.java` (new)
- `src/main/java/dev/jpe/optimizer/world/MinecraftChunkReader.java` — added
  `signature(...)`
- `src/main/java/dev/jpe/optimizer/world/WorldEngineHost.java` — full-distance
  streaming, bounded dirty resubmit, interpolated live-FOV camera, shared reader
- `src/test/java/dev/jpe/optimizer/world/BlockSignatureTest.java` (new)
- `src/test/java/dev/jpe/optimizer/world/WorldHostMathTest.java` (new)