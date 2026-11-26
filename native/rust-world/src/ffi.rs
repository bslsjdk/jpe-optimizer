//! C ABI surface consumed by the Java FFM bridge.
//!
//! This is the temporary integration point requested for this phase: Java calls
//! the Rust world engine directly, with **no C++ involvement**. Terrain math is
//! not called from here yet; tests use the Java side's own synthetic data.
//!
//! Handles are opaque `u64` values indexing an internal registry of
//! `SchedulerCore`. All functions validate handles and pointers and return a
//! negative status instead of panicking across the boundary. Status codes are
//! documented per function and mirrored in `JpeWorldNative.java`.

use crate::chunk::ChunkState;
use crate::coords::ChunkPos;
use crate::mesh::{build_section_mesh, ChunkSection, Vertex};
use crate::render::{Backend, DrawBatch, Mat4, MeshHandle, RenderError, Renderer};
use crate::scheduler::{Completion, SchedulerCore};
use crate::task::{ChunkJob, JobKind, Priority};
use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, OnceLock};

/// ABI version of this Java-facing surface (independent of the C++ ABI v8).
pub const JAVA_ABI_VERSION: u32 = 1;

/// Worst-case quads for one 16^3 section: a checkerboard of 2048 solid blocks,
/// every face exposed.
pub const MAX_SECTION_QUADS: u32 = 2048 * 6;

#[derive(Default)]
struct Registry {
    next: u64,
    entries: HashMap<u64, SchedulerCore>,
}

fn registry() -> &'static Mutex<Registry> {
    static REG: OnceLock<Mutex<Registry>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(Registry::default()))
}

fn lock() -> MutexGuard<'static, Registry> {
    registry().lock().unwrap_or_else(|e| e.into_inner())
}

#[inline]
fn kind_code(kind: JobKind) -> u32 {
    match kind {
        JobKind::GenerateTerrain => 0,
        JobKind::BuildMesh => 1,
        JobKind::UploadMesh => 2,
        JobKind::Unload => 3,
    }
}

fn kind_from(code: u32) -> Option<JobKind> {
    match code {
        0 => Some(JobKind::GenerateTerrain),
        1 => Some(JobKind::BuildMesh),
        2 => Some(JobKind::UploadMesh),
        3 => Some(JobKind::Unload),
        _ => None,
    }
}

#[inline]
fn priority_code(priority: Priority) -> u32 {
    match priority {
        Priority::Urgent => 0,
        Priority::Near => 1,
        Priority::Far => 2,
    }
}

#[inline]
fn state_code(state: ChunkState) -> u32 {
    match state {
        ChunkState::Empty => 0,
        ChunkState::TerrainQueued => 1,
        ChunkState::TerrainGenerating => 2,
        ChunkState::TerrainReady => 3,
        ChunkState::MeshQueued => 4,
        ChunkState::Meshing => 5,
        ChunkState::MeshReady => 6,
        ChunkState::Uploaded => 7,
        ChunkState::Unloading => 8,
    }
}

