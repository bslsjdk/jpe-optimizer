//! C ABI for the frame-budgeted world streamer.
//!
//! The Java side never touches Rust types: it gets an opaque `*mut
//! WorldStreamer`, calls `jpe_world_*` functions on it, and hands it back to
//! `jpe_world_destroy`. Everything below that is Rust.
//!
//! Why a separate handle instead of exposing `TerrainEngine` directly: a
//! streamer is *stateful across frames* (queue + centre + scratch buffer), and
//! that state has to live somewhere between two `jpe_world_pump` calls. Bundling
//! it here means Java holds one pointer and one integer, not five.
//!
//! # Threading
//!
//! Every `jpe_world_*` function is `&mut`-equivalent: the caller must not call
//! two of them on the same handle concurrently. The handle is `Send` (the
//! engine already is), so it may be moved between threads, just not shared.
//!
//! # Errors
//!
//! Functions that can fail return a negative `i32`; `jpe_world_last_error`
//! describes the most recent failure on that handle and is cleared by the next
//! successful call. A negative return from a "count" function therefore means
//! "failed", never "minus one column".
//!
//! The error channel is deliberately small. `set_cache_budget` is the only
//! operation that can currently fail with a live handle, and only if the C++
//! layer ever starts rejecting a budget it used to accept -- note that a budget
//! of *zero bytes* is accepted on purpose and disables the cache rather than
//! erroring, so it is not a failure path.

use std::ffi::c_char;
use std::time::Duration;

use crate::stream_scheduler::StreamScheduler;
use crate::terrain_ffi::TerrainEngine;

/// Opaque handle. Java only ever sees a pointer to this.
pub struct WorldStreamer {
    engine: TerrainEngine,
    scheduler: StreamScheduler,
    /// Reused between pumps; one column's worth of block ids.
    scratch: Vec<u16>,
    /// Last error message, NUL-terminated when read back.
    last_error: [u8; 128],
}

/// A pointer argument was null.
pub const JPE_WORLD_ERR_NULL: i32 = -2;
/// The C++ cache rejected the configuration.
pub const JPE_WORLD_ERR_CACHE: i32 = -4;

impl WorldStreamer {
    fn set_error(&mut self, msg: &str) {
        let bytes = msg.as_bytes();
        let n = bytes.len().min(self.last_error.len() - 1);
        self.last_error[..n].copy_from_slice(&bytes[..n]);
        for slot in &mut self.last_error[n..] {
            *slot = 0;
        }
    }

    fn clear_error(&mut self) {
        self.last_error = [0u8; 128];
    }
}

/// Creates a streamer around a fresh overworld generator.
///
/// `radius` is the view radius in columns and must be `1..=32`; anything else
/// is rejected rather than clamped, because a typo that silently becomes a
/// 32-column view is a 3213-column stall nobody asked for. The queue starts
/// empty -- call [`jpe_world_retarget`] to fill it.
///
/// Returns null on failure.
///
/// # Safety
///
/// The returned pointer must be passed to [`jpe_world_destroy`] exactly once.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_create(seed: i64, radius: i32) -> *mut WorldStreamer {
    if !(1..=32).contains(&radius) {
        return std::ptr::null_mut();
    }
    let engine = match TerrainEngine::overworld(seed) {
        Some(e) => e,
        None => return std::ptr::null_mut(),
    };
    let boxed = Box::new(WorldStreamer {
        engine,
        scheduler: StreamScheduler::new(radius),
        scratch: Vec::new(),
        last_error: [0u8; 128],
    });
    Box::into_raw(boxed)
}

/// Frees a streamer created by [`jpe_world_create`]. Null is a no-op.
///
/// # Safety
///
/// `handle` must come from `jpe_world_create` and must not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_destroy(handle: *mut WorldStreamer) {
    if handle.is_null() {
        return;
    }
    drop(Box::from_raw(handle));
}

/// Re-centres the queue on `(chunk_x, chunk_z)`, nearest column first.
///
/// Returns the number of columns queued, or a negative error code.
///
/// # Safety
///
/// `handle` must be a live pointer from [`jpe_world_create`].
#[no_mangle]
pub unsafe extern "C" fn jpe_world_retarget(
    handle: *mut WorldStreamer,
    chunk_x: i32,
    chunk_z: i32,
) -> i32 {
    let s = match handle.as_mut() {
        Some(s) => s,
        None => return JPE_WORLD_ERR_NULL,
    };
    s.clear_error();
    s.scheduler.retarget(chunk_x, chunk_z);
    s.scheduler.remaining() as i32
}

