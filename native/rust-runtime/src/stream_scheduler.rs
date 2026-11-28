//! Frame-budgeted world streaming: continuous loading that never stalls a frame.
//!
//! The generator can produce a column in one call, but a radius-20 window is
//! 1257 columns. Doing that in one frame is a multi-second hitch. This module
//! turns "load the world" into "make progress every frame, finish in bounded
//! time", which is the difference between a loading screen and a playable game.
//!
//! ## The contract
//!
//! `pump` is called once per frame with a wall-clock budget in microseconds.
//! It generates as many columns as fit in the budget, then returns. It never
//! blocks past the budget by more than one column's cost -- there is no
//! preemption inside `generate_chunk`, so the budget is a floor on politeness,
//! not a hard cap. `max_columns_per_pump` bounds that overshoot.
//!
//! ## Why a ring order and not row-major
//!
//! Columns are queued nearest-first, so the terrain the player is standing on
//! appears before the terrain at the edge of view. Row-major would fill the
//! top-left corner first, which is the one place nobody is looking.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

use crate::terrain_ffi::TerrainEngine;

/// A column waiting to be generated.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PendingColumn {
    pub x: i32,
    pub z: i32,
    /// Squared distance from the view centre, used for ordering.
    pub dist_sq: i32,
}

/// One frame's worth of streaming work.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PumpStats {
    /// Columns generated this frame.
    pub generated: u32,
    /// Columns still queued after this frame.
    pub remaining: u32,
    /// Wall time actually spent, in microseconds.
    pub elapsed_us: u64,
    /// True when the budget ran out before the queue did.
    pub budget_hit: bool,
}

/// Streams a window of columns into the generator, a few per frame.
pub struct StreamScheduler {
    /// Queued columns, nearest first.
    queue: VecDeque<PendingColumn>,
    /// The view the queue was built for.
    centre: (i32, i32),
    radius: i32,
    /// Hard ceiling on work per pump, so a fast machine cannot monopolise a
    /// frame just because it fits the time budget.
    max_columns_per_pump: u32,
}

impl StreamScheduler {
    /// Builds a scheduler for a radius, with the queue empty.
    pub fn new(radius: i32) -> Self {
        StreamScheduler {
            queue: VecDeque::new(),
            centre: (0, 0),
            radius: radius.max(0),
            max_columns_per_pump: 16,
        }
    }

    /// Caps how many columns one `pump` may generate.
    pub fn set_max_columns_per_pump(&mut self, n: u32) -> u32 {
        self.max_columns_per_pump = n.clamp(1, 4096);
        self.max_columns_per_pump
    }

    /// The view this scheduler is filling.
    pub fn centre(&self) -> (i32, i32) {
        self.centre
    }

    /// Columns still waiting.
    pub fn remaining(&self) -> u32 {
        self.queue.len() as u32
    }

    /// True when every column of the current view has been generated.
    pub fn is_idle(&self) -> bool {
        self.queue.is_empty()
    }

    /// Re-targets the view and rebuilds the queue, nearest first.
    ///
    /// Already-generated columns are not tracked here: the generator's own
    /// cache answers that, and a redundant `generate_chunk` costs one lookup
    /// plus a memcpy. Tracking it twice would mean two sources of truth that
    /// can disagree, which is exactly the class of bug this codebase keeps
    /// having to dig out of.
    pub fn retarget(&mut self, centre_x: i32, centre_z: i32) {
        self.centre = (centre_x, centre_z);
        self.queue.clear();

        let radius = self.radius;
        let mut pending: Vec<PendingColumn> = Vec::new();
        for dz in -radius..=radius {
            for dx in -radius..=radius {
                let dist_sq = dx * dx + dz * dz;
                if dist_sq > radius * radius {
                    continue;
                }
                pending.push(PendingColumn {
                    x: centre_x + dx,
                    z: centre_z + dz,
                    dist_sq,
                });
            }
        }
        // Nearest first. Ties broken by x then z so the order is deterministic
        // and a test can assert it without sorting the result first.
        pending.sort_unstable_by(|a, b| {
            a.dist_sq
                .cmp(&b.dist_sq)
                .then(a.x.cmp(&b.x))
                .then(a.z.cmp(&b.z))
        });
        self.queue.extend(pending);
    }

