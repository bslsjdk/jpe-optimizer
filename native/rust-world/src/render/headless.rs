//! Headless renderer used to validate the renderer contract without a GPU.
//!
//! It records mesh uploads and draws so the world engine's geometry output can
//! be tested deterministically in CI, where no Vulkan/GL device exists. It is
//! explicitly *not* a rendering backend: it produces no image.

use super::{
    Backend, Capabilities, DrawBatch, MeshHandle, RenderError, Renderer, TextureHandle,
};
use crate::mesh::MeshData;
use std::collections::BTreeMap;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct MeshStats {
    pub vertices: usize,
    pub indices: usize,
    pub quad_count: u32,
}

/// CPU-only recorder implementing [`Renderer`].
#[derive(Debug)]
pub struct HeadlessRenderer {
    capabilities: Capabilities,
    next_handle: u64,
    meshes: BTreeMap<u64, MeshStats>,
    destroyed: Vec<u64>,
    frame_extent: Option<[u32; 2]>,
    frame_count: u64,
    draws: Vec<DrawBatch>,
}

impl HeadlessRenderer {
    pub fn new() -> Self {
        Self {
            capabilities: Capabilities {
                backend: Backend::Headless,
                api_version: (0, 0),
                max_texture_size: 0,
                supports_compute: false,
            },
            next_handle: 1,
            meshes: BTreeMap::new(),
            destroyed: Vec::new(),
            frame_extent: None,
            frame_count: 0,
            draws: Vec::new(),
        }
    }

    pub fn mesh_stats(&self, handle: MeshHandle) -> Option<MeshStats> {
        self.meshes.get(&handle.0).copied()
    }

    pub fn live_mesh_count(&self) -> usize {
        self.meshes.len()
    }

    pub fn destroyed_meshes(&self) -> &[u64] {
        &self.destroyed
    }

    pub fn draw_count(&self) -> usize {
        self.draws.len()
    }

    pub fn frame_count(&self) -> u64 {
        self.frame_count
    }

    pub fn frame_extent(&self) -> Option<[u32; 2]> {
        self.frame_extent
    }

    pub fn create_texture(&mut self) -> TextureHandle {
        let handle = TextureHandle(self.next_handle);
        self.next_handle += 1;
        handle
    }
}

impl Default for HeadlessRenderer {
    fn default() -> Self {
        Self::new()
    }
}

impl Renderer for HeadlessRenderer {
    fn backend(&self) -> Backend {
        Backend::Headless
    }

    fn capabilities(&self) -> Capabilities {
        self.capabilities
    }

    fn begin_frame(&mut self, extent: [u32; 2]) -> Result<(), RenderError> {
        if extent[0] == 0 || extent[1] == 0 {
            return Err(RenderError::InvalidMesh);
        }
        self.frame_extent = Some(extent);
        self.draws.clear();
        Ok(())
    }

    fn create_mesh(&mut self, mesh: &MeshData) -> Result<MeshHandle, RenderError> {
        if mesh.vertices.is_empty() || mesh.indices.is_empty() {
            return Err(RenderError::InvalidMesh);
        }
        let handle = MeshHandle(self.next_handle);
        self.next_handle += 1;
        self.meshes.insert(
            handle.0,
            MeshStats {
                vertices: mesh.vertices.len(),
                indices: mesh.indices.len(),
                quad_count: mesh.quad_count,
            },
        );
        Ok(handle)
    }

    fn destroy_mesh(&mut self, handle: MeshHandle) {
        if self.meshes.remove(&handle.0).is_some() {
            self.destroyed.push(handle.0);
        }
    }

    fn draw(&mut self, batch: DrawBatch) -> Result<(), RenderError> {
        if self.frame_extent.is_none() {
            return Err(RenderError::InvalidMesh);
        }
        if !self.meshes.contains_key(&batch.mesh.0) {
            return Err(RenderError::InvalidMesh);
        }
        self.draws.push(batch);
        Ok(())
    }

    fn end_frame(&mut self) -> Result<(), RenderError> {
        if self.frame_extent.take().is_none() {
            return Err(RenderError::InvalidMesh);
        }
        self.frame_count += 1;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mesh::{build_section_mesh, ChunkSection};

    fn one_block_mesh() -> MeshData {
        let mut section = ChunkSection::new();
        section.set(0, 0, 0, 1);
        build_section_mesh(&section, [0, 0, 0])
    }

    #[test]
    fn upload_and_draw_round_trip() {
        let mut r = HeadlessRenderer::new();
        let mesh = one_block_mesh();
        let handle = r.create_mesh(&mesh).unwrap();
        let stats = r.mesh_stats(handle).unwrap();
        assert_eq!(stats.quad_count, 6);
        assert_eq!(stats.vertices, 24);

        r.begin_frame([1920, 1080]).unwrap();
        r.draw(DrawBatch {
            mesh: handle,
            index_count: stats.indices as u32,
            instance_count: 1,
        })
        .unwrap();
        r.end_frame().unwrap();
        assert_eq!(r.frame_count(), 1);
        assert_eq!(r.draw_count(), 1);
        assert_eq!(r.frame_extent(), None);

        r.destroy_mesh(handle);
        assert_eq!(r.live_mesh_count(), 0);
        assert_eq!(r.destroyed_meshes(), &[handle.0]);
    }

    #[test]
    fn empty_mesh_is_rejected() {
        let mut r = HeadlessRenderer::new();
        let empty = build_section_mesh(&ChunkSection::new(), [0, 0, 0]);
        assert_eq!(r.create_mesh(&empty), Err(RenderError::InvalidMesh));
    }

    #[test]
    fn draw_requires_open_frame() {
        let mut r = HeadlessRenderer::new();
        let handle = r.create_mesh(&one_block_mesh()).unwrap();
        let err = r
            .draw(DrawBatch {
                mesh: handle,
                index_count: 36,
                instance_count: 1,
            })
            .unwrap_err();
        assert_eq!(err, RenderError::InvalidMesh);
    }
}
