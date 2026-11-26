//! Chunk lifecycle state machine and chunk store.
//!
//! This is the Rust-owned bookkeeping that will eventually replace Java-side
//! chunk scheduling. It deliberately keeps no GPU or Minecraft objects: only
//! positions, states, ticket counts and a world generation epoch, so it can be
//! unit-tested without a running game.
//!
//! Stale results are rejected by comparing the job's epoch against the store's
//! current world epoch. A new world (or a generator reload) bumps the epoch and
//! invalidates every in-flight result.

use crate::coords::ChunkPos;
use std::collections::BTreeMap;

/// Bumped whenever the world identity changes (new world, generator reload,
/// dimension swap). Results computed against an older epoch must be discarded.
pub type WorldEpoch = u64;

/// Lifecycle states for a single chunk column.
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash, Debug)]
pub enum ChunkState {
    /// Known but nothing scheduled.
    Empty,
    /// Terrain job submitted to the scheduler.
    TerrainQueued,
    /// A worker is currently generating terrain.
    TerrainGenerating,
    /// Terrain data present; mesh not requested yet.
    TerrainReady,
    /// Mesh job submitted.
    MeshQueued,
    /// A worker is currently building the mesh.
    Meshing,
    /// Mesh data present, not uploaded to the GPU.
    MeshReady,
    /// Mesh uploaded and drawable.
    Uploaded,
    /// Scheduled for removal; no new work may start.
    Unloading,
}

impl ChunkState {
    /// Legal transitions. Anything not listed is a bug at the call site and is
    /// rejected instead of silently accepted.
    pub fn can_transition(self, next: ChunkState) -> bool {
        use ChunkState::*;
        if next == Unloading {
            // Any live state may begin unloading; Empty/Unloading are no-ops.
            return matches!(
                self,
                TerrainQueued
                    | TerrainGenerating
                    | TerrainReady
                    | MeshQueued
                    | Meshing
                    | MeshReady
                    | Uploaded
            );
        }
        matches!(
            (self, next),
            (Empty, TerrainQueued)
                | (TerrainQueued, TerrainGenerating)
                | (TerrainQueued, Empty) // cancelled before start
                | (TerrainGenerating, TerrainReady)
                | (TerrainGenerating, Empty) // aborted
                | (TerrainReady, MeshQueued)
                | (MeshQueued, Meshing)
                | (MeshQueued, MeshReady) // cached mesh reused
                | (MeshQueued, TerrainReady) // mesh job cancelled before start
                | (Meshing, MeshReady)
                | (Meshing, MeshQueued) // remesh requested while meshing
                | (MeshReady, Uploaded)
                | (Uploaded, MeshQueued) // neighbour changed
                | (MeshQueued, Uploaded) // remesh job cancelled before start
                | (Unloading, Empty)
        )
    }

    /// True while a job referencing this chunk may be in flight.
    pub fn is_busy(self) -> bool {
        matches!(
            self,
            ChunkState::TerrainQueued
                | ChunkState::TerrainGenerating
                | ChunkState::MeshQueued
                | ChunkState::Meshing
        )
    }
}

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub struct ChunkEntry {
    pub pos: ChunkPos,
    pub state: ChunkState,
    pub epoch: WorldEpoch,
    pub revision: u64,
    pub tickets: u32,
    pub last_touch_seq: u64,
}

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum ChunkStoreError {
    NotFound(ChunkPos),
    AlreadyExists(ChunkPos),
    IllegalTransition { from: ChunkState, to: ChunkState },
    HasTickets { tickets: u32 },
}

/// Owns all chunk bookkeeping for one world epoch.
#[derive(Debug)]
pub struct ChunkStore {
    entries: BTreeMap<ChunkPos, ChunkEntry>,
    world_epoch: WorldEpoch,
    seq: u64,
}

impl ChunkStore {
    pub fn new(world_epoch: WorldEpoch) -> Self {
        Self {
            entries: BTreeMap::new(),
            world_epoch,
            seq: 0,
        }
    }