/// FFM-visible statistics block. All fields are `u64`; size is 56 bytes.
#[repr(C)]
#[derive(Copy, Clone, Debug, Default)]
pub struct JpeWorldStats {
    pub submitted: u64,
    pub started: u64,
    pub completed: u64,
    pub stale_discarded: u64,
    pub rejected_full: u64,
    pub evicted: u64,
    pub epoch_bumps: u64,
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn jpe_world_abi_version() -> u32 {
    JAVA_ABI_VERSION
}

#[no_mangle]
pub extern "C" fn jpe_world_max_section_quads() -> u32 {
    MAX_SECTION_QUADS
}

/// Create a scheduler for a world epoch. Returns a non-zero handle, or 0.
#[no_mangle]
pub extern "C" fn jpe_world_create(world_epoch: u64, queue_capacity: u32) -> u64 {
    let mut reg = lock();
    reg.next = reg.next.wrapping_add(1).max(1);
    let handle = reg.next;
    reg.entries
        .insert(handle, SchedulerCore::new(world_epoch, queue_capacity.max(1) as usize));
    handle
}

/// Destroy a handle. Returns 0 on success, -1 for an unknown handle.
#[no_mangle]
pub extern "C" fn jpe_world_destroy(handle: u64) -> i32 {
    if lock().entries.remove(&handle).is_some() {
        0
    } else {
        -1
    }
}

// ---------------------------------------------------------------------------
// Scheduling
// ---------------------------------------------------------------------------

/// Update the player chunk so queued jobs are re-ranked. 0 ok, -1 unknown.
#[no_mangle]
pub extern "C" fn jpe_world_set_player(handle: u64, chunk_x: i32, chunk_z: i32) -> i32 {
    let mut reg = lock();
    match reg.entries.get_mut(&handle) {
        Some(sched) => {
            sched.set_player_chunk(ChunkPos::new(chunk_x, chunk_z));
            0
        }
        None => -1,
    }
}

/// Queue terrain generation.
/// Returns 0 ok, -1 unknown handle, -2 illegal state, -3 queue rejected,
/// -4 null out pointer.
#[no_mangle]
pub extern "C" fn jpe_world_request_terrain(
    handle: u64,
    chunk_x: i32,
    chunk_z: i32,
    out_job_id: *mut u64,
) -> i32 {
    if out_job_id.is_null() {
        return -4;
    }
    let mut reg = lock();
    let Some(sched) = reg.entries.get_mut(&handle) else {
        return -1;
    };
    match sched.request_terrain(ChunkPos::new(chunk_x, chunk_z)) {
        Ok(id) => {
            // SAFETY: caller guarantees a writable u64 (checked non-null above).
            unsafe { *out_job_id = id };
            0
        }
        Err(crate::scheduler::SchedError::IllegalState { .. }) => -2,
        Err(crate::scheduler::SchedError::QueueRejected) => -3,
        Err(crate::scheduler::SchedError::Store(_)) => -2,
    }
}

/// Queue mesh construction for a terrain-ready chunk. Same status codes as
/// `jpe_world_request_terrain`.
#[no_mangle]
pub extern "C" fn jpe_world_request_mesh(
    handle: u64,
    chunk_x: i32,
    chunk_z: i32,
    out_job_id: *mut u64,
) -> i32 {
    if out_job_id.is_null() {
        return -4;
    }
    let mut reg = lock();
    let Some(sched) = reg.entries.get_mut(&handle) else {
        return -1;
    };
    match sched.request_mesh(ChunkPos::new(chunk_x, chunk_z)) {
        Ok(id) => {
            // SAFETY: checked non-null above.
            unsafe { *out_job_id = id };
            0
        }
        Err(crate::scheduler::SchedError::IllegalState { .. }) => -2,
        Err(crate::scheduler::SchedError::QueueRejected) => -3,
        Err(crate::scheduler::SchedError::Store(_)) => -2,
    }
}

/// Pop the next runnable job into the out parameters.
/// Returns 1 job written, 0 no job, -1 unknown handle, -4 null pointer.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn jpe_world_take_job(
    handle: u64,
    out_id: *mut u64,
    out_kind: *mut u32,
    out_priority: *mut u32,
    out_chunk_x: *mut i32,
    out_chunk_z: *mut i32,
    out_epoch: *mut u64,
    out_distance_sq: *mut u64,
) -> i32 {
    if out_id.is_null()
        || out_kind.is_null()
        || out_priority.is_null()
        || out_chunk_x.is_null()
        || out_chunk_z.is_null()
        || out_epoch.is_null()
        || out_distance_sq.is_null()
    {
        return -4;
    }
    let mut reg = lock();
    let Some(sched) = reg.entries.get_mut(&handle) else {
        return -1;
    };
    match sched.take_job() {
        Some(job) => {
            // SAFETY: all pointers checked non-null above; caller owns them.
            unsafe {
                *out_id = job.id;
                *out_kind = kind_code(job.kind);
                *out_priority = priority_code(job.priority);
                *out_chunk_x = job.pos.x;
                *out_chunk_z = job.pos.z;
                *out_epoch = job.epoch;
                *out_distance_sq = job.distance_sq;
            }
            1
        }
        None => 0,
    }
}

/// Feed a finished job back.
/// Returns 0 committed, 1 stale, 2 missing, -1 unknown handle, -2 bad kind.
#[no_mangle]
pub extern "C" fn jpe_world_complete(
    handle: u64,
    epoch: u64,
    kind: u32,
    chunk_x: i32,
    chunk_z: i32,
) -> i32 {
    let Some(kind) = kind_from(kind) else {
        return -2;
    };
    let job = ChunkJob {
        id: 0,
        priority: Priority::Near,
        kind,
        pos: ChunkPos::new(chunk_x, chunk_z),
        epoch,
        distance_sq: 0,
        seq: 0,
    };
    let mut reg = lock();
    let Some(sched) = reg.entries.get_mut(&handle) else {
        return -1;
    };
    match sched.complete(job) {
        Completion::Committed { .. } => 0,
        Completion::Stale => 1,
        Completion::Missing => 2,
    }
}

/// Mark a mesh-ready chunk as uploaded (render thread). 0 ok, -1 unknown,
/// -2 illegal state.
#[no_mangle]
pub extern "C" fn jpe_world_mark_uploaded(handle: u64, chunk_x: i32, chunk_z: i32) -> i32 {
    let mut reg = lock();
    let Some(sched) = reg.entries.get_mut(&handle) else {
        return -1;
    };
    match sched.mark_uploaded(ChunkPos::new(chunk_x, chunk_z)) {
        Ok(()) => 0,
        Err(_) => -2,
    }
}

