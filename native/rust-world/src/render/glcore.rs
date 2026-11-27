//! Shared implementation of the OpenGL and OpenGL ES native backends.
//!
//! Desktop GL and GLES are the same API with different version/profile
//! constraints, so the entry-point table, VAO/VBO/IBO setup, shader program,
//! framebuffer readback and draw submission are written once here. Each
//! backend module ([`super::gl`], [`super::gles`]) picks a [`Profile`] and wraps
//! this core behind its own distinct renderer type, so the two backends remain
//! separately identifiable and each makes native calls through the profile it
//! selected (EGL OpenGL vs EGL OpenGL ES, `libGL` vs `libGLESv2`, GLSL 330 vs
//! GLSL ES 300).
//!
//! No crate is used: entry points are resolved from the GL library with
//! `dlsym`, falling back to `eglGetProcAddress`, and are re-interpreted as the
//! documented C signatures.
//!
//! Headless rendering goes through [`super::egl::EglContext`] with a surfaceless
//! display, and the frame is captured from an RGBA8 renderbuffer, flipped to a
//! top-left origin so it matches the Vulkan backend and [`FramePixels`].

#![allow(non_snake_case)]

use super::egl::{dlopen_first, EglContext, EGL_OPENGL_API, EGL_OPENGL_ES_API};
use super::{
    Backend, Capabilities, DrawBatch, FramePixels, Mat4, MeshHandle, PresentStatus,
    PresentSupport, RenderError, SurfaceDesc, MAT4_IDENTITY,
};
use crate::mesh::{MeshData, Vertex};
use std::ffi::{c_char, c_void, CStr};
use std::mem::transmute;

