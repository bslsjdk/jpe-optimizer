//! A `BlockSource` that generates terrain itself, through the C++ engine.
//!
//! The pipeline is generic over `BlockSource`:
//!
//! ```ignore
//! pub trait BlockSource {
//!     fn section(&mut self, pos: SectionPos) -> ChunkSection;
//! }
//! ```
//!
//! Every other source in this crate answers that call from data someone else
//! handed in -- `FlatSource` invents it, and the density adapter thresholds a
//! volume the native side already produced. This one is different: it owns a
//! `NativeGenerator` and asks the C++ engine for the column directly, so a world
//! can load with no host round-trip at all.
//!
//! # Why this is the seam
//!
//! Before this module the only way to get terrain into the pipeline was for the
//! host to generate a column somewhere else, copy it across the ABI, and hand it
//! back through `submit_chunk`. That put a host-side allocation and a 192 KiB
//! copy in the path of every single chunk, and made "generate a world" a job the
//! host side had to drive frame by frame.
//!
//! With `NativeTerrainSource` the pipeline pulls instead of being pushed to:
//! `section()` is called for a section whose terrain just became ready, and it
//! answers by asking C++ for that column.
//!
//! # Column caching
//!
//! `section()` is per-16³, but the C++ generator works per-column (384 blocks
//! tall). Generating the column on every section call would be 24x the work, so
//! the last column is kept and reused while consecutive calls land in the same
//! column. The mesher walks a column bottom to top, so in practice this is a
//! perfect hit; a miss simply regenerates.

use std::collections::HashMap;

use crate::coords::{ChunkPos, SectionPos};
use crate::mesh::ChunkSection;
use crate::pipeline::BlockSource;
use crate::terrain_gen::{NativeGenerator, TerrainConfig};

/// Terrain straight from the C++ generator, one column at a time.
pub struct NativeTerrainSource {
    generator: NativeGenerator,
    /// The generator's own geometry. Kept because `base_section_y` and
    /// `sections` live on it and duplicating that arithmetic here is how the
    /// two layers drift apart.
    config: TerrainConfig,
    /// Blocks of the column currently held.
    column: Vec<u16>,
    /// Which column `column` holds.
    cached: Option<ChunkPos>,
    /// Columns generated since construction. Exposed so a test can prove the
    /// cache prevents the 24x blow-up instead of trusting the comment.
    pub generations: u64,
    /// Section calls served without touching the generator.
    pub cache_hits: u64,
    /// Per-column scratch reused across generations.
    scratch: Vec<u16>,
    /// Small ring of recently generated columns, for multi-column mesh passes.
    ring: HashMap<ChunkPos, Vec<u16>>,
    ring_capacity: usize,
}

impl NativeTerrainSource {
    /// Wraps a generator. Fails only if the generator's geometry is not a whole
    /// number of sections, which would mean the two layers disagree about the
    /// chunk format and nothing above this point could be trusted.
    pub fn new(generator: NativeGenerator, config: TerrainConfig) -> Result<Self, String> {
        let blocks = generator.blocks_per_chunk();
        let volume = ChunkSection::VOLUME;
        if blocks == 0 || volume == 0 || blocks % volume != 0 {
            return Err(format!(
                "generator reports {} blocks per column, which is not a whole number of {}-block sections",
                blocks, volume
            ));
        }
        if config.sections() * volume != blocks {
            return Err(format!(
                "config says {} sections but the generator reports {} blocks ({} sections)",
                config.sections(),
                blocks,
                blocks / volume
            ));
        }
        Ok(Self {
            generator,
            config,
            column: Vec::new(),
            cached: None,
            generations: 0,
            cache_hits: 0,
            scratch: Vec::new(),
            ring: HashMap::new(),
            ring_capacity: 4,
        })
    }

    /// How many columns the ring keeps. One is enough for a bottom-to-top mesh
    /// walk; more helps when several columns are meshed per frame.
    pub fn set_ring_capacity(&mut self, n: usize) {
        self.ring_capacity = n.max(1);
        while self.ring.len() > self.ring_capacity {
            if let Some(key) = self.ring.keys().next().copied() {
                self.ring.remove(&key);
            } else {
                break;
            }
        }
    }

