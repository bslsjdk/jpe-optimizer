//! Per-frame world pipeline: chunk lifecycle to GPU draw list, in one loop.
//!
//! This is the wiring that makes [`crate::render::registry::SectionRegistry`]
//! useful: chunk state transitions produce resident sections, resident sections
//! produce draw records, and the records are culled and submitted by
//! [`crate::render::plan::plan_draws`]. Before this module the planner took a
//! caller-owned list and nothing in the crate produced one.
//!
//! # Thread model
//!
//! Renderer handles are thread-affine, so **only this thread uploads and draws**.
//! The CPU-heavy part — turning block data into geometry — runs on many threads
//! through [`crate::scheduler::parallel_map`], and the results are handed back as
//! owned [`MeshData`] which this thread uploads. That is why meshing is batched:
//! a chunk of geometry is built in parallel, then submitted serially.
//!
//! Block data comes from a [`BlockSource`] trait. The real source is the C++
//! Terrain Engine behind ABI v8; it is deliberately *not* called here, so this
//! pipeline is testable with a synthetic source and adds no native dependency.

use crate::coords::ChunkPos;
use crate::coords::SectionPos;
use crate::mesh::{
    build_section_mesh_with_neighbors, ChunkSection, MeshData, MeshScratch, Neighbors,
};
use crate::render::plan::{plan_draws, PlanStats};
use crate::render::registry::{record_key, SectionRegistry};
use crate::render::{MeshHandle, RenderError, Renderer};
use crate::scheduler::{parallel_map, Completion, SchedulerCore};
use crate::task::{ChunkJob, JobKind};
use std::collections::{BTreeMap, BTreeSet};
use std::time::Instant;

/// Per-frame phase timings, in nanoseconds, accumulated across the pipeline's
/// lifetime. This is deliberately **not** part of the Java ABI (`JpePipelineStats`
/// keeps its frozen 128-byte layout); it exists so every optimization can be
/// measured at the Rust level before it is exposed or relied upon.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct PhaseTiming {
    /// Frames sampled into these accumulators.
    pub samples: u64,
    pub pump_ns: u64,
    pub targets_ns: u64,
    pub build_ns: u64,
    pub publish_ns: u64,
    pub draw_ns: u64,
}

/// Where the pipeline gets block data for a section.
///
/// Implementations must be cheap to call repeatedly for the same position; the
/// pipeline caches what it fetches.
pub trait BlockSource {
    /// Blocks for `pos`. Only called for chunks whose terrain is ready.
    fn section(&mut self, pos: SectionPos) -> ChunkSection;
}

/// Uniform terrain: solid up to and including `surface_y`, air above.
///
/// Deterministic and dependency-free, which is what the pipeline tests need in
/// order to assert exact quad counts. It is **not** terrain generation.
#[derive(Debug, Default)]
pub struct FlatSource {
    pub surface_y: i32,
    pub block: u16,
    /// How many times the pipeline asked for section data.
    pub calls: u64,
}

impl FlatSource {
    pub fn new(surface_y: i32) -> Self {
        Self {
            surface_y,
            block: 1,
            calls: 0,
        }
    }
}

impl BlockSource for FlatSource {
    fn section(&mut self, pos: SectionPos) -> ChunkSection {
        self.calls += 1;
        let mut section = ChunkSection::new();
        let (_, min_y, _) = pos.origin_block();
        for local_y in 0..16usize {
            let world_y = min_y + local_y as i32;
            if world_y > self.surface_y || world_y < crate::coords::MIN_Y {
                continue;
            }
            for z in 0..16usize {
                for x in 0..16usize {
                    section.set(x, local_y, z, self.block);
                }
            }
        }
        section
    }
}

/// Block data handed in by the caller, one chunk at a time.
///
/// This is the source the FFI uses during the transitional period, while Java
/// still owns terrain: the world engine never generates blocks, it only receives
/// finished index arrays in [`ChunkSection::from_indices`] order. A section that
/// was never submitted reads as empty and is counted in `missing_reads` rather
/// than hidden — for a neighbour chunk that simply means "terrain not known yet,
/// so its boundary faces stay visible", which is the correct conservative result.
#[derive(Default, Debug)]
pub struct PushSource {
    chunks: BTreeMap<ChunkPos, BTreeMap<i32, ChunkSection>>,
    /// Sections stored by a submit (including replacements).
    pub sections_stored: u64,
    /// Sections overwritten by a later submit of the same position.
    pub sections_overwritten: u64,
    /// Reads of sections that were never submitted.
    pub missing_reads: u64,
}

impl PushSource {
    pub fn new() -> Self {
        Self::default()
    }

    /// Store `sections` for `chunk`, keyed by section Y from `base_section_y` up.
    /// Returns how many sections were stored.
    pub fn put_chunk(
        &mut self,
        chunk: ChunkPos,
        base_section_y: i32,
        sections: &[ChunkSection],
    ) -> usize {
        if sections.is_empty() {
            return 0;
        }
        let column = self.chunks.entry(chunk).or_default();
        for (i, section) in sections.iter().enumerate() {
            let y = base_section_y + i as i32;
            if column.insert(y, section.clone()).is_some() {
                self.sections_overwritten += 1;
            }
            self.sections_stored += 1;
        }
        sections.len()
    }

    /// True if the caller has handed in data for `chunk`.
    pub fn has_chunk(&self, chunk: ChunkPos) -> bool {
        self.chunks.contains_key(&chunk)
    }

    /// Chunks the caller has submitted data for.
    pub fn chunk_count(&self) -> usize {
        self.chunks.len()
    }

    /// Sections currently held.
    pub fn section_count(&self) -> usize {
        self.chunks.values().map(|column| column.len()).sum()
    }

    /// Release every submitted section whose chunk the scheduler no longer
    /// tracks. Without this, data for an unloaded chunk would survive and a
    /// reload in the same world epoch would resurrect stale terrain.
    pub fn flush_untracked(&mut self, tracked: impl Fn(ChunkPos) -> bool) -> usize {
        let stale: Vec<ChunkPos> = self
            .chunks
            .keys()
            .filter(|chunk| !tracked(**chunk))
            .copied()
            .collect();
        let mut dropped = 0;
        for chunk in stale {
            if let Some(column) = self.chunks.remove(&chunk) {
                dropped += column.len();
            }
        }
        dropped
    }
}

impl BlockSource for PushSource {
    fn section(&mut self, pos: SectionPos) -> ChunkSection {
        match self
            .chunks
            .get(&pos.chunk())
            .and_then(|column| column.get(&pos.y))
        {
            Some(section) => section.clone(),
            None => {
                self.missing_reads += 1;
                ChunkSection::new()
            }
        }
    }
}

/// Per-frame limits. Every budget is an upper bound: the pipeline does less work
/// when there is nothing to do.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct PipelineConfig {
    /// Maximum terrain jobs finished per frame.
    pub terrain_budget: usize,
    /// Maximum mesh jobs built and uploaded per frame.
    pub mesh_budget: usize,
    /// Upper bound on chunks re-meshed per frame because new neighbour terrain
    /// made their boundary faces hidden. Remeshes draw from this budget, not
    /// from `mesh_budget`, and a remesh declined here stays queued untouched,
    /// so the frame processes at most `mesh_budget` fresh meshes plus at most
    /// `remesh_budget` remeshes, and fresh meshes are never held behind remeshes
    /// whose budget is already spent.
    pub remesh_budget: usize,
    /// Worker threads used for geometry construction.
    pub threads: usize,
    /// Inclusive section-Y range meshed per chunk.
    pub section_y: (i32, i32),
    /// Offscreen target size.
    pub extent: [u32; 2],
}

impl Default for PipelineConfig {
    fn default() -> Self {
        Self {
            terrain_budget: 8,
            mesh_budget: 2,
            remesh_budget: 4,
            threads: 1,
            section_y: (crate::coords::MIN_SECTION_Y, crate::coords::MIN_SECTION_Y + 7),
            extent: [128, 128],
        }
    }
}

impl PipelineConfig {
    /// Sections meshed per chunk under this config.
    pub fn sections_per_chunk(&self) -> usize {
        (self.section_y.1 - self.section_y.0 + 1).max(0) as usize
    }
}

