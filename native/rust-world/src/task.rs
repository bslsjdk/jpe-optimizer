//! Bounded, priority-aware chunk job queue.
//!
//! Ordering follows the Horizon study: urgent work first, then near work, then
//! far (speculative) work. Within a priority band the nearest chunk wins, and
//! ties fall back to submission order so behaviour is deterministic.
//!
//! The queue is bounded. When it is full a new job may only displace an
//! existing lower-priority (or farther) job; otherwise it is rejected and the
//! rejection is counted. This keeps far pregeneration from starving urgent
//! gameplay work.

use crate::chunk::WorldEpoch;
use crate::coords::ChunkPos;
use std::cmp::Ordering;
use std::collections::BinaryHeap;

/// Coarse urgency bands.
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash, Debug)]
pub enum Priority {
    /// Work the game is waiting for right now.
    Urgent = 0,
    /// Nearby generation / meshing.
    Near = 1,
    /// Distant or speculative work.
    Far = 2,
}

impl Priority {
    fn rank(self) -> u8 {
        self as u8
    }
}

/// What a job will do when a worker picks it up.
#[derive(Copy, Clone, Eq, PartialEq, Hash, Debug)]
pub enum JobKind {
    GenerateTerrain,
    BuildMesh,
    /// Re-mesh of a chunk that is already uploaded, because a neighbour's
    /// terrain arrived and hid some of its boundary faces. Kept distinct from
    /// `BuildMesh` so dropping the job rolls back to `Uploaded`, not to
    /// `TerrainReady`, and the chunk never loses its drawable state.
    RemeshMesh,
    UploadMesh,
    Unload,
}

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub struct ChunkJob {
    pub id: u64,
    pub priority: Priority,
    pub kind: JobKind,
    pub pos: ChunkPos,
    pub epoch: WorldEpoch,
    /// Squared distance in block units from the player at submission time.
    pub distance_sq: u64,
    pub seq: u64,
}

impl ChunkJob {
    /// Lower is better: priority, then distance, then submission order.
    fn key(&self) -> (u8, u64, u64) {
        (self.priority.rank(), self.distance_sq, self.seq)
    }
}

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum PushOutcome {
    Accepted(u64),
    /// The queue was full and the worst existing job was displaced. The caller
    /// owns rolling that job's chunk state back.
    Replaced { accepted: u64, evicted: ChunkJob },
    /// The queue was full and the new job was worse than everything in it.
    Rejected,
}

#[derive(Debug)]
struct QueueEntry {
    job: ChunkJob,
}

impl QueueEntry {
    fn key(&self) -> (u8, u64, u64) {
        self.job.key()
    }
}

impl PartialEq for QueueEntry {
    fn eq(&self, other: &Self) -> bool {
        self.key() == other.key()
    }
}
impl Eq for QueueEntry {}
impl PartialOrd for QueueEntry {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for QueueEntry {
    fn cmp(&self, other: &Self) -> Ordering {
        // `BinaryHeap` is a max-heap; inverting the key makes the best job pop
        // first.
        other.key().cmp(&self.key())
    }
}

#[derive(Debug)]
pub struct BoundedJobQueue {
    heap: BinaryHeap<QueueEntry>,
    capacity: usize,
    next_id: u64,
    next_seq: u64,
}

impl BoundedJobQueue {
    pub fn new(capacity: usize) -> Self {
        Self {
            heap: BinaryHeap::with_capacity(capacity.max(1)),
            capacity: capacity.max(1),
            next_id: 1,
            next_seq: 0,
        }
    }

    pub fn len(&self) -> usize {
        self.heap.len()
    }

    pub fn is_empty(&self) -> bool {
        self.heap.is_empty()
    }

    pub fn capacity(&self) -> usize {
        self.capacity
    }

    /// Submit a job. `distance_sq` is the squared block distance from the
    /// player at submission time; it may be refreshed later with
    /// [`Self::reprioritize`].
    pub fn submit(
        &mut self,
        priority: Priority,
        kind: JobKind,
        pos: ChunkPos,
        epoch: WorldEpoch,
        distance_sq: u64,
    ) -> PushOutcome {
        let job = ChunkJob {
            id: self.next_id,
            priority,
            kind,
            pos,
            epoch,
            distance_sq,
            seq: self.next_seq,
        };
        self.next_id = self.next_id.wrapping_add(1);
        self.next_seq = self.next_seq.wrapping_add(1);

        let mut evicted = None;
        if self.heap.len() >= self.capacity {
            let worst = self.take_worst().expect("queue is full");
            if job.key() < worst.job.key() {
                evicted = Some(worst.job);
            } else {
                self.heap.push(worst);
                return PushOutcome::Rejected;
            }
        }
        let id = job.id;
        self.heap.push(QueueEntry { job });
        match evicted {
            Some(evicted) => PushOutcome::Replaced {
                accepted: id,
                evicted,
            },
            None => PushOutcome::Accepted(id),
        }
    }

    pub fn pop(&mut self) -> Option<ChunkJob> {
        self.heap.pop().map(|e| e.job)
    }

    pub fn peek(&self) -> Option<&ChunkJob> {
        self.heap.peek().map(|e| &e.job)
    }

