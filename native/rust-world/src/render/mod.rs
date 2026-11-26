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

use crate::mesh::{Aabb, Frustum, MeshData, Plane};

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

/// Column-major 4x4 matrix mapping world space into clip space.
///
/// The depth component is expected in the Vulkan convention `0..=1`; GL accepts
/// that range too, so a single matrix works for every backend.
pub type Mat4 = [f32; 16];

/// Identity matrix.
pub const MAT4_IDENTITY: Mat4 = [
    1.0, 0.0, 0.0, 0.0, //
    0.0, 1.0, 0.0, 0.0, //
    0.0, 0.0, 1.0, 0.0, //
    0.0, 0.0, 0.0, 1.0,
];

/// Multiply two column-major matrices (`a * b`).
pub fn mat4_mul(a: &Mat4, b: &Mat4) -> Mat4 {
    let mut out = [0.0f32; 16];
    for col in 0..4 {
        for row in 0..4 {
            let mut sum = 0.0;
            for k in 0..4 {
                sum += a[k * 4 + row] * b[col * 4 + k];
            }
            out[col * 4 + row] = sum;
        }
    }
    out
}

/// Negate the clip-space Y row of a column-major matrix.
///
/// Camera matrices here are world/GL oriented (clip +y is up, `y = 0` is the
/// bottom of the framebuffer). Vulkan's framebuffer origin is top-left, so
/// without this its images come out vertically mirrored relative to GL and
/// OpenGL ES. Flipping once when the camera is set keeps a single shared camera
/// convention and makes the Vulkan framebuffer correct for presentation.
///
/// Note this reverses triangle winding, so Vulkan front-face/cull state has to
/// account for it once back-face culling is enabled.
pub fn flip_clip_y(m: &Mat4) -> Mat4 {
    let mut out = *m;
    for row in [1usize, 5, 9, 13] {
        out[row] = -out[row];
    }
    out
}

/// Build a column-major orthographic view-projection matrix looking from `eye`
/// at `target`, with the given half extents and depth range.
///
/// Returns `None` for a degenerate camera (eye == target, or `up` parallel to
/// the view direction).
pub fn ortho_look_at(
    eye: [f32; 3],
    target: [f32; 3],
    up: [f32; 3],
    half_width: f32,
    half_height: f32,
    near: f32,
    far: f32,
) -> Option<Mat4> {
    let sub = |a: [f32; 3], b: [f32; 3]| [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
    let dot = |a: [f32; 3], b: [f32; 3]| a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    let cross = |a: [f32; 3], b: [f32; 3]| {
        [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ]
    };
    let normalize = |v: [f32; 3]| {
        let len = dot(v, v).sqrt();
        if len <= f32::EPSILON {
            None
        } else {
            Some([v[0] / len, v[1] / len, v[2] / len])
        }
    };

    let f = normalize(sub(target, eye))?;
    let s = normalize(cross(f, up))?;
    let u = cross(s, f);
    if half_width <= 0.0 || half_height <= 0.0 || far <= near {
        return None;
    }

    // View matrix, column-major.
    let view: Mat4 = [
        s[0], u[0], -f[0], 0.0, //
        s[1], u[1], -f[1], 0.0, //
        s[2], u[2], -f[2], 0.0, //
        -dot(s, eye), -dot(u, eye), dot(f, eye), 1.0,
    ];
    // Orthographic projection with a 0..1 depth range.
    let inv_depth = 1.0 / (far - near);
    let proj: Mat4 = [
        1.0 / half_width, 0.0, 0.0, 0.0, //
        0.0, 1.0 / half_height, 0.0, 0.0, //
        0.0, 0.0, -inv_depth, 0.0, //
        0.0, 0.0, -near * inv_depth, 1.0,
    ];
    Some(mat4_mul(&proj, &view))
}

/// Extract the six inward-facing frustum planes from a view-projection matrix.
///
/// Gribb-Hartmann: the planes come from the rows of the combined matrix, so the
/// caller only has to keep the camera it already gave the renderer. Order is
/// `left, right, bottom, top, near, far`, and `dot(normal, p) + d >= 0` is
/// inside — exactly what [`Frustum::intersects`] expects.
///
/// The near plane uses row 2 alone because the shared projection maps depth to
/// `0..=1` rather than `-1..=1`. A degenerate row yields a plane that rejects
/// everything instead of silently accepting the world.
///
/// The Vulkan clip-Y flip does not change culling results: negating the Y row
/// mirrors the top and bottom planes into each other, so the plane set is the
/// same and one camera matrix serves visibility on every backend.
pub fn frustum_from_view_proj(m: &Mat4) -> Frustum {
    let row = |r: usize| [m[r], m[4 + r], m[8 + r], m[12 + r]];
    let (r0, r1, r2, r3) = (row(0), row(1), row(2), row(3));
    let add = |a: &[f32; 4], b: &[f32; 4]| [a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]];
    let sub = |a: &[f32; 4], b: &[f32; 4]| [a[0] - b[0], a[1] - b[1], a[2] - b[2], a[3] - b[3]];
    let plane = |p: [f32; 4]| -> Plane {
        let len = (p[0] * p[0] + p[1] * p[1] + p[2] * p[2]).sqrt();
        if len <= f32::EPSILON {
            // No usable half-space: treat everything as outside.
            return Plane { normal: [0.0, 0.0, 0.0], d: f32::MIN };
        }
        Plane { normal: [p[0] / len, p[1] / len, p[2] / len], d: p[3] / len }
    };
    Frustum::new([
        plane(add(&r3, &r0)),
        plane(sub(&r3, &r0)),
        plane(add(&r3, &r1)),
        plane(sub(&r3, &r1)),
        plane(r2),
        plane(sub(&r3, &r2)),
    ])
}

