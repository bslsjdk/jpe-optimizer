//! The World Engine's GPU section resource manager.
//!
//! [`crate::chunk`] tracks *lifecycle* and deliberately holds no GPU objects.
//! [`super::plan`] turns a list of [`SectionRecord`]s into a draw list but owns
//! nothing. This module is the missing middle: it owns the mapping
//! `SectionPos -> (mesh handle, index count)`, so a chunk state transition can
//! publish a drawable section, a remesh can replace the old GPU buffer, and an
//! unload can report exactly which handles the caller must destroy.
//!
//! Like `plan`, nothing here talks to a driver: handles are opaque
//! [`MeshHandle`]s obtained elsewhere. That keeps the whole resource policy
//! unit-testable without a GPU, and keeps `destroy_mesh` a caller action on the
//! render thread rather than a `Drop` that could run on a worker.

use super::plan::SectionRecord;
use super::MeshHandle;
use crate::coords::SectionPos;

/// Deterministic record key for a section: the packed vanilla-style position,
/// reinterpreted as unsigned so the ordering used by [`super::plan::plan_draws`]
/// tie-breaks is stable and unique per section.
pub fn record_key(pos: SectionPos) -> u64 {
    pos.to_long() as u64
}

/// One resident, drawable section.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct ResidentSection {
    pub pos: SectionPos,
    pub mesh: MeshHandle,
    /// Indices to draw; 0 means the section meshed to nothing (all air).
    pub index_count: u32,
    /// Chunk revision the mesh was built from, for staleness checks by callers.
    pub revision: u64,
}

/// What [`SectionRegistry::publish`] did, so the caller can free GPU memory it
/// now owns and account for empty sections without re-deriving it.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Default)]
pub struct PublishOutcome {
    /// True if this position was already resident.
    pub replaced: bool,
    /// Previous mesh at this position, which the caller must destroy.
    pub superseded: Option<MeshHandle>,
    /// True if the new mesh has no indices (nothing will be drawn for it).
    pub empty: bool,
}

/// Accounting for the registry's lifetime, reported rather than implied.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Default)]
pub struct RegistryStats {
    /// First-time publications.
    pub published: u64,
    /// Publications that replaced an existing mesh.
    pub replaced: u64,
    /// Sections removed by position or by clear.
    pub removed: u64,
    /// Currently resident sections, including empty ones.
    pub resident: u64,
    /// Currently visible index total (sum of non-zero index counts).
    pub resident_indices: u64,
}

/// All sections currently uploaded and drawable, keyed by section position.
///
/// The backing map is ordered, so [`Self::records`] is deterministic for
/// identical content regardless of insertion order.
#[derive(Debug, Default)]
pub struct SectionRegistry {
    sections: std::collections::BTreeMap<SectionPos, ResidentSection>,
    cached: Vec<SectionRecord>,
    dirty: bool,
    stats: RegistryStats,
}