/// Lifetime counters for the pipeline.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct PipelineStats {
    pub frames: u64,
    pub terrain_jobs: u64,
    pub mesh_jobs: u64,
    pub remesh_chunks: u64,
    /// Sections published for the first time.
    pub published: u64,
    /// Sections whose GPU mesh was replaced.
    pub replaced: u64,
    /// Sections that meshed to nothing and were not uploaded at all.
    pub empty_sections: u64,
    /// GPU meshes destroyed (superseded or unloaded).
    pub meshes_destroyed: u64,
    pub chunks_unloaded: u64,
    pub sections_unloaded: u64,
    /// Cached block arrays released (superset of `sections_unloaded`: includes
    /// sections that never reached the GPU).
    pub sections_dropped: u64,
    /// Chunks waiting for queue space to rebuild a resident mesh.
    pub pending_remesh: u64,
    /// Chunks waiting for queue space to mesh loaded terrain for the first time.
    pub pending_mesh: u64,
}

/// What one [`WorldPipeline::frame`] did.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct FrameOutcome {
    pub terrain_jobs: usize,
    pub mesh_jobs: usize,
    pub remesh_chunks: usize,
    pub published: usize,
    pub replaced: usize,
    pub destroyed: usize,
    /// Resident sections considered by the planner.
    pub resident: usize,
    pub batches: usize,
    pub plan: PlanStats,
}

/// The per-frame camera: a column-major view-projection matrix plus the eye
/// position used for front-to-back ordering. Same convention as
/// [`crate::render::ortho_look_at`], shared by the planner and every backend.
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Camera {
    pub view_proj: crate::render::Mat4,
    pub eye: [f32; 3],
}

/// One build target: a section plus the origins it must be meshed at.
#[derive(Copy, Clone, Debug)]
struct Target {
    pos: SectionPos,
    origin: [i32; 3],
    /// Chunk revision the resulting mesh is valid for.
    revision: u64,
}

/// Owns the scheduler, the GPU section registry and the per-frame loop.
pub struct WorldPipeline<S: BlockSource> {
    scheduler: SchedulerCore,
    source: S,
    registry: SectionRegistry,
    /// Cached block data for loaded sections, so neighbour culling never
    /// re-queries the source mid-frame.
    blocks: BTreeMap<SectionPos, ChunkSection>,
    /// Resident chunks whose boundary geometry is stale because a neighbour's
    /// terrain just became available.
    pending_remesh: BTreeSet<ChunkPos>,
    /// Chunks whose terrain is ready but whose mesh request the queue rejected.
    pending_mesh: BTreeSet<ChunkPos>,
    config: PipelineConfig,
    stats: PipelineStats,
    /// Rust-only per-phase timing accumulators (not exposed over the Java ABI).
    timing: PhaseTiming,
}

impl<S: BlockSource> WorldPipeline<S> {
    pub fn new(
        world_epoch: u64,
        queue_capacity: usize,
        config: PipelineConfig,
        source: S,
    ) -> Self {
        Self {
            scheduler: SchedulerCore::new(world_epoch, queue_capacity),
            source,
            registry: SectionRegistry::new(),
            blocks: BTreeMap::new(),
            pending_remesh: BTreeSet::new(),
            pending_mesh: BTreeSet::new(),
            config,
            stats: PipelineStats::default(),
            timing: PhaseTiming::default(),
        }
    }

    /// Accumulated Rust-only per-phase timings. Not part of the Java ABI.
    pub fn timing(&self) -> PhaseTiming {
        self.timing
    }

    pub fn config(&self) -> PipelineConfig {
        self.config
    }

    pub fn stats(&self) -> PipelineStats {
        PipelineStats {
            pending_remesh: self.pending_remesh.len() as u64,
            pending_mesh: self.pending_mesh.len() as u64,
            ..self.stats
        }
    }

    pub fn scheduler(&self) -> &SchedulerCore {
        &self.scheduler
    }

    pub fn registry(&self) -> &SectionRegistry {
        &self.registry
    }

    pub fn source(&self) -> &S {
        &self.source
    }

    pub fn set_player(&mut self, player: ChunkPos) {
        self.scheduler.set_player_chunk(player);
    }

    /// Queue terrain for every chunk within `radius` of `center` that is not
    /// already tracked. Returns how many chunks were newly queued.
    pub fn load_area(&mut self, center: ChunkPos, radius: i32) -> usize {
        let mut queued = 0;
        for dz in -radius..=radius {
            for dx in -radius..=radius {
                let pos = ChunkPos::new(center.x + dx, center.z + dz);
                if self.scheduler.store().state(pos).is_some() {
                    continue;
                }
                if self.scheduler.request_terrain(pos).is_ok() {
                    queued += 1;
                }
            }
        }
        queued
    }

    /// True once block data for the chunk exists and may be read.
    fn terrain_loaded(&self, pos: ChunkPos) -> bool {
        matches!(
            self.scheduler.store().state(pos),
            Some(
                crate::chunk::ChunkState::TerrainReady
                    | crate::chunk::ChunkState::MeshQueued
                    | crate::chunk::ChunkState::Meshing
                    | crate::chunk::ChunkState::MeshReady
                    | crate::chunk::ChunkState::Uploaded
            )
        )
    }

    /// Section positions covered by the config's Y range for one chunk.
    fn section_positions(&self, chunk: ChunkPos) -> Vec<SectionPos> {
        let (lo, hi) = self.config.section_y;
        (lo..=hi)
            .filter(|y| (crate::coords::MIN_SECTION_Y..=crate::coords::MAX_SECTION_Y).contains(y))
            .map(|y| SectionPos::new(chunk.x, y, chunk.z))
            .collect()
    }

    /// Fetch and cache block data for `pos`, and for the six sections that
    /// touch it, so boundary culling has something to test against.
    fn prefetch(&mut self, pos: SectionPos) {
        if self.blocks.contains_key(&pos) {
            return;
        }
        if !self.terrain_loaded(pos.chunk()) {
            return;
        }
        let data = self.source.section(pos);
        self.blocks.insert(pos, data);
    }

