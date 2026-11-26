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
//! - terrain FFI declarations for the existing ABI v8 (`cpp-bridge` feature)
//!
//! **Not implemented yet** (must not be reported as done):
//! - adopting Minecraft's live window/context (backends render offscreen today;
//!   host-context adoption is part of the client integration)
//! - presentation / swapchain, textures, lighting
//! - wiring into live Minecraft chunk generation / rendering
//! - greedy meshing, ambient occlusion, textures
//! - shared-memory / zero-copy transport to C++

#![deny(unsafe_op_in_unsafe_fn)]

pub mod chunk;
pub mod coords;
pub mod ffi;
pub mod mesh;
pub mod render;
pub mod scheduler;
pub mod task;
pub mod terrain;

pub use chunk::{ChunkEntry, ChunkState, ChunkStore, ChunkStoreError, WorldEpoch};
pub use coords::{
    BlockPos, ChunkPos, SectionPos, MAX_SECTION_Y, MIN_SECTION_Y, MIN_Y, SECTION_COUNT, WORLD_HEIGHT,
};
pub use mesh::{Aabb, ChunkSection, Frustum, MeshData, MeshScratch, Plane, Vertex};
pub use render::headless::HeadlessRenderer;
pub use render::{
    Backend, Capabilities, DrawBatch, MeshHandle, RenderError, Renderer, TextureHandle,
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
        // A solid 16x16 layer: top + bottom + 4 side strips.
        assert_eq!(mesh.quad_count, 16 * 16 * 2 + 4 * 16);

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