    /// Generates up to the budget's worth of columns.
    ///
    /// `scratch` is reused across frames to avoid a 192 KiB allocation per
    /// column; it is resized here if the caller passed something short.
    pub fn pump(
        &mut self,
        engine: &TerrainEngine,
        budget: Duration,
        scratch: &mut Vec<u16>,
    ) -> PumpStats {
        let need = engine.chunk_blocks() as usize;
        if scratch.len() < need {
            scratch.resize(need, 0);
        }

        let start = Instant::now();
        let mut generated = 0u32;

        // The budget is checked *after* each column, not before the first one.
        // Checking first would make a zero budget generate nothing, and a
        // frame under heavy load reports exactly that -- the world would then
        // never load at all. Making one column of progress per pump is the
        // guarantee that streaming always terminates.
        while let Some(col) = self.queue.front().copied() {
            if generated >= self.max_columns_per_pump {
                break;
            }
            // Pop before generating: a failed column is dropped rather than
            // retried forever. A generator that cannot produce a column will
            // not start producing it on the next frame.
            self.queue.pop_front();
            if engine.generate_chunk(col.x, col.z, scratch).is_ok() {
                generated += 1;
            }
            if start.elapsed() >= budget {
                break;
            }
        }

        let elapsed = start.elapsed();
        PumpStats {
            generated,
            remaining: self.queue.len() as u32,
            elapsed_us: elapsed.as_micros() as u64,
            budget_hit: !self.queue.is_empty() && elapsed >= budget,
        }
    }

    /// Pumps until the queue drains or `max_frames` is reached.
    ///
    /// Returns the total columns generated. This is the "load the whole view
    /// now" entry point for a loading screen, where blocking is acceptable but
    /// an unbounded loop is not.
    pub fn drain(
        &mut self,
        engine: &TerrainEngine,
        budget_per_frame: Duration,
        max_frames: u32,
        scratch: &mut Vec<u16>,
    ) -> u32 {
        let mut total = 0u32;
        for _ in 0..max_frames {
            if self.is_idle() {
                break;
            }
            total += self.pump(engine, budget_per_frame, scratch).generated;
        }
        total
    }
}

#[cfg(test)]
mod stream_scheduler_tests {
    use super::*;
    use crate::terrain_ffi::TerrainConfig;

    fn engine() -> TerrainEngine {
        let mut e = TerrainEngine::create(&TerrainConfig::overworld(3)).expect("engine");
        // Small budget: the cache may evict freely, the scheduler does not care.
        e.set_budget(16 * 1024 * 1024).expect("budget");
        e
    }

    /// The queue must cover exactly the disc the generator fills.
    #[test]
    fn the_queue_matches_the_disc() {
        for radius in [0i32, 1, 4, 8] {
            let mut s = StreamScheduler::new(radius);
            s.retarget(0, 0);
            let mut expected = 0u32;
            for dz in -radius..=radius {
                for dx in -radius..=radius {
                    if dx * dx + dz * dz <= radius * radius {
                        expected += 1;
                    }
                }
            }
            assert_eq!(
                s.remaining(),
                expected,
                "radius {} queued {} columns, disc has {}",
                radius,
                s.remaining(),
                expected
            );
        }
    }

