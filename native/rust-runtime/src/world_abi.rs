//! C ABI for the frame-budgeted world streamer.
//!
//! The Java side never touches Rust types: it gets an opaque `*mut
//! WorldStreamer`, calls `jpe_stream_*` functions on it, and hands it back to
//! `jpe_stream_destroy`. Everything below that is Rust.
//!
//! Why a separate handle instead of exposing `TerrainEngine` directly: a
//! streamer is *stateful across frames* (queue + centre + scratch buffer), and
//! that state has to live somewhere between two `jpe_stream_pump` calls. Bundling
//! it here means Java holds one pointer and one integer, not five.
//!
//! # Threading
//!
//! Every `jpe_stream_*` function is `&mut`-equivalent: the caller must not call
//! two of them on the same handle concurrently. The handle is `Send` (the
//! engine already is), so it may be moved between threads, just not shared.
//!
//! # Namespace
//!
//! These symbols are `jpe_stream_*`, deliberately not `jpe_world_*`. The
//! `jpe_world_*` namespace belongs to `libjpe_world` (`native/rust-world`),
//! which the Java side already binds through FFM, and it exports 33 functions
//! there -- including `jpe_world_create` and `jpe_world_destroy`. Both crates
//! produce a `cdylib`, so two same-named exported symbols in one process is a
//! collision waiting to happen, and the world engine has the prior claim.
//!
//! This module is transitional: the scheduler and section model below are
//! meant to be folded into `native/rust-world` so the C++ generator and the
//! frame budget live in one shared object with no boundary between them.
//! Until that happens it stays out of the way of the namespace it is joining.
//!
//! # Errors
//!
//! Functions that can fail return a negative `i32`; `jpe_stream_last_error`
//! describes the most recent failure on that handle and is cleared by the next
//! successful call. A negative return from a "count" function therefore means
//! "failed", never "minus one column".
//!
//! The error channel is small. Two operations can fail with a live handle:
//! `set_cache_budget`, when the C++ cache refuses the configuration, and
//! `cache_stats`, when the cache is disabled -- `ChunkCache::enabled()` is
//! `capacity_ > 0`, so a disabled cache has no stats to report rather than
//! reporting zeros. A zero-byte budget lands in the second case: it is accepted
//! (capacity_ = 0, cache cleared) and then every later `cache_stats` fails.

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
/// empty -- call [`jpe_stream_retarget`] to fill it.
///
/// Returns null on failure.
///
/// # Safety
///
/// The returned pointer must be passed to [`jpe_stream_destroy`] exactly once.
#[no_mangle]
pub unsafe extern "C" fn jpe_stream_create(seed: i64, radius: i32) -> *mut WorldStreamer {
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

/// Frees a streamer created by [`jpe_stream_create`]. Null is a no-op.
///
/// # Safety
///
/// `handle` must come from `jpe_stream_create` and must not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn jpe_stream_destroy(handle: *mut WorldStreamer) {
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
/// `handle` must be a live pointer from [`jpe_stream_create`].
#[no_mangle]
pub unsafe extern "C" fn jpe_stream_retarget(
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
pub unsafe extern "C" fn jpe_stream_pump(
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
pub unsafe extern "C" fn jpe_stream_drain(
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
pub unsafe extern "C" fn jpe_stream_remaining(handle: *const WorldStreamer) -> i32 {
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
pub unsafe extern "C" fn jpe_stream_set_max_columns_per_pump(
    handle: *mut WorldStreamer,
    n: u32,
) -> i32 {
    let s = match handle.as_mut() {
        Some(s) => s,
        None => return JPE_WORLD_ERR_NULL,
    };
    s.scheduler.set_max_columns_per_pump(n) as i32
}

/// Sets the C++ cache budget in bytes.
///
/// `bytes` is rounded down to a whole number of columns by the cache, so
/// `jpe_stream_cache_stats` will report a capacity at or below this number, not
/// this number itself.
///
/// Returns 0 on success. A budget smaller than one column leaves the cache
/// disabled; see `jpe_stream_cache_stats` for how that shows up.
///
/// # Safety
///
/// `handle` must be live.
#[no_mangle]
pub unsafe extern "C" fn jpe_stream_set_cache_budget(handle: *mut WorldStreamer, bytes: u64) -> i32 {
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
pub unsafe extern "C" fn jpe_stream_centre(
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
/// how a caller ends up disagreeing with the cache about its own size. Note the
/// value is the budget floored to whole columns, not the number passed to
/// `jpe_stream_set_cache_budget`.
///
/// Returns 0 on success. Returns an error when the cache is disabled, because
/// `enabled()` is `capacity_ > 0` and a disabled cache has nothing to report --
/// zeros here would be indistinguishable from "configured but empty".
///
/// # Safety
///
/// `handle` must be live; the out-pointers, when non-null, must be writable.
#[no_mangle]
pub unsafe extern "C" fn jpe_stream_cache_stats(
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
/// next `jpe_stream_*` call on the same handle.
///
/// # Safety
///
/// `handle` must be live for as long as the returned pointer is used.
#[no_mangle]
pub unsafe extern "C" fn jpe_stream_last_error(handle: *const WorldStreamer) -> *const c_char {
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
pub unsafe extern "C" fn jpe_stream_chunk_blocks(handle: *const WorldStreamer) -> u64 {
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
        let h = unsafe { jpe_stream_create(seed, radius) };
        assert!(!h.is_null(), "create returned null");
        h
    }

    #[test]
    fn create_starts_idle_and_reports_the_column_size() {
        let h = make(0, 1);
        unsafe {
            assert_eq!(
                jpe_stream_remaining(h),
                0,
                "a fresh scheduler must not queue anything before a retarget"
            );
            assert_eq!(
                jpe_stream_chunk_blocks(h),
                crate::terrain_ffi::CHUNK_BLOCKS_DEFAULT_HEIGHT
            );
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn a_bad_radius_is_rejected_instead_of_clamped() {
        unsafe {
            assert!(jpe_stream_create(0, 0).is_null());
            assert!(jpe_stream_create(0, 33).is_null());
            assert!(jpe_stream_create(0, -1).is_null());
        }
    }

    #[test]
    fn null_handles_are_rejected_not_dereferenced() {
        unsafe {
            assert_eq!(jpe_stream_remaining(std::ptr::null()), JPE_WORLD_ERR_NULL);
            assert_eq!(
                jpe_stream_pump(
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    std::ptr::null_mut()
                ),
                JPE_WORLD_ERR_NULL
            );
            assert_eq!(jpe_stream_retarget(std::ptr::null_mut(), 0, 0), JPE_WORLD_ERR_NULL);
            assert_eq!(jpe_stream_drain(std::ptr::null_mut(), 0, 1), JPE_WORLD_ERR_NULL);
            assert_eq!(
                jpe_stream_set_cache_budget(std::ptr::null_mut(), 1),
                JPE_WORLD_ERR_NULL
            );
            assert_eq!(
                jpe_stream_set_max_columns_per_pump(std::ptr::null_mut(), 1),
                JPE_WORLD_ERR_NULL
            );
            assert_eq!(jpe_stream_chunk_blocks(std::ptr::null()), 0);
            assert!(jpe_stream_last_error(std::ptr::null()).is_null());
            // destroy(null) must be a no-op rather than UB.
            jpe_stream_destroy(std::ptr::null_mut());
        }
    }

    #[test]
    fn retarget_rebuilds_the_queue_and_reports_it() {
        let h = make(0, 2);
        unsafe {
            let n = jpe_stream_retarget(h, 7, -3);
            assert_eq!(n, 13, "radius 2 must queue 13 columns");
            let mut x = 0i32;
            let mut z = 0i32;
            assert_eq!(jpe_stream_centre(h, &mut x, &mut z), 0);
            assert_eq!((x, z), (7, -3));
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn retargeting_again_discards_the_old_view() {
        // Moving the view must not leave the previous window's columns in the
        // queue, or a long walk would accumulate work instead of replacing it.
        let h = make(0, 1);
        unsafe {
            assert_eq!(jpe_stream_retarget(h, 0, 0), 5);
            assert_eq!(jpe_stream_retarget(h, 100, 100), 5);
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn a_zero_budget_still_makes_progress() {
        // The whole point of checking the budget after the first column: a
        // frame that reports no time must still generate something, or the
        // world never finishes loading under load.
        let h = make(0, 1);
        unsafe {
            jpe_stream_retarget(h, 0, 0);
            let mut generated = 0u32;
            let mut remaining = 0u32;
            let mut elapsed = 0u64;
            let rc = jpe_stream_pump(h, 0, &mut generated, &mut remaining, &mut elapsed);
            assert_eq!(rc, 1, "a zero budget must generate exactly one column");
            assert_eq!(generated, 1);
            assert_eq!(remaining, 4);
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn a_pump_respects_the_per_frame_cap() {
        let h = make(0, 4);
        unsafe {
            assert_eq!(jpe_stream_set_max_columns_per_pump(h, 3), 3);
            jpe_stream_retarget(h, 0, 0);
            let mut generated = 0u32;
            let rc = jpe_stream_pump(
                h,
                1_000_000,
                &mut generated,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
            assert_eq!(rc, 3, "the cap must win even with a generous budget");
            assert_eq!(generated, 3);
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn the_cap_is_clamped_not_trusted() {
        let h = make(0, 1);
        unsafe {
            assert_eq!(jpe_stream_set_max_columns_per_pump(h, 0), 1);
            assert_eq!(jpe_stream_set_max_columns_per_pump(h, u32::MAX), 4096);
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn drain_empties_the_queue_within_its_frame_budget() {
        let h = make(0, 2);
        unsafe {
            jpe_stream_retarget(h, 0, 0);
            let total = jpe_stream_drain(h, 1_000_000, 100);
            assert_eq!(total, 13, "drain must generate every queued column");
            assert_eq!(jpe_stream_remaining(h), 0);
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn drain_honours_max_frames() {
        let h = make(0, 3);
        unsafe {
            jpe_stream_set_max_columns_per_pump(h, 1);
            jpe_stream_retarget(h, 0, 0);
            let total = jpe_stream_drain(h, 1_000_000, 4);
            assert_eq!(total, 4, "one column per frame for four frames");
            assert_eq!(jpe_stream_remaining(h), 29 - 4);
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn a_cache_budget_is_rounded_down_to_whole_chunks() {
        // C++ stores a chunk count, not a byte count: capacity_ = bytes /
        // bytes_per_chunk, and budget_bytes is that count multiplied back out.
        // So the reported capacity is the budget rounded *down* to a whole
        // number of columns, never the number the caller typed. Asserting the
        // typed number would be asserting a lie.
        let h = make(0, 1);
        unsafe {
            let budget = 64u64 * 1024 * 1024;
            let chunk = jpe_stream_chunk_blocks(h) * 2; // u16 per block
            assert_eq!(jpe_stream_set_cache_budget(h, budget), 0);

            let mut resident = u64::MAX;
            let mut capacity = 0u64;
            assert_eq!(jpe_stream_cache_stats(h, &mut resident, &mut capacity), 0);
            assert!(capacity <= budget, "capacity must not exceed the budget");
            assert!(
                budget - capacity < chunk,
                "capacity must be the budget floored to whole columns, not some other number"
            );
            assert_eq!(
                capacity % chunk,
                0,
                "capacity must be a whole number of columns"
            );
            assert!(resident <= capacity, "resident must fit inside capacity");
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn a_zero_budget_leaves_the_cache_disabled() {
        // ChunkCache::set_budget leaves capacity_ at 0 for a sub-column budget
        // and clear()s, and enabled() is exactly `capacity_ > 0`. A disabled
        // cache therefore *reports* through a failed stats call rather than a
        // zero reading -- so "is it disabled" is answered by the error, and the
        // ABI must pass that through instead of inventing a fake zero.
        let h = make(0, 1);
        unsafe {
            // Whatever set_budget returns for zero, the observable contract is
            // that the cache is not enabled afterwards.
            let _ = jpe_stream_set_cache_budget(h, 0);
            let mut resident = u64::MAX;
            assert_eq!(
                jpe_stream_cache_stats(h, &mut resident, std::ptr::null_mut()),
                JPE_WORLD_ERR_CACHE,
                "a disabled cache has no stats to report"
            );
            jpe_stream_destroy(h);
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
            assert!(!CStr::from_ptr(jpe_stream_last_error(h)).to_bytes().is_empty());
            jpe_stream_retarget(h, 0, 0);
            assert!(
                CStr::from_ptr(jpe_stream_last_error(h)).to_bytes().is_empty(),
                "a successful call must clear the stale error"
            );
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn out_pointers_may_be_null() {
        let h = make(0, 1);
        unsafe {
            jpe_stream_retarget(h, 0, 0);
            let rc = jpe_stream_pump(
                h,
                1_000_000,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
            assert!(rc >= 1);
            assert_eq!(
                jpe_stream_centre(h, std::ptr::null_mut(), std::ptr::null_mut()),
                0
            );
            jpe_stream_destroy(h);
        }
    }

    #[test]
    fn streaming_a_full_view_terminates() {
        // The guarantee that matters: repeated zero-budget pumps still reach
        // zero remaining. A zero budget generates exactly one column per pump
        // by design, so a 29-column view needs exactly 29 pumps -- and if the
        // budget check ever regressed to "generate nothing", this loop would
        // hit the guard instead of finishing.
        let h = make(0, 3);
        unsafe {
            jpe_stream_retarget(h, 0, 0);
            let queued = jpe_stream_remaining(h);
            assert_eq!(queued, 29, "radius 3 must queue 29 columns");

            let mut frames = 0u32;
            while jpe_stream_remaining(h) > 0 {
                let generated = jpe_stream_pump(
                    h,
                    0,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                );
                assert_eq!(generated, 1, "a zero budget must generate exactly one column");
                frames += 1;
                assert!(frames < 1000, "streaming did not terminate");
            }
            assert_eq!(frames, queued as u32, "one column per pump, no stalls");
            jpe_stream_destroy(h);
        }
    }
}