/// Unload a chunk immediately (no tickets held). 0 ok, -1 unknown, -2 rejected
/// (tickets held or bad state).
#[no_mangle]
pub extern "C" fn jpe_world_unload(handle: u64, chunk_x: i32, chunk_z: i32) -> i32 {
    let mut reg = lock();
    let Some(sched) = reg.entries.get_mut(&handle) else {
        return -1;
    };
    match sched.unload_now(ChunkPos::new(chunk_x, chunk_z)) {
        Ok(_) => 0,
        Err(_) => -2,
    }
}

/// Chunk state code (0..8), -3 if the chunk is not tracked, -1 unknown handle.
#[no_mangle]
pub extern "C" fn jpe_world_chunk_state(handle: u64, chunk_x: i32, chunk_z: i32) -> i32 {
    let reg = lock();
    let Some(sched) = reg.entries.get(&handle) else {
        return -1;
    };
    match sched.store().state(ChunkPos::new(chunk_x, chunk_z)) {
        Some(state) => state_code(state) as i32,
        None => -3,
    }
}

/// Copy statistics into `out`. 0 ok, -1 unknown handle, -4 null pointer.
#[no_mangle]
pub extern "C" fn jpe_world_stats(handle: u64, out: *mut JpeWorldStats) -> i32 {
    if out.is_null() {
        return -4;
    }
    let reg = lock();
    let Some(sched) = reg.entries.get(&handle) else {
        return -1;
    };
    let stats = sched.stats();
    // SAFETY: non-null checked; caller provides a 56-byte writable block.
    unsafe {
        *out = JpeWorldStats {
            submitted: stats.submitted,
            started: stats.started,
            completed: stats.completed,
            stale_discarded: stats.stale_discarded,
            rejected_full: stats.rejected_full,
            evicted: stats.evicted,
            epoch_bumps: stats.epoch_bumps,
        };
    }
    0
}

/// Start a new world epoch, draining the queue. Writes the new epoch to
/// `out_new_epoch` when non-null. 0 ok, -1 unknown handle.
#[no_mangle]
pub extern "C" fn jpe_world_reset(handle: u64, out_new_epoch: *mut u64) -> i32 {
    let mut reg = lock();
    let Some(sched) = reg.entries.get_mut(&handle) else {
        return -1;
    };
    sched.reset_for_new_world();
    if !out_new_epoch.is_null() {
        // SAFETY: non-null checked; caller provides writable u64.
        unsafe { *out_new_epoch = sched.world_epoch() };
    }
    0
}

// ---------------------------------------------------------------------------
// Meshing (no GPU required)
// ---------------------------------------------------------------------------

/// Build a section mesh from a caller-owned block array.
///
/// `section` points to 4096 `u16` block ids indexed `(y<<8)|(z<<4)|x`.
/// Vertex bytes are written interleaved (32 bytes each); indices are `u32`.
///
/// Returns the vertex count (>= 0) on success, or:
/// -1 null pointer, -2 capacity too small (use `jpe_world_max_section_quads`
/// to size buffers), -3 arithmetic overflow.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn jpe_world_mesh_section(
    section: *const u16,
    origin_x: i32,
    origin_y: i32,
    origin_z: i32,
    vertex_bytes: *mut u8,
    vertex_capacity: usize,
    index_out: *mut u32,
    index_capacity: usize,
    out_quad_count: *mut u32,
) -> i64 {
    if section.is_null() || vertex_bytes.is_null() || index_out.is_null() {
        return -1;
    }
    // SAFETY: caller provides 4096 readable u16 values (checked non-null).
    let chunk_section = unsafe { read_section(section) };
    let mesh = build_section_mesh(&chunk_section, [origin_x, origin_y, origin_z]);

    let vertex_count = mesh.vertices.len();
    let required_vertex_bytes = vertex_count * Vertex::STRIDE;
    let required_index_bytes = mesh.indices.len() * std::mem::size_of::<u32>();
    if vertex_capacity < required_vertex_bytes || index_capacity < required_index_bytes {
        return -2;
    }

    // SAFETY: capacities checked above; caller owns both buffers.
    unsafe {
        let mut offset = 0usize;
        for vertex in &mesh.vertices {
            let out = std::slice::from_raw_parts_mut(vertex_bytes.add(offset), Vertex::STRIDE);
            vertex.write_le(out);
            offset += Vertex::STRIDE;
        }
        std::ptr::copy_nonoverlapping(
            mesh.indices.as_ptr(),
            index_out,
            mesh.indices.len(),
        );
        if !out_quad_count.is_null() {
            *out_quad_count = mesh.quad_count;
        }
    }
    vertex_count as i64
}


