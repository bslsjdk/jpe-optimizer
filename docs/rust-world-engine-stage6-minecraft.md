# Rust World Engine — Stage 6: Java / Minecraft integration test

Branch `work/rust-world-engine`, crate `native/rust-world/`, mod `jpe_optimizer`.
Nothing in `native/cpp-kernels` or `native/include` was touched and the C++ terrain
ABI stays v8. This stage does **not** connect the C++ terrain engine — that was
explicitly deferred. It connects the Rust world engine to *real Minecraft 26.3*
through the Java bridge, so the three-tier split can be proven end to end with
data coming out of actual generated chunks:

```
Minecraft (26.3, Java)  ->  MinecraftChunkReader (Java, transitional)
                          ->  Rust World Engine (chunk lifecycle, meshing,
                              visibility, culling, GPU resources)
                          ->  native Vulkan / OpenGL / OpenGL ES backend
```

The point of the stage is the hand-off and the *proof*, not a takeover: Minecraft
still generates the terrain and still renders the game. The Rust engine is driven
in parallel over real section data and its output is measured directly.

## What changed

- **`src/main/java/dev/jpe/optimizer/world/MinecraftChunkReader.java`** — reads
  `ChunkAccess.getSections()` into the engine's flat `(y<<8)|(z<<4)|x` hand-off
  shape. Air is id `0`, first-appearance block states get ids from `1`, overflow
  clamps at `65535`. Every non-air block the scan counts is cross-checked against
  the section palette (`PalettedContainer.count`), and a mismatch throws — the
  reader cannot silently drop or duplicate blocks. It also reports distinct states,
  scan counts and a short `describe()` for the log line.
- **`src/main/java/dev/jpe/optimizer/world/RustWorldBridge.java`** — owns a
  renderer + pipeline inside Minecraft on a **dedicated daemon thread**
  (`jpe-world-engine`). Loads `libjpe_world.so` through the Java 25 FFM binding,
  asserts `JAVA_ABI_VERSION == 2`, creates the renderer and pipeline, and routes
  every native call through `submit(...)` so all GPU work happens on the engine
  thread and never on the server thread. `open(...)` tears down pipeline +
  renderer on the engine thread if construction fails. Exposes `submitChunk`,
  `frame`, `stats`, `residentKeys`, `readPixels`, `unloadOutside`, `setPlayer`,
  `ticket`, `reset`, `close`, and `ortho` for the test camera.
- **`build.gradle`** — one shared copy of the binding for the standalone harness
  and the mod: `main` source set adds `native/rust-world/java/src` and excludes the
  harness entry point, so `JpeWorldNative` compiles exactly once.
- **`src/main/java/dev/jpe/optimizer/GameTests.java`** — new
  `rust_world_pipeline` GameTest (`rustWorldPipeline` + `stampParityBlocks`),
  registered through `BuiltInRegistries.TEST_FUNCTION` /
  `RegisterGameTestsEvent`.
- **`native/rust-world/java/run_minecraft_gametest.sh`** — builds the release
  library if absent, resolves the lavapipe ICD, passes the bridge properties, and
  runs `gradle runGameTestServer`.
- **`.github/workflows/ci.yml`** — the Java verify job now builds
  `libjpe_world.so`, installs Mesa software drivers, exports the bridge properties
  and `jpe.worldRequired=true`, and asserts the result line (details below).

## What the test actually exercises

The test runs inside a live **Minecraft 26.3 dedicated GameTest server**. It does
not fabricate a world: it force-loads real generated chunks, reads them through
`MinecraftChunkReader`, and hands the bytes to `jpe_pipeline_submit_chunk`.

1. **Real chunk hand-off + layout parity.** Candidate 2×2 patches are force-loaded
   (`getChunk(x, z)` — the plain two-argument form is required because the GameTest
   origin is far from these coordinates and `getChunk(..., FULL, false)` returns
   null for unloaded positions). A 3-section window is read and, before the
   hand-off, `stampParityBlocks` places **three distinct block states at positions
   that differ along x, y and z** through `Level.setBlock`. The scan index formula
   `(section<<12)|(localY<<8)|(z<<4)|x` is then re-derived independently from
   Minecraft: for every sampled cell `handed[index]` must equal
   `reader.idOf(chunk.getBlockState(x, y, z))`, and the topmost solid block of each
   sampled column must match a direct vanilla scan. The stamped, axis-symmetric-
   defeating pattern is what makes this **non-vacuous**: a transposed layout or an
   x/z or y/z swap moves the gold/diamond/planks to cells Minecraft reports as
   something else. Natural terrain here is a low-variety sandstone/ocean plateau,
   which alone would not expose such a bug — so variety is imposed, not hoped for.
2. **Native meshing and draw.** Six frames over the pipeline must make the engine
   registry hold exactly the submitted non-empty sections (`resident == expected`),
   publish them, and report their `SectionPos` longs equal to
   `SectionPos.asLong(x, y, z)`. A facing orthographic camera must draw every
   section (`visible == expected`, `culled == 0`, `batches > 0`) and read back a
   non-empty frame: `litPixels > 200` and at least two distinct colours, so shading
   is observed in pixels, not just counted.
3. **Culling.** Turning the camera away must yield `visible == 0`,
   `culled == expected`, `batches == 0` and `awayLitPixels == 0` — nothing draws.
4. **A real Minecraft block update.** Three more blocks are placed through
   `Level.setBlock(GOLD/GLASS/OAK_PLANKS, UPDATE_NEIGHBORS|UPDATE_CLIENTS)`, the
   chunk is re-read from live data and resubmitted. The engine must remesh and
   **replace** the GPU geometry it already holds — `replaced` strictly grows past
   the settled count, and each placed cell must match the id Minecraft now reports.
