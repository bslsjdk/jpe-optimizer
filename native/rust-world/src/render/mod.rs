//! Renderer abstraction shared by the Vulkan, OpenGL and OpenGL ES backends.
//!
//! The contract is intentionally narrow: the world engine produces [`MeshData`]
//! plus draw batches, and a backend owns all GPU resources and submission. No
//! third-party renderer is involved; each backend talks to the native API
//! directly through hand-written `extern "C"` bindings.
//!
//! Two ways to obtain a backend are supported:
//!
//! * **Offscreen / headless** — `create_headless()` builds a device or context
//!   that needs no window. This is what the tests and CI use to prove the
//!   native path actually runs on a driver.
//! * **Host context** — the production path reuses Minecraft's existing
//!   window/surface/context instead of creating a second one. That entry point
//!   is added together with the Minecraft integration; it is not faked here.
//!
//! Every backend also exposes [`Renderer::read_pixels`] so a frame can be read
//! back and checked, which is how the tests demonstrate real output rather than
//! trusting a "submitted" status.

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

/// Pixels read back from the most recent frame, tightly packed RGBA8,
/// top-left origin.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FramePixels {
    pub width: u32,
    pub height: u32,
    pub rgba8: Vec<u8>,
}

impl FramePixels {
    /// RGBA of the pixel at `(x, y)`, top-left origin.
    pub fn pixel(&self, x: u32, y: u32) -> Option<[u8; 4]> {
        if x >= self.width || y >= self.height {
            return None;
        }
        let offset = ((y * self.width + x) * 4) as usize;
        self.rgba8
            .get(offset..offset + 4)
            .map(|p| [p[0], p[1], p[2], p[3]])
    }

    /// Number of pixels that differ from `background` (inclusive of alpha).
    pub fn count_non_background(&self, background: [u8; 4]) -> usize {
        self.rgba8
            .chunks_exact(4)
            .filter(|p| p[0] != background[0] || p[1] != background[1]
                || p[2] != background[2] || p[3] != background[3])
            .count()
    }
}

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum RenderError {
    /// The backend cannot run here (missing driver, library, extension).
    BackendUnavailable(&'static str),
    /// A handle or argument was not valid for this renderer.
    InvalidMesh,
    DeviceLost,
    OutOfMemory,
    /// The backend is present but the requested feature is not wired up yet.
    Unsupported(&'static str),
    /// The native API returned an error; carries a short static description.
    Api(&'static str),
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

    /// Finish the frame and (for offscreen targets) make it readable.
    fn end_frame(&mut self) -> Result<(), RenderError>;

    /// Read back the last completed frame. Backends that cannot read back
    /// return [`RenderError::Unsupported`]; the default keeps CPU-only
    /// renderers out of the contract.
    fn read_pixels(&mut self) -> Result<FramePixels, RenderError> {
        Err(RenderError::Unsupported("pixel readback"))
    }
}

pub mod egl;
pub mod glcore;
pub mod gles;
pub mod gl;
pub mod headless;
pub mod vulkan;

/// Try every backend in priority order and return the first that initialises a
/// real offscreen device/context. This is the honest "what can actually run
/// here" probe used by diagnostics; it never fabricates a backend.
pub fn create_best_headless() -> Result<Box<dyn Renderer>, RenderError> {
    let mut last = RenderError::BackendUnavailable("no native backend available");
    if let Ok(r) = vulkan::VulkanRenderer::create_headless() {
        return Ok(Box::new(r));
    }
    if let Ok(r) = gl::GlRenderer::create_headless() {
        return Ok(Box::new(r));
    }
    if let Ok(r) = gles::GlesRenderer::create_headless() {
        return Ok(Box::new(r));
    }
    if let Err(e) = vulkan::probe() {
        last = e;
    }
    Err(last)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Probes must either return real capabilities or a clean
    /// `BackendUnavailable`. They must never panic and must never claim success
    /// without an initialised device.
    #[test]
    fn backend_probes_never_panic() {
        let probes: [(&str, fn() -> Result<Capabilities, RenderError>); 3] = [
            ("vulkan", vulkan::probe),
            ("gl", gl::probe),
            ("gles", gles::probe),
        ];
        for (name, probe) in probes {
            match probe() {
                Ok(caps) => {
                    assert_eq!(caps.backend.name(), name);
                    assert!(caps.api_version.0 >= 1, "{} api version", name);
                }
                Err(RenderError::BackendUnavailable(_)) => {}
                Err(other) => panic!("{name} probe returned unexpected error {other:?}"),
            }
        }
    }

    #[test]
    fn frame_pixels_pixel_and_count() {
        let px = FramePixels {
            width: 2,
            height: 2,
            rgba8: vec![
                0, 0, 0, 255, // (0,0) background
                1, 2, 3, 255, // (1,0) differs
                0, 0, 0, 255, // (0,1) background
                9, 9, 9, 255, // (1,1) differs
            ],
        };
        assert_eq!(px.pixel(0, 0), Some([0, 0, 0, 255]));
        assert_eq!(px.pixel(1, 0), Some([1, 2, 3, 255]));
        assert_eq!(px.pixel(2, 0), None);
        assert_eq!(px.count_non_background([0, 0, 0, 255]), 2);
    }
}
