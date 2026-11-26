//! World task scheduler core.
//!
//! `SchedulerCore` is deliberately single-threaded and free of locks so the
//! ordering and stale-result rules can be unit-tested deterministically. A
//! worker pool calls `take_job`, runs the work elsewhere, and feeds the result
//! back through `complete`. Anything produced for an older world epoch is
//! discarded instead of committed.

use crate::chunk::{ChunkState, ChunkStore, ChunkStoreError, WorldEpoch};
use crate::coords::ChunkPos;
use crate::task::{BoundedJobQueue, ChunkJob, JobKind, Priority, PushOutcome};

/// Chunks within this Chebyshev radius are treated as urgent.
pub const URGENT_RADIUS_CHUNKS: i32 = 4;
/// Chunks within this Chebyshev radius are treated as near.
pub const NEAR_RADIUS_CHUNKS: i32 = 12;

#[derive(Copy, Clone, Debug, Default, Eq, PartialEq)]
pub struct SchedulerStats {
    pub submitted: u64,
    pub started: u64,
    pub completed: u64,
    pub stale_discarded: u64,
    pub rejected_full: u64,
    pub evicted: u64,
    pub epoch_bumps: u64,
}

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum SchedError {
    Store(ChunkStoreError),
    IllegalState {
        expected: ChunkState,
        actual: Option<ChunkState>,
    },
    QueueRejected,
}

impl From<ChunkStoreError> for SchedError {
    fn from(value: ChunkStoreError) -> Self {
        SchedError::Store(value)
    }
}

/// Result of feeding a finished job back to the scheduler.
#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum Completion {
    /// Result accepted; the chunk advanced to its next state.
    Committed { revision: u64 },
    /// Result belongs to a previous world epoch or a chunk that no longer
    /// matches the expected in-flight state. It must not be committed.
    Stale,
    /// The chunk entry disappeared (e.g. unloaded while the job ran).
    Missing,
}

pub struct SchedulerCore {
    store: ChunkStore,
    queue: BoundedJobQueue,
    player: ChunkPos,
    stats: SchedulerStats,
}

impl SchedulerCore {
    pub fn new(world_epoch: WorldEpoch, queue_capacity: usize) -> Self {
        Self {
            store: ChunkStore::new(world_epoch),
            queue: BoundedJobQueue::new(queue_capacity),
            player: ChunkPos::ZERO,
            stats: SchedulerStats::default(),
        }
    }

    pub fn world_epoch(&self) -> WorldEpoch {
        self.store.world_epoch()
    }

    pub fn stats(&self) -> SchedulerStats {
        self.stats
    }

    pub fn store(&self) -> &ChunkStore {
        &self.store
    }

    pub fn queue_len(&self) -> usize {
        self.queue.len()
    }

    pub fn set_player_chunk(&mut self, player: ChunkPos) {
        if player != self.player {
            self.player = player;
            let (this_player, urgent, near) = (self.player, URGENT_RADIUS_CHUNKS, NEAR_RADIUS_CHUNKS);
            self.queue.reprioritize(|pos| {
                (priority_for(pos, this_player, urgent, near), distance_sq(pos, this_player))
            });
        }
    }

    fn rank(&self, pos: ChunkPos) -> (Priority, u64) {
        (
            priority_for(pos, self.player, URGENT_RADIUS_CHUNKS, NEAR_RADIUS_CHUNKS),
            distance_sq(pos, self.player),
        )
    }

    /// Queue terrain generation for a chunk. If the chunk is already scheduled
    /// or further along, nothing is queued.
    pub fn request_terrain(&mut self, pos: ChunkPos) -> Result<u64, SchedError> {
        self.store.ensure(pos)?;
        let state = self.store.state(pos);
        if state != Some(ChunkState::Empty) {
            return Err(SchedError::IllegalState {
                expected: ChunkState::Empty,
                actual: state,
            });
        }
        self.store.transition(pos, ChunkState::TerrainQueued)?;
        let (priority, dist) = self.rank(pos);
        let outcome = self
            .queue
            .submit(priority, JobKind::GenerateTerrain, pos, self.world_epoch(), dist);
        self.finish_submit(pos, ChunkState::Empty, outcome)
    }