5. **Retirement.** An air hand-off for one chunk must drop residency by exactly the
   number of that chunk's non-empty sections and report matching
   `meshesDestroyed`, proving the GPU buffers are freed, not just untracked.
6. **Lifetime control surface.** `setPlayer`, `ticket` (acquire returns 1, release
   0, an untracked chunk is rejected with -4), `unloadOutside(radius 0)` → resident
   0, and `reset` → epoch 2 all behave as the documented ABI specifies. The engine
   runs on its own thread (`engineThread != serverThread`), and `rendererCapabilities`
   reports a concrete backend rather than the `AUTO` selector.

## Verified evidence

From a run on this sandbox (`libjpe_world.so` release build, lavapipe):

```
JPE_RUST_WORLD_GAME_TEST result=passed backend=vulkan api=1.3
  library=.../native/rust-world/target/release/libjpe_world.so
  patchesScanned=1 patch=(256,-256@0) windowSections=3 baseSectionY=0
  topFilledSection=0 vanillaHeightmapY=3 handedStates=4
  nonEmptySections=4 resident=4 published=4 replaced=5 remeshChunks=6
  meshesDestroyed=6 emptySections=21 pendingMesh=0 pendingRemesh=0
  sourceSections=12 sourceMissingReads=12 sectionsUnloaded=3 chunksUnloaded=3
  visible=4 culled=0 batches=4 litPixels=1508 of=16384 distinctColours=5
  awayLitPixels=0 updateSubmitCode=2 afterAirResident=3 afterUnloadResident=0
  epochAfterReset=2 columnsChecked=64 columnsSolid=64 cellsChecked=448
  distinctBlockStates=5
  states=minecraft:sandstone=1,minecraft:gold_block=2,minecraft:diamond_block=3,minecraft:oak_planks=4,minecraft:glass=5
  blocksScanned=20480 nonAirBlocks=5123 paletteParity=true
  engineThread=55 serverThread=44 takeover=false

GameTestServer: All 11 required tests passed :)
```

The `states=` list is the proof the layout parity was non-vacuous: the natural
sandstone plus the four states the test wrote through Minecraft's own `setBlock`
(gold, diamond, planks from the initial stamp; glass from the later update) all
reached the engine with the ids Minecraft assigns them. `replaced=5` over
`published=4` is the block update genuinely supersiding resident GPU geometry.
`litPixels=1508/16384` with `distinctColours=5`, and `awayLitPixels=0`, are read
back from the framebuffer, not inferred from counters.

## Verification

```sh
# from the repo root, on a JDK-25-capable machine
native/rust-world/java/run_minecraft_gametest.sh
```

The script builds `libjpe_world` (release) if it is not already present, resolves
the lavapipe ICD, and runs the full `runGameTestServer` suite with
`jpe.worldRequired=true`. All 11 tests must pass.

CI (`.github/workflows/ci.yml`, Java verify job) now mirrors this: it builds
`libjpe_world.so`, installs the Mesa software drivers, exports
`-Djpe.worldLibrary=... -Djpe.worldRequired=true --enable-native-access=ALL-UNNAMED`
plus the `VK_*`/`LIBGL_ALWAYS_SOFTWARE` environment, asserts the
`JPE_RUST_WORLD_GAME_TEST result=passed ... litPixels=[1-9]... awayLitPixels=0 ...
distinctBlockStates=[3-9] ... paletteParity=true ... takeover=false` line, fails if
the engine ever reports `result=skipped`, and checks `All 11 required tests passed`.
Because `jpe.worldRequired=true` is set, a missing library or a driver that cannot
start turns into a hard failure rather than a silent skip, so the stage cannot go
green by not running.

## Explicit non-claims

- **This is not a vanilla render takeover** (`takeover=false`). Minecraft still
  generates the terrain and still draws the game. The Rust engine is driven
  alongside it over copied section data and its frame is read back offscreen; no
  Minecraft `RenderChunk`/`LevelRenderer` path is replaced.
- **The data source is `MinecraftChunkReader`, not the C++ terrain engine.** Block
  ids come from Java reading live chunks. The `cpp-bridge` `PushSource` wiring (the
  C++ terrain engine as the actual block source) is deliberately still unwritten —
  the user deferred it ("后面补上 c++"), so this stage proves the *Rust ↔ Java ↔
  GPU* side only.
- **Offscreen only.** The pipeline draws into the renderer's own offscreen target.
  Presentation/swapchain and adopting Minecraft's live window/context are not part
  of this stage.
- **The ids are the reader's, not Minecraft's block-state ids.** `1..` are assigned
  in first-appearance order and shared with the C++ convention; there is no
  resource-pack block-colour atlas, no per-block light, skylight or biome colour.
  What is drawn uses the stage-3 flat directional shading.
- **Headless / software GPU.** The measured run and CI both use lavapipe
  (software Vulkan). A hardware Vulkan/GL/GLES device is exercised by the
  `rust-world.yml` `gpu-backends` matrix at the Rust/FFI level, not by this Minecraft
  GameTest.
- **Small rig, bounded coverage.** A 3-section window over four chunks, 64 sampled
  columns / 448 cells; culling is frustum + residency only (no HZB/occlusion
  queries), meshing is section-level per-section quads (no greedy merging, no AO),
  and a block update reaches the chunk plus its four horizontal neighbours only.
- **`cargo fmt --check` / `clippy`** remain advisory (see the rust-world workflow);
  nothing here claims they passed.

## What comes next

Adopting Minecraft's live window/context and presentation; the `cpp-bridge`
`PushSource` so the C++ terrain engine is the real data source; a block-colour /
atlas + light pipeline; greedy meshing and AO; occlusion via HZB; and a shared-memory
transport to replace the per-chunk copy.