    fn prefetch_with_neighbors(&mut self, pos: SectionPos) {
        self.prefetch(pos);
        let (x, y, z) = (pos.x, pos.y, pos.z);
        for (dx, dy, dz) in [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)] {
            let neighbour = SectionPos::new(x + dx, y + dy, z + dz);
            // Vertical neighbours outside the world have no sections at all.
            if y + dy < crate::coords::MIN_SECTION_Y || y + dy > crate::coords::MAX_SECTION_Y {
                continue;
            }
            self.prefetch(neighbour);
        }
    }

    /// Build geometry for `targets` on `threads` workers. Only reads cached
    /// block data, so it needs no renderer and no render thread.
    fn build_geometry(&self, targets: &[Target]) -> Vec<MeshData> {
        // One reusable scratch buffer per worker thread, so `build_section_mesh_into`
        // keeps its capacity warm across sections instead of growing from zero on
        // every target (the contract `MeshScratch` documents, mesh.rs:172). The
        // produced vertices/indices are cloned into owned `MeshData`; the clone costs
        // a single copy of the final buffers, which is strictly cheaper than the
        // geometric re-growth (and repeated reallocation) the old per-target
        // `MeshScratch::new()` paid for each section.
        std::thread_local! {
            static SCRATCH: std::cell::RefCell<MeshScratch> =
                const { std::cell::RefCell::new(MeshScratch::new()) };
        }
        let blocks = &self.blocks;
        parallel_map(targets, self.config.threads, |_index, target| {
            let Some(section) = blocks.get(&target.pos) else {
                return MeshData {
                    vertices: Vec::new(),
                    indices: Vec::new(),
                    quad_count: 0,
                    origin: target.origin,
                };
            };
            let (x, y, z) = (target.pos.x, target.pos.y, target.pos.z);
            let neighbors = Neighbors {
                pos_x: blocks.get(&SectionPos::new(x + 1, y, z)),
                neg_x: blocks.get(&SectionPos::new(x - 1, y, z)),
                pos_y: blocks.get(&SectionPos::new(x, y + 1, z)),
                neg_y: blocks.get(&SectionPos::new(x, y - 1, z)),
                pos_z: blocks.get(&SectionPos::new(x, y, z + 1)),
                neg_z: blocks.get(&SectionPos::new(x, y, z - 1)),
            };
            SCRATCH.with(|cell| {
                let mut scratch = cell.borrow_mut();
                // build_section_mesh_into clears the scratch itself (mesh.rs:225), so
                // the reused buffers start empty for this section.
                let quad_count =
                    build_section_mesh_with_neighbors(section, &neighbors, target.origin, &mut scratch);
                MeshData {
                    vertices: scratch.vertices.clone(),
                    indices: scratch.indices.clone(),
                    quad_count,
                    origin: target.origin,
                }
            })
        })
    }

    /// Upload built geometry and publish it in the registry.
    ///
    /// Empty sections are recorded as empty rather than uploaded: a mesh with no
    /// indices would allocate a GPU buffer that can never be drawn.
    fn publish_geometry<R: Renderer + ?Sized>(
        &mut self,
        renderer: &mut R,
        targets: &[Target],
        meshes: Vec<MeshData>,
        outcome: &mut FrameOutcome,
    ) -> Result<(), RenderError> {
        for (target, mesh) in targets.iter().zip(meshes.into_iter()) {
            let index_count = mesh.indices.len() as u32;
            if mesh.quad_count == 0 {
                self.stats.empty_sections += 1;
                // An empty result must also retire a previously resident mesh:
                // otherwise a section whose blocks were removed stays on the GPU
                // and stays in the draw list forever.
                if let Some(old) = self.registry.remove(target.pos) {
                    renderer.destroy_mesh(old);
                    self.stats.meshes_destroyed += 1;
                    outcome.destroyed += 1;
                }
                continue;
            }
            let handle = renderer.create_mesh(&mesh)?;
            let published = self
                .registry
                .publish(target.pos, handle, index_count, target.revision);
            if let Some(old) = published.superseded {
                renderer.destroy_mesh(old);
                self.stats.meshes_destroyed += 1;
                outcome.destroyed += 1;
                self.stats.replaced += 1;
                outcome.replaced += 1;
            } else {
                self.stats.published += 1;
                outcome.published += 1;
            }
        }
        Ok(())
    }

    /// Prepare the build targets for a chunk, caching block data first.
    fn targets_for(&mut self, chunk: ChunkPos) -> Vec<Target> {
        let revision = self
            .scheduler
            .store()
            .get(chunk)
            .map(|entry| entry.revision)
            .unwrap_or(0);
        let positions = self.section_positions(chunk);
        for pos in &positions {
            self.prefetch_with_neighbors(*pos);
        }
        positions
            .into_iter()
            .map(|pos| {
                let (x, y, z) = pos.origin_block();
                Target {
                    pos,
                    origin: [x, y, z],
                    revision,
                }
            })
            .collect()
    }

    /// Called when a chunk's terrain becomes available: schedule its meshing, and
    /// queue a remesh for every already-uploaded neighbour whose boundary faces
    /// may now be hidden. Work the queue cannot take is retried on a later frame.
    fn on_terrain_ready(&mut self, chunk: ChunkPos) {
        if self.scheduler.request_mesh(chunk).is_err() {
            self.pending_mesh.insert(chunk);
        }
        let (x, z) = (chunk.x, chunk.z);
        for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
            let neighbour = ChunkPos::new(x + dx, z + dz);
            match self.scheduler.store().state(neighbour) {
                Some(crate::chunk::ChunkState::Uploaded) => {
                    if self.scheduler.request_remesh(neighbour).is_err() {
                        self.pending_remesh.insert(neighbour);
                    }
                }
                Some(crate::chunk::ChunkState::TerrainReady) => {
                    // Its terrain landed before its own mesh request did.
                    if self.scheduler.request_mesh(neighbour).is_err() {
                        self.pending_mesh.insert(neighbour);
                    }
                }
                _ => {}
            }
        }
    }

    /// Re-queue work that the bounded queue could not accept earlier.
    fn retry_deferred(&mut self) {
        let remesh: Vec<ChunkPos> = self.pending_remesh.iter().copied().collect();
        for chunk in remesh {
            if self.scheduler.request_remesh(chunk).is_ok() {
                self.pending_remesh.remove(&chunk);
            }
        }
        let mesh: Vec<ChunkPos> = self.pending_mesh.iter().copied().collect();
        for chunk in mesh {
            if self.scheduler.request_mesh(chunk).is_ok() {
                self.pending_mesh.remove(&chunk);
            }
        }
    }

    /// Run one frame: pump scheduled work, upload results, cull, draw.
    ///
    /// The camera is supplied by the caller every frame; the planner and all
    /// backends share exactly this matrix.
    pub fn frame<R: Renderer + ?Sized>(
        &mut self,
        renderer: &mut R,
        camera: Camera,
    ) -> Result<FrameOutcome, RenderError> {
        let Camera { view_proj, eye } = camera;
        let mut outcome = FrameOutcome::default();
        self.stats.frames += 1;
        self.retry_deferred();

        // Phases 1+2: one pump over the budgets. Terrain completions enqueue mesh
        // work, so mesh jobs have to be taken in the same pass — a separate terrain
        // loop would let that newly queued work bypass the mesh budget.
        //
        // Fresh meshes and remeshes draw from *separate* budgets: the pump asks
        // the scheduler for a job of a kind whose budget is not yet spent. A
        // declined head stays queued with no in-progress transition (see
        // `SchedulerCore::take_job_where`), so an exhausted remesh budget neither
        // runs extra remeshes nor holds fresh meshes hostage behind them.
        let pump_start = Instant::now();
        let mut mesh_jobs: Vec<(ChunkJob, bool)> = Vec::new();
        let terrain_budget = self.config.terrain_budget;
        let mesh_budget = self.config.mesh_budget;
        let remesh_budget = self.config.remesh_budget;
        let mut terrain_done = 0usize;
        let mut mesh_taken = 0usize;
        let mut remesh_taken = 0usize;
        while terrain_done < terrain_budget
            || mesh_taken < mesh_budget
            || remesh_taken < remesh_budget
        {
            let terrain_left = terrain_done < terrain_budget;
            let mesh_left = mesh_taken < mesh_budget;
            let remesh_left = remesh_taken < remesh_budget;
            let Some(job) = self.scheduler.take_job_where(|kind| match kind {
                JobKind::GenerateTerrain => terrain_left,
                JobKind::BuildMesh => mesh_left,
                JobKind::RemeshMesh => remesh_left,
                // Lifecycle work (upload/unload) is not budgeted: it is cheap
                // and delaying it would only stall a chunk's state machine.
                JobKind::UploadMesh | JobKind::Unload => true,
            }) else {
                break;
            };
            match job.kind {
                JobKind::GenerateTerrain => {
                    if matches!(self.scheduler.complete(job), Completion::Committed { .. }) {
                        terrain_done += 1;
                        self.stats.terrain_jobs += 1;
                        outcome.terrain_jobs += 1;
                        self.on_terrain_ready(job.pos);
                    }
                }
                JobKind::BuildMesh | JobKind::RemeshMesh => {
                    let is_remesh = job.kind == JobKind::RemeshMesh;
                    mesh_jobs.push((job, is_remesh));
                    if is_remesh {
                        remesh_taken += 1;
                    } else {
                        mesh_taken += 1;
                    }
                }
                _ => {
                    self.scheduler.complete(job);
                }
            }
        }
        let pump_ns = pump_start.elapsed().as_nanos() as u64;

        // Phase 3: build everything in parallel, then upload on this thread.
        let targets_start = Instant::now();
        let mut targets: Vec<Target> = Vec::new();
        let mut accepted: Vec<(ChunkJob, bool)> = Vec::new();
        for (job, is_remesh) in mesh_jobs {
            let chunk_targets = self.targets_for(job.pos);
            if chunk_targets.is_empty() {
                self.scheduler.complete(job);
                continue;
            }
            targets.extend(chunk_targets);
            accepted.push((job, is_remesh));
        }
        let targets_ns = targets_start.elapsed().as_nanos() as u64;

        let build_start = Instant::now();
        let meshes = self.build_geometry(&targets);
        let build_ns = build_start.elapsed().as_nanos() as u64;

        let publish_start = Instant::now();
        self.publish_geometry(renderer, &targets, meshes, &mut outcome)?;
        let publish_ns = publish_start.elapsed().as_nanos() as u64;

        // Counters are derived from the jobs actually accepted (a job whose chunk
        // produced no targets was completed and excluded above), split by kind, so
        // `mesh_jobs + remesh_chunks` equals the accepted job count exactly. The old
        // `accepted.len() - remesh_seen` form could underflow because `remesh_seen`
        // counted dequeued remeshes, including ones dropped for having no targets.
        let remesh_accepted = accepted.iter().filter(|(_, r)| *r).count();
        let mesh_accepted = accepted.len() - remesh_accepted;
        outcome.mesh_jobs = mesh_accepted;
        outcome.remesh_chunks = remesh_accepted;
        self.stats.mesh_jobs += mesh_accepted as u64;
        self.stats.remesh_chunks += remesh_accepted as u64;
        for (job, _) in accepted {
            let _ = self.scheduler.complete(job);
            let _ = self.scheduler.mark_uploaded(job.pos);
        }

        // Phase 4: cull and submit.
        let draw_start = Instant::now();
        renderer.set_view_proj(view_proj)?;
        renderer.begin_frame(self.config.extent)?;
        let (batches, plan) = plan_draws(self.registry.records(), &view_proj, eye);
        for batch in &batches {
            renderer.draw(*batch)?;
        }
        renderer.end_frame()?;
        let draw_ns = draw_start.elapsed().as_nanos() as u64;

        outcome.plan = plan;
        outcome.batches = batches.len();
        outcome.resident = self.registry.len();

        self.timing.samples += 1;
        self.timing.pump_ns += pump_ns;
        self.timing.targets_ns += targets_ns;
        self.timing.build_ns += build_ns;
        self.timing.publish_ns += publish_ns;
        self.timing.draw_ns += draw_ns;

        Ok(outcome)
    }

    /// Unload every tracked chunk outside `radius` of `center`, destroying their
    /// GPU meshes on this (render) thread before dropping the entries.
    ///
    /// Chunks with in-flight work are skipped rather than torn down mid-job, so
    /// the state machine never sees an out-of-order unload.
    pub fn unload_outside<R: Renderer + ?Sized>(
        &mut self,
        renderer: &mut R,
        center: ChunkPos,
        radius: i32,
    ) -> usize {
        let doomed: Vec<ChunkPos> = self
            .scheduler
            .store()
            .iter()
            .filter(|entry| !entry.pos.within_radius(center, radius))
            .map(|entry| entry.pos)
            .collect();
        let mut unloaded = 0;
        for chunk in doomed {
            if self
                .scheduler
                .store()
                .get(chunk)
                .is_some_and(|entry| entry.tickets > 0)
            {
                continue;
            }
            if self.scheduler.unload_now(chunk).is_err() {
                continue;
            }
            unloaded += 1;
            self.stats.chunks_unloaded += 1;
            self.pending_remesh.remove(&chunk);
            self.pending_mesh.remove(&chunk);
            let (x, z) = (chunk.x, chunk.z);
            let resident: Vec<SectionPos> = self
                .registry
                .iter()
                .filter(|s| s.pos.x == x && s.pos.z == z)
                .map(|s| s.pos)
                .collect();
            for pos in resident {
                if let Some(handle) = self.registry.remove(pos) {
                    renderer.destroy_mesh(handle);
                    self.stats.meshes_destroyed += 1;
                    self.stats.sections_unloaded += 1;
                }
            }
            // The block cache also holds sections that meshed to nothing and were
            // never uploaded, so it must be swept by position rather than by
            // registry membership — otherwise every air section leaks its blocks.
            let cached: Vec<SectionPos> = self
                .blocks
                .keys()
                .filter(|pos| pos.x == x && pos.z == z)
                .copied()
                .collect();
            for pos in cached {
                self.blocks.remove(&pos);
                self.stats.sections_dropped += 1;
            }
        }
        unloaded
    }

    /// Drop all GPU data and restart the world epoch. Returns the **new** epoch,
    /// the same convention as `jpe_world_reset` on the scheduler ABI.
    pub fn reset<R: Renderer + ?Sized>(&mut self, renderer: &mut R) -> u64 {
        self.scheduler.reset_for_new_world();
        let epoch = self.scheduler.world_epoch();
        let handles: Vec<MeshHandle> = self.registry.clear();
        for handle in handles {
            renderer.destroy_mesh(handle);
            self.stats.meshes_destroyed += 1;
        }
        self.blocks.clear();
        self.pending_remesh.clear();
        self.pending_mesh.clear();
        epoch
    }

    /// Hold a chunk open against unloading. Mirrors the Java-side keep-alive
    /// need: an entity or a pending block update can pin a chunk.
    pub fn acquire_ticket(&mut self, pos: ChunkPos) -> Result<u32, crate::scheduler::SchedError> {
        self.scheduler.acquire_ticket(pos)
    }

    /// Release one ticket. Returns the remaining count.
    pub fn release_ticket(&mut self, pos: ChunkPos) -> Result<u32, crate::scheduler::SchedError> {
        self.scheduler.release_ticket(pos)
    }

    /// Sections whose block data is cached, i.e. the CPU-side memory the
    /// pipeline is holding on to.
    pub fn cached_section_count(&self) -> usize {
        self.blocks.len()
    }

    /// Registry keys currently resident, for tests and diagnostics.
    pub fn resident_keys(&mut self) -> Vec<u64> {
        let mut keys: Vec<u64> = self
            .registry
            .iter()
            .map(|s| record_key(s.pos))
            .collect();
        keys.sort_unstable();
        keys
    }
}