    /// Recompute every queued job's priority and distance against the current
    /// player position, then restore heap order. Used when the player crosses a
    /// chunk boundary: both the urgency band and the distance can change.
    pub fn reprioritize<F>(&mut self, mut rank: F)
    where
        F: FnMut(ChunkPos) -> (Priority, u64),
    {
        let mut items: Vec<QueueEntry> = self.heap.drain().collect();
        for entry in &mut items {
            let (priority, distance_sq) = rank(entry.job.pos);
            entry.job.priority = priority;
            entry.job.distance_sq = distance_sq;
        }
        self.heap = BinaryHeap::from(items);
    }

    /// Drain the whole queue, e.g. when the world epoch changes.
    pub fn clear(&mut self) {
        self.heap.clear();
    }

    fn take_worst(&mut self) -> Option<QueueEntry> {
        if self.heap.is_empty() {
            return None;
        }
        let mut items: Vec<QueueEntry> = self.heap.drain().collect();
        // Natural key order: the maximum key is the worst job.
        let idx = items
            .iter()
            .enumerate()
            .max_by_key(|(_, e)| e.key())
            .map(|(i, _)| i)
            .expect("non-empty");
        let worst = items.swap_remove(idx);
        self.heap = BinaryHeap::from(items);
        Some(worst)
    }

    /// Snapshot of the current ordering, best first. Test/diagnostic only.
    pub fn drain_sorted(&mut self) -> Vec<ChunkJob> {
        let mut out = Vec::with_capacity(self.heap.len());
        while let Some(job) = self.pop() {
            out.push(job);
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn submit(
        q: &mut BoundedJobQueue,
        priority: Priority,
        dist: u64,
        pos: ChunkPos,
    ) -> PushOutcome {
        q.submit(priority, JobKind::GenerateTerrain, pos, 1, dist)
    }

    #[test]
    fn pops_urgent_before_near_before_far() {
        let mut q = BoundedJobQueue::new(16);
        submit(&mut q, Priority::Far, 100, ChunkPos::new(10, 0));
        submit(&mut q, Priority::Urgent, 400, ChunkPos::new(1, 0));
        submit(&mut q, Priority::Near, 50, ChunkPos::new(2, 0));

        let order: Vec<_> = q.drain_sorted().into_iter().map(|j| j.priority).collect();
        assert_eq!(order, vec![Priority::Urgent, Priority::Near, Priority::Far]);
    }

    #[test]
    fn nearer_wins_within_same_priority() {
        let mut q = BoundedJobQueue::new(16);
        submit(&mut q, Priority::Near, 900, ChunkPos::new(9, 0));
        submit(&mut q, Priority::Near, 4, ChunkPos::new(1, 0));
        submit(&mut q, Priority::Near, 100, ChunkPos::new(3, 0));
        let xs: Vec<_> = q.drain_sorted().into_iter().map(|j| j.pos.x).collect();
        assert_eq!(xs, vec![1, 3, 9]);
    }

    #[test]
    fn fifo_for_equal_keys() {
        let mut q = BoundedJobQueue::new(16);
        let p = ChunkPos::new(0, 0);
        submit(&mut q, Priority::Near, 10, p);
        submit(&mut q, Priority::Near, 10, p);
        let jobs = q.drain_sorted();
        assert!(jobs[0].seq < jobs[1].seq);
    }

    #[test]
    fn full_queue_rejects_worse_job() {
        let mut q = BoundedJobQueue::new(2);
        submit(&mut q, Priority::Near, 10, ChunkPos::new(0, 0));
        submit(&mut q, Priority::Near, 20, ChunkPos::new(1, 0));
        let outcome = submit(&mut q, Priority::Far, 9999, ChunkPos::new(50, 50));
        assert_eq!(outcome, PushOutcome::Rejected);
        assert_eq!(q.len(), 2);
    }

    #[test]
    fn full_queue_evicts_worst_for_better_job() {
        let mut q = BoundedJobQueue::new(2);
        submit(&mut q, Priority::Far, 900, ChunkPos::new(9, 0));
        submit(&mut q, Priority::Far, 800, ChunkPos::new(8, 0));
        let outcome = submit(&mut q, Priority::Urgent, 700, ChunkPos::new(0, 0));
        assert!(matches!(outcome, PushOutcome::Replaced { .. }));
        let jobs = q.drain_sorted();
        assert_eq!(jobs.len(), 2);
        assert_eq!(jobs[0].priority, Priority::Urgent);
        assert_eq!(jobs[1].pos.x, 8); // the farther (worse) Far job was evicted
    }

    #[test]
    fn reprioritize_reorders_by_new_distance() {
        let mut q = BoundedJobQueue::new(16);
        submit(&mut q, Priority::Near, 0, ChunkPos::new(1, 0)); // was nearest
        submit(&mut q, Priority::Near, 0, ChunkPos::new(2, 0));
        // Player moved to chunk 20: now chunk 2 is nearer than chunk 1, and
        // both stay in the Near band.
        q.reprioritize(|pos| {
            let d = (pos.x as i64 - 20).unsigned_abs();
            (Priority::Near, d * d)
        });
        let xs: Vec<_> = q.drain_sorted().into_iter().map(|j| j.pos.x).collect();
        assert_eq!(xs, vec![2, 1]);
    }
}