/// Read 4096 block ids indexed `(y<<8)|(z<<4)|x`.
///
/// # Safety
/// `section` must point to at least 4096 readable `u16` values.
unsafe fn read_section(section: *const u16) -> ChunkSection {
    let mut chunk_section = ChunkSection::new();
    for y in 0..16usize {
        for z in 0..16usize {
            for x in 0..16usize {
                // SAFETY: guaranteed by the caller; index is within 4096.
                let block = unsafe { *section.add((y << 8) | (z << 4) | x) };
                chunk_section.set(x, y, z, block);
            }
        }
    }
    chunk_section
}

// ---------------------------------------------------------------------------
// Renderer (native GPU backends)
// ---------------------------------------------------------------------------

/// Backend selector codes, mirrored in `JpeWorldNative.java`.
pub const RENDER_BACKEND_AUTO: u32 = 0;
pub const RENDER_BACKEND_VULKAN: u32 = 1;
pub const RENDER_BACKEND_GL: u32 = 2;
pub const RENDER_BACKEND_GLES: u32 = 3;

/// Renderer capabilities, FFM-visible. 24 bytes, all fields 4-byte.
#[repr(C)]
#[derive(Copy, Clone, Debug, Default)]
pub struct JpeRendererCaps {
    pub backend: u32,
    pub api_major: u32,
    pub api_minor: u32,
    pub max_texture_size: u32,
    pub supports_compute: u32,
    pub reserved: u32,
}

fn backend_code(backend: Backend) -> u32 {
    match backend {
        Backend::Vulkan => RENDER_BACKEND_VULKAN,
        Backend::Gl => RENDER_BACKEND_GL,
        Backend::Gles => RENDER_BACKEND_GLES,
        Backend::Headless => 0,
    }
}

struct RendererEntry {
    thread: std::thread::ThreadId,
    renderer: Box<dyn Renderer>,
}

// SAFETY: a renderer must be driven from the thread that created it (EGL/GL
// contexts are thread-affine). `with_renderer` compares the caller's thread id
// and rejects anything else with -7 before touching the backend, and the
// registry mutex serialises calls, so no renderer is used concurrently or
// cross-thread. `Send` is required only to store entries in the process-wide
// registry.
unsafe impl Send for RendererEntry {}

#[derive(Default)]
struct RendererRegistry {
    next: u64,
    entries: HashMap<u64, RendererEntry>,
}

fn renderer_registry() -> &'static Mutex<RendererRegistry> {
    static REG: OnceLock<Mutex<RendererRegistry>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(RendererRegistry::default()))
}

fn renderer_lock() -> MutexGuard<'static, RendererRegistry> {
    renderer_registry().lock().unwrap_or_else(|e| e.into_inner())
}

/// Build a renderer for `backend`, or `None` when that backend cannot run here.
fn make_renderer(backend: u32) -> Option<Box<dyn Renderer>> {
    match backend {
        RENDER_BACKEND_AUTO => crate::render::create_best_headless().ok(),
        RENDER_BACKEND_VULKAN => crate::render::vulkan::VulkanRenderer::create_headless()
            .ok()
            .map(|r| Box::new(r) as Box<dyn Renderer>),
        RENDER_BACKEND_GL => crate::render::gl::GlRenderer::create_headless()
            .ok()
            .map(|r| Box::new(r) as Box<dyn Renderer>),
        RENDER_BACKEND_GLES => crate::render::gles::GlesRenderer::create_headless()
            .ok()
            .map(|r| Box::new(r) as Box<dyn Renderer>),
        _ => None,
    }
}

fn render_error_code(error: RenderError) -> i32 {
    match error {
        RenderError::BackendUnavailable(_) => -3,
        RenderError::InvalidMesh => -4,
        RenderError::Unsupported(_) => -6,
        RenderError::DeviceLost | RenderError::OutOfMemory | RenderError::Api(_) => -5,
    }
}

/// Drive the renderer behind `handle`.
///
/// `Err(-1)` unknown handle, `Err(-7)` called from a thread other than the one
/// that created it. Backend errors map to -3 (unavailable), -4 (invalid mesh),
/// -5 (API/device), -6 (unsupported/read before a frame exists).
fn with_renderer<R>(
    handle: u64,
    f: impl FnOnce(&mut dyn Renderer) -> Result<R, RenderError>,
) -> Result<R, i32> {
    let mut reg = renderer_lock();
    let Some(entry) = reg.entries.get_mut(&handle) else {
        return Err(-1);
    };
    if entry.thread != std::thread::current().id() {
        return Err(-7);
    }
    f(entry.renderer.as_mut()).map_err(render_error_code)
}