/// Generates columns until `budget_us` elapses or the queue drains.
///
/// Writes `out_generated`, `out_remaining` and `out_elapsed_us` when non-null,
/// and returns the number generated (never negative on a live handle).
///
/// A zero budget still generates exactly one column: the budget is a floor on
/// progress, not a licence to stall. See `StreamScheduler::pump` for why that
/// distinction decides whether a loaded frame can ever finish loading the world.
///
/// # Safety
///
/// `handle` must be live; the three out-pointers, when non-null, must be valid
/// for writes of the stated width.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_pump(
    handle: *mut WorldStreamer,
    budget_us: u64,
    out_generated: *mut u32,
    out_remaining: *mut u32,
    out_elapsed_us: *mut u64,
) -> i32 {
    let s = match handle.as_mut() {
        Some(s) => s,
        None => return JPE_WORLD_ERR_NULL,
    };
    s.clear_error();

    let stats = s
        .scheduler
        .pump(&s.engine, Duration::from_micros(budget_us), &mut s.scratch);

    if !out_generated.is_null() {
        *out_generated = stats.generated;
    }
    if !out_remaining.is_null() {
        *out_remaining = stats.remaining;
    }
    if !out_elapsed_us.is_null() {
        *out_elapsed_us = stats.elapsed_us;
    }
    stats.generated as i32
}

/// Pumps until the queue drains or `max_frames` is reached, then reports the
/// total generated. This is the loading-screen entry point: blocking is
/// acceptable there, an unbounded loop is not.
///
/// # Safety
///
/// `handle` must be live.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_drain(
    handle: *mut WorldStreamer,
    budget_us: u64,
    max_frames: u32,
) -> i32 {
    let s = match handle.as_mut() {
        Some(s) => s,
        None => return JPE_WORLD_ERR_NULL,
    };
    s.clear_error();
    let total = s.scheduler.drain(
        &s.engine,
        Duration::from_micros(budget_us),
        max_frames,
        &mut s.scratch,
    );
    total as i32
}

/// Columns still queued. `0` means the current view is fully generated.
///
/// # Safety
///
/// `handle` must be live.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_remaining(handle: *const WorldStreamer) -> i32 {
    match handle.as_ref() {
        Some(s) => s.scheduler.remaining() as i32,
        None => JPE_WORLD_ERR_NULL,
    }
}

/// Caps the work per pump so a fast machine cannot monopolise a frame.
/// Returns the clamped value, which is what the scheduler now uses.
///
/// # Safety
///
/// `handle` must be live.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_set_max_columns_per_pump(
    handle: *mut WorldStreamer,
    n: u32,
) -> i32 {
    let s = match handle.as_mut() {
        Some(s) => s,
        None => return JPE_WORLD_ERR_NULL,
    };
    s.scheduler.set_max_columns_per_pump(n) as i32
}

/// Sets the C++ cache budget in bytes. Returns 0 on success.
///
/// `bytes == 0` is accepted and disables the cache -- that is the C++ contract
/// (`ChunkCache::set_budget` keeps `capacity_ = 0` and clears), not an error, so
/// it is passed straight through instead of being second-guessed here.
///
/// # Safety
///
/// `handle` must be live.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_set_cache_budget(handle: *mut WorldStreamer, bytes: u64) -> i32 {
    let s = match handle.as_mut() {
        Some(s) => s,
        None => return JPE_WORLD_ERR_NULL,
    };
    s.clear_error();
    match s.engine.set_budget(bytes) {
        Ok(()) => 0,
        Err(_) => {
            s.set_error("set_budget rejected by the C++ cache");
            JPE_WORLD_ERR_CACHE
        }
    }
}

/// Copies the current view centre into `out_x` / `out_z`. Returns 0.
///
/// # Safety
///
/// `handle` must be live; the out-pointers, when non-null, must be writable.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_centre(
    handle: *const WorldStreamer,
    out_x: *mut i32,
    out_z: *mut i32,
) -> i32 {
    let s = match handle.as_ref() {
        Some(s) => s,
        None => return JPE_WORLD_ERR_NULL,
    };
    let (x, z) = s.scheduler.centre();
    if !out_x.is_null() {
        *out_x = x;
    }
    if !out_z.is_null() {
        *out_z = z;
    }
    0
}

/// Cache occupancy in bytes.
///
/// Reports the *configured* budget as capacity rather than deriving it from the
/// chunk count: the C++ side stores both, and recomputing one from the other is
/// how a caller ends up disagreeing with the cache about its own size.
///
/// Returns 0 on success, or a negative error code.
///
/// # Safety
///
/// `handle` must be live; the out-pointers, when non-null, must be writable.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_cache_stats(
    handle: *const WorldStreamer,
    out_resident_bytes: *mut u64,
    out_capacity_bytes: *mut u64,
) -> i32 {
    let s = match handle.as_ref() {
        Some(s) => s,
        None => return JPE_WORLD_ERR_NULL,
    };
    match s.engine.stats() {
        Some(stats) => {
            if !out_resident_bytes.is_null() {
                *out_resident_bytes = stats.resident_bytes();
            }
            if !out_capacity_bytes.is_null() {
                *out_capacity_bytes = stats.budget_bytes;
            }
            0
        }
        None => JPE_WORLD_ERR_CACHE,
    }
}