    /// Queue mesh construction for a chunk whose terrain is ready.
    pub fn request_mesh(&mut self, pos: ChunkPos) -> Result<u64, SchedError> {
        let state = self.store.state(pos);
        if state != Some(ChunkState::TerrainReady) {
            return Err(SchedError::IllegalState {
                expected: ChunkState::TerrainReady,
                actual: state,
            });
        }
        self.store.transition(pos, ChunkState::MeshQueued)?;
        let (_, dist) = self.rank(pos);
        // Meshing is never speculative: promote to at least Near.
        let priority = match priority_for(pos, self.player, URGENT_RADIUS_CHUNKS, NEAR_RADIUS_CHUNKS) {
            Priority::Urgent => Priority::Urgent,
            _ => Priority::Near,
        };
        let outcome = self
            .queue
            .submit(priority, JobKind::BuildMesh, pos, self.world_epoch(), dist);
        // On rejection the chunk goes straight back to TerrainReady, so it can be
        // requested again on a later frame instead of being stuck in MeshQueued.
        self.finish_submit(pos, ChunkState::TerrainReady, outcome)
    }

    fn finish_submit(
        &mut self,
        pos: ChunkPos,
        rollback: ChunkState,
        outcome: PushOutcome,
    ) -> Result<u64, SchedError> {
        match outcome {
            PushOutcome::Accepted(id) => {
                self.stats.submitted += 1;
                Ok(id)
            }
            PushOutcome::Replaced { accepted, evicted } => {
                self.rollback_evicted(evicted);
                self.stats.evicted += 1;
                self.stats.submitted += 1;
                Ok(accepted)
            }
            PushOutcome::Rejected => {
                let _ = self.store.transition(pos, rollback);
                self.stats.rejected_full += 1;
                Err(SchedError::QueueRejected)
            }
        }
    }

    fn rollback_evicted(&mut self, job: ChunkJob) {
        // (queued state, state to return to). A cancelled remesh must land back on
        // `Uploaded`, because its old mesh is still resident and still drawn.
        let (expected, previous) = match job.kind {
            JobKind::GenerateTerrain => (ChunkState::TerrainQueued, ChunkState::Empty),
            JobKind::BuildMesh => (ChunkState::MeshQueued, ChunkState::TerrainReady),
            JobKind::RemeshMesh => (ChunkState::MeshQueued, ChunkState::Uploaded),
            JobKind::UploadMesh | JobKind::Unload => return,
        };
        if self.store.state(job.pos) == Some(expected) {
            let _ = self.store.transition(job.pos, previous);
        }
    }

    /// Pop the next runnable job and mark its chunk as in progress. Stale
    /// entries are skipped and counted.
    pub fn take_job(&mut self) -> Option<ChunkJob> {
        loop {
            let job = self.queue.pop()?;
            if job.epoch != self.world_epoch() {
                self.stats.stale_discarded += 1;
                continue;
            }
            let (expected, in_progress) = match job.kind {
                JobKind::GenerateTerrain => (ChunkState::TerrainQueued, ChunkState::TerrainGenerating),
                JobKind::BuildMesh | JobKind::RemeshMesh => {
                    (ChunkState::MeshQueued, ChunkState::Meshing)
                }
                JobKind::UploadMesh => (ChunkState::MeshReady, ChunkState::Uploaded),
                JobKind::Unload => (ChunkState::Unloading, ChunkState::Unloading),
            };
            if self.store.state(job.pos) != Some(expected) {
                self.stats.stale_discarded += 1;
                continue;
            }
            if job.kind != JobKind::Unload {
                let _ = self.store.transition(job.pos, in_progress);
            }
            self.stats.started += 1;
            return Some(job);
        }
    }

