//! Built-in block colour atlas.
//!
//! The mesher emits one quad per visible face with a UV rect that selects a
//! 32x32 tile of a 256x256 RGBA8 atlas; this module owns that table. It exists
//! so a headless build renders a *recognisable* world with no resource pack,
//! no Minecraft jar and no Java: the tile colours below are the only thing
//! standing between "grey noise" and "grass, sand, water, snow".
//!
//! Tile ids are the native generator's block ids (`native/cpp-world`), plus a
//! handful of extra tiles for faces whose texture differs from the block's
//! default one (grass sides, for example). Ids 0..=`BLOCK_COUNT-1` are blocks;
//! everything above is a face variant and never appears in block data.

/// Tiles per atlas row.
pub const TILES: i32 = 8;
/// Texels per tile edge.
pub const TILE_PX: i32 = 32;
/// Atlas edge in texels.
pub const ATLAS_SIZE: i32 = TILES * TILE_PX;

/// Tile used for a face whose block has no entry (should not happen).
const FALLBACK_TILE: u16 = 1;

/// Extra tiles past the block id range.
pub mod tile {
    /// Dirt with a grass band at the top: the side of a grass block.
    pub const GRASS_SIDE: u16 = 28;
}

/// Base colour per block id, in the native generator's id order.
///
/// Values are sRGB, chosen to read correctly under the renderer's 0.5..1.0
/// directional shade term: the darkest face of a block lands near the vanilla
/// side texture and the lit top near the vanilla top.
const BLOCK_RGB: [[u8; 3]; 29] = [
    [16, 16, 20],    // 0  air (never meshed; dark so a hole is obvious)
    [126, 126, 126], // 1  stone
    [104, 156, 66],  // 2  grass block (top colour)
    [134, 96, 67],   // 3  dirt
    [119, 85, 59],   // 4  coarse dirt
    [91, 63, 24],    // 5  podzol
    [219, 207, 163], // 6  sand
    [169, 88, 33],   // 7  red sand
    [216, 203, 155], // 8  sandstone
    [136, 126, 126], // 9  gravel
    [159, 164, 177], // 10 clay
    [48, 96, 190],   // 11 water
    [212, 96, 22],   // 12 lava
    [58, 58, 62],    // 13 bedrock
    [78, 78, 86],    // 14 deepslate
    [240, 240, 250], // 15 snow block
    [158, 198, 250], // 16 ice
    [152, 94, 67],   // 17 terracotta
    [132, 132, 134], // 18 andesite
    [149, 103, 85],  // 19 granite
    [223, 224, 220], // 20 calcite
    [92, 138, 62],   // 21 moss block
    [58, 52, 46],    // 22 mud
    [140, 180, 246], // 23 packed ice
    [138, 118, 108], // 24 mycelium
    [108, 109, 102], // 25 tuff
    [134, 107, 92],  // 26 dripstone
    [247, 247, 252], // 27 snow layer
    [134, 96, 67],   // 28 grass side base (dirt; the band is painted below)
];

/// The tile a face of `block` should sample.
///
/// `face` is the mesher's face index: 0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z.
/// Only the grass block special-cases: vanilla gives it a green top, a dirt
/// bottom and a banded side, and a landscape reads wrong without that.
pub fn tile_for_face(block: u16, face: usize) -> u16 {
    if block == 2 {
        return match face {
            2 => 2,
            3 => 3,
            _ => tile::GRASS_SIDE,
        };
    }
    if block as usize >= BLOCK_RGB.len() {
        return FALLBACK_TILE;
    }
    block
}

/// UV rect of `tile` as `[u0, v0, u1, v1]`, inset by half a texel.
///
/// The inset matters because the sampler is nearest-neighbour with no mips: a
/// UV of exactly 1.0 on a tile edge can round into the neighbouring tile and
/// draw a one-texel stripe of the wrong block along every chunk face.
pub fn tile_rect(tile: u16) -> [f32; 4] {
    let t = tile as i32;
    let tx = t % TILES;
    let ty = t / TILES;
    let step = 1.0 / TILES as f32;
    let half = 0.5 / ATLAS_SIZE as f32;
    [
        tx as f32 * step + half,
        ty as f32 * step + half,
        (tx + 1) as f32 * step - half,
        (ty + 1) as f32 * step - half,
    ]
}