extern "C" {
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

// --- GL tokens ------------------------------------------------------------
const GL_RENDERER: u32 = 0x1F01;
const GL_VERSION: u32 = 0x1F02;
const GL_MAX_TEXTURE_SIZE: u32 = 0x0D33;

const GL_VERTEX_SHADER: u32 = 0x8B31;
const GL_FRAGMENT_SHADER: u32 = 0x8B30;
const GL_COMPILE_STATUS: u32 = 0x8B81;
const GL_LINK_STATUS: u32 = 0x8B82;

const GL_ARRAY_BUFFER: u32 = 0x8892;
const GL_ELEMENT_ARRAY_BUFFER: u32 = 0x8893;
const GL_STATIC_DRAW: u32 = 0x88E4;
const GL_FLOAT: u32 = 0x1406;
const GL_FALSE: u8 = 0;

const GL_TRIANGLES: u32 = 0x0004;
const GL_UNSIGNED_INT: u32 = 0x1405;

const GL_DEPTH_TEST: u32 = 0x0B71;
const GL_CULL_FACE: u32 = 0x0B44;
const GL_LESS: u32 = 0x0201;
const GL_COLOR_BUFFER_BIT: u32 = 0x0000_4000;
const GL_DEPTH_BUFFER_BIT: u32 = 0x0000_0100;

const GL_FRAMEBUFFER: u32 = 0x8D40;
const GL_RENDERBUFFER: u32 = 0x8D41;
const GL_COLOR_ATTACHMENT0: u32 = 0x8CE0;
const GL_DEPTH_ATTACHMENT: u32 = 0x8D00;
const GL_FRAMEBUFFER_COMPLETE: u32 = 0x8CD5;
const GL_RGBA8: u32 = 0x8058;
const GL_DEPTH_COMPONENT24: u32 = 0x81A6;

const GL_RGBA: u32 = 0x1908;
const GL_UNSIGNED_BYTE: u32 = 0x1401;
const GL_PACK_ALIGNMENT: u32 = 0x0D05;
const GL_UNPACK_ALIGNMENT: u32 = 0x0D05;

const GL_TEXTURE_2D: u32 = 0x0DE1;
const GL_TEXTURE0: u32 = 0x84C0;
const GL_TEXTURE_MIN_FILTER: u32 = 0x2801;
const GL_TEXTURE_MAG_FILTER: u32 = 0x2800;
const GL_TEXTURE_WRAP_S: u32 = 0x2802;
const GL_TEXTURE_WRAP_T: u32 = 0x2803;
const GL_NEAREST: u32 = 0x2600;
const GL_REPEAT: u32 = 0x2901;

// --- function pointer table ----------------------------------------------
macro_rules! gl_fns {
    ($( $name:ident : fn($($arg:ty),* $(,)?) -> $ret:ty; )*) => {
        #[allow(non_snake_case)]
        struct GlFns { $($name: unsafe extern "C" fn($($arg),*) -> $ret,)* }
        impl GlFns {
            unsafe fn load(lib: *mut c_void, egl: &EglContext) -> Result<Self, RenderError> {
                // SAFETY: each name is looked up in the live GL library, with
                // `eglGetProcAddress` as fallback, and the resulting address is
                // re-interpreted as the documented C signature.
                unsafe {
                    Ok(Self {
                        $($name: {
                            let p = resolve_entry(lib, egl, concat!(stringify!($name), "\0").as_ptr() as *const c_char);
                            let Some(p) = p else {
                                return Err(RenderError::BackendUnavailable(
                                    concat!("gl: missing entry point ", stringify!($name))));
                            };
                            transmute::<*mut c_void, unsafe extern "C" fn($($arg),*) -> $ret>(p)
                        },)*
                    })
                }
            }
        }
    };
}

gl_fns! {
    glGetString: fn(u32) -> *const u8;
    glGetIntegerv: fn(u32, *mut i32) -> ();
    glGetError: fn() -> u32;
    glViewport: fn(i32, i32, i32, i32) -> ();
    glClearColor: fn(f32, f32, f32, f32) -> ();
    glClear: fn(u32) -> ();
    glEnable: fn(u32) -> ();
    glDisable: fn(u32) -> ();
    glDepthFunc: fn(u32) -> ();
    glFinish: fn() -> ();
    glFlush: fn() -> ();
    glPixelStorei: fn(u32, i32) -> ();
    glGenVertexArrays: fn(i32, *mut u32) -> ();
    glBindVertexArray: fn(u32) -> ();
    glDeleteVertexArrays: fn(i32, *const u32) -> ();
    glGenBuffers: fn(i32, *mut u32) -> ();
    glBindBuffer: fn(u32, u32) -> ();
    glBufferData: fn(u32, isize, *const c_void, u32) -> ();
    glDeleteBuffers: fn(i32, *const u32) -> ();
    glEnableVertexAttribArray: fn(u32) -> ();
    glVertexAttribPointer: fn(u32, i32, u32, u8, i32, *const c_void) -> ();
    glCreateShader: fn(u32) -> u32;
    glShaderSource: fn(u32, i32, *const *const c_char, *const i32) -> ();
    glCompileShader: fn(u32) -> ();
    glGetShaderiv: fn(u32, u32, *mut i32) -> ();
    glGetShaderInfoLog: fn(u32, i32, *mut i32, *mut c_char) -> ();
    glDeleteShader: fn(u32) -> ();
    glCreateProgram: fn() -> u32;
    glAttachShader: fn(u32, u32) -> ();
    glLinkProgram: fn(u32) -> ();
    glGetProgramiv: fn(u32, u32, *mut i32) -> ();
    glGetProgramInfoLog: fn(u32, i32, *mut i32, *mut c_char) -> ();
    glUseProgram: fn(u32) -> ();
    glDeleteProgram: fn(u32) -> ();
    glGetUniformLocation: fn(u32, *const c_char) -> i32;
    glUniform1i: fn(i32, i32) -> ();
    glUniformMatrix4fv: fn(i32, i32, u8, *const f32) -> ();
    glGenTextures: fn(i32, *mut u32) -> ();
    glDeleteTextures: fn(i32, *const u32) -> ();
    glBindTexture: fn(u32, u32) -> ();
    glActiveTexture: fn(u32) -> ();
    glTexParameteri: fn(u32, u32, i32) -> ();
    glTexImage2D: fn(u32, i32, i32, i32, i32, i32, u32, u32, *const c_void) -> ();
    glGenRenderbuffers: fn(i32, *mut u32) -> ();
    glBindRenderbuffer: fn(u32, u32) -> ();
    glRenderbufferStorage: fn(u32, u32, i32, i32) -> ();
    glDeleteRenderbuffers: fn(i32, *const u32) -> ();
    glGenFramebuffers: fn(i32, *mut u32) -> ();
    glBindFramebuffer: fn(u32, u32) -> ();
    glFramebufferRenderbuffer: fn(u32, u32, u32, u32) -> ();
    glCheckFramebufferStatus: fn(u32) -> u32;
    glDeleteFramebuffers: fn(i32, *const u32) -> ();
    glDrawElements: fn(u32, i32, u32, *const c_void) -> ();
    glDrawElementsInstanced: fn(u32, i32, u32, *const c_void, i32) -> ();
    glReadPixels: fn(i32, i32, i32, i32, u32, u32, *mut c_void) -> ();
}

unsafe fn resolve_entry(lib: *mut c_void, egl: &EglContext, name: *const c_char) -> Option<*mut c_void> {
    // SAFETY: `lib` is a live shared-library handle and `name` is a
    // NUL-terminated string literal.
    let direct = unsafe { dlsym(lib, name) };
    if !direct.is_null() {
        return Some(direct);
    }
    egl.proc_raw(name)
}

// --- profile --------------------------------------------------------------

/// Which GL flavour a [`GlCore`] drives. The choice fixes the EGL client API,
/// the shared library, the GLSL version and the reported backend.
#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum Profile {
    Core,
    Es,
}

impl Profile {
    pub fn backend(self) -> Backend {
        match self {
            Profile::Core => Backend::Gl,
            Profile::Es => Backend::Gles,
        }
    }

    fn egl_api(self) -> u32 {
        match self {
            Profile::Core => EGL_OPENGL_API,
            Profile::Es => EGL_OPENGL_ES_API,
        }
    }

    fn egl_version(self) -> (u32, u32) {
        match self {
            Profile::Core => (3, 3),
            Profile::Es => (3, 0),
        }
    }

