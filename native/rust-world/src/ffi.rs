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
    let mut chunk_section = ChunkSection::new();
    for y in 0..16usize {
        for z in 0..16usize {
            for x in 0..16usize {
                // SAFETY: caller provides 4096 readable u16 values.
                let block = unsafe { *section.add((y << 8) | (z << 4) | x) };
                chunk_section.set(x, y, z, block);
            }
        }
    }
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
}