impl SectionRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.sections.len()
    }

    pub fn is_empty(&self) -> bool {
        self.sections.is_empty()
    }

    pub fn stats(&self) -> RegistryStats {
        self.stats
    }

    pub fn get(&self, pos: SectionPos) -> Option<&ResidentSection> {
        self.sections.get(&pos)
    }

    pub fn iter(&self) -> impl Iterator<Item = &ResidentSection> {
        self.sections.values()
    }

    /// Publish a meshed section, replacing any mesh already resident there.
    ///
    /// The registry takes logical ownership of `mesh`: the superseded handle is
    /// handed back so the caller can destroy it on the render thread.
    pub fn publish(
        &mut self,
        pos: SectionPos,
        mesh: MeshHandle,
        index_count: u32,
        revision: u64,
    ) -> PublishOutcome {
        let previous = self.sections.insert(
            pos,
            ResidentSection {
                pos,
                mesh,
                index_count,
                revision,
            },
        );
        self.dirty = true;
        let outcome = match previous {
            Some(old) => {
                self.stats.replaced += 1;
                self.stats.resident_indices -= old.index_count as u64;
                PublishOutcome {
                    replaced: true,
                    superseded: Some(old.mesh),
                    empty: index_count == 0,
                }
            }
            None => {
                self.stats.published += 1;
                self.stats.resident += 1;
                PublishOutcome {
                    replaced: false,
                    superseded: None,
                    empty: index_count == 0,
                }
            }
        };
        self.stats.resident_indices += index_count as u64;
        outcome
    }

    /// Forget a section, returning the mesh the caller must destroy.
    pub fn remove(&mut self, pos: SectionPos) -> Option<MeshHandle> {
        let removed = self.sections.remove(&pos)?;
        self.dirty = true;
        self.stats.removed += 1;
        self.stats.resident -= 1;
        self.stats.resident_indices -= removed.index_count as u64;
        Some(removed.mesh)
    }

    /// Forget every section, returning all meshes for destruction. Used when the
    /// world epoch changes and all GPU data becomes invalid.
    pub fn clear(&mut self) -> Vec<MeshHandle> {
        if self.sections.is_empty() {
            return Vec::new();
        }
        let handles = self.sections.values().map(|s| s.mesh).collect();
        self.stats.removed += self.sections.len() as u64;
        self.sections.clear();
        self.stats.resident = 0;
        self.stats.resident_indices = 0;
        self.dirty = true;
        handles
    }

    /// Snapshot for [`super::plan::plan_draws`], rebuilt only when the contents
    /// changed so a per-frame call is just a borrow. The record vector keeps its
    /// capacity between rebuilds (clear + push) instead of allocating a fresh one
    /// every time the registry mutates.
    pub fn records(&mut self) -> &[SectionRecord] {
        if self.dirty {
            self.cached.clear();
            for s in self.sections.values() {
                let (x, y, z) = s.pos.origin_block();
                self.cached.push(SectionRecord {
                    key: record_key(s.pos),
                    origin: [x, y, z],
                    mesh: s.mesh,
                    index_count: s.index_count,
                });
            }
            self.dirty = false;
        }
        &self.cached
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pos(x: i32, y: i32, z: i32) -> SectionPos {
        SectionPos::new(x, y, z)
    }

    #[test]
    fn keys_are_unique_and_stable() {
        let a = record_key(pos(1, 2, 3));
        let b = record_key(pos(1, 2, 4));
        let c = record_key(pos(-1, -4, 0));
        assert_ne!(a, b);
        assert_ne!(a, c);
        assert_eq!(a, record_key(pos(1, 2, 3)), "key must be pure");
    }

    #[test]
    fn publish_then_replace_returns_the_old_handle() {
        let mut r = SectionRegistry::new();
        let p = pos(0, 4, 0);
        let first = r.publish(p, MeshHandle(11), 60, 1);
        assert_eq!(
            first,
            PublishOutcome {
                replaced: false,
                superseded: None,
                empty: false
            }
        );
        let second = r.publish(p, MeshHandle(12), 66, 2);
        assert!(second.replaced);
        assert_eq!(second.superseded, Some(MeshHandle(11)));
        assert_eq!(r.get(p).map(|s| (s.mesh, s.index_count, s.revision)),
            Some((MeshHandle(12), 66, 2)));
        let st = r.stats();
        assert_eq!((st.published, st.replaced, st.resident), (1, 1, 1));
        assert_eq!(st.resident_indices, 66, "old count must not be double-counted");
    }

    #[test]
    fn empty_mesh_is_recorded_and_would_be_culled() {
        let mut r = SectionRegistry::new();
        let out = r.publish(pos(0, 0, 0), MeshHandle(5), 0, 3);
        assert!(out.empty, "an all-air section must be reported as empty");
        assert_eq!(r.stats().resident_indices, 0);
        assert_eq!(r.records()[0].index_count, 0);
        // plan_draws culls zero-index records, so it never reaches the backend.
        let eye = [8.0f32, 8.0, 64.0];
        let view_proj = crate::render::ortho_look_at(
            eye, [8.0, 8.0, 0.0], [0.0, 1.0, 0.0], 24.0, 24.0, 0.5, 100.0,
        )
        .expect("non-degenerate camera");
        let (batches, stats) = super::super::plan::plan_draws(r.records(), &view_proj, eye);
        assert!(batches.is_empty());
        assert_eq!((stats.considered, stats.visible, stats.culled), (1, 0, 1));
    }

    #[test]
    fn remove_returns_the_handle_and_drops_the_record() {
        let mut r = SectionRegistry::new();
        let p = pos(2, 3, 4);
        r.publish(p, MeshHandle(9), 24, 0);
        assert_eq!(r.remove(p), Some(MeshHandle(9)));
        assert_eq!(r.remove(p), None, "double removal must not free twice");
        assert!(r.is_empty());
        let st = r.stats();
        assert_eq!((st.published, st.removed, st.resident, st.resident_indices), (1, 1, 0, 0));
    }

    #[test]
    fn clear_returns_every_live_handle() {
        let mut r = SectionRegistry::new();
        for i in 0..5 {
            r.publish(pos(i, 0, 0), MeshHandle(100 + i as u64), 12, 0);
        }
        // An empty section still owns a GPU buffer and must be freed too.
        r.publish(pos(9, 9, 9), MeshHandle(200), 0, 0);
        let mut handles = r.clear();
        handles.sort_by_key(|h| h.0);
        let ids: Vec<u64> = handles.iter().map(|h| h.0).collect();
        assert_eq!(ids, vec![100, 101, 102, 103, 104, 200]);
        assert!(r.is_empty());
        assert_eq!(r.stats().resident, 0);
        assert_eq!(r.stats().removed, 6, "including the empty one");
    }

    #[test]
    fn records_are_deterministic_regardless_of_insert_order() {
        let build = |reverse: bool| -> Vec<u64> {
            let mut r = SectionRegistry::new();
            let mut keys: Vec<SectionPos> = Vec::new();
            for x in 0..4 {
                for z in 0..4 {
                    keys.push(pos(x, 1, z));
                }
            }
            if reverse {
                keys.reverse();
            }
            for (i, p) in keys.iter().enumerate() {
                r.publish(*p, MeshHandle(i as u64), 6, 0);
            }
            r.records().iter().map(|rec| rec.key).collect()
        };
        assert_eq!(build(false), build(true), "map order must not leak into the draw list");
    }

    #[test]
    fn record_origins_are_block_coordinates() {
        let mut r = SectionRegistry::new();
        r.publish(pos(3, -2, 5), MeshHandle(1), 6, 0);
        let rec = &r.records()[0];
        assert_eq!(rec.origin, [48, -32, 80], "origin must be section << 4");
        assert_eq!(rec.key, record_key(pos(3, -2, 5)));
    }

    #[test]
    fn records_are_rebuilt_only_after_mutation() {
        let mut r = SectionRegistry::new();
        r.publish(pos(0, 0, 0), MeshHandle(1), 6, 0);
        let first = r.records().len();
        let ptr_same = {
            let a = r.records().as_ptr();
            let b = r.records().as_ptr();
            core::ptr::eq(a, b)
        };
        assert_eq!(first, 1);
        assert!(ptr_same, "a clean registry must reuse its cached snapshot");
        r.publish(pos(1, 0, 0), MeshHandle(2), 6, 0);
        assert_eq!(r.records().len(), 2, "mutation must invalidate the snapshot");
    }
}
