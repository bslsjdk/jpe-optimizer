# jpe-world-engine

Rust **World Engine** for the JPE native split. Zero dependencies.

Owns: chunk coordinates, lifecycle, scheduling, mesh construction and culling,
and the renderer interface. Terrain math stays in C++ (ABI v8); Minecraft
lifecycle stays in Java.

## Build and test

```sh
cargo test
cargo build --release
```

Linking the existing C++ terrain kernels (does not change their ABI):

```sh
cargo build --features cpp-bridge   # expects jpe_runtime symbols at link time
```

## Java bridge (temporary integration path)

The current phase wires Java to the Rust engine directly, with no C++, while the
C++ side is still in progress. `src/ffi.rs` exports a stable C ABI
(`jpe_world_*`), and `java/src/dev/jpe/world/` holds the Java 25 FFM binding plus
an end-to-end harness that exercises scheduling, lifecycle, meshing, epoch
invalidation **and a native GPU backend** (create, capabilities, frame, section
upload, draw, pixel readback) through that ABI.

```sh
# Builds libjpe_world (release), compiles the harness, and runs it.
# Needs JDK 25+ (FFM). No Minecraft, no C++ library. If a GPU driver exists the
# renderer section runs for real; otherwise it skips unless
# JPE_REQUIRE_RENDER_BACKENDS=1 is set.
java/run_harness.sh
```

Renderer handles are thread-affine: a handle must be driven from the thread that
created it, and any other thread gets status `-7` instead of touching a
thread-local GL/EGL context.

## Status

Implemented and unit-tested: coordinates, chunk lifecycle, bounded scheduler
with stale-epoch rejection, scoped parallel map, culled section mesher, AABB /
frustum culling, renderer trait, headless reference renderer, **three native GPU
backends (Vulkan, OpenGL, OpenGL ES) with textured, depth-tested, camera-driven
offscreen rendering and frame readback**, ABI v8 FFI declarations, Java-facing
C ABI (`src/ffi.rs`), Java 25 FFM binding and harness (`java/`).

The three backends call the native APIs directly through hand-written bindings —
no wgpu/gfx-hal/vulkano/glium. Each one creates a real device or context, uploads
position/normal/uv vertices and indices, samples a texture atlas, applies
directional shading against a real depth buffer, and reads the frame back; the
tests assert actual pixel coverage rather than a submitted status. They are
verified on software Mesa (lavapipe for Vulkan, llvmpipe for GL/GLES), and
`render::tests::backends_agree_on_pixels_and_orientation` checks that all three
produce **identical pixels** from the same mesh and camera, right way up. See
`docs/rust-world-engine-stage2-renderers.md` and
`docs/rust-world-engine-stage3-textures-and-camera.md` for the design and the
exact verification commands.

Not implemented yet: adopting Minecraft's live window/context (the backends
render headless or offscreen today; host-context adoption lands with the client
integration), presentation/swapchain, the real resource-pack/block-colour atlas
and game light data (the backends build a small test atlas), greedy meshing,
ambient occlusion, Minecraft integration, shared-memory transport. The C++ terrain
bridge is gated behind the `cpp-bridge` feature and not yet exercised. See
`docs/rust-world-engine-stage1.md`,
`docs/rust-world-engine-stage2-renderers.md` and
`docs/rust-world-engine-stage3-textures-and-camera.md` for the full audit trail.