    /// Sections the generator's column covers.
    pub fn sections_per_column(&self) -> usize {
        self.config.sections()
    }

    /// Blocks per column, straight from the generator.
    pub fn blocks_per_column(&self) -> usize {
        self.generator.blocks_per_chunk()
    }

    fn generate_column(&mut self, chunk: ChunkPos) -> Result<(), String> {
        let blocks = self.generator.blocks_per_chunk();
        if self.scratch.len() < blocks {
            self.scratch.resize(blocks, 0);
        }
        self.generator
            .generate_chunk(chunk.x, chunk.z, &mut self.scratch[..blocks])
            .map_err(|e| format!("generate_chunk({}, {}) failed: {}", chunk.x, chunk.z, e))?;
        self.generations += 1;
        self.column.clear();
        self.column.extend_from_slice(&self.scratch[..blocks]);
        self.cached = Some(chunk);
        if self.ring_capacity > 1 {
            self.ring.insert(chunk, self.column.clone());
            while self.ring.len() > self.ring_capacity {
                if let Some(key) = self.ring.keys().next().copied() {
                    self.ring.remove(&key);
                } else {
                    break;
                }
            }
        }
        Ok(())
    }

    /// Index of `pos` within the column, or `None` when the section lies outside
    /// the generated column entirely.
    ///
    /// `SectionPos.y` is a section index (`from_block` does `block_y >> 4`), so
    /// the offset from the column's own base section is a plain subtraction --
    /// not a division, which would reintroduce the block/section unit mix-up
    /// that `base_section_y` exists to avoid.
    fn section_index_in_column(&self, pos: SectionPos) -> Option<usize> {
        let relative = pos.y - self.config.base_section_y();
        if relative < 0 {
            return None;
        }
        let index = relative as usize;
        if index >= self.sections_per_column() {
            return None;
        }
        Some(index)
    }
}

impl BlockSource for NativeTerrainSource {
    fn section(&mut self, pos: SectionPos) -> ChunkSection {
        let mut section = ChunkSection::new();

        // A section outside the column is answered without generating anything:
        // asking C++ for a column just to throw the result away would make the
        // empty sky above the world cost a full generation.
        let section_index = match self.section_index_in_column(pos) {
            Some(index) => index,
            None => return section,
        };

        let chunk = ChunkPos::new(pos.x, pos.z);
        if self.cached != Some(chunk) {
            if let Some(hit) = self.ring.get(&chunk) {
                self.column.clear();
                self.column.extend_from_slice(hit);
                self.cached = Some(chunk);
                self.cache_hits += 1;
            } else if self.generate_column(chunk).is_err() {
                // A generator that cannot produce this column leaves it empty.
                // Returning a default section is the honest answer: the mesher
                // renders nothing there rather than stale neighbour data.
                return section;
            }
        } else {
            self.cache_hits += 1;
        }

        let base = section_index * ChunkSection::VOLUME;
        if base + ChunkSection::VOLUME > self.column.len() {
            return section;
        }
        section = ChunkSection::from_indices(&self.column[base..base + ChunkSection::VOLUME])
            .unwrap_or_else(ChunkSection::new);
        section
    }
}

#[cfg(test)]
mod native_terrain_source_tests {
    use super::*;

    fn config(seed: i64) -> TerrainConfig {
        TerrainConfig {
            seed,
            ..TerrainConfig::default()
        }
    }

    fn source(seed: i64) -> NativeTerrainSource {
        let cfg = config(seed);
        NativeTerrainSource::new(NativeGenerator::new(cfg).expect("generator"), cfg)
            .expect("source")
    }

    /// The trait bound the pipeline needs. If this compiles, the source drops
    /// into `WorldPipeline` unchanged.
    fn takes_a_block_source<S: BlockSource>(s: S) -> S {
        s
    }

