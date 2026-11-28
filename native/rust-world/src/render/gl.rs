//! OpenGL (desktop, core-profile) native backend.
//!
//! This backend drives a real GL context through EGL and the `libGL` entry
//! points, and does its own VAO/VBO/IBO setup, shader compilation, FBO rendering
//! and pixel readback. The shared GL/GLES work lives in [`super::glcore`]; this
//! module selects the desktop profile (GLSL 330, `libGL.so.1`) and exposes the
//! distinct [`GlRenderer`] type.
//!
//! In production the context is Minecraft's already-current one; the headless
//! constructor here exists so the native path can be proven on a driver without
//! a window.

use super::glcore::{GlCore, Profile};
use super::{
    Backend, Capabilities, DrawBatch, FramePixels, MeshHandle, PresentStatus, PresentSupport,
    RenderError, Renderer, SurfaceDesc,
};
use crate::mesh::MeshData;

pub const BACKEND: Backend = Backend::Gl;

/// Probe for a usable desktop GL context without keeping it alive.
pub fn probe() -> Result<Capabilities, RenderError> {
    GlCore::probe(Profile::Core)
}

/// A desktop OpenGL renderer.
pub struct GlRenderer {
    core: GlCore,
}

impl GlRenderer {
    /// Create a headless renderer using a surfaceless EGL context.
    pub fn create_headless() -> Result<Self, RenderError> {
        Ok(Self {
            core: GlCore::create(Profile::Core)?,
        })
    }

    /// Wrap the GL context already current on this thread (the game's context)
    /// and present into its default framebuffer at `width` x `height`.
    ///
    /// Must be called on the thread that owns the GL context.
    pub fn create_external(width: u32, height: u32) -> Result<Self, RenderError> {
        Ok(Self {
            core: GlCore::create_external(Profile::Core, width, height)?,
        })
    }

    /// Driver-reported renderer string (for diagnostics/tests).
    pub fn renderer_name(&self) -> &str {
        self.core.renderer_name()
    }

    /// Full `GL_VERSION` string.
    pub fn gl_version_string(&self) -> String {
        self.core.gl_version_string()
    }
}

