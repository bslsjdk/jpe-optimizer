//! Coordinate types that mirror Minecraft 26.3 block/section/chunk layout.
//!
//! The packing functions follow the vanilla formulas so values can cross the
//! Java/native boundary without re-encoding. Every type here is `Copy` and
//! allocation-free.

/// Horizontal section size (16 blocks) and the bit mask for the local axis.
pub const SECTION_SIZE: i32 = 16;
pub const SECTION_MASK: i32 = SECTION_SIZE - 1;

/// Overworld vertical bounds for the 26.3 default generator.
pub const MIN_Y: i32 = -64;
pub const WORLD_HEIGHT: i32 = 384;
pub const SECTION_COUNT: i32 = WORLD_HEIGHT / SECTION_SIZE; // 24
pub const MIN_SECTION_Y: i32 = MIN_Y >> 4; // -4
pub const MAX_SECTION_Y: i32 = MIN_SECTION_Y + SECTION_COUNT - 1; // 19

/// A chunk column position. Mirrors `net.minecraft.world.level.ChunkPos`.
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash, Debug, Default)]
pub struct ChunkPos {
    pub x: i32,
    pub z: i32,
}

impl ChunkPos {
    pub const ZERO: ChunkPos = ChunkPos { x: 0, z: 0 };

    pub const fn new(x: i32, z: i32) -> Self {
        Self { x, z }
    }

    /// Chunk containing the given block X/Z coordinate (arithmetic shift, so
    /// negative coordinates correctly land in the previous chunk).
    pub const fn from_block(block_x: i32, block_z: i32) -> Self {
        Self {
            x: block_x >> 4,
            z: block_z >> 4,
        }
    }

    /// First block coordinate of this chunk.
    pub const fn origin_block(self) -> (i32, i32) {
        (self.x << 4, self.z << 4)
    }

    /// Center of the chunk in block coordinates.
    pub fn center_block(self) -> (f64, f64) {
        let (bx, bz) = self.origin_block();
        (bx as f64 + 8.0, bz as f64 + 8.0)
    }

    /// Vanilla `ChunkPos.toLong`: low 32 bits = x, high 32 bits = z.
    pub const fn to_long(self) -> i64 {
        ((self.x as u32 as u64) | ((self.z as u32 as u64) << 32)) as i64
    }

    pub const fn from_long(packed: i64) -> Self {
        let x = packed as u32 as i32;
        let z = ((packed as u64) >> 32) as u32 as i32;
        Self { x, z }
    }

    pub fn chebyshev(self, other: ChunkPos) -> i32 {
        (self.x - other.x).abs().max((self.z - other.z).abs())
    }

    /// Squared distance from this chunk center to a block-space point.
    pub fn distance_sq_to_block(self, block_x: f64, block_z: f64) -> f64 {
        let (cx, cz) = self.center_block();
        let dx = cx - block_x;
        let dz = cz - block_z;
        dx * dx + dz * dz
    }

    pub fn within_radius(self, center: ChunkPos, radius: i32) -> bool {
        self.chebyshev(center) <= radius
    }
}

/// A 16x16x16 section position. X/Z are chunk coordinates, Y is a section index
/// (block Y >> 4), which may be negative.
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash, Debug, Default)]
pub struct SectionPos {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl SectionPos {
    pub const fn new(x: i32, y: i32, z: i32) -> Self {
        Self { x, y, z }
    }

    pub const fn from_block(block_x: i32, block_y: i32, block_z: i32) -> Self {
        Self {
            x: block_x >> 4,
            y: block_y >> 4,
            z: block_z >> 4,
        }
    }

    pub const fn origin_block(self) -> (i32, i32, i32) {
        (self.x << 4, self.y << 4, self.z << 4)
    }

    pub const fn chunk(self) -> ChunkPos {
        ChunkPos {
            x: self.x,
            z: self.z,
        }
    }

    /// Vanilla `SectionPos.asLong`. Field layout is Y (bits 0..19),
    /// Z (bits 20..41), X (bits 42..63); there is no overlap.
    pub const fn to_long(self) -> i64 {
        ((self.x as i64 & 0x3F_FFFF) << 42)
            | ((self.z as i64 & 0x3F_FFFF) << 20)
            | (self.y as i64 & 0xF_FFFF)
    }