/// What a [`WorldPipeline::submit_chunk`] did to the chunk's lifecycle.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Submit {
    /// Chunk was unknown: terrain work is now queued.
    New,
    /// Chunk's terrain was ready but unmeshed: a mesh job is now queued.
    MeshRequested,
    /// Chunk was drawable: a remesh of it and of its loaded horizontal
    /// neighbours is queued, so the old mesh stays drawn until the new one lands.
    RemeshRequested,
    /// Data was stored but nothing was queued, because the chunk already has
    /// work in flight or the bounded queue rejected the request.
    InFlight,
}

impl WorldPipeline<PushSource> {
    /// Hand finished block data to the world engine. This is the transitional
    /// entry point while Java owns terrain: the caller submits whole chunks, and
    /// the scheduler, mesher, registry and planner do the rest.
    ///
    /// Re-submitting a chunk replaces its stored sections and drops the cached
    /// copies, so the remesh reads the new data instead of the stale cache.
    pub fn submit_chunk(
        &mut self,
        chunk: ChunkPos,
        base_section_y: i32,
        sections: &[ChunkSection],
    ) -> Submit {
        let stored = self.source.put_chunk(chunk, base_section_y, sections);
        if stored == 0 {
            return Submit::InFlight;
        }
        match self.scheduler.store().state(chunk) {
            None => {
                self.invalidate_chunk_blocks(chunk);
                if self.scheduler.request_terrain(chunk).is_ok() {
                    Submit::New
                } else {
                    // Chunk stays `Empty` and requestable; the next frame retries.
                    Submit::InFlight
                }
            }
            Some(crate::chunk::ChunkState::TerrainReady) => {
                self.invalidate_chunk_blocks(chunk);
                if self.scheduler.request_mesh(chunk).is_ok() {
                    Submit::MeshRequested
                } else {
                    self.pending_mesh.insert(chunk);
                    Submit::InFlight
                }
            }
            Some(crate::chunk::ChunkState::Uploaded) => {
                self.invalidate_chunk_blocks(chunk);
                self.request_remesh(chunk);
                let (x, z) = (chunk.x, chunk.z);
                for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
                    let neighbour = ChunkPos::new(x + dx, z + dz);
                    if self.terrain_loaded(neighbour) {
                        self.request_remesh(neighbour);
                    }
                }
                Submit::RemeshRequested
            }
            Some(_) => Submit::InFlight,
        }
    }

    fn request_remesh(&mut self, chunk: ChunkPos) {
        if self.scheduler.request_remesh(chunk).is_ok() {
            self.pending_remesh.remove(&chunk);
        } else {
            self.pending_remesh.insert(chunk);
        }
    }

    /// Drop the cached block arrays for one chunk so the next mesh reads the
    /// source again.
    fn invalidate_chunk_blocks(&mut self, chunk: ChunkPos) {
        let (x, z) = (chunk.x, chunk.z);
        let stale: Vec<SectionPos> = self
            .blocks
            .keys()
            .filter(|pos| pos.x == x && pos.z == z)
            .copied()
            .collect();
        for pos in stale {
            self.blocks.remove(&pos);
        }
    }

    /// Release submitted source data for chunks the scheduler no longer tracks.
    /// The FFI calls this after every unload sweep so a reload within the same
    /// world epoch cannot resurrect stale terrain. Returns sections released.
    pub fn flush_source_data(&mut self) -> usize {
        self.source
            .flush_untracked(|chunk| self.scheduler.store().get(chunk).is_some())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chunk::ChunkState;
    use crate::render::headless::HeadlessRenderer;
    use crate::render::ortho_look_at;

    /// Orthographic camera looking along -Z at the section column around x=8.
    fn camera() -> Camera {
        let eye = [8.0f32, 8.0, 96.0];
        let view_proj =
            ortho_look_at(eye, [8.0, 8.0, 0.0], [0.0, 1.0, 0.0], 48.0, 48.0, 0.5, 256.0)
                .expect("non-degenerate camera");
        Camera { view_proj, eye }
    }

    fn pipeline(surface_y: i32, config: PipelineConfig) -> WorldPipeline<FlatSource> {
        WorldPipeline::new(1, 64, config, FlatSource::new(surface_y))
    }

    #[test]
    fn flat_source_fills_only_the_rows_at_or_below_the_surface() {
        let mut source = FlatSource::new(17);
        let section = source.section(SectionPos::new(0, 1, 0)); // world y = 16..=31
        assert!(section.is_solid(0, 0, 0), "y=16 is at/below the surface");
        assert!(section.is_solid(15, 1, 15), "y=17 is the surface row");
        assert!(!section.is_solid(0, 2, 0), "y=18 is air");
        assert_eq!(section.get(0, 0, 0), 1);
        assert_eq!(source.calls, 1);
    }

    #[test]
    fn a_frame_pumps_terrain_then_mesh_and_populates_the_registry() {
        let config = PipelineConfig {
            section_y: (1, 1),
            ..PipelineConfig::default()
        };
        let mut world = pipeline(17, config);
        let mut renderer = HeadlessRenderer::new();
        let camera = camera();

        assert_eq!(world.load_area(ChunkPos::ZERO, 0), 1);
        let first = world.frame(&mut renderer, camera).expect("frame");
        assert_eq!(first.terrain_jobs, 1);
        assert_eq!(first.mesh_jobs, 1);
        assert_eq!(first.published, 1);
        assert_eq!(world.registry().len(), 1);
        assert_eq!(
            world.scheduler().store().state(ChunkPos::ZERO),
            Some(ChunkState::Uploaded),
            "the lifecycle must reach Uploaded through the pipeline alone"
        );
        assert_eq!(renderer.draw_count(), first.batches);

        // Nothing left to do: the second frame only draws.
        let second = world.frame(&mut renderer, camera).expect("frame 2");
        assert_eq!((second.terrain_jobs, second.mesh_jobs, second.published), (0, 0, 0));
        assert!(second.batches >= 1);
        assert_eq!(world.stats().frames, 2);
    }

    #[test]
    fn draw_list_is_produced_by_the_pipeline_not_the_caller() {
        let config = PipelineConfig {
            section_y: (1, 3),
            ..PipelineConfig::default()
        };
        // Surface above the whole Y range, so every section in range is solid.
        let mut world = pipeline(63, config);
        let mut renderer = HeadlessRenderer::new();
        world.load_area(ChunkPos::ZERO, 0);
        let frame = world.frame(&mut renderer, camera()).expect("frame");
        assert_eq!(frame.resident, 3);
        assert_eq!(frame.plan.considered, 3);
        assert_eq!(frame.batches, frame.plan.visible as usize);
        assert_eq!(renderer.draw_count(), frame.batches);
    }

    #[test]
    fn the_same_resident_section_is_culled_when_the_camera_turns_away() {
        let config = PipelineConfig {
            section_y: (1, 1),
            ..PipelineConfig::default()
        };
        let mut world = pipeline(17, config);
        let mut renderer = HeadlessRenderer::new();
        world.load_area(ChunkPos::ZERO, 0);
        world.frame(&mut renderer, camera()).expect("frame");
        let drawn_once = renderer.draw_count();

        let eye = [8.0f32, 8.0, -400.0];
        let away = Camera {
            view_proj: ortho_look_at(
                eye,
                [8.0, 8.0, -500.0],
                [0.0, 1.0, 0.0],
                48.0,
                48.0,
                0.5,
                256.0,
            )
            .expect("camera"),
            eye,
        };
        let frame = world.frame(&mut renderer, away).expect("frame away");
        assert_eq!(frame.resident, 1, "culling must not drop the GPU resource");
        assert_eq!((frame.plan.visible, frame.plan.culled), (0, 1));
        assert_eq!(frame.batches, 0);
        assert_eq!(drawn_once, 1, "the first frame did draw");
        // `draw_count` is per frame (cleared by begin_frame), so this is the
        // away frame's submission count: it must be zero.
        assert_eq!(renderer.draw_count(), 0, "no batch may reach the renderer");
        assert_eq!(renderer.frame_count(), 2);
    }

    #[test]
    fn neighbour_terrain_queues_a_remesh_that_shrinks_the_old_mesh() {
        let config = PipelineConfig {
            section_y: (1, 1),
            mesh_budget: 4,
            ..PipelineConfig::default()
        };
        let mut world = pipeline(17, config);
        let mut renderer = HeadlessRenderer::new();
        let camera = camera();

        world.load_area(ChunkPos::ZERO, 0);
        world.frame(&mut renderer, camera).expect("first");
        let alone = *world.registry().get(SectionPos::new(0, 1, 0)).unwrap();

        world.load_area(ChunkPos::new(1, 0), 0);
        let second = world.frame(&mut renderer, camera).expect("second");
        assert_eq!(second.terrain_jobs, 1);
        assert_eq!(second.remesh_chunks, 1, "chunk (0,0) was re-meshed");
        assert!(second.replaced >= 1, "and its GPU mesh replaced");

        let after = *world.registry().get(SectionPos::new(0, 1, 0)).unwrap();
        assert!(
            after.index_count < alone.index_count,
            "boundary faces must go away: {} -> {}",
            alone.index_count,
            after.index_count
        );
        assert_ne!(after.mesh, alone.mesh, "a new handle must be allocated");
        assert!(
            renderer.destroyed_meshes().contains(&alone.mesh.0),
            "the superseded mesh must be destroyed, not leaked"
        );
        assert_eq!(after.revision > alone.revision, true, "revision must advance");
    }

    #[test]
    fn unload_destroys_every_mesh_and_cache_for_the_chunk() {
        let config = PipelineConfig {
            section_y: (0, 2),
            ..PipelineConfig::default()
        };
        let mut world = pipeline(63, config);
        let mut renderer = HeadlessRenderer::new();
        world.load_area(ChunkPos::ZERO, 0);
        world.frame(&mut renderer, camera()).expect("frame");
        assert_eq!(world.registry().len(), 3);
        let live_before = renderer.live_mesh_count();
        assert!(world.cached_section_count() > 0);

        // (0,0) is outside a radius-0 view centred far away, so it must be torn down.
        assert_eq!(world.unload_outside(&mut renderer, ChunkPos::new(20, 20), 0), 1);
        assert!(world.registry().is_empty());
        assert_eq!(
            world.cached_section_count(),
            0,
            "block cache must be dropped too, including sections that never uploaded"
        );
        assert_eq!(renderer.live_mesh_count(), live_before - 3);
        let stats = world.stats();
        assert_eq!((stats.sections_unloaded, stats.chunks_unloaded), (3, 1));
        assert_eq!(stats.meshes_destroyed, 3);
        assert!(
            stats.sections_dropped >= 4,
            "the all-air section's blocks must be released too: {}",
            stats.sections_dropped
        );
    }

    #[test]
    fn a_ticketed_chunk_survives_the_unload_sweep() {
        let config = PipelineConfig {
            section_y: (1, 1),
            ..PipelineConfig::default()
        };
        let mut world = pipeline(17, config);
        let mut renderer = HeadlessRenderer::new();
        world.load_area(ChunkPos::ZERO, 0);
        world.frame(&mut renderer, camera()).expect("frame");
        let live = renderer.live_mesh_count();

        assert_eq!(world.acquire_ticket(ChunkPos::ZERO).unwrap(), 1);
        assert_eq!(
            world.unload_outside(&mut renderer, ChunkPos::new(50, 50), 0),
            0,
            "a held chunk must not be torn down"
        );
        assert_eq!(world.registry().len(), 1);
        assert_eq!(renderer.live_mesh_count(), live);

        assert_eq!(world.release_ticket(ChunkPos::ZERO).unwrap(), 0);
        assert_eq!(world.unload_outside(&mut renderer, ChunkPos::new(50, 50), 0), 1);
        assert!(world.registry().is_empty());
        assert_eq!(renderer.live_mesh_count(), 0);
    }

    #[test]
    fn reset_frees_every_gpu_mesh_and_invalidates_the_epoch() {
        let config = PipelineConfig {
            section_y: (1, 2),
            ..PipelineConfig::default()
        };
        let mut world = pipeline(63, config);
        let mut renderer = HeadlessRenderer::new();
        world.load_area(ChunkPos::ZERO, 0);
        world.frame(&mut renderer, camera()).expect("frame");
        assert_eq!(renderer.live_mesh_count(), 2);

        let epoch = world.reset(&mut renderer);
        assert_eq!(epoch, 2, "reset reports the new epoch");
        assert_eq!(world.scheduler().world_epoch(), 2);
        assert!(world.registry().is_empty());
        assert_eq!(renderer.live_mesh_count(), 0, "both meshes must be freed");
        assert_eq!(world.stats().meshes_destroyed, 2);
    }

    #[test]
    fn budgets_are_hard_upper_bounds_per_frame() {
        let config = PipelineConfig {
            terrain_budget: 2,
            mesh_budget: 1,
            section_y: (1, 1),
            ..PipelineConfig::default()
        };
        let mut world = pipeline(17, config);
        let mut renderer = HeadlessRenderer::new();
        assert_eq!(world.load_area(ChunkPos::ZERO, 2), 25);
        let frame = world.frame(&mut renderer, camera()).expect("frame");
        assert_eq!(frame.terrain_jobs, 2, "terrain budget respected");
        assert_eq!(frame.mesh_jobs, 1, "mesh budget respected");
        assert!(world.scheduler().queue_len() > 0, "the rest stays queued");
        assert_eq!(world.stats().terrain_jobs, 2);
    }

    #[test]
    fn remesh_budget_is_a_separate_hard_upper_bound_per_frame() {
        // Remeshes used to draw from the mesh budget, so the documented
        // "at most mesh_budget + remesh_budget mesh jobs per frame" was false and
        // a burst of remeshes could crowd fresh meshes out of a frame entirely.
        // A 3x3 block is uploaded in one frame, then a new block of terrain lands
        // beside it: that queues several remeshes *and* several fresh meshes at
        // once — exactly the burst the two budgets must bound separately.
        let config = PipelineConfig {
            terrain_budget: 16,
            mesh_budget: 1,
            remesh_budget: 1,
            section_y: (1, 1),
            ..PipelineConfig::default()
        };
        let mut world = pipeline(17, config);
        let mut renderer = HeadlessRenderer::new();
        assert_eq!(world.load_area(ChunkPos::ZERO, 1), 9);
        drain_until_idle(&mut world, &mut renderer);
        let stats = world.stats();
        assert_eq!(
            (stats.terrain_jobs, stats.mesh_jobs, stats.remesh_chunks),
            (9, 9, 0),
            "the initial block uploads cleanly; nothing to remesh yet"
        );
        assert_eq!(world.registry().len(), 9);

        // Nine new chunks in the x=2..=4 columns. Each of the three uploaded
        // chunks at x=1 gains a loaded neighbour, so three remeshes queue
        // alongside nine fresh meshes.
        assert_eq!(world.load_area(ChunkPos::new(3, 0), 1), 9);
        let second = world.frame(&mut renderer, camera()).expect("frame 2");
        assert_eq!(second.terrain_jobs, 9, "terrain has its own budget");
        assert_eq!(second.remesh_chunks, 1, "at most remesh_budget remeshes per frame");
        assert_eq!(second.mesh_jobs, 1, "at most mesh_budget fresh meshes per frame");
        assert_eq!(world.scheduler().queue_len(), 10, "eight meshes and two remeshes stay queued");
        let in_flight: Vec<_> = world
            .scheduler()
            .store()
            .iter()
            .filter(|e| e.state == ChunkState::Meshing)
            .map(|e| e.pos)
            .collect();
        assert!(
            in_flight.is_empty(),
            "a declined job must not have been started (no in-progress transition): {in_flight:?}"
        );

        // Drain under the same limits: every frame respects both bounds, the
        // remeshes never spend the fresh meshes' budget, and the work converges
        // with nothing stranded in the queue.
        let mut frames = 0;
        loop {
            let frame = world.frame(&mut renderer, camera()).expect("drain frame");
            frames += 1;
            assert!(frame.remesh_chunks <= 1, "remesh budget: {frame:?}");
            assert!(frame.mesh_jobs <= 1, "mesh budget: {frame:?}");
            let idle = frame.terrain_jobs == 0 && frame.mesh_jobs == 0 && frame.remesh_chunks == 0;
            if idle {
                assert_eq!((world.stats().pending_mesh, world.stats().pending_remesh), (0, 0));
                assert_eq!(world.scheduler().queue_len(), 0, "nothing stranded");
                break;
            }
            assert!(frames < 64, "work must converge: {frame:?}");
        }
        let stats = world.stats();
        assert_eq!(stats.terrain_jobs, 18);
        assert_eq!(stats.mesh_jobs, 18, "every chunk built a fresh mesh");
        assert_eq!(stats.remesh_chunks, 3, "each x=1 chunk remeshed exactly once");
    }

    /// Run frames, each under the pipeline's own budgets, until one does no
    /// queued work. Returns the number of frames that did work.
    fn drain_until_idle(
        world: &mut WorldPipeline<FlatSource>,
        renderer: &mut HeadlessRenderer,
    ) -> usize {
        for busy in 0..64 {
            let frame = world.frame(renderer, camera()).expect("frame");
            let idle = frame.terrain_jobs == 0 && frame.mesh_jobs == 0 && frame.remesh_chunks == 0;
            if idle {
                assert_eq!((world.stats().pending_mesh, world.stats().pending_remesh), (0, 0));
                assert_eq!(world.scheduler().queue_len(), 0, "nothing stranded");
                return busy;
            }
        }
        panic!("work must converge within 64 frames");
    }

    #[test]
    fn per_phase_timing_is_recorded_for_every_frame() {
        let config = PipelineConfig {
            section_y: (0, 2),
            mesh_budget: 4,
            terrain_budget: 8,
            ..PipelineConfig::default()
        };
        let mut world = pipeline(63, config);
        let mut renderer = HeadlessRenderer::new();
        world.load_area(ChunkPos::ZERO, 0);
        world.frame(&mut renderer, camera()).expect("frame");
        let timing = world.timing();
        assert_eq!(timing.samples, 1, "one frame sampled");
        let total = timing.pump_ns
            + timing.targets_ns
            + timing.build_ns
            + timing.publish_ns
            + timing.draw_ns;
        assert!(total > 0, "a frame must record non-zero phase time: {timing:?}");
    }

    #[test]
    fn counters_split_accepted_jobs_without_underflow() {
        // The neighbour remesh path previously computed `mesh_jobs` by subtracting
        // the count of *dequeued* remeshes from the count of *accepted* jobs, which
        // could underflow when a dequeued job produced no targets. With the split by
        // accepted kind the two counters must stay small, correct, and never wrap.
        let config = PipelineConfig {
            section_y: (1, 1),
            mesh_budget: 4,
            terrain_budget: 4,
            ..PipelineConfig::default()
        };
        let mut world = pipeline(17, config);
        let mut renderer = HeadlessRenderer::new();
        world.load_area(ChunkPos::ZERO, 0);
        let first = world.frame(&mut renderer, camera()).expect("frame 1");
        assert_eq!(first.mesh_jobs, 1, "the first chunk builds a fresh mesh");
        assert_eq!(first.remesh_chunks, 0, "nothing to re-mesh yet");

        world.load_area(ChunkPos::new(1, 0), 0);
        let second = world.frame(&mut renderer, camera()).expect("frame 2");
        assert!(
            second.remesh_chunks >= 1,
            "neighbour terrain must re-mesh the uploaded chunk: {second:?}"
        );
        let stats = world.stats();
        assert!(stats.mesh_jobs >= 1 && stats.remesh_chunks >= 1, "{stats:?}");
        // No u64 underflow: a wrapped counter would be astronomically large.
        assert!(
            stats.mesh_jobs < 1_000_000 && stats.remesh_chunks < 1_000_000,
            "counters must not wrap: {stats:?}"
        );
    }

    #[test]
    fn mesh_work_converges_across_frames_under_tiny_budgets() {
        // One job per phase, repeatedly: progress must be monotone and must
        // converge, with no work stranded by the bounded queue.
        let config = PipelineConfig {
            terrain_budget: 1,
            mesh_budget: 1,
            section_y: (1, 1),
            ..PipelineConfig::default()
        };
        let mut world = WorldPipeline::new(1, 1, config, FlatSource::new(17));
        let mut renderer = HeadlessRenderer::new();
        assert_eq!(world.load_area(ChunkPos::ZERO, 0), 1);
        let mut per_frame = Vec::new();
        for _ in 0..6 {
            let frame = world.frame(&mut renderer, camera()).expect("frame");
            assert!(frame.terrain_jobs <= 1 && frame.mesh_jobs <= 1, "budgets: {frame:?}");
            per_frame.push((frame.terrain_jobs, frame.mesh_jobs, frame.resident));
        }
        // One chunk: terrain and its mesh both fit in frame 1, then nothing.
        assert_eq!(per_frame[0], (1, 1, 1), "converged in one frame: {per_frame:?}");
        assert!(
            per_frame[1..].iter().all(|f| *f == (0, 0, 1)),
            "steady state after convergence: {per_frame:?}"
        );
        let stats = world.stats();
        assert_eq!((stats.pending_mesh, stats.pending_remesh), (0, 0));
        assert_eq!((stats.terrain_jobs, stats.mesh_jobs), (1, 1));
        assert_eq!(stats.frames, 6);
    }

    #[test]
    fn thread_count_does_not_change_geometry() {
        let run = |threads: usize| -> Vec<u32> {
            let config = PipelineConfig {
                section_y: (0, 3),
                threads,
                mesh_budget: 4,
                terrain_budget: 16,
                ..PipelineConfig::default()
            };
            let mut world = pipeline(20, config);
            let mut renderer = HeadlessRenderer::new();
            world.load_area(ChunkPos::ZERO, 1);
            for _ in 0..5 {
                world.frame(&mut renderer, camera()).expect("frame");
            }
            let mut counts: Vec<u32> = world.registry().iter().map(|s| s.index_count).collect();
            counts.sort_unstable();
            counts
        };
        let serial = run(1);
        let parallel = run(4);
        assert!(serial.len() > 1, "expected several resident sections");
        assert_eq!(serial, parallel, "meshing must be thread-count invariant");
    }

    #[test]
    fn full_columns_are_meshed_and_culled_together() {
        // The whole vertical range: air sections above the surface must never be
        // uploaded, so the registry only holds geometry that can be drawn.
        let config = PipelineConfig {
            section_y: (crate::coords::MIN_SECTION_Y, crate::coords::MAX_SECTION_Y),
            mesh_budget: 4,
            ..PipelineConfig::default()
        };
        let mut world = pipeline(20, config);
        let mut renderer = HeadlessRenderer::new();
        world.load_area(ChunkPos::ZERO, 0);
        let frame = world.frame(&mut renderer, camera()).expect("frame");
        let stats = world.stats();
        assert!(
            stats.empty_sections > 0,
            "air sections must be counted as empty"
        );
        assert!(
            (stats.empty_sections as usize + frame.published) <= config.sections_per_chunk(),
            "published + empty must not exceed the sections in one chunk"
        );
        for resident in world.registry().iter() {
            assert!(resident.index_count > 0, "no empty mesh may be resident");
        }
        let keys = world.resident_keys();
        assert_eq!(keys.len(), world.registry().len());
        let mut sorted = keys.clone();
        sorted.sort_unstable();
        assert_eq!(keys, sorted, "resident keys must be reported in order");
    }

    /// A [`BlockSource`] whose per-section cost is one 8 KiB copy: the sections
    /// are built once per Y level and handed out as clones.
    ///
    /// `FlatSource::section` writes 4096 blocks through a nested loop on every
    /// call. With 8 sections per chunk and 6 prefetched neighbours per section
    /// that made the *mock generator* 44% of the fill, which hid what the
    /// pipeline itself costs. The engine does not generate terrain (the Java host
    /// pushes finished sections through `PushSource`), so the benchmark should
    /// measure the pipeline, not the stand-in.
    struct CachedFlatSource {
        templates: std::collections::BTreeMap<i32, ChunkSection>,
        calls: u64,
    }

    impl CachedFlatSource {
        fn new(surface_y: i32, section_y: (i32, i32)) -> Self {
            let mut builder = FlatSource::new(surface_y);
            let mut templates = std::collections::BTreeMap::new();
            for y in section_y.0..=section_y.1 {
                let template = builder.section(SectionPos::new(0, y, 0));
                templates.insert(y, template);
            }
            Self { templates, calls: 0 }
        }
    }

    impl BlockSource for CachedFlatSource {
        fn section(&mut self, pos: SectionPos) -> ChunkSection {
            self.calls += 1;
            self.templates.get(&pos.y).cloned().unwrap_or_default()
        }
    }

    /// Whole-frame throughput report, not a CI gate:
    /// `cargo test --release frame_throughput_report -- --ignored --nocapture`.
    ///
    /// `mesh_throughput_report` and `plan_throughput_report` each measure one
    /// function in isolation. This one measures the loop a host actually drives:
    /// `load_area` over a 17x17 chunk block (Minecraft's default view distance of
    /// 8), frames until the work converges, then idle frames — which is where the
    /// per-frame cost that scales with *resident* sections rather than with new
    /// work shows up. Budgets are deliberately large so the fill is bounded by
    /// throughput, not by pacing.
    #[test]
    #[ignore = "throughput report; run with --ignored --nocapture"]
    fn frame_throughput_report() {
        const RADIUS: i32 = 8;
        const IDLE_FRAMES: u32 = 200;
        let config = PipelineConfig {
            terrain_budget: 512,
            mesh_budget: 512,
            remesh_budget: 512,
            threads: 4,
            section_y: (
                crate::coords::MIN_SECTION_Y,
                crate::coords::MIN_SECTION_Y + 7,
            ),
            extent: [256, 256],
        };
        // A queue deep enough to hold the whole area, so the fill measures
        // throughput instead of the bounded-queue back-pressure path.
        let mut world =
            WorldPipeline::new(1, 4096, config, CachedFlatSource::new(17, config.section_y));
        let mut renderer = HeadlessRenderer::new();
        let mut chunks = 0usize;
        let mut frames = 0u32;

        let fill_start = Instant::now();
        loop {
            let queued = world.load_area(ChunkPos::ZERO, RADIUS);
            chunks += queued;
            // Drain this burst completely: an idle frame means no job ran, not
            // that the work is done, because deferred chunks re-enter the queue
            // through `retry_deferred` on the next frame.
            loop {
                let frame = world.frame(&mut renderer, camera()).expect("fill frame");
                frames += 1;
                let pending = world.stats().pending_mesh + world.stats().pending_remesh;
                let idle = frame.terrain_jobs == 0
                    && frame.mesh_jobs == 0
                    && frame.remesh_chunks == 0
                    && world.scheduler().queue_len() == 0
                    && pending == 0;
                if idle {
                    break;
                }
                assert!(frames < 10_000, "fill must converge: {frame:?}");
            }
            if queued == 0 {
                break;
            }
        }
        let fill = fill_start.elapsed();
        let sections = chunks * config.sections_per_chunk();

        // Sampled before the idle frames, so the phase split describes the fill
        // rather than being diluted by 200 empty frames.
        let timing = world.timing();
        let busy_frames = timing.samples.max(1) as f64;

        let idle_start = Instant::now();
        let mut batches = 0usize;
        for _ in 0..IDLE_FRAMES {
            let frame = world.frame(&mut renderer, camera()).expect("idle frame");
            batches = frame.batches;
        }
        let idle = idle_start.elapsed();

        let stats = world.stats();
        let ms = |ns: u64| ns as f64 / 1e6;
        let per_busy = |ns: u64| ns as f64 / busy_frames / 1e3;
        println!(
            "fill    chunks={chunks} sections={sections} frames={frames} \
             total={:.1}ms chunk={:.2}us section={:.3}us",
            fill.as_secs_f64() * 1e3,
            fill.as_secs_f64() * 1e9 / chunks as f64 / 1e3,
            fill.as_secs_f64() * 1e9 / sections as f64 / 1e3,
        );
        println!(
            "phases  fill ms: pump={:.2} targets={:.2} build={:.2} publish={:.2} draw={:.2} \
             (sum={:.2} of {:.2})",
            ms(timing.pump_ns),
            ms(timing.targets_ns),
            ms(timing.build_ns),
            ms(timing.publish_ns),
            ms(timing.draw_ns),
            ms(timing.pump_ns + timing.targets_ns + timing.build_ns + timing.publish_ns
                + timing.draw_ns),
            fill.as_secs_f64() * 1e3,
        );
        println!(
            "busy    per-frame us: pump={:.1} targets={:.1} build={:.1} publish={:.1} draw={:.1}",
            per_busy(timing.pump_ns),
            per_busy(timing.targets_ns),
            per_busy(timing.build_ns),
            per_busy(timing.publish_ns),
            per_busy(timing.draw_ns),
        );
        println!(
            "steady  resident={} batches={batches} frames={IDLE_FRAMES} frame={:.2}us",
            world.registry.len(),
            idle.as_secs_f64() * 1e9 / IDLE_FRAMES as f64 / 1e3,
        );
        println!(
            "totals  frames={} terrain_jobs={} mesh_jobs={} remesh={} published={} empty={} \
             source_calls={}",
            stats.frames,
            stats.terrain_jobs,
            stats.mesh_jobs,
            stats.remesh_chunks,
            stats.published,
            stats.empty_sections,
            world.source().calls,
        );
    }
}

