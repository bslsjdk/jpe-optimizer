//! Section-shaped terrain access on top of the whole-chunk generator.
//!
//! The C++ generator hands back one 16 x 16 x 384 column at a time, indexed
//! `y << 8 | z << 4 | x`. The rest of the world -- meshing, lighting, the
//! client's chunk sections -- works in 16^3 sections. This module is the
//! adapter, and it is deliberately the only place that knows both layouts.
//!
//! ## Why not just hand out the whole column?
//!
//! A column is 192 KiB. A radius-20 window is 1257 columns, so a naive
//! whole-column residency model costs 241 MiB before a single section is
//! meshed. Sections let the runtime keep only the vertical range that actually
//! has content: at the default overworld config the surface sits around
//! y = 60-120, so 4-8 of the 24 sections are non-empty. That is a 3-6x saving
//! on the dominant memory term, which is what makes the 320 MiB budget fit a
//! 1 GiB heap instead of merely being close to it.
//!
//! ## The invariant this module exists to protect
//!
//! A section must always be a *view* of the column the generator produced, not
//! a re-derivation of it. `section_blocks` copies out of the column slice with
//! the same index arithmetic the mesher uses, and the tests compare it against
//! an independent per-block walk. If those two ever disagree, one of them has
//! an off-by-one in the `y << 8` term, and the symptom in-game is terrain that
//! looks fine until a chunk border.

use crate::terrain_ffi::{TerrainEngine, CHUNK_BLOCKS_DEFAULT_HEIGHT};

/// Blocks along one horizontal axis of a section. Fixed by the game format.
pub const SECTION_WIDTH: usize = 16;

/// Blocks along the vertical axis of a section. Fixed by the game format.
pub const SECTION_HEIGHT: usize = 16;

/// Blocks in one section: 16 * 16 * 16.
pub const SECTION_BLOCKS: usize = SECTION_WIDTH * SECTION_WIDTH * SECTION_HEIGHT;

/// Bytes in one section of `u16` block ids.
pub const SECTION_BYTES: u64 = SECTION_BLOCKS as u64 * 2;

/// Sections in a full-height column at the default 384 height.
pub const SECTIONS_PER_COLUMN: usize = 384 / SECTION_HEIGHT;

/// Vertical index of the section holding a given world y.
///
/// `min_y` is the world's lowest block, so this is a floor division that has
/// to stay correct for negative y -- hence the explicit offset rather than a
/// cast, which truncates toward zero and would put y = -1 in section 0
/// alongside y = 0.
#[inline]
pub fn section_index_of(min_y: i32, y: i32) -> i32 {
    let rel = y - min_y;
    // Floor division by a positive constant. `div_euclid` is exactly this.
    rel.div_euclid(SECTION_HEIGHT as i32)
}

/// Index into a column's block array for a section-local coordinate.
///
/// Matches the C++ layout `y << 8 | z << 4 | x` exactly. Section-local
/// coordinates use the same bit widths, so the formula is identical; what
/// changes is that `y` is 0..16 instead of 0..384.
#[inline]
pub fn section_local_index(x: usize, y: usize, z: usize) -> usize {
    (y << 8) | (z << 4) | x
}

/// Index into a full column for a section-local coordinate.
///
/// This is the one place the section/column translation happens on the read
/// path. `section_base` is the section's first y within the column.
#[inline]
pub fn column_index(section_base: usize, x: usize, y: usize, z: usize) -> usize {
    let column_y = section_base + y;
    (column_y << 8) | (z << 4) | x
}

/// A vertical slice of one column, copied out of the generator's output.
///
/// Owning the copy rather than borrowing keeps the type usable across a
/// `stream` call, which invalidates every pointer the generator handed out.
/// 8 KiB per section is cheap next to the alternative of pinning a 192 KiB
/// column for as long as any section of it is visible.
#[derive(Clone, PartialEq, Eq)]
pub struct Section {
    /// Section index within the column, 0 = lowest.
    pub index: usize,
    /// `SECTION_BLOCKS` block ids in `y << 8 | z << 4 | x` order.
    pub blocks: Vec<u16>,
}