    pub const fn from_long(packed: i64) -> Self {
        // Sign-extend the top 22-bit X field, the 22-bit Z field starting at
        // bit 20, and the low 20-bit Y field.
        let x = (packed >> 42) as i32;
        let z = ((packed << 22) >> 42) as i32;
        let y = ((packed << 44) >> 44) as i32;
        Self { x, y, z }
    }

    pub fn is_valid_y(self) -> bool {
        (MIN_SECTION_Y..=MAX_SECTION_Y).contains(&self.y)
    }
}

/// A block position in world space.
#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash, Debug, Default)]
pub struct BlockPos {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl BlockPos {
    pub const fn new(x: i32, y: i32, z: i32) -> Self {
        Self { x, y, z }
    }

    pub const fn section(self) -> SectionPos {
        SectionPos::from_block(self.x, self.y, self.z)
    }

    pub const fn chunk(self) -> ChunkPos {
        ChunkPos::from_block(self.x, self.z)
    }

    /// Local 0..15 offset inside the containing section.
    pub const fn local(self) -> (u8, u8, u8) {
        (
            (self.x & SECTION_MASK) as u8,
            (self.y & SECTION_MASK) as u8,
            (self.z & SECTION_MASK) as u8,
        )
    }
}

/// Y range that a chunk section covers in block space.
pub fn section_block_y_range(section_y: i32) -> (i32, i32) {
    let min = section_y << 4;
    (min, min + SECTION_SIZE - 1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chunk_long_roundtrip_including_negatives() {
        for &(x, z) in &[(0, 0), (1, -1), (-1, 1), (-12345, 987654), (i32::MIN, i32::MAX)] {
            let p = ChunkPos::new(x, z);
            assert_eq!(ChunkPos::from_long(p.to_long()), p, "chunk {p:?}");
        }
    }

    #[test]
    fn section_long_roundtrip_matches_vanilla_layout() {
        // Field layout: X | Z | Y. Positive case is easy to reason about.
        let s = SectionPos::new(3, 5, 7);
        let packed = s.to_long();
        assert_eq!(packed, (3 << 42) | (7 << 20) | 5);
        for &(x, y, z) in &[
            (0, 0, 0),
            (-1, -4, -1),
            (19, 19, -19),
            (-(1 << 21), -(1 << 19), (1 << 21) - 1),
        ] {
            let p = SectionPos::new(x, y, z);
            assert_eq!(SectionPos::from_long(p.to_long()), p, "section {p:?}");
        }
    }

    #[test]
    fn from_block_uses_arithmetic_shift() {
        assert_eq!(ChunkPos::from_block(-1, -1), ChunkPos::new(-1, -1));
        assert_eq!(ChunkPos::from_block(-16, 16), ChunkPos::new(-1, 1));
        assert_eq!(BlockPos::new(-1, -65, 17).section(), SectionPos::new(-1, -5, 1));
        assert_eq!(BlockPos::new(-1, -65, 17).local(), (15, 15, 1));
    }

    #[test]
    fn y_bounds_of_default_overworld() {
        assert_eq!((MIN_SECTION_Y, MAX_SECTION_Y), (-4, 19));
        assert!(!SectionPos::new(0, MIN_SECTION_Y - 1, 0).is_valid_y());
        assert!(SectionPos::new(0, MIN_SECTION_Y, 0).is_valid_y());
        assert!(SectionPos::new(0, MAX_SECTION_Y, 0).is_valid_y());
        assert!(!SectionPos::new(0, MAX_SECTION_Y + 1, 0).is_valid_y());
    }

    #[test]
    fn chunk_distance_and_radius() {
        let center = ChunkPos::new(0, 0);
        assert_eq!(ChunkPos::new(3, 4).chebyshev(center), 4);
        assert!(ChunkPos::new(3, 4).within_radius(center, 4));
        assert!(!ChunkPos::new(5, 0).within_radius(center, 4));
        // A chunk centered at (8,8) in block space.
        let d = center.distance_sq_to_block(8.0, 8.0);
        assert_eq!(d, 0.0);
    }
}
