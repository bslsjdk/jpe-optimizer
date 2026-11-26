//! OpenGL ES native backend — **not implemented**.
//!
//! Planned ownership, once implemented: Android/mobile GLES context reuse from
//! the existing EGL surface, buffer/VAO/shader/texture management tuned for
//! tile-based mobile GPUs, and chunk draw submission. GLES is called directly.

use super::{Backend, Capabilities, RenderError};

pub const STATUS: &str = "not implemented";

pub fn probe() -> Result<Capabilities, RenderError> {
    Err(RenderError::BackendUnavailable(
        "gles: native backend not implemented in jpe-world-engine",
    ))
}

pub const BACKEND: Backend = Backend::Gles;