/// The last error message on this handle, as a NUL-terminated string, or an
/// empty string when the last call succeeded. The pointer stays valid until the
/// next `jpe_world_*` call on the same handle.
///
/// # Safety
///
/// `handle` must be live for as long as the returned pointer is used.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_last_error(handle: *const WorldStreamer) -> *const c_char {
    match handle.as_ref() {
        Some(s) => s.last_error.as_ptr() as *const c_char,
        None => std::ptr::null(),
    }
}

/// The block count of one column, so Java can size its own buffer instead of
/// hardcoding 192 KiB. Returns 0 on failure.
///
/// # Safety
///
/// `handle` must be live.
#[no_mangle]
pub unsafe extern "C" fn jpe_world_chunk_blocks(handle: *const WorldStreamer) -> u64 {
    match handle.as_ref() {
        Some(s) => s.engine.chunk_blocks(),
        None => 0,
    }
}

#[cfg(test)]
mod world_abi_tests {
    use super::*;
    use std::ffi::CStr;

    fn make(seed: i64, radius: i32) -> *mut WorldStreamer {
        let h = unsafe { jpe_world_create(seed, radius) };
        assert!(!h.is_null(), "create returned null");
        h
    }

    #[test]
    fn create_starts_idle_and_reports_the_column_size() {
        let h = make(0, 1);
        unsafe {
            assert_eq!(
                jpe_world_remaining(h),
                0,
                "a fresh scheduler must not queue anything before a retarget"
            );
            assert_eq!(
                jpe_world_chunk_blocks(h),
                crate::terrain_ffi::CHUNK_BLOCKS_DEFAULT_HEIGHT
            );
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn a_bad_radius_is_rejected_instead_of_clamped() {
        unsafe {
            assert!(jpe_world_create(0, 0).is_null());
            assert!(jpe_world_create(0, 33).is_null());
            assert!(jpe_world_create(0, -1).is_null());
        }
    }

    #[test]
    fn null_handles_are_rejected_not_dereferenced() {
        unsafe {
            assert_eq!(jpe_world_remaining(std::ptr::null()), JPE_WORLD_ERR_NULL);
            assert_eq!(
                jpe_world_pump(
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    std::ptr::null_mut()
                ),
                JPE_WORLD_ERR_NULL
            );
            assert_eq!(jpe_world_retarget(std::ptr::null_mut(), 0, 0), JPE_WORLD_ERR_NULL);
            assert_eq!(jpe_world_drain(std::ptr::null_mut(), 0, 1), JPE_WORLD_ERR_NULL);
            assert_eq!(
                jpe_world_set_cache_budget(std::ptr::null_mut(), 1),
                JPE_WORLD_ERR_NULL
            );
            assert_eq!(
                jpe_world_set_max_columns_per_pump(std::ptr::null_mut(), 1),
                JPE_WORLD_ERR_NULL
            );
            assert_eq!(jpe_world_chunk_blocks(std::ptr::null()), 0);
            assert!(jpe_world_last_error(std::ptr::null()).is_null());
            // destroy(null) must be a no-op rather than UB.
            jpe_world_destroy(std::ptr::null_mut());
        }
    }

    #[test]
    fn retarget_rebuilds_the_queue_and_reports_it() {
        let h = make(0, 2);
        unsafe {
            let n = jpe_world_retarget(h, 7, -3);
            assert_eq!(n, 13, "radius 2 must queue 13 columns");
            let mut x = 0i32;
            let mut z = 0i32;
            assert_eq!(jpe_world_centre(h, &mut x, &mut z), 0);
            assert_eq!((x, z), (7, -3));
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn retargeting_again_discards_the_old_view() {
        // Moving the view must not leave the previous window's columns in the
        // queue, or a long walk would accumulate work instead of replacing it.
        let h = make(0, 1);
        unsafe {
            assert_eq!(jpe_world_retarget(h, 0, 0), 5);
            assert_eq!(jpe_world_retarget(h, 100, 100), 5);
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn a_zero_budget_still_makes_progress() {
        // The whole point of checking the budget after the first column: a
        // frame that reports no time must still generate something, or the
        // world never finishes loading under load.
        let h = make(0, 1);
        unsafe {
            jpe_world_retarget(h, 0, 0);
            let mut generated = 0u32;
            let mut remaining = 0u32;
            let mut elapsed = 0u64;
            let rc = jpe_world_pump(h, 0, &mut generated, &mut remaining, &mut elapsed);
            assert_eq!(rc, 1, "a zero budget must generate exactly one column");
            assert_eq!(generated, 1);
            assert_eq!(remaining, 4);
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn a_pump_respects_the_per_frame_cap() {
        let h = make(0, 4);
        unsafe {
            assert_eq!(jpe_world_set_max_columns_per_pump(h, 3), 3);
            jpe_world_retarget(h, 0, 0);
            let mut generated = 0u32;
            let rc = jpe_world_pump(
                h,
                1_000_000,
                &mut generated,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
            assert_eq!(rc, 3, "the cap must win even with a generous budget");
            assert_eq!(generated, 3);
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn the_cap_is_clamped_not_trusted() {
        let h = make(0, 1);
        unsafe {
            assert_eq!(jpe_world_set_max_columns_per_pump(h, 0), 1);
            assert_eq!(jpe_world_set_max_columns_per_pump(h, u32::MAX), 4096);
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn drain_empties_the_queue_within_its_frame_budget() {
        let h = make(0, 2);
        unsafe {
            jpe_world_retarget(h, 0, 0);
            let total = jpe_world_drain(h, 1_000_000, 100);
            assert_eq!(total, 13, "drain must generate every queued column");
            assert_eq!(jpe_world_remaining(h), 0);
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn drain_honours_max_frames() {
        let h = make(0, 3);
        unsafe {
            jpe_world_set_max_columns_per_pump(h, 1);
            jpe_world_retarget(h, 0, 0);
            let total = jpe_world_drain(h, 1_000_000, 4);
            assert_eq!(total, 4, "one column per frame for four frames");
            assert_eq!(jpe_world_remaining(h), 29 - 4);
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn a_cache_budget_is_reported_back_verbatim() {
        let h = make(0, 1);
        unsafe {
            let budget = 64u64 * 1024 * 1024;
            assert_eq!(jpe_world_set_cache_budget(h, budget), 0);
            let mut resident = u64::MAX;
            let mut capacity = 0u64;
            assert_eq!(jpe_world_cache_stats(h, &mut resident, &mut capacity), 0);
            assert_eq!(capacity, budget, "capacity must be the configured budget");
            assert!(resident <= capacity, "resident must fit inside capacity");
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn a_zero_budget_disables_the_cache_without_erroring() {
        // Verified against ChunkCache::set_budget: bytes_per_chunk == 0 is the
        // "no cache" signal, and a budget below one chunk leaves capacity_ at
        // zero instead of thrashing. Neither is a failure, so the ABI must not
        // pretend it is. Only the two facts the ABI promises are asserted here:
        // the call succeeds, and the cache ends up holding nothing.
        let h = make(0, 1);
        unsafe {
            assert_eq!(jpe_world_set_cache_budget(h, 0), 0);
            let mut resident = u64::MAX;
            assert_eq!(
                jpe_world_cache_stats(h, &mut resident, std::ptr::null_mut()),
                0
            );
            assert_eq!(resident, 0, "a disabled cache holds nothing");
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn a_successful_call_clears_the_previous_error() {
        let h = make(0, 1);
        unsafe {
            // No public call can fail with a live handle today, so drive the
            // channel directly: the contract under test is that success wipes
            // the stale message, not that some particular call fails.
            (*h).set_error("stale failure");
            assert!(!CStr::from_ptr(jpe_world_last_error(h)).to_bytes().is_empty());
            jpe_world_retarget(h, 0, 0);
            assert!(
                CStr::from_ptr(jpe_world_last_error(h)).to_bytes().is_empty(),
                "a successful call must clear the stale error"
            );
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn out_pointers_may_be_null() {
        let h = make(0, 1);
        unsafe {
            jpe_world_retarget(h, 0, 0);
            let rc = jpe_world_pump(
                h,
                1_000_000,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
            assert!(rc >= 1);
            assert_eq!(
                jpe_world_centre(h, std::ptr::null_mut(), std::ptr::null_mut()),
                0
            );
            assert_eq!(
                jpe_world_cache_stats(h, std::ptr::null_mut(), std::ptr::null_mut()),
                0
            );
            jpe_world_destroy(h);
        }
    }

    #[test]
    fn streaming_a_full_view_terminates() {
        // The guarantee that matters: repeated pumps reach zero remaining. If
        // the budget logic ever regressed to "generate nothing", this loop
        // would spin to the guard instead of finishing.
        let h = make(0, 3);
        unsafe {
            jpe_world_set_max_columns_per_pump(h, 8);
            jpe_world_retarget(h, 0, 0);
            let mut frames = 0u32;
            while jpe_world_remaining(h) > 0 {
                jpe_world_pump(
                    h,
                    0,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                );
                frames += 1;
                assert!(frames < 1000, "streaming did not terminate");
            }
            assert_eq!(jpe_world_remaining(h), 0);
            assert_eq!(frames, 4, "29 columns at 8 per frame is four pumps");
            jpe_world_destroy(h);
        }
    }
}