fn status<R>(result: Result<R, i32>) -> i32 {
    match result {
        Ok(_) => 0,
        Err(code) => code,
    }
}

/// Create a renderer for `backend` (0 = best available).
///
/// Returns a non-zero handle, or 0 when that backend cannot run here. The
/// handle must be used from the creating thread; other threads get -7.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_create(backend: u32) -> u64 {
    let Some(renderer) = make_renderer(backend) else {
        return 0;
    };
    let mut reg = renderer_lock();
    reg.next = reg.next.wrapping_add(1).max(1);
    let handle = reg.next;
    reg.entries.insert(
        handle,
        RendererEntry {
            thread: std::thread::current().id(),
            renderer,
        },
    );
    handle
}

/// 1 if `backend` can be created here (it is created and released), else 0.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_available(backend: u32) -> i32 {
    if make_renderer(backend).is_some() { 1 } else { 0 }
}

/// Destroy a renderer. 0 ok, -1 unknown handle, -7 wrong thread.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_destroy(handle: u64) -> i32 {
    let mut reg = renderer_lock();
    let owned = match reg.entries.get(&handle) {
        Some(entry) => entry.thread == std::thread::current().id(),
        None => return -1,
    };
    if !owned {
        return -7;
    }
    reg.entries.remove(&handle);
    0
}

/// Fill `out` with the renderer's capabilities.
/// 0 ok, -1 unknown handle, -2 null pointer, -7 wrong thread.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_capabilities(handle: u64, out: *mut JpeRendererCaps) -> i32 {
    if out.is_null() {
        return -2;
    }
    match with_renderer(handle, |r| Ok(r.capabilities())) {
        Ok(caps) => {
            // SAFETY: non-null checked; caller provides a 24-byte writable block.
            unsafe {
                *out = JpeRendererCaps {
                    backend: backend_code(caps.backend),
                    api_major: caps.api_version.0,
                    api_minor: caps.api_version.1,
                    max_texture_size: caps.max_texture_size,
                    supports_compute: caps.supports_compute as u32,
                    reserved: 0,
                };
            }
            0
        }
        Err(code) => code,
    }
}

/// Set the column-major 4x4 view-projection matrix used by subsequent draws.
///
/// `view_proj` must point at 16 readable `f32`s (64 bytes). Shared by every
/// backend; the matrix uses the Vulkan 0..1 depth range, which is also correct
/// for GL, so Java never has to care about per-backend projection variants.
/// 0 ok, -1 unknown renderer, -2 null pointer, -4 non-finite matrix,
/// -6 backend has no camera support, -7 wrong thread.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_set_camera(handle: u64, view_proj: *const f32) -> i32 {
    if view_proj.is_null() {
        return -2;
    }
    // SAFETY: non-null checked; the caller provides 16 readable f32 values.
    let matrix: Mat4 = unsafe { std::ptr::read_unaligned(view_proj as *const Mat4) };
    status(with_renderer(handle, |r| r.set_view_proj(matrix)))
}

/// Build an orthographic look-at view-projection matrix, single-sourced in Rust
/// so the Java side passes plain vectors instead of doing matrix maths.
///
/// `eye`, `target`, `up` point at 3 readable `f32`s each; `out_view_proj` must
/// point at 16 writable `f32`s.
/// 0 ok, -2 null pointer, -4 degenerate basis or invalid extents/range.
#[no_mangle]
pub extern "C" fn jpe_world_camera_ortho(
    eye: *const f32,
    target: *const f32,
    up: *const f32,
    half_width: f32,
    half_height: f32,
    near: f32,
    far: f32,
    out_view_proj: *mut f32,
) -> i32 {
    if eye.is_null() || target.is_null() || up.is_null() || out_view_proj.is_null() {
        return -2;
    }
    // SAFETY: non-null checked; each input points at 3 readable f32s.
    let (e, t, u) = unsafe {
        (
            [eye.read(), eye.add(1).read(), eye.add(2).read()],
            [target.read(), target.add(1).read(), target.add(2).read()],
            [up.read(), up.add(1).read(), up.add(2).read()],
        )
    };
    let Some(matrix) = crate::render::ortho_look_at(e, t, u, half_width, half_height, near, far)
    else {
        return -4;
    };
    // SAFETY: `out_view_proj` non-null checked and points at 16 writable f32s.
    unsafe {
        std::ptr::copy_nonoverlapping(matrix.as_ptr(), out_view_proj, 16);
    }
    0
}

/// Start a frame targeting `width` x `height` pixels.
/// 0 ok, -1 unknown, -4 invalid extent, -5 API error, -7 wrong thread.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_begin_frame(handle: u64, width: u32, height: u32) -> i32 {
    status(with_renderer(handle, |r| r.begin_frame([width, height])))
}

