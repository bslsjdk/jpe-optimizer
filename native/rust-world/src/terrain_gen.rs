//! Native terrain generator binding — C ABI v1, `native/cpp-world/`.
//!
//! This is the **C++ terrain engine** side of the split: seed in, finished
//! chunk block ids out. No Java, no Minecraft, no JVM. The generator owns the
//! noise router, density, aquifer, carvers, surface rules and biome selection;
//! this module only marshals buffers.
//!
//! Enabled with the `cpp-bridge` feature, which compiles `native/cpp-world`
//! into this crate (see `build.rs`). Without the feature the module still
//! exists but only [`FlatGenerator`] is available, so downstream code can be
//! written once and tested without a C++ toolchain.

use crate::mesh::ChunkSection;

/// Blocks per section edge.
const SECTION: usize = 16;

/// One chunk column of block ids, section-major:
/// `index = (block_y - min_y) << 8 | (block_z & 15) << 4 | (block_x & 15)`.
pub type ChunkBlocks = Vec<u16>;

/// Configuration mirroring `JpeTerrainConfig`.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct TerrainConfig {
    pub seed: i64,
    pub min_y: i32,
    pub height: i32,
    pub sea_level: i32,
    pub lava_level: i32,
    pub stages: u32,
    pub threads: u32,
}

impl Default for TerrainConfig {
    fn default() -> Self {
        Self {
            seed: 0,
            min_y: -64,
            height: 384,
            sea_level: 63,
            lava_level: -54,
            stages: 0x1f,
            threads: 0,
        }
    }
}

impl TerrainConfig {
    pub fn sections(&self) -> usize {
        (self.height / SECTION as i32).max(0) as usize
    }

    /// First section index (`block_y >> 4`) covered by this column.
    pub fn base_section_y(&self) -> i32 {
        self.min_y >> 4
    }

    pub fn blocks_per_chunk(&self) -> usize {
        SECTION * SECTION * (self.height.max(0) as usize)
    }
}

/// Per-column summary returned by the generator.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct ColumnInfo {
    pub biome: i32,
    pub surface_y: i32,
    pub water_top_y: i32,
    pub solid_blocks: u32,
    pub fluid_blocks: u32,
}

/// Generator counters. `total_nanos` is summed worker time.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct TerrainStats {
    pub chunks_generated: u64,
    pub blocks_written: u64,
    pub density_samples: u64,
    pub noise_samples: u64,
    pub carve_cells: u64,
    pub total_nanos: u64,
    pub threads: u32,
    pub biome_count: u32,
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum TerrainGenError {
    /// The native side rejected the configuration.
    Create,
    /// A generate call returned a negative status.
    Status(i32),
    /// Output buffer is smaller than one chunk.
    Buffer,
}

impl std::fmt::Display for TerrainGenError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TerrainGenError::Create => write!(f, "terrain generator rejected the configuration"),
            TerrainGenError::Status(code) => write!(f, "terrain generate failed with status {code}"),
            TerrainGenError::Buffer => write!(f, "output buffer smaller than one chunk"),
        }
    }
}

impl std::error::Error for TerrainGenError {}

/// Split one generated column into the 16³ sections the mesher consumes.
///
/// The native layout is already `(y << 8) | (z << 4) | x`, so each section is
/// one contiguous 4096-element slice and this is 24 memcpys per chunk.
pub fn sections_from_chunk(data: &[u16], sections: usize) -> Vec<ChunkSection> {
    let mut out = Vec::with_capacity(sections);
    for i in 0..sections {
        let lo = i * ChunkSection::VOLUME;
        let hi = lo + ChunkSection::VOLUME;
        if hi > data.len() {
            break;
        }
        out.push(ChunkSection::from_indices(&data[lo..hi]).expect("4096 blocks"));
    }
    out
}

// ---------------------------------------------------------------------------
// Native implementation
// ---------------------------------------------------------------------------

#[cfg(feature = "cpp-bridge")]
mod native {
    use std::os::raw::{c_char, c_int};

    #[repr(C)]
    #[derive(Copy, Clone, Debug)]
    pub struct RawConfig {
        pub seed: i64,
        pub min_y: i32,
        pub height: i32,
        pub sea_level: i32,
        pub lava_level: i32,
        pub stages: u32,
        pub threads: u32,
    }

    #[repr(C)]
    #[derive(Copy, Clone, Debug, Default)]
    pub struct RawColumnInfo {
        pub biome: i32,
        pub surface_y: i32,
        pub water_top_y: i32,
        pub solid_blocks: u32,
        pub fluid_blocks: u32,
    }

    #[repr(C)]
    #[derive(Copy, Clone, Debug, Default)]
    pub struct RawStats {
        pub chunks_generated: u64,
        pub blocks_written: u64,
        pub density_samples: u64,
        pub noise_samples: u64,
        pub carve_cells: u64,
        pub total_nanos: u64,
        pub threads: u32,
        pub biome_count: u32,
    }