/// Map a face-local UV (0..1 on the quad) into `tile`.
pub fn tile_uv(tile: u16, local: [f32; 2]) -> [f32; 2] {
    let r = tile_rect(tile);
    [
        r[0] + (r[2] - r[0]) * local[0],
        r[1] + (r[3] - r[1]) * local[1],
    ]
}

/// Deterministic brightness jitter, so a flat colour still reads as a textured
/// block instead of a plastic panel.
///
/// Deliberately low-frequency and low-amplitude: a block is 1-2 pixels at view
/// distance, so a per-texel pattern turns into moire speckle instead of
/// texture. `x >> 1` gives 2x2-texel blotches and +-7 keeps the average colour
/// stable when the sampler undersamples it.
fn texel_noise(tile: u16, x: i32, y: i32) -> i32 {
    let mut h = (tile as u32).wrapping_mul(0x9E37_79B1)
        ^ (x as u32).wrapping_mul(0x85EB_CA6B)
        ^ (y as u32).wrapping_mul(0xC2B2_AE35);
    h ^= h >> 15;
    h = h.wrapping_mul(0x2545_F491);
    h ^= h >> 13;
    ((h & 0x0F) as i32) - 7 // -7..=8
}

/// Build the atlas as RGBA8, row-major, `ATLAS_SIZE * ATLAS_SIZE` texels.
pub fn atlas_pixels() -> Vec<u8> {
    let size = ATLAS_SIZE as usize;
    // Unused tiles are opaque grey rather than transparent black: a bug that
    // samples one shows up as an obviously wrong grey panel, not as a hole.
    let mut px = vec![0u8; size * size * 4];
    for texel in px.chunks_mut(4) {
        texel.copy_from_slice(&[128, 128, 128, 255]);
    }
    for (tile, base) in BLOCK_RGB.iter().enumerate() {
        let tile = tile as u16;
        let tx = (tile as i32 % TILES) * TILE_PX;
        let ty = (tile as i32 / TILES) * TILE_PX;
        for y in 0..TILE_PX {
            for x in 0..TILE_PX {
                let mut rgb = *base;
                // The grass side keeps a dirt body and paints a green band
                // with a ragged edge, which is what makes a cliff face look
                // like a cliff and not like brown concrete.
                //
                // The band sits at the *end* of the tile's rows because the
                // mesher's face UVs put v=0 at the bottom corner of a face and
                // both GL and Vulkan resolve v=0 to the first row in memory.
                // Row 0 of a tile is therefore the bottom of the face.
                if tile == tile::GRASS_SIDE && y >= TILE_PX - 9 - (texel_noise(tile, x >> 1, 0) & 1) {
                    rgb = BLOCK_RGB[2];
                }
                let n = texel_noise(tile, x >> 1, y >> 1);
                let o = (((ty + y) * ATLAS_SIZE + tx + x) * 4) as usize;
                for c in 0..3 {
                    let v = rgb[c] as i32 + n;
                    px[o + c] = v.clamp(0, 255) as u8;
                }
                px[o + 3] = 255;
            }
        }
    }
    px
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn atlas_is_fully_covered() {
        let px = atlas_pixels();
        assert_eq!(px.len(), (ATLAS_SIZE * ATLAS_SIZE * 4) as usize);
        // Every texel of every used tile is opaque.
        for tile in 0..=tile::GRASS_SIDE {
            let r = tile_rect(tile);
            assert!(r[0] > 0.0 && r[1] > 0.0 && r[2] < 1.0 && r[3] < 1.0);
            assert!(r[0] < r[2] && r[1] < r[3]);
        }
        assert!(px.chunks(4).all(|p| p[3] == 255));
    }

    #[test]
    fn tiles_do_not_overlap() {
        let mut seen = std::collections::HashSet::new();
        for tile in 0..=(TILES * TILES) as u16 {
            let r = tile_rect(tile);
            let key = (
                (r[0] * ATLAS_SIZE as f32) as i32,
                (r[1] * ATLAS_SIZE as f32) as i32,
            );
            assert!(seen.insert(key), "tile {tile} shares a corner with another");
        }
    }

    #[test]
    fn grass_uses_three_tiles() {
        assert_eq!(tile_for_face(2, 2), 2);
        assert_eq!(tile_for_face(2, 3), 3);
        assert_eq!(tile_for_face(2, 0), tile::GRASS_SIDE);
        assert_eq!(tile_for_face(1, 0), 1);
        assert_eq!(tile_for_face(999, 0), FALLBACK_TILE);
    }
}
