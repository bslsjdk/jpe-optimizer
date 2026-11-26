//! OpenGL native backend — **not implemented**.
//!
//! Planned ownership, once implemented: reuse Minecraft's existing GL context
//! (never create a second one), VAO/VBO/IBO creation, shader/program
//! compilation, texture upload, and per-chunk draw calls, with the native API
//! called directly instead of through a wrapper crate.

use super::{Backend, Capabilities, RenderError};

pub const STATUS: &str = "not implemented";

pub fn probe() -> Result<Capabilities, RenderError> {
    Err(RenderError::BackendUnavailable(
        "gl: native backend not implemented in jpe-world-engine",
    ))
}

pub const BACKEND: Backend = Backend::Gl;