    extern "C" {
        pub fn jpe_terrain_abi_version() -> u32;
        pub fn jpe_terrain_create(config: *const RawConfig) -> u64;
        pub fn jpe_terrain_destroy(handle: u64);
        pub fn jpe_terrain_chunk_blocks(handle: u64) -> u64;
        pub fn jpe_terrain_generate_chunk(
            handle: u64,
            chunk_x: i32,
            chunk_z: i32,
            out: *mut u16,
            out_length: u64,
        ) -> c_int;
        pub fn jpe_terrain_generate_chunks(
            handle: u64,
            chunk_xz: *const i32,
            count: u32,
            out: *mut u16,
            out_length: u64,
            threads: u32,
        ) -> c_int;
        pub fn jpe_terrain_column_info(
            handle: u64,
            chunk_x: i32,
            chunk_z: i32,
            out: *mut RawColumnInfo,
            out_length: u64,
        ) -> c_int;
        pub fn jpe_terrain_heightmap(
            handle: u64,
            chunk_x: i32,
            chunk_z: i32,
            out: *mut i32,
            out_length: u64,
        ) -> c_int;
        pub fn jpe_terrain_stats(handle: u64, out: *mut RawStats) -> c_int;
        pub fn jpe_terrain_block_name(id: u16) -> *const c_char;
        pub fn jpe_terrain_biome_name(id: i32) -> *const c_char;
    }
}

/// A live native generator. Dropping it joins the generator's worker pool.
#[cfg(feature = "cpp-bridge")]
pub struct NativeGenerator {
    handle: u64,
    config: TerrainConfig,
    blocks_per_chunk: usize,
}

#[cfg(feature = "cpp-bridge")]
impl NativeGenerator {
    pub fn new(config: TerrainConfig) -> Result<Self, TerrainGenError> {
        let raw = native::RawConfig {
            seed: config.seed,
            min_y: config.min_y,
            height: config.height,
            sea_level: config.sea_level,
            lava_level: config.lava_level,
            stages: config.stages,
            threads: config.threads,
        };
        let handle = unsafe { native::jpe_terrain_create(&raw) };
        if handle == 0 {
            return Err(TerrainGenError::Create);
        }
        let blocks = unsafe { native::jpe_terrain_chunk_blocks(handle) } as usize;
        Ok(Self {
            handle,
            config,
            blocks_per_chunk: blocks,
        })
    }

    pub fn abi_version() -> u32 {
        unsafe { native::jpe_terrain_abi_version() }
    }

    pub fn config(&self) -> TerrainConfig {
        self.config
    }

    pub fn blocks_per_chunk(&self) -> usize {
        self.blocks_per_chunk
    }

    pub fn generate_chunk(
        &self,
        chunk_x: i32,
        chunk_z: i32,
        out: &mut [u16],
    ) -> Result<(), TerrainGenError> {
        if out.len() < self.blocks_per_chunk {
            return Err(TerrainGenError::Buffer);
        }
        let rc = unsafe {
            native::jpe_terrain_generate_chunk(
                self.handle,
                chunk_x,
                chunk_z,
                out.as_mut_ptr(),
                out.len() as u64,
            )
        };
        if rc != 0 {
            return Err(TerrainGenError::Status(rc));
        }
        Ok(())
    }

    /// Generate `coords.len()` chunks in parallel. `out` is chunk-major and in
    /// input order. This is the hot path: one call per batch, no per-chunk
    /// marshalling and no allocation inside the generator.
    pub fn generate_batch(
        &self,
        coords: &[(i32, i32)],
        out: &mut [u16],
        threads: u32,
    ) -> Result<(), TerrainGenError> {
        let need = self.blocks_per_chunk * coords.len();
        if out.len() < need {
            return Err(TerrainGenError::Buffer);
        }
        if coords.is_empty() {
            return Ok(());
        }
        let mut flat = Vec::with_capacity(coords.len() * 2);
        for (x, z) in coords {
            flat.push(*x);
            flat.push(*z);
        }
        let rc = unsafe {
            native::jpe_terrain_generate_chunks(
                self.handle,
                flat.as_ptr(),
                coords.len() as u32,
                out.as_mut_ptr(),
                out.len() as u64,
                threads,
            )
        };
        if rc != 0 {
            return Err(TerrainGenError::Status(rc));
        }
        Ok(())
    }

    pub fn heightmap(&self, chunk_x: i32, chunk_z: i32) -> [i32; 256] {
        let mut out = [0i32; 256];
        unsafe {
            native::jpe_terrain_heightmap(self.handle, chunk_x, chunk_z, out.as_mut_ptr(), 256);
        }
        out
    }

    pub fn column_info(&self, chunk_x: i32, chunk_z: i32) -> [ColumnInfo; 256] {
        let mut raw = [native::RawColumnInfo::default(); 256];
        unsafe {
            native::jpe_terrain_column_info(self.handle, chunk_x, chunk_z, raw.as_mut_ptr(), 256);
        }
        let mut out = [ColumnInfo::default(); 256];
        for (dst, src) in out.iter_mut().zip(raw.iter()) {
            *dst = ColumnInfo {
                biome: src.biome,
                surface_y: src.surface_y,
                water_top_y: src.water_top_y,
                solid_blocks: src.solid_blocks,
                fluid_blocks: src.fluid_blocks,
            };
        }
        out
    }