    /// Candidate SONAMEs, most specific first. Distros ship both the versioned
    /// name (`libGL.so.1`, `libGLESv2.so.2`) and, when the `-dev` package is
    /// installed, the unversioned linker name.
    fn libraries(self) -> &'static [&'static CStr] {
        match self {
            Profile::Core => &[c"libGL.so.1", c"libGL.so"],
            Profile::Es => &[c"libGLESv2.so.2", c"libGLESv2.so"],
        }
    }

    fn label(self) -> &'static str {
        match self {
            Profile::Core => "gl",
            Profile::Es => "gles",
        }
    }

    fn vertex_source(self) -> &'static CStr {
        // Both profiles take the same attribute layout as the shared mesh
        // format: position (0), normal (1), uv (2).
        match self {
            Profile::Core => c"#version 330\n\
                layout(location = 0) in vec3 aPos;\n\
                layout(location = 1) in vec3 aNormal;\n\
                layout(location = 2) in vec2 aUv;\n\
                uniform mat4 uViewProj;\n\
                out vec3 vNormal;\n\
                out vec2 vUv;\n\
                void main() { vNormal = aNormal; vUv = aUv; \
                gl_Position = uViewProj * vec4(aPos, 1.0); }\n",
            Profile::Es => c"#version 300 es\n\
                layout(location = 0) in vec3 aPos;\n\
                layout(location = 1) in vec3 aNormal;\n\
                layout(location = 2) in vec2 aUv;\n\
                uniform mat4 uViewProj;\n\
                out vec3 vNormal;\n\
                out vec2 vUv;\n\
                void main() { vNormal = aNormal; vUv = aUv; \
                gl_Position = uViewProj * vec4(aPos, 1.0); }\n",
        }
    }

    fn fragment_source(self) -> &'static CStr {
        // One directional light term plus the chunk atlas: identical GLSL for
        // both profiles apart from the version and float precision.
        match self {
            Profile::Core => c"#version 330\n\
                in vec3 vNormal;\n\
                in vec2 vUv;\n\
                uniform sampler2D uAtlas;\n\
                out vec4 fragColor;\n\
                void main() { vec3 light = vec3(0.40824829, 0.81649658, 0.40824829); \
                float shade = 0.5 + 0.5 * max(dot(normalize(vNormal), light), 0.0); \
                fragColor = vec4(texture(uAtlas, vUv).rgb * shade, 1.0); }\n",
            Profile::Es => c"#version 300 es\n\
                precision mediump float;\n\
                in vec3 vNormal;\n\
                in vec2 vUv;\n\
                uniform sampler2D uAtlas;\n\
                out vec4 fragColor;\n\
                void main() { vec3 light = vec3(0.40824829, 0.81649658, 0.40824829); \
                float shade = 0.5 + 0.5 * max(dot(normalize(vNormal), light), 0.0); \
                fragColor = vec4(texture(uAtlas, vUv).rgb * shade, 1.0); }\n",
        }
    }

    /// Minimum reported version for compute capability.
    fn compute_min(self) -> (u32, u32) {
        match self {
            Profile::Core => (4, 3),
            Profile::Es => (3, 1),
        }
    }
}

// --- state ----------------------------------------------------------------

struct GlMesh {
    vao: u32,
    vbo: u32,
    ibo: u32,
    index_count: u32,
}

struct GlTarget {
    extent: [u32; 2],
    framebuffer: u32,
    color: u32,
    depth: u32,
}

/// The shared GL/GLES renderer core. Owns an EGL context and every GPU object.
pub struct GlCore {
    gl: GlFns,
    // `egl` is held only to keep the context current for this renderer's GPU
    // objects; `Drop::drop` releases those objects before any field drops.
    _lib: *mut c_void,
    _egl: EglContext,
    profile: Profile,
    capabilities: Capabilities,
    renderer_name: String,
    program: u32,
    /// Location of `uViewProj` in `program`, or -1 if the driver optimised it out.
    u_view_proj: i32,
    /// Location of `uAtlas` in `program`, or -1 if unused.
    u_atlas: i32,
    /// Built-in block atlas (see [`atlas_pixels`]).
    atlas: u32,
    /// Column-major view-projection applied to every frame.
    view_proj: Mat4,
    meshes: Vec<Option<GlMesh>>,
    next_handle: u64,
    target: Option<GlTarget>,
    /// When set, frames render into the default framebuffer (`0`) of the host's
    /// already-current context instead of an owned FBO. The value is the drawable
    /// pixel extent. This is the Minecraft-sharing path: the engine never owns a
    /// window; the context owner swaps.
    external: Option<[u32; 2]>,
    pending: Vec<DrawBatch>,
    last_frame: Option<FramePixels>,
}

/// Width of the built-in block atlas in texels.
pub const ATLAS_SIZE: i32 = 8;

/// Build the built-in block atlas: an 8x8 two-tone checker in RGBA8.
///
/// It is a real GPU texture sampled by the fragment shader through the mesh's
/// UVs; a per-block texture atlas loaded from the game's resource pack replaces
/// it once the Minecraft integration supplies one.
pub fn atlas_pixels() -> Vec<u8> {
    let mut px = vec![0u8; (ATLAS_SIZE * ATLAS_SIZE * 4) as usize];
    for y in 0..ATLAS_SIZE {
        for x in 0..ATLAS_SIZE {
            let tone = if (x + y) % 2 == 0 { 235 } else { 175 };
            let o = ((y * ATLAS_SIZE + x) * 4) as usize;
            px[o] = tone;
            px[o + 1] = tone;
            px[o + 2] = tone;
            px[o + 3] = 255;
        }
    }
    px
}

