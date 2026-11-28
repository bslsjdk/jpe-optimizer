//! JPE native world engine (Rust).
//!
//! This crate is the **World Engine** layer of the three-tier architecture:
//!
//! - **C++ Terrain Engine** — already implemented behind ABI v8
//!   (`native/cpp-kernels`, `native/include/jpe_native_api.h`). Not touched or
//!   reimplemented here.
//! - **Rust World Engine** — this crate: chunk coordinates, lifecycle,
//!   scheduling, mesh construction, culling, and the renderer interface.
//! - **Java** — Minecraft/NeoForge lifecycle and compatibility, unchanged.
//!
//! # Status (honest, no claims beyond what is tested)
//!
//! Implemented and unit-tested:
//! - coordinate packing matching vanilla `ChunkPos` / `SectionPos`
//! - chunk lifecycle state machine with ticket and world-epoch rules
//! - bounded, priority-aware, distance-re-ranked job queue
//! - scheduler core with stale-result rejection and queue-rollback safety
//! - scoped multi-threaded `parallel_map` foundation
//! - culled section mesher producing the shared 32-byte vertex format
//! - AABB / frustum section culling
//! - renderer interface + headless reference implementation
//! - native Vulkan, OpenGL and OpenGL ES backends: real device/context, mesh
//!   upload, draw and pixel readback, verified on software Mesa
//! - textured, shaded rendering on all three backends: position + normal + uv
//!   vertex attributes, a sampled texture atlas, directional lighting, a real
//!   depth buffer and a shared orthographic camera (including the Vulkan
//!   clip-Y convention), with the backends verified to produce identical pixels
//! - frustum planes extracted from the shared camera plus a camera-driven draw
//!   list (`render::plan`): section culling, deterministic front-to-back order and
//!   accounting stats, exposed as `jpe_world_renderer_draw_visible` and verified
//!   through the Java harness by pixel count (culled sections really are absent)
//! - renderer handles over the Java C ABI (create/caps/camera/frame/upload/
//!   draw/draw-visible/readback), thread-affinity enforced, exercised by the
//!   Java harness
//! - terrain FFI declarations for the existing ABI v8 (`cpp-bridge` feature)
//! - per-frame world pipeline (`pipeline`): terrain and mesh jobs pumped under
//!   per-frame budgets, geometry built on worker threads through `parallel_map`,
//!   uploaded on the render thread, published into a GPU-owned
//!   `render::registry::SectionRegistry`, then culled and submitted by
//!   `render::plan` — chunk state transitions now produce the draw list instead
//!   of the caller supplying one, neighbour arrival re-meshes the old chunk
//!   through the scheduler (`JobKind::RemeshMesh`), and an empty result retires
//!   the superseded GPU mesh so cleared blocks stop being drawn
//! - `pipeline::PushSource`: Java hands over finished block ids (section layout
//!   `(y<<8)|(z<<4)|x`, no transpose) and Rust owns everything after that
//! - Java ABI v2: `jpe_pipeline_*` entry points (create/destroy/submit-chunk/
//!   frame/unload-outside/set-player/ticket/stats/resident-keys/reset) plus
//!   `JpePipelineConfig` / `JpeFrameStats` / `JpePipelineStats`, enforced
//!   thread-affine and exercised end to end by the Java harness, including pixel
//!   proof that a culled camera and a cleared section draw nothing
//!
//! **Not implemented yet** (must not be reported as done):
//! - adopting Minecraft's live window/context (backends render offscreen today;
//!   host-context adoption is part of the client integration)
//! - presentation / swapchain
//! - the real Minecraft block-colour/resource-pack atlas (backends generate a
//!   small test atlas; per-block texture coordinates from the game are not
//!   wired), biome light / skylight data, fog
//! - wiring into live Minecraft chunk generation / rendering (the C ABI and
//!   Java binding exist; no mod code calls them yet). The pipeline is driven only
//!   by explicit `jpe_pipeline_submit_chunk` calls, so it does not see
//!   Minecraft's own chunk loads, block updates or light data yet
//! - terrain handoff from the C++ engine: `pipeline::PushSource` takes block ids
//!   from Java; the `cpp-bridge` path is still unwired
//! - greedy meshing, ambient occlusion
//! - shared-memory / zero-copy transport to C++

