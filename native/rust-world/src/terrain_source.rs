//! Terrain-engine → block-source adapter: the C++ seam.
//!
//! The C++ Terrain Engine owns terrain **math** behind ABI v8 and is exposed to
//! Rust only through [`crate::terrain::TerrainEngine`] (see `terrain.rs`). The
//! world pipeline, meanwhile, consumes *blocks* through
//! [`crate::pipeline::BlockSource`]. This module is the single, small adapter
//! that joins them: it asks a [`TerrainEngine`] for one section's density volume
//! and thresholds it into a [`ChunkSection`].
//!
//! It is deliberately generic over `E: TerrainEngine`, so:
//!
//! * today it runs against [`MockTerrainEngine`] in tests (deterministic,
//!   dependency-free), and
//! * once the user wires the C++ side, it is constructed with
//!   [`crate::terrain::NativeTerrainEngine`] unchanged — see
//!   [`native_density_block_source`]. No pipeline or renderer code changes.
//!
//! Nothing here reimplements or duplicates terrain math; it only maps a density
//! volume onto the block ids the mesher already understands.

use crate::coords::SectionPos;
use crate::mesh::ChunkSection;
use crate::pipeline::BlockSource;
use crate::terrain::{PerlinLayerSpec, TerrainEngine, TerrainError, VolumeDesc};

/// Blocks per section edge, matching [`crate::mesh::ChunkSection`].
const SECTION: usize = 16;

/// A [`BlockSource`] backed by a [`TerrainEngine`].
///
/// For each requested section it evaluates the configured Perlin layer stack
/// over that section's `16 x 16 x 16` block region (ABI v8 `VolumeDesc`, step 1)
/// and marks a block solid when its density exceeds [`Self::solid_threshold`].
pub struct DensityBlockSource<E: TerrainEngine> {
    engine: E,
    layers: Vec<PerlinLayerSpec>,
    /// Density strictly greater than this becomes a solid block.
    pub solid_threshold: f32,
    /// Block id written for solid cells.
    pub block: u16,
    /// Sections evaluated through the engine (diagnostics / tests).
    pub evaluated: u64,
    /// Last engine error, if the most recent evaluation failed. A failed section
    /// reads back empty (conservative: neighbour faces stay visible), never panics.
    last_error: Option<TerrainError>,
}

impl<E: TerrainEngine> DensityBlockSource<E> {
    /// Build an adapter over `engine` using `layers`, with threshold `0.0` and
    /// block id `1`.
    pub fn new(engine: E, layers: Vec<PerlinLayerSpec>) -> Self {
        Self {
            engine,
            layers,
            solid_threshold: 0.0,
            block: 1,
            evaluated: 0,
            last_error: None,
        }
    }

    /// The most recent engine error, if any.
    pub fn last_error(&self) -> Option<TerrainError> {
        self.last_error
    }

    /// Mutable access to the wrapped engine (e.g. to configure worker count).
    pub fn engine_mut(&mut self) -> &mut E {
        &mut self.engine
    }

    fn volume_desc(pos: SectionPos) -> VolumeDesc {
        let (bx, by, bz) = pos.origin_block();
        VolumeDesc {
            size: [SECTION as u32, SECTION as u32, SECTION as u32],
            min: [bx, by, bz],
            step: [1, 1, 1],
        }
    }
}

impl<E: TerrainEngine> BlockSource for DensityBlockSource<E> {
    fn section(&mut self, pos: SectionPos) -> ChunkSection {
        let desc = Self::volume_desc(pos);
        let mut out = ChunkSection::new();
        match self.engine.perlin_stack_volume(&self.layers, &desc) {
            Ok(volume) => {
                self.evaluated += 1;
                // ABI v8 order is ((z * size_x + x) * size_y + y); mirror it
                // exactly and index the destination by (x, y, z).
                let data = &volume.data;
                let mut i = 0usize;
                for z in 0..SECTION {
                    for x in 0..SECTION {
                        for y in 0..SECTION {
                            if data[i] > self.solid_threshold {
                                out.set(x, y, z, self.block);
                            }
                            i += 1;
                        }
                    }
                }
            }
            Err(error) => {
                // Conservative empty section; remember why for diagnostics.
                self.last_error = Some(error);
            }
        }
        out
    }
}