#[cfg(test)]
mod push_source_tests {
    use super::*;
    use crate::chunk::ChunkState;
    use crate::coords::SECTION_SIZE;
    use crate::render::headless::HeadlessRenderer;
    use crate::render::ortho_look_at;

    /// Orthographic camera looking along -Z at the origin section column.
    fn camera() -> Camera {
        let eye = [8.0f32, 24.0, 96.0];
        let view_proj =
            ortho_look_at(eye, [8.0, 24.0, 0.0], [0.0, 1.0, 0.0], 96.0, 96.0, 0.5, 256.0)
                .expect("non-degenerate camera");
        Camera { view_proj, eye }
    }

    fn filled(fill: bool) -> ChunkSection {
        let mut section = ChunkSection::new();
        if fill {
            for y in 0..(SECTION_SIZE as usize) {
                for z in 0..(SECTION_SIZE as usize) {
                    for x in 0..(SECTION_SIZE as usize) {
                        section.set(x, y, z, 1);
                    }
                }
            }
        }
        section
    }

    fn pushed() -> WorldPipeline<PushSource> {
        let config = PipelineConfig {
            section_y: (1, 1),
            ..PipelineConfig::default()
        };
        WorldPipeline::new(1, 64, config, PushSource::new())
    }

    #[test]
    fn an_unsubmitted_section_reads_as_empty_and_is_counted() {
        let mut source = PushSource::new();
        let section = source.section(SectionPos::new(0, 1, 0));
        assert!(section.is_empty());
        assert_eq!(source.missing_reads, 1);

        source.put_chunk(ChunkPos::ZERO, 1, &[filled(true)]);
        assert!(source.has_chunk(ChunkPos::ZERO));        assert_eq!(source.section_count(), 1);
        assert_eq!(source.chunk_count(), 1);
        assert_eq!(source.put_chunk(ChunkPos::ZERO, 1, &[]), 0);

        let read = source.section(SectionPos::new(0, 1, 0));
        assert!(read.is_solid(0, 0, 0));
        assert_eq!(source.missing_reads, 1, "a submitted section is not a miss");
    }

