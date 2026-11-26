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
an end-to-end harness that exercises scheduling, lifecycle, meshing and epoch
invalidation through that ABI.

```sh
# Builds libjpe_world (release), compiles the harness, and runs it.
# Needs JDK 25+ (FFM). No GPU, no Minecraft, no C++ library.
java/run_harness.sh
```

## Status

Implemented and unit-tested: coordinates, chunk lifecycle, bounded scheduler
with stale-epoch rejection, scoped parallel map, culled section mesher, AABB /
frustum culling, renderer trait, headless reference renderer, ABI v8 FFI
declarations, Java-facing C ABI (`src/ffi.rs`), Java 25 FFM binding and harness
(`java/`).

Not implemented: Vulkan / OpenGL / OpenGL ES backends (probes return
`BackendUnavailable`), Minecraft integration, greedy meshing, lighting,
textures, shared-memory transport. The C++ terrain bridge is gated behind the
`cpp-bridge` feature and not yet exercised. See
`docs/rust-world-engine-stage1.md` at the repository root for the full Stage 1
audit.