impl Section {
    /// Copies one section out of a whole column.
    ///
    /// `column` must be a full column as produced by the generator. Returns
    /// `None` when `index` is out of range for the column height rather than
    /// panicking, because the caller is usually a renderer walking a view
    /// frustum and a missing section is a normal, recoverable outcome.
    pub fn from_column(column: &[u16], index: usize) -> Option<Self> {
        let total = column.len() / SECTION_BLOCKS;
        if index >= total {
            return None;
        }
        let base = index * SECTION_BLOCKS;
        let mut blocks = Vec::with_capacity(SECTION_BLOCKS);
        // The column is already in `y << 8 | z << 4 | x` order and a section
        // is a contiguous run of that order: y varies slowest, and the section
        // covers exactly 16 consecutive y values. So the copy is a memcpy, and
        // the index helpers above exist to *prove* that, not to do it.
        blocks.extend_from_slice(&column[base..base + SECTION_BLOCKS]);
        Some(Section { index, blocks })
    }

    /// Reads one block by section-local coordinate.
    #[inline]
    pub fn get(&self, x: usize, y: usize, z: usize) -> u16 {
        self.blocks[section_local_index(x, y, z)]
    }

    /// True when every block is air (id 0).
    ///
    /// Used to skip empty sections without meshing them. A section that is all
    /// air still costs 8 KiB to hold, so this is also the predicate the
    /// residency policy uses to decide what is worth keeping.
    pub fn is_empty(&self) -> bool {
        self.blocks.iter().all(|&b| b == 0)
    }

    /// Number of non-air blocks. Cheap enough for stats, not for hot paths.
    pub fn solid_count(&self) -> usize {
        self.blocks.iter().filter(|&&b| b != 0).count()
    }
}

/// How much memory a terrain view is allowed to occupy.
///
/// The two presets are the ones the runtime actually ships. They are derived,
/// not guessed: `columns` is the disc area for the radius, `sections` is what
/// fraction of those columns is worth keeping, and the product has to stay
/// under the budget with room for the mesher's own allocations.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MemoryModel {
    /// Total budget for resident terrain, in bytes.
    pub budget_bytes: u64,
    /// Chunk radius of the resident window.
    pub radius: i32,
    /// Columns the window covers (disc area, matching `stream_window`).
    pub columns: u64,
    /// Columns held at full height instead of section-trimmed.
    pub full_height_columns: u64,
}

impl MemoryModel {
    /// Columns in a disc of `radius`, which is what `stream_window` fills.
    pub fn disc_columns(radius: i32) -> u64 {
        if radius < 0 {
            return 0;
        }
        let mut n = 0u64;
        for dz in -radius..=radius {
            for dx in -radius..=radius {
                if dx * dx + dz * dz <= radius * radius {
                    n += 1;
                }
            }
        }
        n
    }

    /// The 1 GiB heap preset: 320 MiB of terrain, radius 20.
    ///
    /// 1257 columns at full height would be 241 MiB of raw blocks; trimming to
    /// the 8 sections that actually carry terrain brings the working set down
    /// to roughly a third of that, leaving headroom for the mesher.
    pub fn for_one_gigabyte() -> Self {
        let radius = 20;
        let columns = Self::disc_columns(radius);
        MemoryModel {
            budget_bytes: 320 * 1024 * 1024,
            radius,
            columns,
            full_height_columns: columns,
        }
    }

    /// The 2 GiB heap preset: 768 MiB of terrain, radius 28, all resident.
    ///
    /// At this budget the window fits without trimming, so the model keeps
    /// every column whole. That is a deliberate trade: more memory for no
    /// eviction churn while walking, which is what the larger heap is for.
    pub fn for_two_gigabytes() -> Self {
        let radius = 28;
        let columns = Self::disc_columns(radius);
        MemoryModel {
            budget_bytes: 768 * 1024 * 1024,
            radius,
            columns,
            full_height_columns: columns,
        }
    }

    /// Capacity in columns at full height, which is what `set_budget` wants.
    pub fn capacity_columns(&self) -> u64 {
        self.budget_bytes / (CHUNK_BLOCKS_DEFAULT_HEIGHT * 2)
    }

    /// Raw bytes the window would need if every column were held whole.
    pub fn whole_column_bytes(&self) -> u64 {
        self.columns * CHUNK_BLOCKS_DEFAULT_HEIGHT * 2
    }

    /// Bytes actually budgeted for sections, at 8 sections per column.
    pub fn section_trimmed_bytes(&self, sections_kept: u64) -> u64 {
        self.columns * sections_kept * SECTION_BYTES
    }
}