    #[test]
    fn a_pushed_chunk_runs_the_full_lifecycle() {
        let mut world = pushed();
        let mut renderer = HeadlessRenderer::new();
        let chunk = ChunkPos::ZERO;

        assert_eq!(
            world.submit_chunk(chunk, 1, &[filled(true)]),
            Submit::New
        );
        assert_eq!(
            world.submit_chunk(chunk, 1, &[filled(true)]),
            Submit::InFlight,
            "terrain work is already queued, so a second submit must not double-queue"
        );
        assert_eq!(
            world.scheduler().store().state(chunk),
            Some(ChunkState::TerrainQueued)
        );

        let frame = world.frame(&mut renderer, camera()).expect("frame");
        assert_eq!(world.scheduler().store().state(chunk), Some(ChunkState::Uploaded));
        assert_eq!(frame.published, 1, "one section, one mesh");
        assert_eq!(world.registry().len(), 1);
        assert_eq!(renderer.live_mesh_count(), 1);
        // Only the two vertical neighbours inside the submitted chunk are read
        // and found missing; horizontal neighbour chunks are not queried at all,
        // because the scheduler has no terrain for them.
        assert_eq!(world.source().missing_reads, 2);
    }

    #[test]
    fn resubmitting_an_uploaded_chunk_remeshes_it_and_frees_the_old_mesh() {
        let mut world = pushed();
        let mut renderer = HeadlessRenderer::new();
        let chunk = ChunkPos::ZERO;
        world.submit_chunk(chunk, 1, &[filled(true)]);
        world.frame(&mut renderer, camera()).expect("frame");
        let before = world.registry().iter().next().expect("resident").mesh;

        // The chunk becomes all air: the new geometry is empty, so the old GPU
        // mesh must be destroyed rather than left resident.
        assert_eq!(
            world.submit_chunk(chunk, 1, &[filled(false)]),
            Submit::RemeshRequested
        );
        let frame = world.frame(&mut renderer, camera()).expect("frame");
        assert_eq!(frame.remesh_chunks, 1);
        assert_eq!(world.registry().len(), 0, "an empty section is not resident");
        assert_eq!(renderer.live_mesh_count(), 0, "the superseded mesh must be freed");
        assert_eq!(
            renderer.destroyed_meshes(),
            &[before.0],
            "the superseded mesh is the one that was freed"
        );
        assert_eq!(before, before, "placeholder to keep the handle read used");

        let stats = world.stats();
        assert_eq!(stats.meshes_destroyed, 1);
    }

