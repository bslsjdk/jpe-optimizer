//! Minimal EGL bindings used by the GL and GLES backends to create a headless
//! context. No third-party crate: `libEGL` is loaded with `dlopen`, entry points
//! are resolved directly, and a surfaceless display is requested so tests can
//! run without X/Wayland. In production the GL/GLES backends will adopt the
//! game's already-current context instead of calling this.
//!
//! This module is compiled but only exercised when a GL/GLES backend runs; on a
//! machine without Mesa's surfaceless platform it returns
//! [`RenderError::BackendUnavailable`].

#![allow(non_snake_case)]

use super::RenderError;
use std::ffi::{c_char, c_int, c_void};
use std::ptr::null_mut;

extern "C" {
    fn dlopen(filename: *const c_char, flag: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlclose(handle: *mut c_void) -> c_int;
}
const RTLD_NOW: c_int = 2;

// EGL tokens.
pub const EGL_OPENGL_API: u32 = 0x30A2;
pub const EGL_OPENGL_ES_API: u32 = 0x30A0;
const EGL_PLATFORM_SURFACELESS_MESA: u32 = 0x31DD;
const EGL_SURFACE_TYPE: i32 = 0x3033;
const EGL_PBUFFER_BIT: i32 = 0x0001;
const EGL_RENDERABLE_TYPE: i32 = 0x3040;
const EGL_DEPTH_SIZE: i32 = 0x3025;
const EGL_OPENGL_BIT: i32 = 0x0008;
const EGL_OPENGL_ES2_BIT: i32 = 0x0004;
const EGL_OPENGL_ES3_BIT: i32 = 0x0040;
const EGL_NONE: i32 = 0x3038;
const EGL_CONTEXT_MAJOR_VERSION: i32 = 0x3098;
const EGL_CONTEXT_MINOR_VERSION: i32 = 0x30FB;
const EGL_CONTEXT_CLIENT_VERSION: i32 = 0x3098;

type PfnGetProcAddress = unsafe extern "C" fn(*const c_char) -> *mut c_void;
type PfnGetPlatformDisplay = unsafe extern "C" fn(u32, *mut c_void, *const i32) -> *mut c_void;
type PfnGetDisplay = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type PfnInitialize = unsafe extern "C" fn(*mut c_void, *mut i32, *mut i32) -> u32;
type PfnChooseConfig = unsafe extern "C" fn(*mut c_void, *const i32, *mut *mut c_void, i32, *mut i32) -> u32;
type PfnBindApi = unsafe extern "C" fn(u32) -> u32;
type PfnCreateContext = unsafe extern "C" fn(*mut c_void, *mut c_void, *mut c_void, *const i32) -> *mut c_void;
type PfnMakeCurrent = unsafe extern "C" fn(*mut c_void, *mut c_void, *mut c_void, *mut c_void) -> u32;
type PfnDestroyContext = unsafe extern "C" fn(*mut c_void, *mut c_void) -> u32;
type PfnTerminate = unsafe extern "C" fn(*mut c_void) -> u32;
type PfnGetError = unsafe extern "C" fn() -> i32;
type PfnQueryString = unsafe extern "C" fn(*mut c_void, i32) -> *const c_char;

/// A headless EGL context owning its loader handle and display.
pub struct EglContext {
    // Kept open for the context's lifetime; see `Drop`.
    _lib: *mut c_void,
    display: *mut c_void,
    context: *mut c_void,
    make_current: PfnMakeCurrent,
    destroy_context: PfnDestroyContext,
    terminate: PfnTerminate,
    get_proc: PfnGetProcAddress,
    get_error: PfnGetError,
    version: u32,
    vendor: String,
}

// The context is bound to the thread that created it; callers must use it on
// that thread. Marking Send would be misleading, so we deliberately do not.
impl EglContext {
    /// Create a surfaceless context for `api` (EGL_OPENGL_API or
    /// EGL_OPENGL_ES_API) with the requested version.
    pub fn create(api: u32, major: u32, minor: u32) -> Result<Self, RenderError> {
        unsafe {
            // `libegl1` ships the SONAME; some images only provide the unversioned
            // dev symlink, so try both before declaring EGL unavailable.
            let lib = dlopen_first(&[c"libEGL.so.1", c"libEGL.so"]);
            if lib.is_null() {
                return Err(RenderError::BackendUnavailable("egl: libEGL.so.1 not loadable"));
            }
            macro_rules! required {
                ($sym:literal, $ty:ty, $what:literal) => {
                    match load_sym(lib, $sym) {
                        Some(p) => std::mem::transmute::<*mut c_void, $ty>(p),
                        None => {
                            dlclose(lib);
                            return Err(RenderError::BackendUnavailable(concat!("egl: missing ", $what)));
                        }
                    }
                };
            }
            let get_proc: PfnGetProcAddress = required!(c"eglGetProcAddress", PfnGetProcAddress, "eglGetProcAddress");
            let get_error: PfnGetError = required!(c"eglGetError", PfnGetError, "eglGetError");

            // Prefer the surfaceless platform (Mesa), then fall back to the
            // default display (which needs a real window system).
            let get_platform_ext: Option<PfnGetPlatformDisplay> =
                load_sym(lib, c"eglGetPlatformDisplayEXT").map(|p| std::mem::transmute(p));
            let get_platform: Option<PfnGetPlatformDisplay> = non_null(get_proc(
                c"eglGetPlatformDisplay".as_ptr(),
            ))
            .map(|p| std::mem::transmute(p));
            let get_display: Option<PfnGetDisplay> =
                load_sym(lib, c"eglGetDisplay").map(|p| std::mem::transmute(p));

            let display = if let Some(f) = get_platform_ext {
                f(EGL_PLATFORM_SURFACELESS_MESA, null_mut(), std::ptr::null())
            } else if let Some(f) = get_platform {
                f(EGL_PLATFORM_SURFACELESS_MESA, null_mut(), std::ptr::null())
            } else if let Some(f) = get_display {
                f(null_mut())
            } else {
                dlclose(lib);
                return Err(RenderError::BackendUnavailable("egl: no display API"));
            };
            if display.is_null() {
                dlclose(lib);
                return Err(RenderError::BackendUnavailable("egl: no display (headless unsupported)"));
            }

            let initialize: PfnInitialize = match load_sym(lib, c"eglInitialize") {
                Some(p) => std::mem::transmute(p),
                None => { dlclose(lib); return Err(RenderError::BackendUnavailable("egl: eglInitialize missing")); }
            };
            let mut ver_major = 0i32;
            let mut ver_minor = 0i32;
            if initialize(display, &mut ver_major, &mut ver_minor) == 0 {
                dlclose(lib);
                return Err(RenderError::BackendUnavailable("egl: eglInitialize failed"));
            }

            let choose_config: PfnChooseConfig = required!(c"eglChooseConfig", PfnChooseConfig, "eglChooseConfig");
            let bind_api: PfnBindApi = required!(c"eglBindAPI", PfnBindApi, "eglBindAPI");
            let create_context: PfnCreateContext = required!(c"eglCreateContext", PfnCreateContext, "eglCreateContext");
            let make_current: PfnMakeCurrent = required!(c"eglMakeCurrent", PfnMakeCurrent, "eglMakeCurrent");
            let destroy_context: PfnDestroyContext = required!(c"eglDestroyContext", PfnDestroyContext, "eglDestroyContext");
            let terminate: PfnTerminate = required!(c"eglTerminate", PfnTerminate, "eglTerminate");

            let renderable = if api == EGL_OPENGL_API {
                EGL_OPENGL_BIT
            } else if major >= 3 {
                EGL_OPENGL_ES3_BIT
            } else {
                EGL_OPENGL_ES2_BIT
            };
            // A depth buffer is requested explicitly: the shared GL core does a
            // real depth test, and a config without depth bits would silently
            // make that test a no-op.
            let config_attribs = [
                EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                EGL_RENDERABLE_TYPE, renderable,
                EGL_DEPTH_SIZE, 24,
                EGL_NONE,
            ];
            let mut config: *mut c_void = null_mut();
            let mut num = 0i32;
            if choose_config(display, config_attribs.as_ptr(), &mut config, 1, &mut num) == 0 || num == 0 {
                terminate(display);
                dlclose(lib);
                return Err(RenderError::BackendUnavailable("egl: no matching config"));
            }

            if bind_api(api) == 0 {
                terminate(display);
                dlclose(lib);
                return Err(RenderError::BackendUnavailable("egl: eglBindAPI failed"));
            }

            let context_attribs: [i32; 5] = if api == EGL_OPENGL_API {
                [EGL_CONTEXT_MAJOR_VERSION, major as i32, EGL_CONTEXT_MINOR_VERSION, minor as i32, EGL_NONE]
            } else {
                [EGL_CONTEXT_CLIENT_VERSION, major as i32, EGL_NONE, EGL_NONE, EGL_NONE]
            };
            let context = create_context(display, config, null_mut(), context_attribs.as_ptr());
            if context.is_null() {
                terminate(display);
                dlclose(lib);
                return Err(RenderError::BackendUnavailable("egl: eglCreateContext failed"));
            }
            if make_current(display, null_mut(), null_mut(), context) == 0 {
                destroy_context(display, context);
                terminate(display);
                dlclose(lib);
                return Err(RenderError::BackendUnavailable("egl: eglMakeCurrent failed (no surfaceless)"));
            }

            let query_string: Option<PfnQueryString> =
                load_sym(lib, c"eglQueryString").map(|p| std::mem::transmute(p));
            let mut vendor = String::new();
            let mut version = 0u32;
            if let Some(q) = query_string {
                let v = q(display, 0x3054 /* EGL_VERSION */);
                if !v.is_null() {
                    let text = std::ffi::CStr::from_ptr(v).to_string_lossy();
                    let mut parts = text.split_whitespace();
                    if let Some(first) = parts.next() {
                        if let Some(major) = first.split('.').next().and_then(|s| s.parse().ok()) {
                            version = major;
                        }
                    }
                }
                let v = q(display, 0x3053 /* EGL_VENDOR */);
                if !v.is_null() {
                    vendor = std::ffi::CStr::from_ptr(v).to_string_lossy().into_owned();
                }
            }

            Ok(EglContext {
                _lib: lib,
                display,
                context,
                make_current,
                destroy_context,
                terminate,
                get_proc,
                get_error,
                version,
                vendor,
            })
        }
    }

    /// Resolve an OpenGL/GLES entry point.
    pub fn proc(&self, name: &std::ffi::CStr) -> Option<*mut c_void> {
        self.proc_raw(name.as_ptr())
    }

    /// Resolve a NUL-terminated entry point name; used by the `gl_fns!` table
    /// macro, which builds the literal with `concat!(name, "\0")`.
    pub fn proc_raw(&self, name: *const c_char) -> Option<*mut c_void> {
        // SAFETY: `get_proc` is a live EGL function pointer and `name` is a
        // NUL-terminated string supplied by the caller.
        let p = unsafe { (self.get_proc)(name) };
        non_null(p)
    }

    pub fn egl_version(&self) -> u32 { self.version }
    pub fn vendor(&self) -> &str { &self.vendor }
    pub fn last_error(&self) -> i32 {
        // SAFETY: live function pointer.
        unsafe { (self.get_error)() }
    }
}

impl Drop for EglContext {
    fn drop(&mut self) {
        unsafe {
            (self.make_current)(self.display, null_mut(), null_mut(), null_mut());
            (self.destroy_context)(self.display, self.context);
            (self.terminate)(self.display);
            // The loader handle is intentionally left open: closing it can
            // unmap the vendor driver while glvnd/Mesa process state still
            // references it, which crashes at exit. One handle per context is
            // harmless.
        }
    }
}

/// `dlopen` the first candidate name that resolves.
///
/// Distros differ on whether only the SONAME or also an unversioned symlink is
/// present at runtime, so a missing `.so.1` should not by itself mean "no
/// backend".
pub unsafe fn dlopen_first(names: &[&std::ffi::CStr]) -> *mut c_void {
    for name in names {
        // SAFETY: static, NUL-terminated names passed to the platform loader.
        let handle = unsafe { dlopen(name.as_ptr(), RTLD_NOW) };
        if !handle.is_null() {
            return handle;
        }
    }
    null_mut()
}

unsafe fn load_sym(lib: *mut c_void, name: &std::ffi::CStr) -> Option<*mut c_void> {
    // SAFETY: `lib` is a live loader handle; `name` is NUL-terminated.
    non_null(unsafe { dlsym(lib, name.as_ptr()) })
}

fn non_null(p: *mut c_void) -> Option<*mut c_void> {
    if p.is_null() { None } else { Some(p) }
}