/// Convenience: is the section at `origin` (16x16x16) possibly visible?
pub fn section_is_visible(frustum: &Frustum, origin: [i32; 3]) -> bool {
    frustum.intersects(&Aabb::section(origin))
}

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

    /// Set the view-projection matrix used by subsequent frames.
    ///
    /// Column-major; see [`Mat4`]. Backends that cannot take a camera reject
    /// this with [`RenderError::Unsupported`] rather than silently ignoring it.
    fn set_view_proj(&mut self, _view_proj: Mat4) -> Result<(), RenderError> {
        Err(RenderError::Unsupported("camera"))
    }

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
pub mod plan;
pub mod registry;
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

/// Helpers shared by the backend test modules.
#[cfg(test)]
pub(crate) mod test_support {
    use super::{ortho_look_at, FramePixels, Mat4};

    /// Camera used by the shading tests: a three-quarter orthographic view of
    /// the unit cube, so three faces with different normals are visible.
    pub fn sample_camera() -> Mat4 {
        ortho_look_at(
            [2.4, 2.0, 2.8],
            [0.5, 0.5, 0.5],
            [0.0, 1.0, 0.0],
            0.9,
            0.9,
            0.1,
            20.0,
        )
        .expect("sample camera is non-degenerate")
    }

    /// Number of distinct colours in `px`, quantised to a 16-level cube so
    /// minor interpolation differences do not count as new colours.
    pub fn quantized_colour_count(px: &FramePixels, background: [u8; 4]) -> usize {
        let mut seen = std::collections::BTreeSet::new();
        for p in px.rgba8.chunks_exact(4) {
            if p[0] == background[0] && p[1] == background[1] && p[2] == background[2] {
                continue;
            }
            seen.insert((p[0] / 16, p[1] / 16, p[2] / 16));
        }
        seen.len()
    }
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

    #[test]
    fn identity_leaves_points_unchanged() {
        let m = MAT4_IDENTITY;
        assert_eq!(mat4_mul(&m, &m), m);
    }

    #[test]
    fn ortho_camera_maps_target_to_screen_centre() {
        // The look-at target must land at NDC (0, 0) with a depth inside 0..1.
        let eye = [3.0, 3.0, 3.0];
        let target = [0.0, 0.0, 0.0];
        let m = ortho_look_at(eye, target, [0.0, 1.0, 0.0], 2.0, 2.0, 1.0, 10.0)
            .expect("non-degenerate camera");
        let x = m[0 * 4 + 0] * target[0] + m[1 * 4 + 0] * target[1] + m[2 * 4 + 0] * target[2]
            + m[3 * 4 + 0];
        let y = m[0 * 4 + 1] * target[0] + m[1 * 4 + 1] * target[1] + m[2 * 4 + 1] * target[2]
            + m[3 * 4 + 1];
        let z = m[0 * 4 + 2] * target[0] + m[1 * 4 + 2] * target[1] + m[2 * 4 + 2] * target[2]
            + m[3 * 4 + 2];
        assert!(x.abs() < 1e-4, "target x should be centred, got {x}");
        assert!(y.abs() < 1e-4, "target y should be centred, got {y}");
        // The target sits at view depth -|eye|, which must fall in 0..=1.
        assert!(z > 0.0 && z < 1.0, "target depth should be inside the range, got {z}");
    }

    /// The Vulkan compensation must only touch the clip Y row, and must be its
    /// own inverse, so a matrix can be flipped back and forth safely.
    #[test]
    fn flip_clip_y_negates_only_the_y_row() {
        let m = ortho_look_at([2.0, 3.0, 4.0], [0.0, 0.0, 0.0], [0.0, 1.0, 0.0], 2.0, 2.0, 0.1, 50.0)
            .expect("non-degenerate camera");
        let flipped = flip_clip_y(&m);
        for (i, (a, b)) in m.iter().zip(flipped.iter()).enumerate() {
            if matches!(i, 1 | 5 | 9 | 13) {
                assert_eq!(*b, -*a, "row index {i} should be negated");
            } else {
                assert_eq!(*b, *a, "index {i} should be untouched");
            }
        }
        assert_eq!(flip_clip_y(&flipped), m);
    }

