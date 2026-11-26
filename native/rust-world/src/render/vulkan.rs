//! Vulkan native backend — **not implemented**.
//!
//! Planned ownership, once implemented: `VkInstance`/`VkDevice` created from
//! the platform surface owned by Minecraft (never a second window system),
//! queue families, command buffers, buffers/images, pipelines, descriptor sets,
//! synchronization and per-chunk draw submission. Vulkan is called directly;
//! no `wgpu`/`vulkano`-style wrapper manages the GPU.
//!
//! This module must only start returning capabilities after it can create a
//! real device and pass the shared render tests on real hardware.

use super::{Backend, Capabilities, RenderError};

pub const STATUS: &str = "not implemented";

/// Probe the backend. Reports `BackendUnavailable` until the native path lands.
pub fn probe() -> Result<Capabilities, RenderError> {
    Err(RenderError::BackendUnavailable(
        "vulkan: native backend not implemented in jpe-world-engine",
    ))
}

pub const BACKEND: Backend = Backend::Vulkan;