impl GlCore {
    /// Create a headless renderer for `profile`.
    pub fn create(profile: Profile) -> Result<Self, RenderError> {
        let (major, minor) = profile.egl_version();
        let egl = EglContext::create(profile.egl_api(), major, minor)?;
        // SAFETY: `libraries()` yields static, NUL-terminated names.
        let lib = unsafe { dlopen_first(profile.libraries()) };
        if lib.is_null() {
            return Err(RenderError::BackendUnavailable(match profile {
                Profile::Core => "gl: libGL not loadable",
                Profile::Es => "gles: libGLESv2 not loadable",
            }));
        }
        Self::init(profile, egl, lib)
    }

    /// Probe by building and dropping a renderer.
    pub fn probe(profile: Profile) -> Result<Capabilities, RenderError> {
        let core = Self::create(profile)?;
        Ok(core.capabilities)
    }

    fn init(profile: Profile, egl: EglContext, lib: *mut c_void) -> Result<Self, RenderError> {
        // SAFETY: all calls below go through the validated entry-point table,
        // with the EGL context made current for this thread, and only touch
        // objects created here.
        unsafe {
            let gl = GlFns::load(lib, &egl)?;

            let version_text = gl_string(&gl, GL_VERSION).ok_or(RenderError::BackendUnavailable(
                concat!("gl: glGetString(GL_VERSION) failed"),
            ))?;
            let renderer_name = gl_string(&gl, GL_RENDERER).unwrap_or_else(|| "unknown".to_string());
            let (api_major, api_minor) = parse_version(&version_text);
            let (want_major, want_minor) = profile.egl_version();
            if (api_major, api_minor) < (want_major, want_minor) {
                return Err(RenderError::BackendUnavailable(match profile {
                    Profile::Core => "gl: context below OpenGL 3.3",
                    Profile::Es => "gles: context below OpenGL ES 3.0",
                }));
            }

            let mut max_tex = 0i32;
            (gl.glGetIntegerv)(GL_MAX_TEXTURE_SIZE, &mut max_tex);

            let capabilities = Capabilities {
                backend: profile.backend(),
                api_version: (api_major, api_minor),
                max_texture_size: max_tex.max(0) as u32,
                supports_compute: (api_major, api_minor) >= profile.compute_min(),
            };

            let program = build_program(&gl, profile)?;
            let name = c"uViewProj".as_ptr();
            let u_view_proj = (gl.glGetUniformLocation)(program, name);
            let name = c"uAtlas".as_ptr();
            let u_atlas = (gl.glGetUniformLocation)(program, name);
            if u_view_proj < 0 || u_atlas < 0 {
                (gl.glDeleteProgram)(program);
                return Err(RenderError::Api("gl: shader uniform missing"));
            }
            let atlas = create_atlas(&gl)?;

            Ok(GlCore {
                gl,
                _lib: lib,
                _egl: egl,
                profile,
                capabilities,
                renderer_name,
                program,
                u_view_proj,
                u_atlas,
                atlas,
                view_proj: MAT4_IDENTITY,
                meshes: Vec::new(),
                next_handle: 1,
                target: None,
                external: None,
                pending: Vec::new(),
                last_frame: None,
            })
        }
    }

    pub fn renderer_name(&self) -> &str {
        &self.renderer_name
    }
    pub fn capabilities(&self) -> Capabilities {
        self.capabilities
    }
    pub fn backend(&self) -> Backend {
        self.profile.backend()
    }
    /// Set the column-major view-projection used by subsequent frames.
    pub fn set_view_proj_core(&mut self, view_proj: Mat4) -> Result<(), RenderError> {
        if view_proj.iter().any(|v| !v.is_finite()) {
            return Err(RenderError::InvalidMesh);
        }
        self.view_proj = view_proj;
        Ok(())
    }
    pub fn view_proj(&self) -> Mat4 {
        self.view_proj
    }
    pub fn gl_version_string(&self) -> String {
        // SAFETY: context is current and owned by this renderer.
        unsafe { gl_string(&self.gl, GL_VERSION).unwrap_or_default() }
    }