#![deny(unsafe_op_in_unsafe_fn)]

pub mod chunk;
pub mod coords;
pub mod ffi;
pub mod mesh;
pub mod pipeline;
pub mod render;
pub mod scheduler;
pub mod task;
pub mod terrain;
pub mod terrain_gen;
pub mod terrain_source;
// Terrain pulled straight from the C++ generator instead of pushed in by
// the host: this is what lets a world load without a Java round-trip per
// chunk. Gated because it needs the generator compiled in.
#[cfg(feature = "cpp-bridge")]
pub mod native_terrain_source;

pub use chunk::{ChunkEntry, ChunkState, ChunkStore, ChunkStoreError, WorldEpoch};
pub use coords::{
    BlockPos, ChunkPos, SectionPos, MAX_SECTION_Y, MIN_SECTION_Y, MIN_Y, SECTION_COUNT, WORLD_HEIGHT,
};
pub use mesh::{Aabb, ChunkSection, Frustum, MeshData, MeshScratch, Plane, Vertex};
pub use render::headless::HeadlessRenderer;
pub use render::{
    Backend, Capabilities, DrawBatch, MeshHandle, PresentStatus, PresentSupport, RenderError,
    Renderer, SurfaceDesc, TextureHandle,
};
pub use scheduler::{
    parallel_map, Completion, SchedError, SchedulerCore, SchedulerStats, NEAR_RADIUS_CHUNKS,
    URGENT_RADIUS_CHUNKS,
};
pub use task::{BoundedJobQueue, ChunkJob, JobKind, Priority, PushOutcome};
pub use terrain::{
    DensityVolume, JpePerlinLayer, MockTerrainEngine, PerlinLayerSpec, TerrainEngine, TerrainError,
    VolumeDesc, JPE_ABI_VERSION,
};
pub use terrain_source::DensityBlockSource;

/// Crate version, useful in logs and benchmark headers.
pub const CRATE_VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(test)]
mod integration {
    use super::*;

    /// End-to-end slice that is actually implemented today: schedule a chunk
    /// through terrain-ready, build its mesh from synthetic section data, and
    /// upload it through the renderer interface.
    #[test]
    fn scheduler_to_mesh_to_renderer() {
        let mut scheduler = SchedulerCore::new(1, 16);
        let pos = ChunkPos::new(0, 0);
        scheduler.request_terrain(pos).unwrap();
        let job = scheduler.take_job().unwrap();
        assert!(matches!(
            scheduler.complete(job),
            Completion::Committed { .. }
        ));

        let mut section = ChunkSection::new();
        for x in 0..16 {
            for z in 0..16 {
                section.set(x, 0, z, 1);
            }
        }
        let mesh = mesh::build_section_mesh(&section, [0, 0, 0]);
        // A solid 16x16 layer: top, bottom and four side strips, each merged
        // into a single quad.
        assert_eq!(mesh.quad_count, 6);

        let mut renderer = HeadlessRenderer::new();
        let handle = renderer.create_mesh(&mesh).unwrap();
        renderer.begin_frame([1280, 720]).unwrap();
        renderer
            .draw(DrawBatch {
                mesh: handle,
                index_count: mesh.indices.len() as u32,
                instance_count: 1,
            })
            .unwrap();
        renderer.end_frame().unwrap();
        assert_eq!(renderer.frame_count(), 1);

        scheduler.request_mesh(pos).unwrap();
        assert!(matches!(
            scheduler.store().state(pos),
            Some(ChunkState::MeshQueued)
        ));
    }
}