/// Convenience: a [`DensityBlockSource`] over the **real C++** terrain kernels
/// (ABI v8). This is the drop-in once the C++ bridge is enabled; the pipeline
/// and every renderer stay identical.
#[cfg(feature = "cpp-bridge")]
pub fn native_density_block_source(
    layers: Vec<PerlinLayerSpec>,
) -> DensityBlockSource<crate::terrain::NativeTerrainEngine> {
    DensityBlockSource::new(crate::terrain::NativeTerrainEngine, layers)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mesh::{build_section_mesh_with_neighbors, MeshScratch, Neighbors};

    fn one_layer() -> Vec<PerlinLayerSpec> {
        vec![PerlinLayerSpec {
            permutation: [0u8; 256],
            offset_x: 0.0,
            offset_y: 0.0,
            offset_z: 0.0,
            xz_scale: 1.0,
            y_scale: 1.0,
            amplitude: 1.0,
            fudge_y_scale: 1.0,
            smeared: false,
        }]
    }

    #[test]
    fn dense_source_fills_every_block_above_threshold() {
        // Mock density is strictly positive here, so threshold 0 => solid.
        let mut source = DensityBlockSource::new(
            crate::terrain::MockTerrainEngine::default(),
            one_layer(),
        );
        let section = source.section(SectionPos::new(0, 0, 0));
        let mut solid = 0;
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    if section.get(x, y, z) == 1 {
                        solid += 1;
                    }
                }
            }
        }
        assert_eq!(solid, 16 * 16 * 16);
        assert_eq!(source.evaluated, 1);
        assert!(source.last_error().is_none());
    }

    #[test]
    fn threshold_controls_solidity_and_is_monotonic() {
        let low = {
            let mut s = DensityBlockSource::new(crate::terrain::MockTerrainEngine::default(), one_layer());
            s.solid_threshold = 0.0;
            s.section(SectionPos::new(0, 0, 0))
        };
        let high = {
            let mut s = DensityBlockSource::new(crate::terrain::MockTerrainEngine::default(), one_layer());
            s.solid_threshold = 100.0; // above the mock's maximum value
            s.section(SectionPos::new(0, 0, 0))
        };
        assert!(!low.is_empty());
        assert!(high.is_empty(), "threshold above all densities must be all air");
    }

    #[test]
    fn evaluation_is_deterministic() {
        let a = DensityBlockSource::new(crate::terrain::MockTerrainEngine::default(), one_layer())
            .section(SectionPos::new(-1, 3, 2));
        let b = DensityBlockSource::new(crate::terrain::MockTerrainEngine::default(), one_layer())
            .section(SectionPos::new(-1, 3, 2));
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    assert_eq!(a.get(x, y, z), b.get(x, y, z));
                }
            }
        }
    }

    #[test]
    fn feeds_the_mesher_a_non_empty_mesh() {
        let mut source = DensityBlockSource::new(
            crate::terrain::MockTerrainEngine::default(),
            one_layer(),
        );
        let section = source.section(SectionPos::new(0, 0, 0));
        // Neighbouring sections are absent; boundary faces stay visible, so the
        // all-solid section must yield surface quads, not nothing.
        let neighbors = Neighbors {
            pos_x: None,
            neg_x: None,
            pos_y: None,
            neg_y: None,
            pos_z: None,
            neg_z: None,
        };
        let mut scratch = MeshScratch::new();
        let quads = build_section_mesh_with_neighbors(
            &section,
            &neighbors,
            [0, 0, 0],
            &mut scratch,
        );
        assert!(quads > 0, "mesher produced no geometry");
    }
}