    /// Nearest-first: the centre column is generated before the rim.
    #[test]
    fn the_centre_is_generated_first() {
        let e = engine();
        let mut s = StreamScheduler::new(4);
        s.retarget(10, -7);
        s.set_max_columns_per_pump(1);

        let mut scratch = Vec::new();
        let stats = s.pump(&e, Duration::from_secs(10), &mut scratch);
        assert_eq!(stats.generated, 1);
        // One column was consumed, and it was the centre.
        assert_eq!(s.remaining() as usize, 49 - 1);
        // Retargeting to the same place rebuilds the same queue, so the first
        // entry is still the centre; check that directly.
        let mut s2 = StreamScheduler::new(4);
        s2.retarget(10, -7);
        let first = *s2.queue.front().expect("queue");
        assert_eq!((first.x, first.z), (10, -7));
        assert_eq!(first.dist_sq, 0);
    }

    /// A zero budget still makes progress: one column, then stop.
    ///
    /// This is the anti-stall guarantee. If a zero budget generated nothing,
    /// a frame under load would never load anything and the world would stay
    /// empty forever.
    #[test]
    fn a_zero_budget_still_makes_progress() {
        let e = engine();
        let mut s = StreamScheduler::new(4);
        s.retarget(0, 0);
        let mut scratch = Vec::new();

        let stats = s.pump(&e, Duration::ZERO, &mut scratch);
        assert!(
            stats.generated >= 1,
            "a zero budget generated nothing, so the world would never load"
        );
        assert!(stats.budget_hit);
        assert_eq!(stats.remaining as usize + stats.generated as usize, 49);
    }

    /// The per-pump cap holds regardless of how generous the budget is.
    #[test]
    fn the_per_pump_cap_holds() {
        let e = engine();
        let mut s = StreamScheduler::new(4);
        s.retarget(0, 0);
        s.set_max_columns_per_pump(3);
        let mut scratch = Vec::new();

        let stats = s.pump(&e, Duration::from_secs(30), &mut scratch);
        assert_eq!(stats.generated, 3, "cap of 3 was exceeded");
        assert_eq!(stats.remaining, 49 - 3);
    }

    /// Draining a whole view terminates and generates every column.
    #[test]
    fn draining_covers_the_whole_view() {
        let e = engine();
        let mut s = StreamScheduler::new(2);
        s.retarget(0, 0);
        let mut scratch = Vec::new();

        let total = s.drain(&e, Duration::from_millis(5), 64, &mut scratch);
        assert_eq!(total, 13, "radius 2 is 13 columns");
        assert!(s.is_idle(), "queue not drained");
    }

    /// Drain respects its frame limit instead of running away.
    #[test]
    fn drain_respects_the_frame_limit() {
        let e = engine();
        let mut s = StreamScheduler::new(6);
        s.retarget(0, 0);
        s.set_max_columns_per_pump(1);
        let mut scratch = Vec::new();

        let total = s.drain(&e, Duration::from_secs(5), 5, &mut scratch);
        assert_eq!(total, 5, "drain ignored its frame limit");
        assert!(!s.is_idle(), "queue should still have work");
    }

    /// Retargeting rebuilds the queue from scratch rather than appending.
    #[test]
    fn retarget_replaces_the_queue() {
        let e = engine();
        let mut s = StreamScheduler::new(3);
        s.retarget(0, 0);
        let before = s.remaining();
        let mut scratch = Vec::new();
        s.pump(&e, Duration::from_secs(10), &mut scratch);
        assert!(s.remaining() < before);

        s.retarget(100, 100);
        assert_eq!(s.remaining(), before, "retarget did not rebuild the queue");
        assert_eq!(s.centre(), (100, 100));
    }

    /// The scratch buffer is grown to hold a column and then reused.
    #[test]
    fn the_scratch_buffer_is_reused() {
        let e = engine();
        let mut s = StreamScheduler::new(1);
        s.retarget(0, 0);
        let mut scratch = Vec::new();

        s.pump(&e, Duration::from_secs(10), &mut scratch);
        let cap = scratch.capacity();
        assert!(scratch.len() >= e.chunk_blocks() as usize);

        s.pump(&e, Duration::from_secs(10), &mut scratch);
        assert_eq!(
            scratch.capacity(),
            cap,
            "scratch was reallocated between pumps"
        );
    }
}