/// Mesh a 16^3 section and upload it to the renderer in one call.
///
/// `section` is 4096 block ids indexed `(y<<8)|(z<<4)|x`, exactly as
/// `jpe_world_mesh_section`. Writes the mesh handle and its index count.
/// 0 ok, -1 unknown, -2 null pointer, -4 empty/invalid mesh, -5 API error,
/// -7 wrong thread.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn jpe_world_renderer_upload_section(
    handle: u64,
    section: *const u16,
    origin_x: i32,
    origin_y: i32,
    origin_z: i32,
    out_mesh: *mut u64,
    out_index_count: *mut u32,
) -> i32 {
    if section.is_null() || out_mesh.is_null() {
        return -2;
    }
    // SAFETY: caller provides 4096 readable u16 block ids.
    let chunk_section = unsafe { read_section(section) };
    let mesh = build_section_mesh(&chunk_section, [origin_x, origin_y, origin_z]);
    let index_count = mesh.indices.len() as u32;
    match with_renderer(handle, |r| r.create_mesh(&mesh)) {
        Ok(mesh_handle) => {
            // SAFETY: `out_mesh` non-null checked; `out_index_count` optional.
            unsafe {
                *out_mesh = mesh_handle.0;
                if !out_index_count.is_null() {
                    *out_index_count = index_count;
                }
            }
            0
        }
        Err(code) => code,
    }
}

/// Release a mesh uploaded by this renderer.
/// 0 ok, -1 unknown renderer, -7 wrong thread.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_destroy_mesh(handle: u64, mesh: u64) -> i32 {
    status(with_renderer(handle, |r| {
        r.destroy_mesh(MeshHandle(mesh));
        Ok(())
    }))
}

/// Queue an indexed draw of `mesh` for the current frame.
/// 0 ok, -1 unknown renderer, -4 bad mesh or no frame begun, -7 wrong thread.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_draw(
    handle: u64,
    mesh: u64,
    index_count: u32,
    instance_count: u32,
) -> i32 {
    status(with_renderer(handle, |r| {
        r.draw(DrawBatch {
            mesh: MeshHandle(mesh),
            index_count,
            instance_count,
        })
    }))
}

/// Finish the frame (draws the queued batches and captures the frame).
/// 0 ok, -1 unknown, -4 no frame begun, -5 API error, -7 wrong thread.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_end_frame(handle: u64) -> i32 {
    status(with_renderer(handle, |r| r.end_frame()))
}