impl Renderer for GlRenderer {
    fn backend(&self) -> Backend {
        BACKEND
    }
    fn capabilities(&self) -> Capabilities {
        self.core.capabilities()
    }
    fn begin_frame(&mut self, extent: [u32; 2]) -> Result<(), RenderError> {
        self.core.begin_frame_core(extent)
    }
    fn create_mesh(&mut self, mesh: &MeshData) -> Result<MeshHandle, RenderError> {
        self.core.create_mesh_core(mesh)
    }
    fn destroy_mesh(&mut self, handle: MeshHandle) {
        self.core.destroy_mesh_core(handle)
    }
    fn draw(&mut self, batch: DrawBatch) -> Result<(), RenderError> {
        self.core.draw_core(batch)
    }
    fn set_view_proj(&mut self, view_proj: super::Mat4) -> Result<(), RenderError> {
        self.core.set_view_proj_core(view_proj)
    }
    fn end_frame(&mut self) -> Result<(), RenderError> {
        self.core.end_frame_core()
    }
    fn read_pixels(&mut self) -> Result<FramePixels, RenderError> {
        self.core.read_pixels_core()
    }
    fn present_support(&self) -> PresentSupport {
        self.core.present_support_core()
    }
    fn attach_surface(&mut self, desc: SurfaceDesc) -> Result<(), RenderError> {
        self.core.attach_surface_core(desc)
    }
    fn resize_surface(&mut self, extent: [u32; 2]) -> Result<(), RenderError> {
        self.core.resize_surface_core(extent)
    }
    fn present(&mut self) -> Result<PresentStatus, RenderError> {
        self.core.present_core()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mesh::{build_section_mesh, ChunkSection};
    use crate::render::test_support::{quantized_colour_count, sample_camera};

    /// CI sets `JPE_REQUIRE_RENDER_BACKENDS=1` so a missing driver is a failure
    /// instead of a silent skip; that proves the backend actually ran.
    fn require_or_skip(msg: &'static str) {
        if std::env::var_os("JPE_REQUIRE_RENDER_BACKENDS").is_some() {
            panic!("render backend required but unavailable: {msg}");
        }
    }

    fn one_block_mesh() -> MeshData {
        let mut section = ChunkSection::new();
        section.set(0, 0, 0, 1);
        build_section_mesh(&section, [0, 0, 0])
    }

    #[test]
    fn probe_or_skip() {
        match probe() {
            Ok(caps) => {
                assert_eq!(caps.backend, Backend::Gl);
                assert!(caps.api_version.0 >= 3);
                assert!(caps.max_texture_size >= 256);
                eprintln!(
                    "gl probe ok: api={:?} maxTex={} compute={}",
                    caps.api_version, caps.max_texture_size, caps.supports_compute
                );
            }
            Err(RenderError::BackendUnavailable(msg)) => {
                require_or_skip(msg);
                eprintln!("gl unavailable here, skipping: {msg}");
            }
            Err(e) => panic!("unexpected probe error: {e:?}"),
        }
    }

    #[test]
    fn offscreen_draw_produces_pixels() {
        let mut renderer = match GlRenderer::create_headless() {
            Ok(r) => r,
            Err(RenderError::BackendUnavailable(msg)) => {
                require_or_skip(msg);
                eprintln!("gl unavailable here, skipping draw: {msg}");
                return;
            }
            Err(e) => panic!("create_headless failed: {e:?}"),
        };
        eprintln!("gl renderer: {} ({})", renderer.renderer_name(), renderer.gl_version_string());

        let mesh = one_block_mesh();
        let handle = renderer.create_mesh(&mesh).expect("create_mesh");
        // A camera outside the block: back faces are culled, and the default
        // identity projection puts the eye inside it.
        renderer.set_view_proj(sample_camera()).expect("set_view_proj");
        renderer.begin_frame([128, 128]).expect("begin_frame");
        renderer
            .draw(DrawBatch { mesh: handle, index_count: mesh.indices.len() as u32, instance_count: 1 })
            .expect("draw");
        renderer.end_frame().expect("end_frame");

        let pixels = renderer.read_pixels().expect("read_pixels");
        assert_eq!(pixels.width, 128);
        assert_eq!(pixels.height, 128);
        assert_eq!(pixels.rgba8.len(), 128 * 128 * 4);

        let background = pixels.pixel(0, 0).expect("corner pixel");
        let non_bg = pixels.count_non_background(background);
        assert!(non_bg > 100, "expected geometry coverage, got {non_bg} non-background pixels");

        renderer.destroy_mesh(handle);
    }

    /// The camera and the atlas must both be observable in the output: an
    /// angled view shows three faces, and each face is shaded and textured
    /// differently, so a flat single-colour frame cannot pass.
    #[test]
    fn camera_and_atlas_produce_shaded_pixels() {
        let mut renderer = match GlRenderer::create_headless() {
            Ok(r) => r,
            Err(RenderError::BackendUnavailable(msg)) => {
                require_or_skip(msg);
                eprintln!("gl unavailable here, skipping shading draw: {msg}");
                return;
            }
            Err(e) => panic!("create_headless failed: {e:?}"),
        };

        let mesh = one_block_mesh();
        let handle = renderer.create_mesh(&mesh).expect("create_mesh");
        renderer.set_view_proj(sample_camera()).expect("set_view_proj");
        renderer.begin_frame([128, 128]).expect("begin_frame");
        renderer
            .draw(DrawBatch { mesh: handle, index_count: mesh.indices.len() as u32, instance_count: 1 })
            .expect("draw");
        renderer.end_frame().expect("end_frame");

        let pixels = renderer.read_pixels().expect("read_pixels");
        let background = pixels.pixel(0, 0).expect("corner pixel");
        let non_bg = pixels.count_non_background(background);
        assert!(non_bg > 100, "expected geometry coverage, got {non_bg}");
        let colours = quantized_colour_count(&pixels, background);
        assert!(colours >= 3, "expected shaded atlas tones, got {colours} distinct colours");
        eprintln!("gl shading: {non_bg} lit pixels, {colours} distinct colours");

        renderer.destroy_mesh(handle);
    }

    /// Wrapping the already-current context must reuse it without taking
    /// ownership: after the wrapped renderer drops, the owner's context is still
    /// current and its own offscreen path still works.
    #[test]
    fn wrap_current_context_does_not_own_it() {
        let mut owner = match GlRenderer::create_headless() {
            Ok(r) => r,
            Err(RenderError::BackendUnavailable(msg)) => {
                require_or_skip(msg);
                eprintln!("gl unavailable here, skipping wrap test: {msg}");
                return;
            }
            Err(e) => panic!("create_headless failed: {e:?}"),
        };

        {
            let mut wrapped = match GlRenderer::create_external(64, 64) {
                Ok(r) => r,
                Err(RenderError::BackendUnavailable(msg)) => {
                    require_or_skip(msg);
                    eprintln!("gl wrap unavailable here, skipping: {msg}");
                    return;
                }
                Err(e) => panic!("create_external failed: {e:?}"),
            };
            assert_eq!(wrapped.present_support(), PresentSupport::ExternalGl);

            // Drive a real frame into the shared default framebuffer and present.
            let mesh = one_block_mesh();
            let handle = wrapped.create_mesh(&mesh).expect("create_mesh");
            wrapped.begin_frame([64, 64]).expect("begin_frame");
            wrapped
                .draw(DrawBatch { mesh: handle, index_count: mesh.indices.len() as u32, instance_count: 1 })
                .expect("draw");
            wrapped.end_frame().expect("end_frame");
            assert_eq!(wrapped.present().expect("present"), PresentStatus::Presented);
            wrapped.resize_surface([80, 60]).expect("resize");
            wrapped.destroy_mesh(handle);
        } // wrapped dropped here: it must NOT destroy the borrowed context.

        // The owner still owns a live, current context and can render offscreen.
        let mesh = one_block_mesh();
        let handle = owner.create_mesh(&mesh).expect("owner create_mesh");
        owner.begin_frame([64, 64]).expect("owner begin_frame");
        owner
            .draw(DrawBatch { mesh: handle, index_count: mesh.indices.len() as u32, instance_count: 1 })
            .expect("owner draw");
        owner.end_frame().expect("owner end_frame");
        let pixels = owner.read_pixels().expect("owner read_pixels");
        assert_eq!(pixels.width, 64);
        assert_eq!(pixels.height, 64);
        owner.destroy_mesh(handle);
    }
}