    fn ensure_target(&mut self, extent: [u32; 2]) -> Result<(), RenderError> {
        if let Some(t) = &self.target {
            if t.extent == extent {
                return Ok(());
            }
        }
        self.destroy_target();
        let gl = &self.gl;
        // SAFETY: standard GL framebuffer/renderbuffer creation; objects are
        // recorded in `GlTarget` and released in `destroy_target`.
        unsafe {
            let mut framebuffer = 0u32;
            (gl.glGenFramebuffers)(1, &mut framebuffer);
            if framebuffer == 0 {
                return Err(RenderError::Api("gl: glGenFramebuffers failed"));
            }
            (gl.glBindFramebuffer)(GL_FRAMEBUFFER, framebuffer);

            let mut color = 0u32;
            (gl.glGenRenderbuffers)(1, &mut color);
            (gl.glBindRenderbuffer)(GL_RENDERBUFFER, color);
            (gl.glRenderbufferStorage)(
                GL_RENDERBUFFER,
                GL_RGBA8,
                extent[0] as i32,
                extent[1] as i32,
            );
            (gl.glFramebufferRenderbuffer)(
                GL_FRAMEBUFFER,
                GL_COLOR_ATTACHMENT0,
                GL_RENDERBUFFER,
                color,
            );

            let mut depth = 0u32;
            (gl.glGenRenderbuffers)(1, &mut depth);
            (gl.glBindRenderbuffer)(GL_RENDERBUFFER, depth);
            (gl.glRenderbufferStorage)(
                GL_RENDERBUFFER,
                GL_DEPTH_COMPONENT24,
                extent[0] as i32,
                extent[1] as i32,
            );
            (gl.glFramebufferRenderbuffer)(
                GL_FRAMEBUFFER,
                GL_DEPTH_ATTACHMENT,
                GL_RENDERBUFFER,
                depth,
            );

            let status = (gl.glCheckFramebufferStatus)(GL_FRAMEBUFFER);
            if status != GL_FRAMEBUFFER_COMPLETE {
                (gl.glDeleteRenderbuffers)(1, &color);
                (gl.glDeleteRenderbuffers)(1, &depth);
                (gl.glDeleteFramebuffers)(1, &framebuffer);
                return Err(RenderError::Api("gl: framebuffer incomplete"));
            }

            self.target = Some(GlTarget {
                extent,
                framebuffer,
                color,
                depth,
            });
        }
        Ok(())
    }

    fn destroy_target(&mut self) {
        if let Some(t) = self.target.take() {
            // SAFETY: objects were created in `ensure_target` and are current.
            unsafe {
                (self.gl.glBindFramebuffer)(GL_FRAMEBUFFER, 0);
                (self.gl.glDeleteFramebuffers)(1, &t.framebuffer);
                (self.gl.glDeleteRenderbuffers)(1, &t.color);
                (self.gl.glDeleteRenderbuffers)(1, &t.depth);
            }
        }
    }

    pub fn begin_frame_core(&mut self, extent: [u32; 2]) -> Result<(), RenderError> {
        if extent[0] == 0 || extent[1] == 0 {
            return Err(RenderError::InvalidMesh);
        }
        if let Some(external) = self.external.as_mut() {
            // Host owns the drawable; record the extent and reset the queue. No
            // FBO is created, so end_frame draws into framebuffer 0.
            *external = extent;
            self.pending.clear();
            return Ok(());
        }
        self.ensure_target(extent)?;
        self.pending.clear();
        Ok(())
    }

    pub fn create_mesh_core(&mut self, mesh: &MeshData) -> Result<MeshHandle, RenderError> {
        if mesh.vertices.is_empty() || mesh.indices.is_empty() {
            return Err(RenderError::InvalidMesh);
        }
        let mut vertex_bytes = vec![0u8; mesh.vertices.len() * Vertex::STRIDE];
        for (i, v) in mesh.vertices.iter().enumerate() {
            v.write_le(&mut vertex_bytes[i * Vertex::STRIDE..(i + 1) * Vertex::STRIDE]);
        }
        let index_bytes = mesh.indices.len() * std::mem::size_of::<u32>();

        let gl = &self.gl;
        // SAFETY: creates VAO/buffers for this mesh and uploads its bytes.
        unsafe {
            let mut vao = 0u32;
            (gl.glGenVertexArrays)(1, &mut vao);
            if vao == 0 {
                return Err(RenderError::Api("gl: glGenVertexArrays failed"));
            }
            (gl.glBindVertexArray)(vao);

            let mut vbo = 0u32;
            (gl.glGenBuffers)(1, &mut vbo);
            (gl.glBindBuffer)(GL_ARRAY_BUFFER, vbo);
            (gl.glBufferData)(
                GL_ARRAY_BUFFER,
                vertex_bytes.len() as isize,
                vertex_bytes.as_ptr() as *const c_void,
                GL_STATIC_DRAW,
            );
            (gl.glEnableVertexAttribArray)(0);
            (gl.glVertexAttribPointer)(
                0,
                3,
                GL_FLOAT,
                GL_FALSE,
                Vertex::STRIDE as i32,
                std::ptr::null(),
            );
            // Normal (location 1) and uv (location 2) from the same 32-byte
            // vertex; offsets match `Vertex` in `crate::mesh`.
            (gl.glEnableVertexAttribArray)(1);
            (gl.glVertexAttribPointer)(
                1,
                3,
                GL_FLOAT,
                GL_FALSE,
                Vertex::STRIDE as i32,
                12usize as *const c_void,
            );
            (gl.glEnableVertexAttribArray)(2);
            (gl.glVertexAttribPointer)(
                2,
                2,
                GL_FLOAT,
                GL_FALSE,
                Vertex::STRIDE as i32,
                24usize as *const c_void,
            );

            let mut ibo = 0u32;
            (gl.glGenBuffers)(1, &mut ibo);
            (gl.glBindBuffer)(GL_ELEMENT_ARRAY_BUFFER, ibo);
            (gl.glBufferData)(
                GL_ELEMENT_ARRAY_BUFFER,
                index_bytes as isize,
                mesh.indices.as_ptr() as *const c_void,
                GL_STATIC_DRAW,
            );

            (gl.glBindVertexArray)(0);
            (gl.glBindBuffer)(GL_ARRAY_BUFFER, 0);

            let handle = MeshHandle(self.next_handle);
            self.next_handle += 1;
            self.meshes.push(Some(GlMesh {
                vao,
                vbo,
                ibo,
                index_count: mesh.indices.len() as u32,
            }));
            Ok(handle)
        }
    }