/// Copy the last completed frame into `dst` (tightly packed RGBA8, top-left
/// origin) and write its size to `out_width`/`out_height`.
///
/// With `dst == null` this only reports the size. Returns 0 on success, -1
/// unknown handle, -2 destination too small, -6 no completed frame, -7 wrong
/// thread.
#[no_mangle]
pub extern "C" fn jpe_world_renderer_read_pixels(
    handle: u64,
    dst: *mut u8,
    dst_len: usize,
    out_width: *mut u32,
    out_height: *mut u32,
) -> i32 {
    let frame = match with_renderer(handle, |r| r.read_pixels()) {
        Ok(frame) => frame,
        Err(code) => return code,
    };
    // SAFETY: optional out pointers are written only when non-null.
    unsafe {
        if !out_width.is_null() {
            *out_width = frame.width;
        }
        if !out_height.is_null() {
            *out_height = frame.height;
        }
    }
    if dst.is_null() {
        return 0;
    }
    let required = frame.rgba8.len();
    if dst_len < required {
        return -2;
    }
    // SAFETY: destination is writable for `required` bytes (checked above).
    unsafe {
        std::ptr::copy_nonoverlapping(frame.rgba8.as_ptr(), dst, required);
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_version_is_stable() {
        assert_eq!(jpe_world_abi_version(), 1);
    }

    #[test]
    fn scheduler_round_trip_over_c_abi() {
        let handle = jpe_world_create(1, 8);
        assert_ne!(handle, 0);

        let mut job_id = 0u64;
        assert_eq!(jpe_world_request_terrain(handle, 0, 0, &mut job_id), 0);
        assert_ne!(job_id, 0);
        assert_eq!(jpe_world_chunk_state(handle, 0, 0), 1); // TerrainQueued

        let (mut id, mut kind, mut prio, mut x, mut z, mut epoch, mut dist) =
            (0u64, 0u32, 0u32, 0i32, 0i32, 0u64, 0u64);
        let taken = jpe_world_take_job(
            handle,
            &mut id,
            &mut kind,
            &mut prio,
            &mut x,
            &mut z,
            &mut epoch,
            &mut dist,
        );
        assert_eq!(taken, 1);
        assert_eq!(kind, 0);
        assert_eq!((x, z), (0, 0));
        assert_eq!(jpe_world_chunk_state(handle, 0, 0), 2); // TerrainGenerating

        assert_eq!(jpe_world_complete(handle, epoch, kind, x, z), 0);
        assert_eq!(jpe_world_chunk_state(handle, 0, 0), 3); // TerrainReady

        let mut stats = JpeWorldStats::default();
        assert_eq!(jpe_world_stats(handle, &mut stats), 0);
        assert_eq!(stats.submitted, 1);
        assert_eq!(stats.completed, 1);

        assert_eq!(jpe_world_destroy(handle), 0);
        assert_eq!(jpe_world_destroy(handle), -1);
    }

    #[test]
    fn stale_epoch_is_reported() {
        let handle = jpe_world_create(1, 8);
        let mut id = 0u64;
        assert_eq!(jpe_world_request_terrain(handle, 2, 2, &mut id), 0);
        let (mut jid, mut kind, mut prio, mut x, mut z, mut epoch, mut dist) =
            (0u64, 0u32, 0u32, 0i32, 0i32, 0u64, 0u64);
        assert_eq!(
            jpe_world_take_job(
                handle, &mut jid, &mut kind, &mut prio, &mut x, &mut z, &mut epoch, &mut dist
            ),
            1
        );
        let mut new_epoch = 0u64;
        assert_eq!(jpe_world_reset(handle, &mut new_epoch), 0);
        assert_eq!(new_epoch, 2);
        assert_eq!(jpe_world_complete(handle, epoch, kind, x, z), 1); // stale
        jpe_world_destroy(handle);
    }

    #[test]
    fn mesh_over_c_abi_matches_rust_api() {
        let mut blocks = vec![0u16; 4096];
        blocks[0] = 1;
        let quads = MAX_SECTION_QUADS as usize;
        let mut vbuf = vec![0u8; quads * 4 * Vertex::STRIDE];
        let mut ibuf = vec![0u32; quads * 6];
        let mut quad_count = 0u32;
        let n = jpe_world_mesh_section(
            blocks.as_ptr(),
            0,
            0,
            0,
            vbuf.as_mut_ptr(),
            vbuf.len(),
            ibuf.as_mut_ptr(),
            ibuf.len(),
            &mut quad_count,
        );
        assert_eq!(n, 24);
        assert_eq!(quad_count, 6);
        // First face is +X of block (0,0,0): its first corner is (1,0,0).
        assert_eq!(f32::from_le_bytes([vbuf[0], vbuf[1], vbuf[2], vbuf[3]]), 1.0);
    }

    #[test]
    fn mesh_reports_small_capacity() {
        let blocks = vec![1u16; 4096];
        let mut vbuf = vec![0u8; 32];
        let mut ibuf = vec![0u32; 6];
        let n = jpe_world_mesh_section(
            blocks.as_ptr(),
            0,
            0,
            0,
            vbuf.as_mut_ptr(),
            vbuf.len(),
            ibuf.as_mut_ptr(),
            ibuf.len(),
            std::ptr::null_mut(),
        );
        assert_eq!(n, -2);
    }

    fn require_or_skip(msg: &'static str) {
        if std::env::var_os("JPE_REQUIRE_RENDER_BACKENDS").is_some() {
            panic!("render backend required but unavailable: {msg}");
        }
    }

    /// One frame/upload/draw/readback round trip on a live renderer handle.
    fn exercise_renderer(handle: u64) {
        // Camera maths lives in Rust so the Java side never builds matrices; the
        // renderer only accepts a finished view-projection.
        let eye = [2.4f32, 2.0, 2.8];
        let target = [0.5f32, 0.5, 0.5];
        let up = [0.0f32, 1.0, 0.0];
        let mut matrix = [0f32; 16];
        assert_eq!(
            jpe_world_camera_ortho(
                eye.as_ptr(),
                target.as_ptr(),
                up.as_ptr(),
                0.9,
                0.9,
                0.1,
                20.0,
                matrix.as_mut_ptr()
            ),
            0
        );
        assert!(matrix.iter().all(|v| v.is_finite()), "camera {matrix:?}");
        // Eye == target is a degenerate basis; a null output must not be written.
        assert_eq!(
            jpe_world_camera_ortho(
                eye.as_ptr(),
                eye.as_ptr(),
                up.as_ptr(),
                0.9,
                0.9,
                0.1,
                20.0,
                matrix.as_mut_ptr()
            ),
            -4
        );
        assert_eq!(
            jpe_world_camera_ortho(
                eye.as_ptr(),
                target.as_ptr(),
                up.as_ptr(),
                0.9,
                0.9,
                0.1,
                20.0,
                std::ptr::null_mut()
            ),
            -2
        );
        assert_eq!(jpe_world_renderer_set_camera(handle, std::ptr::null()), -2);
        let mut nan_matrix = matrix;
        nan_matrix[0] = f32::NAN;
        assert_eq!(jpe_world_renderer_set_camera(handle, nan_matrix.as_ptr()), -4);
        assert_eq!(jpe_world_renderer_set_camera(handle, matrix.as_ptr()), 0);

        assert_eq!(jpe_world_renderer_begin_frame(handle, 128, 128), 0);
        assert_eq!(jpe_world_renderer_begin_frame(handle, 0, 128), -4);

        let mut blocks = vec![0u16; 4096];
        blocks[0] = 1;
        let mut mesh = 0u64;
        let mut index_count = 0u32;
        assert_eq!(
            jpe_world_renderer_upload_section(
                handle,
                blocks.as_ptr(),
                0,
                0,
                0,
                &mut mesh,
                &mut index_count
            ),
            0
        );
        assert_ne!(mesh, 0);
        assert_eq!(index_count, 36);

        assert_eq!(jpe_world_renderer_draw(handle, mesh, index_count, 1), 0);
        assert_eq!(jpe_world_renderer_end_frame(handle), 0);

        let mut width = 0u32;
        let mut height = 0u32;
        let mut pixels = vec![0u8; 128 * 128 * 4];
        assert_eq!(
            jpe_world_renderer_read_pixels(
                handle,
                pixels.as_mut_ptr(),
                pixels.len(),
                &mut width,
                &mut height
            ),
            0
        );
        assert_eq!((width, height), (128, 128));

        // Cleared corner is the dark background, and the geometry must cover a
        // real area wherever this backend maps it (the Y conventions differ).
        let background = [pixels[0], pixels[1], pixels[2], pixels[3]];
        assert!(background[0] <= 16 && background[1] <= 16, "background {background:?}");
        let mut lit = 0usize;
        let mut tones = std::collections::BTreeSet::new();
        for p in pixels.chunks_exact(4) {
            if p[0] == background[0] && p[1] == background[1] && p[2] == background[2] {
                continue;
            }
            lit += 1;
            tones.insert((p[0] / 16, p[1] / 16, p[2] / 16));
        }
        assert!(lit > 100, "expected geometry coverage, got {lit} pixels");
        // A flat single-colour fill would mean the atlas, the normal attribute or
        // the camera never reached the GPU; shading must produce several tones.
        assert!(tones.len() >= 3, "expected shaded atlas tones, got {tones:?}");
        eprintln!("renderer handle {handle}: {lit} lit pixels, {} distinct tones", tones.len());

        // A renderer is thread-affine: another thread is rejected, never served.
        let other = std::thread::spawn(move || jpe_world_renderer_begin_frame(handle, 16, 16));
        assert_eq!(other.join().unwrap(), -7);
        // The camera call is subject to the same rule, so Java cannot smuggle a
        // matrix in from a render thread other than the owner's.
        let other = std::thread::spawn(move || jpe_world_renderer_set_camera(handle, matrix.as_ptr()));
        assert_eq!(other.join().unwrap(), -7);

        assert_eq!(jpe_world_renderer_destroy_mesh(handle, mesh), 0);
    }

    /// Drive the renderer C ABI on every backend that can run here: selector,
    /// capabilities, frame, mesh upload, draw and pixel readback.
    #[test]
    fn renderer_over_c_abi_draws_and_reads_pixels() {
        let mut ran = 0u32;
        for backend in [RENDER_BACKEND_VULKAN, RENDER_BACKEND_GL, RENDER_BACKEND_GLES] {
            let handle = jpe_world_renderer_create(backend);
            if handle == 0 {
                require_or_skip("requested native render backend unavailable");
                eprintln!("backend code {backend} unavailable here, skipping");
                continue;
            }

            let mut caps = JpeRendererCaps::default();
            assert_eq!(jpe_world_renderer_capabilities(handle, &mut caps), 0);
            assert_eq!(caps.backend, backend, "selector created the wrong backend");
            assert!(caps.api_major >= 1);
            assert!(caps.max_texture_size >= 256);

            exercise_renderer(handle);

            assert_eq!(jpe_world_renderer_destroy(handle), 0);
            assert_eq!(jpe_world_renderer_destroy(handle), -1);
            ran += 1;
        }

        // Reading before any completed frame is an explicit error, not stale data.
        let fresh = jpe_world_renderer_create(RENDER_BACKEND_AUTO);
        assert_ne!(fresh, 0);
        let mut pixel = [0u8; 4];
        assert_eq!(
            jpe_world_renderer_read_pixels(
                fresh,
                pixel.as_mut_ptr(),
                pixel.len(),
                std::ptr::null_mut(),
                std::ptr::null_mut()
            ),
            -6
        );
        assert_eq!(jpe_world_renderer_destroy(fresh), 0);
        assert!(ran > 0, "no backend could be exercised");
    }
}