    #[test]
    fn it_satisfies_the_block_source_contract() {
        let _ = takes_a_block_source(source(0));
    }

    #[test]
    fn the_column_geometry_agrees_with_the_generator() {
        // new() rejects a mismatch, so reaching here already proves it; assert
        // the numbers anyway so a change to either side shows up as a failure
        // here rather than as silent section misalignment at runtime.
        let src = source(0);
        assert_eq!(src.sections_per_column(), 24, "384 blocks is 24 sections");
        assert_eq!(
            src.sections_per_column() * ChunkSection::VOLUME,
            src.blocks_per_column()
        );
    }

    #[test]
    fn a_column_is_generated_once_for_all_of_its_sections() {
        // The whole point of the column cache: 24 section calls must cost one
        // generation. Without it this would be 24x the work per column.
        let mut src = source(7);
        let per_column = src.sections_per_column();
        assert!(per_column > 1, "a column must hold more than one section");

        let base = src.config.base_section_y();
        for i in 0..per_column {
            let _ = src.section(SectionPos::new(0, base + i as i32, 0));
        }

        assert_eq!(
            src.generations, 1,
            "{} section calls generated {} columns; the cache is not working",
            per_column, src.generations
        );
        assert_eq!(src.cache_hits, per_column as u64 - 1);
    }

    #[test]
    fn a_different_column_is_generated_separately() {
        let mut src = source(7);
        let base = src.config.base_section_y();
        let _ = src.section(SectionPos::new(0, base, 0));
        let _ = src.section(SectionPos::new(1, base, 0));
        assert_eq!(src.generations, 2);
    }

    #[test]
    fn the_ring_serves_a_column_after_it_left_the_single_slot() {
        let mut src = source(7);
        src.set_ring_capacity(4);
        let base = src.config.base_section_y();

        let a = SectionPos::new(0, base, 0);
        let b = SectionPos::new(1, base, 0);
        let _ = src.section(a);
        let _ = src.section(b);
        assert_eq!(src.generations, 2);

        let _ = src.section(a);
        assert_eq!(src.generations, 2, "the ring did not keep the earlier column");
    }

    #[test]
    fn a_section_outside_the_column_costs_nothing() {
        // Sky above the world and rock below it must not trigger a generation.
        // Getting this wrong makes the empty half of the render distance pay
        // full price.
        let mut src = source(7);
        let base = src.config.base_section_y();

        let below = src.section(SectionPos::new(0, base - 8, 0));
        assert!(below.is_empty(), "a section below the world must be empty");
        assert_eq!(src.generations, 0, "a below-world section generated a column");

        let above = src.section(SectionPos::new(0, base + 4096, 0));
        assert!(above.is_empty(), "a section above the column must be empty");
        assert_eq!(src.generations, 0, "an above-world section generated a column");
    }

    #[test]
    fn a_column_round_trips_through_the_section_layout() {
        // Build the same column two ways: once through the source's per-section
        // path, once as one flat block array, and require they agree. This is
        // what catches a section base computed with the wrong units.
        let cfg = config(11);
        let gen = NativeGenerator::new(cfg).expect("generator");
        let blocks = gen.blocks_per_chunk();
        let mut direct = vec![0u16; blocks];
        gen.generate_chunk(0, 0, &mut direct).expect("generate");

        let mut src = NativeTerrainSource::new(gen, cfg).expect("source");
        let per_column = src.sections_per_column();
        let base_section = cfg.base_section_y();
        let mut rebuilt = vec![0u16; blocks];

        for i in 0..per_column {
            let section = src.section(SectionPos::new(0, base_section + i as i32, 0));
            let base = i * ChunkSection::VOLUME;
            for local_y in 0..16usize {
                for z in 0..16usize {
                    for x in 0..16usize {
                        let dst = base + (local_y << 8) + (z << 4) + x;
                        rebuilt[dst] = section.get(x, local_y, z);
                    }
                }
            }
        }

        assert_eq!(
            rebuilt, direct,
            "the section path and the flat column disagree"
        );
    }
}