    pub fn stats(&self) -> TerrainStats {
        let mut raw = native::RawStats::default();
        unsafe {
            native::jpe_terrain_stats(self.handle, &mut raw);
        }
        TerrainStats {
            chunks_generated: raw.chunks_generated,
            blocks_written: raw.blocks_written,
            density_samples: raw.density_samples,
            noise_samples: raw.noise_samples,
            carve_cells: raw.carve_cells,
            total_nanos: raw.total_nanos,
            threads: raw.threads,
            biome_count: raw.biome_count,
        }
    }

    pub fn block_name(id: u16) -> String {
        unsafe {
            let ptr = native::jpe_terrain_block_name(id);
            if ptr.is_null() {
                return String::from("unknown");
            }
            std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned()
        }
    }

    pub fn biome_name(id: i32) -> String {
        unsafe {
            let ptr = native::jpe_terrain_biome_name(id);
            if ptr.is_null() {
                return String::from("unknown");
            }
            std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned()
        }
    }
}

#[cfg(feature = "cpp-bridge")]
impl Drop for NativeGenerator {
    fn drop(&mut self) {
        unsafe { native::jpe_terrain_destroy(self.handle) };
    }
}

// ---------------------------------------------------------------------------
// Feature-independent fallback
// ---------------------------------------------------------------------------

/// Deterministic height-field generator with no native code.
///
/// Not terrain: it exists so tests and the pipeline can run a full world loop
/// on a machine without a C++ toolchain. Output is a smooth, seeded surface
/// with water below sea level.
#[derive(Copy, Clone, Debug)]
pub struct FlatGenerator {
    pub config: TerrainConfig,
    pub base_height: i32,
    pub amplitude: i32,
}

impl FlatGenerator {
    pub fn new(config: TerrainConfig, base_height: i32, amplitude: i32) -> Self {
        Self {
            config,
            base_height,
            amplitude,
        }
    }

    pub fn blocks_per_chunk(&self) -> usize {
        self.config.blocks_per_chunk()
    }

    pub fn generate_chunk(&self, chunk_x: i32, chunk_z: i32, out: &mut [u16]) {
        let height = self.config.height as usize;
        let min_y = self.config.min_y;
        for z in 0..SECTION {
            for x in 0..SECTION {
                let wx = chunk_x * 16 + x as i32;
                let wz = chunk_z * 16 + z as i32;
                // Cheap deterministic hash-based ripple.
                let n = ((wx * 73856093) ^ (wz * 19349663)) as f32;
                let ripple = (n * 0.00001).sin() * self.amplitude as f32;
                let surface = self.base_height as f32 + ripple;
                for y in 0..height {
                    let world_y = min_y + y as i32;
                    let id = if world_y > surface as i32 {
                        if world_y <= self.config.sea_level {
                            11 // water
                        } else {
                            0
                        }
                    } else if world_y as f32 > surface - 4.0 {
                        2 // grass
                    } else {
                        1 // stone
                    };
                    out[(y << 8) | (z << 4) | x] = id;
                }
            }
        }
    }

    pub fn heightmap(&self, chunk_x: i32, chunk_z: i32) -> [i32; 256] {
        let mut out = [0i32; 256];
        for z in 0..SECTION {
            for x in 0..SECTION {
                let wx = chunk_x * 16 + x as i32;
                let wz = chunk_z * 16 + z as i32;
                let n = ((wx * 73856093) ^ (wz * 19349663)) as f32;
                out[z * 16 + x] = self.base_height + (n * 0.00001).sin() as i32 * self.amplitude;
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_layout_matches_the_native_contract() {
        let cfg = TerrainConfig::default();
        assert_eq!(cfg.blocks_per_chunk(), 16 * 16 * 384);
        assert_eq!(cfg.sections(), 24);
        assert_eq!(cfg.base_section_y(), -4);
    }

    #[test]
    fn sections_split_a_column_in_order() {
        let mut data = vec![0u16; 24 * 4096];
        // Mark each section's first block with its index.
        for i in 0..24 {
            data[i * 4096] = (i + 1) as u16;
        }
        let sections = sections_from_chunk(&data, 24);
        assert_eq!(sections.len(), 24);
        for (i, section) in sections.iter().enumerate() {
            assert_eq!(section.get(0, 0, 0), (i + 1) as u16);
        }
    }

    #[test]
    fn flat_generator_fills_water_above_land_below_sea_level() {
        let cfg = TerrainConfig {
            height: 64,
            min_y: 0,
            sea_level: 20,
            ..TerrainConfig::default()
        };
        let gen = FlatGenerator::new(cfg, 16, 0);
        let mut out = vec![0u16; cfg.blocks_per_chunk()];
        gen.generate_chunk(0, 0, &mut out);
        assert_eq!(out[(16 << 8) | 0], 2, "grass at the surface");
        assert_eq!(out[(19 << 8) | 0], 11, "water above the surface");
        assert_eq!(out[(0 << 8) | 0], 1, "stone at the bottom");
    }
}