    pub fn world_epoch(&self) -> WorldEpoch {
        self.world_epoch
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn get(&self, pos: ChunkPos) -> Option<&ChunkEntry> {
        self.entries.get(&pos)
    }

    pub fn state(&self, pos: ChunkPos) -> Option<ChunkState> {
        self.entries.get(&pos).map(|e| e.state)
    }

    fn tick(&mut self) -> u64 {
        self.seq = self.seq.wrapping_add(1);
        self.seq
    }

    /// Insert a chunk in `Empty` if absent. Idempotent for existing entries.
    pub fn ensure(&mut self, pos: ChunkPos) -> Result<&ChunkEntry, ChunkStoreError> {
        if !self.entries.contains_key(&pos) {
            let seq = self.tick();
            self.entries.insert(
                pos,
                ChunkEntry {
                    pos,
                    state: ChunkState::Empty,
                    epoch: self.world_epoch,
                    revision: 0,
                    tickets: 0,
                    last_touch_seq: seq,
                },
            );
        }
        Ok(self.entries.get(&pos).expect("just inserted"))
    }

    /// Move a chunk to `next`, validating the transition and bumping revision.
    /// Returns the new revision.
    pub fn transition(
        &mut self,
        pos: ChunkPos,
        next: ChunkState,
    ) -> Result<u64, ChunkStoreError> {
        let seq = self.tick();
        let entry = self
            .entries
            .get_mut(&pos)
            .ok_or(ChunkStoreError::NotFound(pos))?;
        if !entry.state.can_transition(next) {
            return Err(ChunkStoreError::IllegalTransition {
                from: entry.state,
                to: next,
            });
        }
        entry.state = next;
        entry.revision = entry.revision.wrapping_add(1);
        entry.last_touch_seq = seq;
        Ok(entry.revision)
    }

    /// Acquire one reference ticket. A chunk with tickets must not be unloaded.
    pub fn acquire_ticket(&mut self, pos: ChunkPos) -> Result<u32, ChunkStoreError> {
        let entry = self
            .entries
            .get_mut(&pos)
            .ok_or(ChunkStoreError::NotFound(pos))?;
        entry.tickets = entry.tickets.saturating_add(1);
        Ok(entry.tickets)
    }

    pub fn release_ticket(&mut self, pos: ChunkPos) -> Result<u32, ChunkStoreError> {
        let entry = self
            .entries
            .get_mut(&pos)
            .ok_or(ChunkStoreError::NotFound(pos))?;
        entry.tickets = entry.tickets.saturating_sub(1);
        Ok(entry.tickets)
    }

    /// Begin unloading. Fails while tickets are still held.
    pub fn begin_unload(&mut self, pos: ChunkPos) -> Result<(), ChunkStoreError> {
        let tickets = self
            .entries
            .get(&pos)
            .ok_or(ChunkStoreError::NotFound(pos))?
            .tickets;
        if tickets > 0 {
            return Err(ChunkStoreError::HasTickets { tickets });
        }
        self.transition(pos, ChunkState::Unloading)?;
        Ok(())
    }

    /// Remove a chunk. Only allowed once `Unloading`.
    pub fn remove(&mut self, pos: ChunkPos) -> Result<ChunkEntry, ChunkStoreError> {
        let state = self
            .entries
            .get(&pos)
            .ok_or(ChunkStoreError::NotFound(pos))?
            .state;
        if state != ChunkState::Unloading {
            return Err(ChunkStoreError::IllegalTransition {
                from: state,
                to: ChunkState::Empty,
            });
        }
        Ok(self.entries.remove(&pos).expect("checked above"))
    }

    /// Invalidate every entry and advance the world epoch. All in-flight
    /// results from the previous epoch become stale.
    pub fn bump_world_epoch(&mut self) -> WorldEpoch {
        let previous = self.world_epoch;
        self.world_epoch = self.world_epoch.wrapping_add(1);
        self.entries.clear();
        previous
    }

    pub fn iter(&self) -> impl Iterator<Item = &ChunkEntry> {
        self.entries.values()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn happy_path_terrain_then_mesh() {
        let mut store = ChunkStore::new(1);
        let pos = ChunkPos::new(2, -3);
        store.ensure(pos).unwrap();
        assert_eq!(store.state(pos), Some(ChunkState::Empty));

        let r1 = store.transition(pos, ChunkState::TerrainQueued).unwrap();
        let r2 = store.transition(pos, ChunkState::TerrainGenerating).unwrap();
        let r3 = store.transition(pos, ChunkState::TerrainReady).unwrap();
        store.transition(pos, ChunkState::MeshQueued).unwrap();
        store.transition(pos, ChunkState::Meshing).unwrap();
        store.transition(pos, ChunkState::MeshReady).unwrap();
        store.transition(pos, ChunkState::Uploaded).unwrap();
        assert!(r1 < r2 && r2 < r3, "revisions must advance");

        // A neighbour change forces a remesh.
        store.transition(pos, ChunkState::MeshQueued).unwrap();
        assert_eq!(store.state(pos), Some(ChunkState::MeshQueued));
    }

    #[test]
    fn illegal_transitions_are_rejected() {
        let mut store = ChunkStore::new(1);
        let pos = ChunkPos::new(0, 0);
        store.ensure(pos).unwrap();
        let err = store.transition(pos, ChunkState::Uploaded).unwrap_err();
        assert_eq!(
            err,
            ChunkStoreError::IllegalTransition {
                from: ChunkState::Empty,
                to: ChunkState::Uploaded
            }
        );
        assert_eq!(store.state(pos), Some(ChunkState::Empty), "state unchanged");
    }

    #[test]
    fn tickets_block_unload() {
        let mut store = ChunkStore::new(7);
        let pos = ChunkPos::new(1, 1);
        store.ensure(pos).unwrap();
        store.transition(pos, ChunkState::TerrainQueued).unwrap();
        store.acquire_ticket(pos).unwrap();
        assert_eq!(store.acquire_ticket(pos).unwrap(), 2);
        assert_eq!(
            store.begin_unload(pos).unwrap_err(),
            ChunkStoreError::HasTickets { tickets: 2 }
        );
        store.release_ticket(pos).unwrap();
        store.release_ticket(pos).unwrap();
        store.begin_unload(pos).unwrap();
        assert_eq!(store.state(pos), Some(ChunkState::Unloading));
        store.remove(pos).unwrap();
        assert!(store.get(pos).is_none());
    }

    #[test]
    fn world_epoch_bump_invalidates_entries() {
        let mut store = ChunkStore::new(3);
        let pos = ChunkPos::new(0, 0);
        store.ensure(pos).unwrap();
        store.transition(pos, ChunkState::TerrainQueued).unwrap();
        let old = store.bump_world_epoch();
        assert_eq!(old, 3);
        assert_eq!(store.world_epoch(), 4);
        assert!(store.get(pos).is_none());
    }

    #[test]
    fn busy_states_reported() {
        assert!(ChunkState::TerrainGenerating.is_busy());
        assert!(ChunkState::Meshing.is_busy());
        assert!(!ChunkState::TerrainReady.is_busy());
        assert!(!ChunkState::Uploaded.is_busy());
    }
}