    /// Feed a finished job back. The result is only committed when the world
    /// epoch and the chunk's in-flight state still match.
    pub fn complete(&mut self, job: ChunkJob) -> Completion {
        if job.epoch != self.world_epoch() {
            self.stats.stale_discarded += 1;
            return Completion::Stale;
        }
        if self.store.state(job.pos).is_none() {
            self.stats.stale_discarded += 1;
            return Completion::Missing;
        }
        let outcome = match job.kind {
            JobKind::GenerateTerrain => self
                .store
                .transition(job.pos, ChunkState::TerrainReady)
                .map(|revision| Completion::Committed { revision }),
            JobKind::BuildMesh | JobKind::RemeshMesh => self
                .store
                .transition(job.pos, ChunkState::MeshReady)
                .map(|revision| Completion::Committed { revision }),
            JobKind::UploadMesh => self
                .store
                .transition(job.pos, ChunkState::Uploaded)
                .map(|revision| Completion::Committed { revision }),
            JobKind::Unload => self.store.remove(job.pos).map(|entry| {
                Completion::Committed {
                    revision: entry.revision,
                }
            }),
        };
        match outcome {
            Ok(completion) => {
                self.stats.completed += 1;
                completion
            }
            Err(_) => {
                self.stats.stale_discarded += 1;
                Completion::Stale
            }
        }
    }

    /// Hold a chunk against unloading.
    pub fn acquire_ticket(&mut self, pos: ChunkPos) -> Result<u32, SchedError> {
        Ok(self.store.acquire_ticket(pos)?)
    }

    /// Release one hold on a chunk; returns the remaining ticket count.
    pub fn release_ticket(&mut self, pos: ChunkPos) -> Result<u32, SchedError> {
        Ok(self.store.release_ticket(pos)?)
    }

    /// Re-mesh a chunk that is already uploaded, because a neighbour's terrain
    /// arrived and its boundary faces may now be hidden. Rolls back to
    /// `Uploaded` (not `TerrainReady`) if the queue rejects it, so the chunk
    /// never stops being drawable.
    pub fn request_remesh(&mut self, pos: ChunkPos) -> Result<u64, SchedError> {
        let state = self.store.state(pos);
        if state != Some(ChunkState::Uploaded) {
            return Err(SchedError::IllegalState {
                expected: ChunkState::Uploaded,
                actual: state,
            });
        }
        self.store.transition(pos, ChunkState::MeshQueued)?;
        let (_, dist) = self.rank(pos);
        let outcome = self
            .queue
            .submit(Priority::Near, JobKind::RemeshMesh, pos, self.world_epoch(), dist);
        self.finish_submit(pos, ChunkState::Uploaded, outcome)
    }

    /// GPU upload happens on the render thread; it is not queued.
    pub fn mark_uploaded(&mut self, pos: ChunkPos) -> Result<(), SchedError> {
        let state = self.store.state(pos);
        if state != Some(ChunkState::MeshReady) {
            return Err(SchedError::IllegalState {
                expected: ChunkState::MeshReady,
                actual: state,
            });
        }
        self.store.transition(pos, ChunkState::Uploaded)?;
        Ok(())
    }

    /// Unload a chunk immediately once no tickets are held. GPU mesh
    /// destruction is the caller's responsibility and must happen on the render
    /// thread before the entry is dropped.
    pub fn unload_now(&mut self, pos: ChunkPos) -> Result<u64, SchedError> {
        self.store.begin_unload(pos)?;
        let entry = self.store.remove(pos)?;
        Ok(entry.revision)
    }

    /// New world / generator reload: invalidate the store, drain the queue and
    /// make every in-flight result stale.
    pub fn reset_for_new_world(&mut self) -> WorldEpoch {
        let previous = self.store.bump_world_epoch();
        self.queue.clear();
        self.stats.epoch_bumps += 1;
        previous
    }
}

fn priority_for(pos: ChunkPos, player: ChunkPos, urgent: i32, near: i32) -> Priority {
    let d = pos.chebyshev(player);
    if d <= urgent {
        Priority::Urgent
    } else if d <= near {
        Priority::Near
    } else {
        Priority::Far
    }
}