    pub fn destroy_mesh_core(&mut self, handle: MeshHandle) {
        let Some(idx) = handle.0.checked_sub(1).map(|v| v as usize) else {
            return;
        };
        if let Some(slot) = self.meshes.get_mut(idx) {
            if let Some(m) = slot.take() {
                // SAFETY: objects belong to this context and are not bound.
                unsafe {
                    (self.gl.glDeleteVertexArrays)(1, &m.vao);
                    (self.gl.glDeleteBuffers)(1, &m.vbo);
                    (self.gl.glDeleteBuffers)(1, &m.ibo);
                }
            }
        }
    }

    pub fn draw_core(&mut self, batch: DrawBatch) -> Result<(), RenderError> {
        if self.target.is_none() {
            return Err(RenderError::InvalidMesh);
        }
        let Some(idx) = batch.mesh.0.checked_sub(1).map(|v| v as usize) else {
            return Err(RenderError::InvalidMesh);
        };
        match self.meshes.get(idx) {
            Some(Some(_)) => {
                self.pending.push(batch);
                Ok(())
            }
            _ => Err(RenderError::InvalidMesh),
        }
    }

    pub fn end_frame_core(&mut self) -> Result<(), RenderError> {
        // External surface draws into the host's default framebuffer (0); the
        // offscreen path draws into this renderer's owned FBO.
        let (framebuffer, width, height) = if let Some(ext) = self.external {
            (0u32, ext[0], ext[1])
        } else {
            let Some(target) = self.target.as_ref() else {
                return Err(RenderError::InvalidMesh);
            };
            (target.framebuffer, target.extent[0], target.extent[1])
        };
        let gl = &self.gl;
        let view_proj = self.view_proj;

        // SAFETY: draws the validated pending batches into the current framebuffer
        // and (for the offscreen path) leaves its colour buffer readable.
        unsafe {
            (gl.glBindFramebuffer)(GL_FRAMEBUFFER, framebuffer);
            (gl.glViewport)(0, 0, width as i32, height as i32);
            (gl.glDisable)(GL_CULL_FACE);
            (gl.glEnable)(GL_DEPTH_TEST);
            (gl.glDepthFunc)(GL_LESS);
            (gl.glClearColor)(0.05, 0.05, 0.1, 1.0);
            (gl.glClear)(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            (gl.glUseProgram)(self.program);
            (gl.glUniformMatrix4fv)(self.u_view_proj, 1, GL_FALSE, view_proj.as_ptr());
            (gl.glActiveTexture)(GL_TEXTURE0);
            (gl.glBindTexture)(GL_TEXTURE_2D, self.atlas);
            (gl.glUniform1i)(self.u_atlas, 0);

            for batch in &self.pending {
                let idx = batch.mesh.0 as usize - 1;
                if let Some(Some(mesh)) = self.meshes.get(idx) {
                    (gl.glBindVertexArray)(mesh.vao);
                    let count = batch.index_count.min(mesh.index_count) as i32;
                    let instances = batch.instance_count.max(1) as i32;
                    if instances > 1 {
                        (gl.glDrawElementsInstanced)(
                            GL_TRIANGLES,
                            count,
                            GL_UNSIGNED_INT,
                            std::ptr::null(),
                            instances,
                        );
                    } else {
                        (gl.glDrawElements)(
                            GL_TRIANGLES,
                            count,
                            GL_UNSIGNED_INT,
                            std::ptr::null(),
                        );
                    }
                }
            }

            (gl.glBindVertexArray)(0);
            (gl.glUseProgram)(0);
            (gl.glFinish)();
            let err = (gl.glGetError)();
            if err != 0 {
                eprintln!("gl: error 0x{err:04x} after frame");
            }
        }
        Ok(())
    }

    /// Copy the last rendered offscreen FBO into host memory and return it.
    ///
    /// Opt-in readback: the steady `end_frame_core` path no longer reads the
    /// pixels, so the full-frame `glReadPixels` plus the row-flip allocation
    /// only run when a frame is actually read back via `read_pixels_core`
    /// (GameTest / pixel tests). The FBO still holds the previous frame's
    /// contents (nothing clears it until the next `begin_frame`), so no redraw
    /// is required.
    pub fn capture_core(&mut self) -> Result<FramePixels, RenderError> {
        // External surface: read the host's default framebuffer. Offscreen: read
        // this renderer's owned FBO (which still holds the previous frame).
        let (framebuffer, width, height) = if let Some(ext) = self.external {
            (0u32, ext[0], ext[1])
        } else {
            let Some(target) = self.target.as_ref() else {
                return Err(RenderError::Unsupported("no completed frame to read"));
            };
            (target.framebuffer, target.extent[0], target.extent[1])
        };
        let gl = &self.gl;

        let mut bottom_up = vec![0u8; (width as usize) * (height as usize) * 4];
        // SAFETY: the FBO is complete and RGBA8; `bottom_up` is sized to the
        // full frame with pack alignment 1.
        unsafe {
            (gl.glBindFramebuffer)(GL_FRAMEBUFFER, framebuffer);
            (gl.glPixelStorei)(GL_PACK_ALIGNMENT, 1);
            (gl.glReadPixels)(
                0,
                0,
                width as i32,
                height as i32,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                bottom_up.as_mut_ptr() as *mut c_void,
            );
        }

        // glReadPixels returns rows bottom-up; flip to a top-left origin to
        // match Vulkan and `FramePixels`.
        let row = (width as usize) * 4;
        let mut top_down = vec![0u8; bottom_up.len()];
        for y in 0..height as usize {
            let src = (height as usize - 1 - y) * row;
            top_down[y * row..(y + 1) * row].copy_from_slice(&bottom_up[src..src + row]);
        }
        let frame = FramePixels {
            width,
            height,
            rgba8: top_down,
        };
        self.last_frame = Some(frame.clone());
        Ok(frame)
    }

    pub fn read_pixels_core(&mut self) -> Result<FramePixels, RenderError> {
        self.capture_core()
    }

    // --- presentation (external GL context = the Minecraft-sharing path) -----

    /// GL/GLES can present only into a host-supplied current context.
    pub fn present_support_core(&self) -> PresentSupport {
        PresentSupport::ExternalGl
    }

    /// Draw subsequent frames into the default framebuffer of the context that is
    /// already current on this thread. This does not create a window, FBO or
    /// swapchain; the host that owns the context swaps the results.
    pub fn attach_surface_core(&mut self, desc: SurfaceDesc) -> Result<(), RenderError> {
        match desc {
            SurfaceDesc::ExternalGlCurrent { width, height } => {
                if width == 0 || height == 0 {
                    return Err(RenderError::InvalidMesh);
                }
                // Drop any offscreen target so begin/end take the framebuffer-0 path.
                self.destroy_target();
                self.external = Some([width, height]);
                Ok(())
            }
            SurfaceDesc::NativeWindow { .. } => Err(RenderError::Unsupported(
                "gl: native window surface; attach the host's current GL context instead",
            )),
        }
    }

    pub fn resize_surface_core(&mut self, extent: [u32; 2]) -> Result<(), RenderError> {
        if extent[0] == 0 || extent[1] == 0 {
            return Err(RenderError::InvalidMesh);
        }
        match self.external.as_mut() {
            Some(current) => {
                *current = extent;
                Ok(())
            }
            None => Err(RenderError::Unsupported("resize before attach_surface")),
        }
    }

    pub fn present_core(&mut self) -> Result<PresentStatus, RenderError> {
        if self.external.is_none() {
            return Err(RenderError::Unsupported("present before attach_surface"));
        }
        // SAFETY: this renderer's (or the host's external) GL context is current
        // on this thread. glFlush hands the finished frame to the driver; the
        // context owner performs the buffer swap.
        unsafe { (self.gl.glFlush)(); }
        Ok(PresentStatus::Presented)
    }
}

impl Drop for GlCore {
    fn drop(&mut self) {
        // SAFETY: context is still current; delete mesh objects, program and
        // frame target, then close the GL library. `egl` drops afterwards.
        unsafe {
            (self.gl.glBindVertexArray)(0);
            (self.gl.glUseProgram)(0);
            for slot in &mut self.meshes {
                if let Some(m) = slot.take() {
                    (self.gl.glDeleteVertexArrays)(1, &m.vao);
                    (self.gl.glDeleteBuffers)(1, &m.vbo);
                    (self.gl.glDeleteBuffers)(1, &m.ibo);
                }
            }
            self.destroy_target();
            if self.atlas != 0 {
                (self.gl.glBindTexture)(GL_TEXTURE_2D, 0);
                (self.gl.glDeleteTextures)(1, &self.atlas);
                self.atlas = 0;
            }
            if self.program != 0 {
                (self.gl.glDeleteProgram)(self.program);
            }
            // The GL loader handle is intentionally left open: closing it can
            // unload the vendor driver while the process still has Mesa/glvnd
            // state referencing it. One handle per renderer is harmless.
        }
    }
}

// --- helpers --------------------------------------------------------------

/// Upload the built-in block atlas as a `GL_TEXTURE_2D`.
unsafe fn create_atlas(gl: &GlFns) -> Result<u32, RenderError> {
    let pixels = atlas_pixels();
    // SAFETY: standard texture creation on the current context; the id is
    // returned to the caller, which deletes it in `GlCore::drop`.
    unsafe {
        let mut tex = 0u32;
        (gl.glGenTextures)(1, &mut tex);
        if tex == 0 {
            return Err(RenderError::Api("gl: glGenTextures failed"));
        }
        (gl.glActiveTexture)(GL_TEXTURE0);
        (gl.glBindTexture)(GL_TEXTURE_2D, tex);
        (gl.glPixelStorei)(GL_UNPACK_ALIGNMENT, 1);
        (gl.glTexParameteri)(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST as i32);
        (gl.glTexParameteri)(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST as i32);
        (gl.glTexParameteri)(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT as i32);
        (gl.glTexParameteri)(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT as i32);
        (gl.glTexImage2D)(
            GL_TEXTURE_2D,
            0,
            GL_RGBA8 as i32,
            ATLAS_SIZE,
            ATLAS_SIZE,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            pixels.as_ptr() as *const c_void,
        );
        let err = (gl.glGetError)();
        if err != 0 {
            (gl.glDeleteTextures)(1, &tex);
            return Err(RenderError::Api("gl: atlas upload failed"));
        }
        Ok(tex)
    }
}

unsafe fn gl_string(gl: &GlFns, name: u32) -> Option<String> {
    // SAFETY: `name` is a valid GL string enum and the pointer is driver-owned.
    let p = unsafe { (gl.glGetString)(name) };
    if p.is_null() {
        return None;
    }
    // SAFETY: GL returns a NUL-terminated string valid until the next call.
    Some(unsafe { CStr::from_ptr(p as *const c_char) }.to_string_lossy().into_owned())
}

fn parse_version(text: &str) -> (u32, u32) {
    let mut nums = text
        .split(|c: char| !c.is_ascii_digit())
        .filter(|s| !s.is_empty());
    let major = nums.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    let minor = nums.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    (major, minor)
}

unsafe fn build_program(gl: &GlFns, profile: Profile) -> Result<u32, RenderError> {
    // SAFETY: compiles and links the built-in shaders for this context.
    unsafe {
        let vs = compile_shader(gl, GL_VERTEX_SHADER, profile.vertex_source())?;
        let fs = match compile_shader(gl, GL_FRAGMENT_SHADER, profile.fragment_source()) {
            Ok(s) => s,
            Err(e) => {
                (gl.glDeleteShader)(vs);
                return Err(e);
            }
        };
        let program = (gl.glCreateProgram)();
        if program == 0 {
            (gl.glDeleteShader)(vs);
            (gl.glDeleteShader)(fs);
            return Err(RenderError::Api("gl: glCreateProgram failed"));
        }
        (gl.glAttachShader)(program, vs);
        (gl.glAttachShader)(program, fs);
        (gl.glLinkProgram)(program);
        let mut ok = 0i32;
        (gl.glGetProgramiv)(program, GL_LINK_STATUS, &mut ok);
        if ok == 0 {
            let log = program_log(gl, program);
            eprintln!("{} program link failed: {log}", profile.label());
            (gl.glDeleteProgram)(program);
            (gl.glDeleteShader)(vs);
            (gl.glDeleteShader)(fs);
            return Err(RenderError::Api("gl: program link failed"));
        }
        (gl.glDeleteShader)(vs);
        (gl.glDeleteShader)(fs);
        Ok(program)
    }
}

unsafe fn compile_shader(gl: &GlFns, kind: u32, src: &CStr) -> Result<u32, RenderError> {
    // SAFETY: creates and compiles one shader from the supplied source.
    unsafe {
        let shader = (gl.glCreateShader)(kind);
        if shader == 0 {
            return Err(RenderError::Api("gl: glCreateShader failed"));
        }
        let ptr = src.as_ptr();
        (gl.glShaderSource)(shader, 1, &ptr, std::ptr::null());
        (gl.glCompileShader)(shader);
        let mut ok = 0i32;
        (gl.glGetShaderiv)(shader, GL_COMPILE_STATUS, &mut ok);
        if ok == 0 {
            let log = shader_log(gl, shader);
            eprintln!("gl shader compile failed: {log}");
            (gl.glDeleteShader)(shader);
            return Err(RenderError::Api("gl: shader compile failed"));
        }
        Ok(shader)
    }
}

unsafe fn shader_log(gl: &GlFns, shader: u32) -> String {
    let mut buf = [0i8; 1024];
    let mut len = 0i32;
    // SAFETY: `buf` is writable for the declared length.
    unsafe {
        (gl.glGetShaderInfoLog)(shader, buf.len() as i32, &mut len, buf.as_mut_ptr() as *mut c_char);
        if len <= 0 {
            return String::new();
        }
        CStr::from_ptr(buf.as_ptr() as *const c_char)
            .to_string_lossy()
            .into_owned()
    }
}

unsafe fn program_log(gl: &GlFns, program: u32) -> String {
    let mut buf = [0i8; 1024];
    let mut len = 0i32;
    // SAFETY: `buf` is writable for the declared length.
    unsafe {
        (gl.glGetProgramInfoLog)(program, buf.len() as i32, &mut len, buf.as_mut_ptr() as *mut c_char);
        if len <= 0 {
            return String::new();
        }
        CStr::from_ptr(buf.as_ptr() as *const c_char)
            .to_string_lossy()
            .into_owned()
    }
}