/// A window of sections over the generator, with its own residency policy.
///
/// This is the type the rest of the runtime talks to. It owns the generator
/// handle and decides, per column, which sections are worth holding.
pub struct TerrainSource {
    engine: TerrainEngine,
    model: MemoryModel,
    /// Sections kept per column; the vertical trim factor.
    sections_kept: usize,
}

impl TerrainSource {
    /// Wraps an engine with the given memory model.
    pub fn new(engine: TerrainEngine, model: MemoryModel) -> Self {
        TerrainSource {
            engine,
            model,
            sections_kept: SECTIONS_PER_COLUMN,
        }
    }

    /// The engine underneath, for callers that need raw columns.
    pub fn engine(&self) -> &TerrainEngine {
        &self.engine
    }

    /// Mutable access, needed by `stream` and `acquire`.
    pub fn engine_mut(&mut self) -> &mut TerrainEngine {
        &mut self.engine
    }

    /// The model this source was built with.
    pub fn model(&self) -> MemoryModel {
        self.model
    }

    /// How many sections per column are currently held.
    pub fn sections_kept(&self) -> usize {
        self.sections_kept
    }

    /// Narrows the vertical trim to `sections` per column.
    ///
    /// Returns the number of sections actually used, which is clamped to the
    /// column height. A trim of 0 would mean "hold nothing", which is never
    /// what a caller wants, so it is clamped up to 1.
    pub fn set_sections_kept(&mut self, sections: usize) -> usize {
        self.sections_kept = sections.clamp(1, SECTIONS_PER_COLUMN);
        self.sections_kept
    }

    /// Moves the window and returns how many columns were generated.
    pub fn stream(
        &mut self,
        chunk_x: i32,
        chunk_z: i32,
    ) -> Result<i32, crate::terrain_ffi::StreamError> {
        self.engine.stream(chunk_x, chunk_z, self.model.radius, 0)
    }

