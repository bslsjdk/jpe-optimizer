//! Renderer abstraction shared by the Vulkan, OpenGL and OpenGL ES backends.
//!
//! The contract is intentionally narrow: the world engine produces
//! [`MeshData`] plus draw batches, and a backend owns all GPU resources and
//! submission. No third-party renderer is involved; each backend talks to the
//! native API directly.
//!
//! Status: only [`headless::HeadlessRenderer`] is implemented. The Vulkan, GL
//! and GLES modules expose capability probes that currently report
//! `BackendUnavailable`. They must not be reported as delivered until they
//! initialize a real device and pass the shared render tests.

use crate::mesh::MeshData;

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum Backend {
    Vulkan,
    Gl,
    Gles,
    Headless,
}

impl Backend {
    pub fn name(self) -> &'static str {
        match self {
            Backend::Vulkan => "vulkan",
            Backend::Gl => "gl",
            Backend::Gles => "gles",
            Backend::Headless => "headless",
        }
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct Capabilities {
    pub backend: Backend,
    /// Reported API version as (major, minor).
    pub api_version: (u32, u32),
    pub max_texture_size: u32,
    pub supports_compute: bool,
}

#[derive(Copy, Clone, Eq, PartialEq, Hash, Debug)]
pub struct MeshHandle(pub u64);

#[derive(Copy, Clone, Eq, PartialEq, Hash, Debug)]
pub struct TextureHandle(pub u64);

/// One indexed draw over a resident mesh.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct DrawBatch {
    pub mesh: MeshHandle,
    pub index_count: u32,
    pub instance_count: u32,
}

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum RenderError {
    BackendUnavailable(&'static str),
    InvalidMesh,
    DeviceLost,
    OutOfMemory,
}

pub trait Renderer {
    fn backend(&self) -> Backend;
    fn capabilities(&self) -> Capabilities;

    /// Start a frame targeting `extent` pixels (width, height).
    fn begin_frame(&mut self, extent: [u32; 2]) -> Result<(), RenderError>;

    /// Upload a mesh. Returns a handle owned by this renderer.
    fn create_mesh(&mut self, mesh: &MeshData) -> Result<MeshHandle, RenderError>;

    /// Release GPU resources for a mesh. Must be called on the render thread.
    fn destroy_mesh(&mut self, handle: MeshHandle);

    fn draw(&mut self, batch: DrawBatch) -> Result<(), RenderError>;

    /// Finish and present the frame.
    fn end_frame(&mut self) -> Result<(), RenderError>;
}

pub mod gles;
pub mod gl;
pub mod headless;
pub mod vulkan;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unimplemented_backends_report_unavailable() {
        let probes: [fn() -> Result<Capabilities, RenderError>; 3] =
            [vulkan::probe, gl::probe, gles::probe];
        for probe in probes {
            match probe() {
                Err(RenderError::BackendUnavailable(msg)) => {
                    assert!(msg.contains("not implemented"), "message was {msg:?}");
                }
                other => panic!("expected BackendUnavailable, got {other:?}"),
            }
        }
    }
}