    #[test]
    fn unloaded_chunks_lose_their_source_data() {
        let mut world = pushed();
        let mut renderer = HeadlessRenderer::new();
        let chunk = ChunkPos::ZERO;
        world.submit_chunk(chunk, 1, &[filled(true)]);
        world.frame(&mut renderer, camera()).expect("frame");
        assert_eq!(world.source().section_count(), 1);

        let unloaded = world.unload_outside(&mut renderer, ChunkPos::new(20, 20), 0);
        assert_eq!(unloaded, 1);
        assert_eq!(world.cached_section_count(), 0);
        assert_eq!(world.flush_source_data(), 1, "source data must not outlive the chunk");
        assert_eq!(world.source().section_count(), 0);
        assert_eq!(renderer.live_mesh_count(), 0);
    }

    /// A pushed section must reach the mesher without being copied. Three owners
    /// hold it in turn — the caller's slice, `PushSource`, and the pipeline's
    /// block cache — and with a plain `Vec` inside `ChunkSection` that was two
    /// 8 KiB copies per section. `indices().as_ptr()` is the buffer identity, so
    /// equal pointers across all three prove one allocation is shared.
    #[test]
    fn pushed_sections_reach_the_mesher_without_being_copied() {
        let mut world = pushed();
        let mut renderer = HeadlessRenderer::new();
        let sections = [filled(true)];
        let caller_buffer = sections[0].indices().as_ptr();

        world.submit_chunk(ChunkPos::ZERO, 1, &sections);
        let stored = world
            .source()
            .chunks
            .get(&ChunkPos::ZERO)
            .and_then(|column| column.get(&1))
            .expect("the source must hold the submitted section");
        assert_eq!(
            stored.indices().as_ptr(),
            caller_buffer,
            "ingest must share the caller's buffer, not copy it"
        );

        world.frame(&mut renderer, camera()).expect("frame");
        let cached = world
            .blocks
            .get(&SectionPos::new(0, 1, 0))
            .expect("the mesher's block cache must hold the section");
        assert_eq!(
            cached.indices().as_ptr(),
            caller_buffer,
            "the block cache must share the same buffer too"
        );
        assert_eq!(world.stats().published, 1, "and it must still have been meshed");
    }
}
