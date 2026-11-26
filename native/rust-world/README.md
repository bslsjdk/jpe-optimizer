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

## Status

Implemented and unit-tested: coordinates, chunk lifecycle, bounded scheduler
with stale-epoch rejection, scoped parallel map, culled section mesher, AABB /
frustum culling, renderer trait, headless reference renderer, ABI v8 FFI
declarations.

Not implemented: Vulkan / OpenGL / OpenGL ES backends (probes return
`BackendUnavailable`), Minecraft integration, greedy meshing, lighting,
textures, shared-memory transport. See `docs/rust-world-engine-stage1.md` at the
repository root for the full Stage 1 audit.