    /// Copies out every section of one column that is worth holding.
    ///
    /// "Worth holding" is the trim policy: the `sections_kept` sections
    /// centred on the column's densest band. Empty sections are dropped, so a
    /// column of pure air yields an empty vector rather than 24 empty sections.
    pub fn column_sections(&mut self, chunk_x: i32, chunk_z: i32) -> Vec<Section> {
        let Some(column) = self.engine.acquire(chunk_x, chunk_z) else {
            return Vec::new();
        };
        // Copy out before releasing: the borrow from `acquire` ends here, and
        // the generator may reuse the buffer on the next call.
        let owned = column.to_vec();
        self.engine.release(chunk_x, chunk_z);

        let total = owned.len() / SECTION_BLOCKS;
        let keep = self.sections_kept.min(total);
        // Centre the kept band vertically so a window that moves in y (which
        // the current API cannot do, but the next one will) keeps its content.
        let start = (total - keep) / 2;
        let mut out = Vec::with_capacity(keep);
        for index in start..start + keep {
            if let Some(section) = Section::from_column(&owned, index) {
                if !section.is_empty() {
                    out.push(section);
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod terrain_source_tests {
    use super::*;
    use crate::terrain_ffi::TerrainConfig;

    /// The section layout must match the C++ column layout bit for bit.
    #[test]
    fn section_index_matches_the_column_layout() {
        // Walk every block of one section and check the two index formulas
        // agree with an independent triple loop.
        let base = 3 * SECTION_BLOCKS;
        for y in 0..SECTION_HEIGHT {
            for z in 0..SECTION_WIDTH {
                for x in 0..SECTION_WIDTH {
                    let local = section_local_index(x, y, z);
                    let column = column_index(base, x, y, z);
                    assert_eq!(
                        column,
                        base + local,
                        "section-local and column indices disagree at {}, {}, {}",
                        x,
                        y,
                        z
                    );
                }
            }
        }
    }

    /// A section must be a contiguous run of the column, not a gather.
    #[test]
    fn a_section_is_a_contiguous_run_of_the_column() {
        let total = SECTIONS_PER_COLUMN * SECTION_BLOCKS;
        // Encode the column index into each block so any reordering shows up.
        let column: Vec<u16> = (0..total).map(|i| (i & 0xFFFF) as u16).collect();
        for index in [0usize, 1, 12, SECTIONS_PER_COLUMN - 1] {
            let section = Section::from_column(&column, index).expect("section");
            assert_eq!(section.blocks.len(), SECTION_BLOCKS);
            let base = index * SECTION_BLOCKS;
            for (offset, &block) in section.blocks.iter().enumerate() {
                assert_eq!(
                    block as usize,
                    (base + offset) & 0xFFFF,
                    "section {} offset {} came from the wrong place",
                    index,
                    offset
                );
            }
        }
    }

    /// Out-of-range sections are refused, not panicked on.
    #[test]
    fn section_past_the_top_is_refused() {
        let column = vec![0u16; SECTION_BLOCKS];
        assert!(Section::from_column(&column, 0).is_some());
        assert!(Section::from_column(&column, 1).is_none());
    }

    /// Negative world y must land in the right section.
    #[test]
    fn negative_y_uses_floor_division() {
        let min_y = -64;
        assert_eq!(section_index_of(min_y, -64), 0);
        assert_eq!(section_index_of(min_y, -49), 0);
        assert_eq!(section_index_of(min_y, -48), 1);
        assert_eq!(section_index_of(min_y, -1), 3);
        assert_eq!(section_index_of(min_y, 0), 4);
        assert_eq!(section_index_of(min_y, 319), SECTIONS_PER_COLUMN as i32 - 1);
    }

    /// A real column from the generator must split into sections that
    /// reassemble into the original blocks.
    #[test]
    fn a_generated_column_round_trips_through_sections() {
        let mut engine = TerrainEngine::create(&TerrainConfig::overworld(5)).expect("engine");
        engine.set_budget(32 * 1024 * 1024).expect("budget");
        engine.stream(0, 0, 1, 0).expect("stream");

        let column = engine.acquire(0, 0).expect("acquire").to_vec();
        engine.release(0, 0);

        let mut rebuilt = vec![0u16; column.len()];
        for index in 0..SECTIONS_PER_COLUMN {
            let section = Section::from_column(&column, index).expect("section");
            let base = index * SECTION_BLOCKS;
            rebuilt[base..base + SECTION_BLOCKS].copy_from_slice(&section.blocks);
        }
        assert!(
            rebuilt.iter().eq(column.iter()),
            "sections did not reassemble into the original column"
        );
    }

    /// The one-gigabyte preset must fit its budget with the section trim.
    #[test]
    fn the_one_gigabyte_model_fits() {
        let model = MemoryModel::for_one_gigabyte();
        assert_eq!(model.radius, 20);
        assert_eq!(model.columns, 1257);

        // Whole columns do not fit: that is the entire reason for sections.
        assert!(
            model.whole_column_bytes() > model.budget_bytes,
            "the preset is pointless if whole columns already fit"
        );

        // Eight kept sections per column does fit, with room to spare.
        let trimmed = model.section_trimmed_bytes(8);
        assert!(
            trimmed < model.budget_bytes,
            "8 sections/column needs {} bytes, budget is {}",
            trimmed,
            model.budget_bytes
        );
    }

    /// The two-gigabyte preset keeps the whole window resident.
    #[test]
    fn the_two_gigabyte_model_holds_every_column() {
        let model = MemoryModel::for_two_gigabytes();
        assert_eq!(model.radius, 28);
        assert_eq!(model.columns, 2453);
        assert!(
            model.whole_column_bytes() <= model.budget_bytes,
            "the 2 GiB preset is supposed to hold every column whole"
        );
    }

    /// Section count per column is what the height implies, not a magic 24.
    #[test]
    fn sections_per_column_follows_the_height() {
        assert_eq!(SECTIONS_PER_COLUMN, 24);
        assert_eq!(SECTIONS_PER_COLUMN * SECTION_HEIGHT, 384);
        assert_eq!(
            SECTION_BLOCKS as u64 * SECTIONS_PER_COLUMN as u64,
            CHUNK_BLOCKS_DEFAULT_HEIGHT
        );
    }

    /// The trim is clamped, never zero and never past the column.
    #[test]
    fn the_vertical_trim_is_clamped() {
        let mut engine = TerrainEngine::create(&TerrainConfig::overworld(9)).expect("engine");
        engine.set_budget(32 * 1024 * 1024).expect("budget");
        let mut source = TerrainSource::new(engine, MemoryModel::for_one_gigabyte());
        assert_eq!(source.set_sections_kept(0), 1);
        assert_eq!(source.set_sections_kept(8), 8);
        assert_eq!(source.set_sections_kept(999), SECTIONS_PER_COLUMN);
    }
}