    #[test]
    fn degenerate_cameras_are_rejected() {
        assert!(ortho_look_at([1.0, 1.0, 1.0], [1.0, 1.0, 1.0], [0.0, 1.0, 0.0], 1.0, 1.0, 0.1, 10.0)
            .is_none());
        assert!(ortho_look_at([0.0, 1.0, 0.0], [0.0, 0.0, 0.0], [0.0, 1.0, 0.0], 1.0, 1.0, 0.1, 10.0)
            .is_none());
        assert!(ortho_look_at([0.0, 0.0, 1.0], [0.0, 0.0, 0.0], [0.0, 1.0, 0.0], 0.0, 1.0, 0.1, 10.0)
            .is_none());
        assert!(ortho_look_at([0.0, 0.0, 1.0], [0.0, 0.0, 0.0], [0.0, 1.0, 0.0], 1.0, 1.0, 10.0, 1.0)
            .is_none());
    }

    /// The three backends must agree on more than "some pixels changed": with the
    /// same mesh, camera and frame size they have to produce the same image, and
    /// that image has to be right way up.
    ///
    /// A block is placed near the top of the section, so with a world-up camera it
    /// must land in the upper half of a top-left-origin frame. Vulkan's framebuffer
    /// origin differs from GL's, which is exactly what [`flip_clip_y`] compensates
    /// for; without it Vulkan reads back vertically mirrored and disagrees with GL.
    #[test]
    fn backends_agree_on_pixels_and_orientation() {
        use crate::mesh::{build_section_mesh, ChunkSection};

        fn render(r: &mut dyn Renderer) -> FramePixels {
            let mut section = ChunkSection::new();
            section.set(8, 15, 8, 1);
            let mesh = build_section_mesh(&section, [0, 0, 0]);
            let handle = r.create_mesh(&mesh).expect("create_mesh");
            let camera =
                ortho_look_at([8.5, 8.0, 20.0], [8.5, 8.0, 0.0], [0.0, 1.0, 0.0], 9.0, 9.0, 0.1, 100.0)
                    .expect("non-degenerate camera");
            r.set_view_proj(camera).expect("set_view_proj");
            r.begin_frame([128, 128]).expect("begin_frame");
            r.draw(DrawBatch {
                mesh: handle,
                index_count: mesh.indices.len() as u32,
                instance_count: 1,
            })
            .expect("draw");
            r.end_frame().expect("end_frame");
            let pixels = r.read_pixels().expect("read_pixels");
            r.destroy_mesh(handle);
            pixels
        }

        /// Mean row of the non-background pixels.
        fn lit_centre_row(px: &FramePixels) -> f32 {
            let background = px.pixel(0, 0).expect("corner");
            let mut sum = 0.0f32;
            let mut count = 0.0f32;
            for y in 0..px.height {
                for x in 0..px.width {
                    if px.pixel(x, y).expect("pixel") != background {
                        sum += y as f32;
                        count += 1.0;
                    }
                }
            }
            assert!(count > 30.0, "expected the block to cover pixels, got {count}");
            sum / count
        }

        fn differing_pixels(a: &FramePixels, b: &FramePixels) -> usize {
            let mut diff = 0usize;
            for y in 0..a.height {
                for x in 0..a.width {
                    if a.pixel(x, y) != b.pixel(x, y) {
                        diff += 1;
                    }
                }
            }
            diff
        }

        let mut frames: Vec<(&str, FramePixels)> = Vec::new();
        let mut missing: Vec<&str> = Vec::new();
        if let Ok(mut r) = vulkan::VulkanRenderer::create_headless() {
            frames.push(("vulkan", render(&mut r)));
        } else {
            missing.push("vulkan");
        }
        if let Ok(mut r) = gl::GlRenderer::create_headless() {
            frames.push(("gl", render(&mut r)));
        } else {
            missing.push("gl");
        }
        if let Ok(mut r) = gles::GlesRenderer::create_headless() {
            frames.push(("gles", render(&mut r)));
        } else {
            missing.push("gles");
        }
        if std::env::var_os("JPE_REQUIRE_RENDER_BACKENDS").is_some() {
            assert!(missing.is_empty(), "required backends unavailable: {missing:?}");
        }
        if frames.len() < 2 {
            eprintln!("fewer than two native backends here, skipping pixel comparison");
            return;
        }

        for (name, pixels) in &frames {
            let row = lit_centre_row(pixels);
            assert!(
                row < (pixels.height as f32) / 2.0,
                "{name}: block at y=15 should sit in the top half, centre row {row}"
            );
            eprintln!("{name}: lit centre row {row} of {}", pixels.height);
        }
        for i in 0..frames.len() {
            for j in (i + 1)..frames.len() {
                let diff = differing_pixels(&frames[i].1, &frames[j].1);
                assert_eq!(
                    diff, 0,
                    "{} and {} disagree on {diff} of {} pixels",
                    frames[i].0,
                    frames[j].0,
                    u64::from(frames[i].1.width) * u64::from(frames[i].1.height)
                );
            }
        }
    }
}