fn distance_sq(pos: ChunkPos, player: ChunkPos) -> u64 {
    let (px, pz) = player.center_block();
    pos.distance_sq_to_block(px, pz) as u64
}

/// Run `f(index, item)` over `items` on up to `workers` scoped threads and
/// return the results in input order.
///
/// This is the multi-threading foundation: workers pull indices from an atomic
/// counter, so items are distributed dynamically and no shared mutable state is
/// exposed to `f`. Per-worker scratch can be created inside `f` and kept
/// thread-local by the caller.
pub fn parallel_map<T, R, F>(items: &[T], workers: usize, f: F) -> Vec<R>
where
    T: Sync,
    R: Send,
    F: Fn(usize, &T) -> R + Sync,
{
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Mutex;

    let n = items.len();
    if n == 0 {
        return Vec::new();
    }
    let workers = workers.max(1).min(n);
    if workers == 1 {
        return items.iter().enumerate().map(|(i, t)| f(i, t)).collect();
    }

    let next = AtomicUsize::new(0);
    let out: Mutex<Vec<Option<R>>> = Mutex::new((0..n).map(|_| None).collect());
    std::thread::scope(|scope| {
        for _ in 0..workers {
            scope.spawn(|| loop {
                let i = next.fetch_add(1, Ordering::Relaxed);
                if i >= n {
                    break;
                }
                let value = f(i, &items[i]);
                out.lock().expect("result mutex poisoned")[i] = Some(value);
            });
        }
    });
    out.into_inner()
        .expect("result mutex poisoned")
        .into_iter()
        .map(|slot| slot.expect("every index is filled"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn drive_terrain(s: &mut SchedulerCore, pos: ChunkPos) {
        s.request_terrain(pos).unwrap();
        let job = s.take_job().expect("terrain job");
        assert_eq!(job.kind, JobKind::GenerateTerrain);
        assert!(matches!(s.complete(job), Completion::Committed { .. }));
        assert_eq!(s.store().state(pos), Some(ChunkState::TerrainReady));
    }

    #[test]
    fn full_generate_mesh_upload_pipeline() {
        let mut s = SchedulerCore::new(1, 64);
        let pos = ChunkPos::new(0, 0);
        drive_terrain(&mut s, pos);

        s.request_mesh(pos).unwrap();
        let job = s.take_job().expect("mesh job");
        assert_eq!(job.kind, JobKind::BuildMesh);
        assert_eq!(s.store().state(pos), Some(ChunkState::Meshing));
        assert!(matches!(s.complete(job), Completion::Committed { .. }));
        assert_eq!(s.store().state(pos), Some(ChunkState::MeshReady));

        s.mark_uploaded(pos).unwrap();
        assert_eq!(s.store().state(pos), Some(ChunkState::Uploaded));

        let st = s.stats();
        assert_eq!(st.submitted, 2);
        assert_eq!(st.started, 2);
        assert_eq!(st.completed, 2);
    }

    #[test]
    fn results_from_old_epoch_are_stale() {
        let mut s = SchedulerCore::new(1, 8);
        let pos = ChunkPos::new(0, 0);
        s.request_terrain(pos).unwrap();
        let job = s.take_job().unwrap();

        s.reset_for_new_world();
        assert_eq!(s.world_epoch(), 2);
        assert_eq!(s.complete(job), Completion::Stale);
        assert_eq!(s.stats().stale_discarded, 1);
    }

    #[test]
    fn priority_bands_follow_player_distance() {
        let mut s = SchedulerCore::new(1, 32);
        s.set_player_chunk(ChunkPos::ZERO);
        let near_chunk = ChunkPos::new(2, 0);
        let mid_chunk = ChunkPos::new(8, 0);
        let far_chunk = ChunkPos::new(40, 0);
        s.request_terrain(far_chunk).unwrap();
        s.request_terrain(near_chunk).unwrap();
        s.request_terrain(mid_chunk).unwrap();

        let first = s.take_job().unwrap();
        let second = s.take_job().unwrap();
        let third = s.take_job().unwrap();
        assert_eq!(first.pos, near_chunk);
        assert_eq!(first.priority, Priority::Urgent);
        assert_eq!(second.pos, mid_chunk);
        assert_eq!(second.priority, Priority::Near);
        assert_eq!(third.pos, far_chunk);
        assert_eq!(third.priority, Priority::Far);
    }

    #[test]
    fn rejected_submission_rolls_state_back() {
        let mut s = SchedulerCore::new(1, 1);
        s.request_terrain(ChunkPos::new(0, 0)).unwrap();
        let err = s.request_terrain(ChunkPos::new(60, 0)).unwrap_err();
        assert_eq!(err, SchedError::QueueRejected);
        assert_eq!(
            s.store().state(ChunkPos::new(60, 0)),
            Some(ChunkState::Empty),
            "rejected chunk must be schedulable again"
        );
        assert_eq!(s.stats().rejected_full, 1);
    }

    #[test]
    fn eviction_rolls_back_the_evicted_chunk() {
        let mut s = SchedulerCore::new(1, 1);
        let far = ChunkPos::new(60, 0);
        let urgent = ChunkPos::new(0, 0);
        s.request_terrain(far).unwrap();
        s.request_terrain(urgent).unwrap();
        assert_eq!(s.stats().evicted, 1);
        assert_eq!(
            s.store().state(far),
            Some(ChunkState::Empty),
            "evicted far chunk is no longer scheduled"
        );
        let job = s.take_job().unwrap();
        assert_eq!(job.pos, urgent);
    }

    #[test]
    fn duplicate_terrain_request_is_rejected() {
        let mut s = SchedulerCore::new(1, 8);
        let pos = ChunkPos::new(1, 1);
        s.request_terrain(pos).unwrap();
        assert_eq!(
            s.request_terrain(pos).unwrap_err(),
            SchedError::IllegalState {
                expected: ChunkState::Empty,
                actual: Some(ChunkState::TerrainQueued)
            }
        );
    }

    fn drive_upload(s: &mut SchedulerCore, pos: ChunkPos) {
        drive_terrain(s, pos);
        s.request_mesh(pos).unwrap();
        let job = s.take_job().expect("mesh job");
        assert!(matches!(s.complete(job), Completion::Committed { .. }));
        s.mark_uploaded(pos).unwrap();
        assert_eq!(s.store().state(pos), Some(ChunkState::Uploaded));
    }

    #[test]
    fn rejected_mesh_request_goes_back_to_terrain_ready() {
        // Regression guard: the mesh rollback target used to be a state that
        // `MeshQueued` could not legally return to, so one full queue permanently
        // stranded every chunk it touched.
        let mut s = SchedulerCore::new(1, 1);
        // Outside the urgent band, so its mesh job is Near and cannot displace
        // the urgent blocker below.
        let chunk = ChunkPos::new(8, 0);
        drive_terrain(&mut s, chunk);

        // Occupy the only slot with an urgent job that a Near mesh job cannot evict.
        let blocker = ChunkPos::new(1, 0);
        s.request_terrain(blocker).unwrap();
        assert_eq!(s.request_mesh(chunk).unwrap_err(), SchedError::QueueRejected);
        assert_eq!(
            s.store().state(chunk),
            Some(ChunkState::TerrainReady),
            "a rejected mesh request must leave the chunk requestable again"
        );
        assert_eq!(s.stats().rejected_full, 1);

        let job = s.take_job().unwrap();
        assert_eq!(job.pos, blocker);
        s.complete(job);
        s.request_mesh(chunk).unwrap();
        assert_eq!(s.take_job().unwrap().kind, JobKind::BuildMesh);
    }

    #[test]
    fn rejected_remesh_stays_uploaded_and_can_be_retried() {
        let mut s = SchedulerCore::new(1, 1);
        let chunk = ChunkPos::new(0, 0);
        drive_upload(&mut s, chunk);

        let blocker = ChunkPos::new(1, 0);
        s.request_terrain(blocker).unwrap();
        assert_eq!(s.request_remesh(chunk).unwrap_err(), SchedError::QueueRejected);
        assert_eq!(
            s.store().state(chunk),
            Some(ChunkState::Uploaded),
            "a cancelled remesh must leave the old mesh drawn, not drop back to TerrainReady"
        );

        let job = s.take_job().unwrap();
        s.complete(job);
        s.request_remesh(chunk).unwrap();
        let job = s.take_job().unwrap();
        assert_eq!(job.kind, JobKind::RemeshMesh);
        assert!(matches!(s.complete(job), Completion::Committed { .. }));
        assert_eq!(s.store().state(chunk), Some(ChunkState::MeshReady));
        s.mark_uploaded(chunk).unwrap();
    }

    #[test]
    fn an_evicted_remesh_job_leaves_the_old_mesh_resident() {
        let mut s = SchedulerCore::new(1, 1);
        let chunk = ChunkPos::new(0, 0);
        drive_upload(&mut s, chunk);
        s.request_remesh(chunk).unwrap();
        assert_eq!(s.store().state(chunk), Some(ChunkState::MeshQueued));

        // A nearer terrain job kicks the queued remesh out of the queue.
        s.request_terrain(ChunkPos::new(1, 1)).unwrap();
        assert_eq!(s.stats().evicted, 1);
        assert_eq!(s.store().state(chunk), Some(ChunkState::Uploaded));

        let job = s.take_job().unwrap();
        assert_eq!(job.kind, JobKind::GenerateTerrain);
    }

    #[test]
    fn remesh_is_only_legal_from_uploaded() {
        let mut s = SchedulerCore::new(1, 8);
        let pos = ChunkPos::new(3, -2);
        assert_eq!(
            s.request_remesh(pos).unwrap_err(),
            SchedError::IllegalState {
                expected: ChunkState::Uploaded,
                actual: None,
            },
            "an unknown chunk must not be created by a remesh request"
        );
        drive_terrain(&mut s, pos);
        assert_eq!(
            s.request_remesh(pos).unwrap_err(),
            SchedError::IllegalState {
                expected: ChunkState::Uploaded,
                actual: Some(ChunkState::TerrainReady),
            }
        );
        s.request_mesh(pos).unwrap();
        let job = s.take_job().unwrap();
        s.complete(job);
        assert_eq!(
            s.request_remesh(pos).unwrap_err(),
            SchedError::IllegalState {
                expected: ChunkState::Uploaded,
                actual: Some(ChunkState::MeshReady),
            }
        );
        assert_eq!(s.store().state(pos), Some(ChunkState::MeshReady));
    }

    #[test]
    fn tickets_are_refcounted_and_hold_off_unload() {
        let mut s = SchedulerCore::new(1, 8);
        let pos = ChunkPos::new(2, 2);
        drive_upload(&mut s, pos);
        assert_eq!(s.acquire_ticket(pos).unwrap(), 1);
        assert_eq!(s.acquire_ticket(pos).unwrap(), 2);
        assert_eq!(s.release_ticket(pos).unwrap(), 1);
        let err = s.unload_now(pos).unwrap_err();
        assert_eq!(err, SchedError::Store(ChunkStoreError::HasTickets { tickets: 1 }));
        assert_eq!(s.release_ticket(pos).unwrap(), 0);
        let revision = s.store().get(pos).expect("entry present").revision;
        // begin_unload is itself a transition, so the removal reports the bumped revision.
        assert_eq!(s.unload_now(pos).unwrap(), revision + 1);
        assert_eq!(s.store().state(pos), None);
        assert_eq!(
            s.acquire_ticket(pos).unwrap_err(),
            SchedError::Store(ChunkStoreError::NotFound(pos))
        );
    }

    #[test]
    fn parallel_map_is_order_preserving() {
        let items: Vec<u64> = (0..1000).collect();
        let out = parallel_map(&items, 4, |_i, &v| v * v);
        assert_eq!(out.len(), items.len());
        for (v, sq) in items.iter().zip(out.iter()) {
            assert_eq!(*sq, v * v);
        }
    }
}
