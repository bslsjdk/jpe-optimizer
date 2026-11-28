//! Chunk section mesh construction and frustum culling.
//!
//! This is a deterministic CPU mesher over a 16x16x16 section. It is not the
//! final renderer: greedy meshing, ambient occlusion and lighting are future
//! work. It exists so the world engine has a real, testable geometric contract
//! (vertex format + index buffer) that all three GPU backends must consume
//! unchanged.

use crate::coords::SECTION_SIZE;
use crate::render::atlas;
use std::sync::Arc;

/// One 16x16x16 section. `0` is air; every non-zero value is opaque for now.
///
/// The block array is shared behind an [`Arc`]. Every owner between ingest and
/// the mesher wants a `ChunkSection` of its own — Java hands one in, `PushSource`
/// stores it, the pipeline copies it into its block cache, and the mesher reads
/// it — so a plain `Vec` meant one 8 KiB allocation and one 8 KiB copy per hand
/// off, two live copies of every section at once. At view distance 8 that is
/// 289 chunks x 8 sections x 16 KiB = 37 MiB and ~11 ms of a 23 ms fill spent in
/// `memcpy`. Cloning now bumps a refcount instead.
///
/// `set` is copy-on-write: a section being filled is uniquely owned, so writes
/// stay in place; only a write to a section someone else still holds copies the
/// array, which is the same visible semantics as the old `Vec` clone.
#[derive(Clone, Debug)]
pub struct ChunkSection {
    blocks: Arc<Vec<u16>>,
    /// Non-air blocks, maintained on every write so [`Self::is_empty`] and
    /// [`Self::is_full`] are O(1). The mesher uses them to skip a section
    /// outright: an air section emits nothing, and a full section whose six
    /// neighbour layers are solid emits nothing either — which is most of the
    /// underground, and used to cost a full 4096-block scan to discover.
    non_air: u32,
}

impl ChunkSection {
    pub const VOLUME: usize = (SECTION_SIZE * SECTION_SIZE * SECTION_SIZE) as usize; // 4096

    pub fn new() -> Self {
        Self {
            blocks: Arc::new(vec![0; Self::VOLUME]),
            non_air: 0,
        }
    }

    #[inline]
    fn index(x: usize, y: usize, z: usize) -> usize {
        (y << 8) | (z << 4) | x
    }

    #[inline]
    pub fn get(&self, x: usize, y: usize, z: usize) -> u16 {
        self.blocks[Self::index(x, y, z)]
    }

    /// Write one block. Copy-on-write: no copy while this section is the only
    /// owner, which is the case for every source filling a fresh section.
    #[inline]
    pub fn set(&mut self, x: usize, y: usize, z: usize, block: u16) {
        let index = Self::index(x, y, z);
        let slot = &mut Arc::make_mut(&mut self.blocks)[index];
        let was_air = *slot == 0;
        let is_air = block == 0;
        if was_air != is_air {
            if is_air {
                self.non_air -= 1;
            } else {
                self.non_air += 1;
            }
        }
        *slot = block;
    }

    #[inline]
    pub fn is_solid(&self, x: usize, y: usize, z: usize) -> bool {
        self.get(x, y, z) != 0
    }

    /// True if every block is air. O(1): the count is maintained on write.
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.non_air == 0
    }

    /// True if every block is solid, so the only faces this section can emit are
    /// the ones its neighbours expose. O(1).
    #[inline]
    pub fn is_full(&self) -> bool {
        self.non_air as usize == Self::VOLUME
    }

    /// Non-air blocks in this section.
    #[inline]
    pub fn non_air(&self) -> u32 {
        self.non_air
    }

    /// Section from a flat block-index array in `(y << 8) | (z << 4) | x` order,
    /// i.e. x fastest, then z, then y — exactly how [`ChunkSection::set`] indexes.
    ///
    /// This is the hand-off shape for callers that own the block data (Java for
    /// now, the C++ terrain engine later): they copy indices straight in with no
    /// transpose. Returns `None` unless the length is exactly [`ChunkSection::VOLUME`].
    pub fn from_indices(blocks: &[u16]) -> Option<Self> {
        if blocks.len() != Self::VOLUME {
            return None;
        }
        Some(Self {
            non_air: blocks.iter().filter(|&&b| b != 0).count() as u32,
            blocks: Arc::new(blocks.to_vec()),
        })
    }

    /// The block indices in the same order [`ChunkSection::from_indices`] expects.
    pub fn indices(&self) -> &[u16] {
        self.blocks.as_slice()
    }
}

impl Default for ChunkSection {
    fn default() -> Self {
        Self::new()
    }
}

/// Interleaved vertex layout shared by every renderer backend.
///
/// Layout (32 bytes): position f32x3, normal f32x3, uv f32x2. All little-endian.
#[derive(Copy, Clone, Debug, PartialEq)]
#[repr(C)]
pub struct Vertex {
    pub position: [f32; 3],
    pub normal: [f32; 3],
    pub uv: [f32; 2],
}

impl Vertex {
    pub const STRIDE: usize = 32;

    pub fn write_le(&self, out: &mut [u8]) {
        assert!(out.len() >= Self::STRIDE, "vertex output buffer too small");
        let mut offset = 0;
        for v in self.position.iter().chain(self.normal.iter()).chain(self.uv.iter()) {
            out[offset..offset + 4].copy_from_slice(&v.to_le_bytes());
            offset += 4;
        }
    }
}

struct FaceDef {
    normal: [i8; 3],
    /// Counter-clockwise corner order, local 0/1 coordinates.
    corners: [[f32; 3]; 4],
}

const FACES: [FaceDef; 6] = [
    // +X
    FaceDef {
        normal: [1, 0, 0],
        corners: [
            [1.0, 0.0, 0.0],
            [1.0, 0.0, 1.0],
            [1.0, 1.0, 1.0],
            [1.0, 1.0, 0.0],
        ],
    },
    // -X
    FaceDef {
        normal: [-1, 0, 0],
        corners: [
            [0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 1.0, 1.0],
            [0.0, 0.0, 1.0],
        ],
    },
    // +Y
    FaceDef {
        normal: [0, 1, 0],
        corners: [
            [0.0, 1.0, 0.0],
            [0.0, 1.0, 1.0],
            [1.0, 1.0, 1.0],
            [1.0, 1.0, 0.0],
        ],
    },
    // -Y
    FaceDef {
        normal: [0, -1, 0],
        corners: [
            [0.0, 0.0, 0.0],
            [1.0, 0.0, 0.0],
            [1.0, 0.0, 1.0],
            [0.0, 0.0, 1.0],
        ],
    },
    // +Z
    FaceDef {
        normal: [0, 0, 1],
        corners: [
            [0.0, 0.0, 1.0],
            [1.0, 0.0, 1.0],
            [1.0, 1.0, 1.0],
            [0.0, 1.0, 1.0],
        ],
    },
    // -Z
    FaceDef {
        normal: [0, 0, -1],
        corners: [
            [0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [1.0, 1.0, 0.0],
            [1.0, 0.0, 0.0],
        ],
    },
];

const UVS: [[f32; 2]; 4] = [[0.0, 0.0], [0.0, 1.0], [1.0, 1.0], [1.0, 0.0]];

/// Reusable mesh buffers, intended to be held per worker thread.
#[derive(Debug)]
pub struct MeshScratch {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    /// Padded block grid for the section currently being meshed: one extra cell
    /// on every side, filled from the neighbouring sections. Reused across
    /// sections so a frame's meshing allocates nothing.
    pad: [u16; PAD_VOLUME],
}

impl Default for MeshScratch {
    fn default() -> Self {
        Self::new()
    }
}

impl MeshScratch {
    /// Const so the pipeline can build one in a `const` thread-local initialiser.
    pub const fn new() -> Self {
        Self {
            vertices: Vec::new(),
            indices: Vec::new(),
            pad: [0; PAD_VOLUME],
        }
    }

    pub fn clear(&mut self) {
        self.vertices.clear();
        self.indices.clear();
    }

    /// Peak capacity retained, in bytes (vertices + indices).
    pub fn retained_bytes(&self) -> usize {
        self.vertices.capacity() * Vertex::STRIDE + self.indices.capacity() * 4
    }
}

/// Finished geometry for one section.
#[derive(Clone, Debug, PartialEq)]
pub struct MeshData {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    pub quad_count: u32,
    /// Block-space bounds of the section this mesh was built for.
    pub origin: [i32; 3],
}

impl MeshData {
    pub fn is_empty(&self) -> bool {
        self.quad_count == 0
    }

    pub fn vertex_bytes(&self) -> usize {
        self.vertices.len() * Vertex::STRIDE
    }
}

/// Build a culled mesh for a section into reusable scratch buffers.
///
/// Faces on the section boundary are always emitted: callers that know the
/// neighbouring section's boundary blocks should cull them afterwards, or use
/// [`build_section_mesh_with_neighbors`].
pub fn build_section_mesh_into(
    section: &ChunkSection,
    origin: [i32; 3],
    scratch: &mut MeshScratch,
) -> u32 {
    build_section_mesh_with_neighbors(section, &Neighbors::none(), origin, scratch)
}

/// Convenience: build into fresh buffers and return owned [`MeshData`].
pub fn build_section_mesh(section: &ChunkSection, origin: [i32; 3]) -> MeshData {
    let mut scratch = MeshScratch::new();
    let quad_count = build_section_mesh_into(section, origin, &mut scratch);
    MeshData {
        vertices: scratch.vertices,
        indices: scratch.indices,
        quad_count,
        origin,
    }
}

/// Optional boundary culling against four horizontal neighbours and the
/// section above/below. Each entry is the neighbouring section if loaded.
pub struct Neighbors<'a> {
    pub pos_x: Option<&'a ChunkSection>,
    pub neg_x: Option<&'a ChunkSection>,
    pub pos_y: Option<&'a ChunkSection>,
    pub neg_y: Option<&'a ChunkSection>,
    pub pos_z: Option<&'a ChunkSection>,
    pub neg_z: Option<&'a ChunkSection>,
}

impl Neighbors<'_> {
    /// True if the six layers that touch this section are entirely solid. A
    /// missing neighbour counts as air, so it can never be solid.
    ///
    /// Only 6 x 256 blocks are read, against the 4096 x 7 a full meshing pass
    /// would load, which is what makes the "nothing to draw" short-circuit worth
    /// taking for a full section.
    fn layers_all_solid(&self) -> bool {
        fn layer(section: Option<&ChunkSection>, axis: usize, coord: usize) -> bool {
            let Some(section) = section else {
                return false;
            };
            // A full neighbour's facing layer is solid by definition, so the
            // underground case (full section, full neighbours) costs six O(1)
            // checks instead of six 256-block scans.
            if section.is_full() {
                return true;
            }
            (0..16).all(|a| {
                (0..16).all(|b| match axis {
                    0 => section.is_solid(coord, a, b),
                    1 => section.is_solid(a, coord, b),
                    _ => section.is_solid(a, b, coord),
                })
            })
        }
        layer(self.pos_x, 0, 0)
            && layer(self.neg_x, 0, 15)
            && layer(self.pos_y, 1, 0)
            && layer(self.neg_y, 1, 15)
            && layer(self.pos_z, 2, 0)
            && layer(self.neg_z, 2, 15)
    }

    /// No neighbour loaded: every boundary face is emitted.
    pub fn none() -> Self {
        Self {
            pos_x: None,
            neg_x: None,
            pos_y: None,
            neg_y: None,
            pos_z: None,
            neg_z: None,
        }
    }
}

// ---------------------------------------------------------------------------
// Padded-grid meshing
//
// The section and its six neighbour boundary layers are copied once into an
// 18³ grid with one extra cell on every side. Every face test is then a single
// indexed load — no `(0..16).contains(...)` per face, no per-axis neighbour
// match — and the block loop never branches on which axis a face belongs to.
// The output is byte-for-byte what the per-face version produced; the
// `padded_mesher_reproduces_the_reference_exactly` test proves it.
// ---------------------------------------------------------------------------

/// Padded edge length: the 16³ section plus one cell on every side.
const PAD: usize = SECTION_SIZE as usize + 2; // 18
const PAD_AREA: usize = PAD * PAD; // 324
const PAD_VOLUME: usize = PAD * PAD * PAD; // 5832

/// Index of local block (x, y, z) in the padded grid. Local 0..16 maps to padded
/// 1..=16, so padded 0 and 17 are the neighbour layers.
#[inline]
fn pad_index(x: usize, y: usize, z: usize) -> usize {
    (y + 1) * PAD_AREA + (z + 1) * PAD + (x + 1)
}

/// Neighbour offsets in the padded grid, one per [`FACES`] entry (+X, -X, +Y,
/// -Y, +Z, -Z): adding one to a cell's index reaches that face's neighbour.
const FACE_OFFSETS: [isize; 6] = [
    1,
    -1,
    PAD_AREA as isize,
    -(PAD_AREA as isize),
    PAD as isize,
    -(PAD as isize),
];

/// Copy the section and its six neighbour boundary layers into `pad`.
///
/// A missing neighbour stays air, which is the previous semantics: a boundary
/// face with no loaded neighbour was emitted. Only face-adjacent cells are ever
/// read, but the whole pad is zeroed so a reused scratch can never leak a
/// previous section's values.
fn fill_padded(pad: &mut [u16; PAD_VOLUME], section: &ChunkSection, neighbors: &Neighbors<'_>) {
    pad.fill(0);
    // Hoisted once: the section's buffer is behind an `Arc`, so indexing it in
    // the loop would chase two pointers per row instead of one.
    let blocks = section.indices();
    for y in 0..16 {
        for z in 0..16 {
            let src = (y << 8) | (z << 4);
            let dst = pad_index(0, y, z);
            pad[dst..dst + 16].copy_from_slice(&blocks[src..src + 16]);
        }
    }
    for y in 0..16 {
        for z in 0..16 {
            if let Some(n) = neighbors.neg_x {
                pad[pad_index(0, y, z) - 1] = n.get(15, y, z);
            }
            if let Some(n) = neighbors.pos_x {
                pad[pad_index(16, y, z)] = n.get(0, y, z);
            }
        }
    }
    for x in 0..16 {
        for z in 0..16 {
            if let Some(n) = neighbors.neg_y {
                pad[pad_index(x, 0, z) - PAD_AREA] = n.get(x, 15, z);
            }
            if let Some(n) = neighbors.pos_y {
                pad[pad_index(x, 16, z)] = n.get(x, 0, z);
            }
        }
    }
    for x in 0..16 {
        for y in 0..16 {
            if let Some(n) = neighbors.neg_z {
                pad[pad_index(x, y, 0) - PAD] = n.get(x, y, 15);
            }
            if let Some(n) = neighbors.pos_z {
                pad[pad_index(x, y, 16)] = n.get(x, y, 0);
            }
        }
    }
}

/// Emit every visible face of a filled padded grid.
fn mesh_padded(
    pad: &[u16; PAD_VOLUME],
    origin: [i32; 3],
    vertices: &mut Vec<Vertex>,
    indices: &mut Vec<u32>,
) -> u32 {
    let mut quads = 0u32;
    let ox = origin[0] as f32;
    let oy = origin[1] as f32;
    let oz = origin[2] as f32;
    for y in 0..16usize {
        for z in 0..16usize {
            for x in 0..16usize {
                let here = pad_index(x, y, z) as isize;
                if pad[here as usize] == 0 {
                    continue;
                }
                // A block enclosed on all six sides emits nothing. Testing that
                // once skips six face tests for every interior block, which is
                // most of a solid section.
                if FACE_OFFSETS
                    .iter()
                    .all(|offset| pad[(here + offset) as usize] != 0)
                {
                    continue;
                }
                let (fx, fy, fz) = (ox + x as f32, oy + y as f32, oz + z as f32);
                let block = pad[here as usize];
                for (face_index, (offset, face)) in
                    FACE_OFFSETS.iter().zip(FACES.iter()).enumerate()
                {
                    if pad[(here + offset) as usize] != 0 {
                        continue;
                    }
                    let base = vertices.len() as u32;
                    let tile = atlas::tile_for_face(block, face_index);
                    for (corner, uv) in face.corners.iter().zip(UVS.iter()) {
                        vertices.push(Vertex {
                            position: [fx + corner[0], fy + corner[1], fz + corner[2]],
                            normal: [
                                face.normal[0] as f32,
                                face.normal[1] as f32,
                                face.normal[2] as f32,
                            ],
                            uv: atlas::tile_uv(tile, *uv),
                        });
                    }
                    indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
                    quads += 1;
                }
            }
        }
    }
    quads
}

/// Like [`build_section_mesh_into`] but culls boundary faces against loaded
/// neighbouring sections. Missing neighbours are treated as air.
pub fn build_section_mesh_with_neighbors(
    section: &ChunkSection,
    neighbors: &Neighbors<'_>,
    origin: [i32; 3],
    scratch: &mut MeshScratch,
) -> u32 {
    scratch.vertices.clear();
    scratch.indices.clear();
    // Two sections cannot emit a single face, and both are common: all air
    // (everything above the surface) and all solid with solid neighbours (most
    // of the underground). Both used to pay the full pad fill plus a 4096-block
    // scan to find nothing.
    if section.is_empty() {
        return 0;
    }
    if section.is_full() && neighbors.layers_all_solid() {
        return 0;
    }
    fill_padded(&mut scratch.pad, section, neighbors);
    mesh_padded(
        &scratch.pad,
        origin,
        &mut scratch.vertices,
        &mut scratch.indices,
    )
}

/// Axis-aligned bounding box in world space.
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Aabb {
    pub min: [f32; 3],
    pub max: [f32; 3],
}

impl Aabb {
    pub fn section(origin: [i32; 3]) -> Self {
        let o = [origin[0] as f32, origin[1] as f32, origin[2] as f32];
        Self {
            min: o,
            max: [o[0] + 16.0, o[1] + 16.0, o[2] + 16.0],
        }
    }
}

/// Half-space `dot(normal, x) + d >= 0` is inside.
#[derive(Copy, Clone, Debug)]
pub struct Plane {
    pub normal: [f32; 3],
    pub d: f32,
}

impl Plane {
    pub fn signed_distance(&self, point: [f32; 3]) -> f32 {
        self.normal[0] * point[0] + self.normal[1] * point[1] + self.normal[2] * point[2] + self.d
    }
}

/// Six-plane view frustum used for section culling.
#[derive(Copy, Clone, Debug)]
pub struct Frustum {
    pub planes: [Plane; 6],
}

impl Frustum {
    pub fn new(planes: [Plane; 6]) -> Self {
        Self { planes }
    }

    /// True if any part of `aabb` may be visible.
    pub fn intersects(&self, aabb: &Aabb) -> bool {
        for plane in &self.planes {
            // Positive vertex: the corner farthest along the plane normal.
            let px = if plane.normal[0] >= 0.0 { aabb.max[0] } else { aabb.min[0] };
            let py = if plane.normal[1] >= 0.0 { aabb.max[1] } else { aabb.min[1] };
            let pz = if plane.normal[2] >= 0.0 { aabb.max[2] } else { aabb.min[2] };
            if plane.signed_distance([px, py, pz]) < 0.0 {
                return false;
            }
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_indices_round_trips_in_documented_order() {
        let mut flat = vec![0u16; ChunkSection::VOLUME];
        // (y << 8) | (z << 4) | x
        flat[(2 << 8) | (3 << 4) | 5] = 7;
        let section = ChunkSection::from_indices(&flat).expect("exact volume");
        assert_eq!(section.get(5, 2, 3), 7);
        assert_eq!(section.indices(), &flat[..]);
        assert!(ChunkSection::from_indices(&flat[..100]).is_none());
        assert!(ChunkSection::from_indices(&[]).is_none());
        let mut sparse = ChunkSection::new();
        sparse.set(1, 1, 1, 4);
        assert_eq!(sparse.indices()[(1 << 8) | (1 << 4) | 1], 4);
    }

    /// The whole point of the `Arc`: handing a section to a second owner must
    /// not copy 8 KiB. `indices().as_ptr()` is the buffer identity, so equal
    /// pointers prove one allocation is shared.
    #[test]
    fn clone_shares_the_block_buffer_until_written() {
        let mut original = ChunkSection::new();
        original.set(1, 2, 3, 7);

        let shared = original.clone();
        assert_eq!(
            original.indices().as_ptr(),
            shared.indices().as_ptr(),
            "a clone must be a refcount bump, not a copy"
        );
        assert_eq!(shared.get(1, 2, 3), 7);

        // Copy-on-write: writing through one handle must be invisible to the
        // other, exactly like the `Vec` clone this replaced.
        let mut written = original.clone();
        written.set(1, 2, 3, 9);
        assert_eq!(original.get(1, 2, 3), 7, "the writer must not mutate its peers");
        assert_eq!(written.get(1, 2, 3), 9);
        assert_ne!(
            original.indices().as_ptr(),
            written.indices().as_ptr(),
            "a write to a shared section must split the buffer"
        );
        // Splitting must not corrupt the rest of the array.
        assert_eq!(written.indices()[ChunkSection::index(1, 2, 3)], 9);
        assert_eq!(original.indices()[ChunkSection::index(1, 2, 3)], 7);
    }

    fn solid_cube() -> ChunkSection {
        let mut s = ChunkSection::new();
        s.set(3, 3, 3, 1);
        s
    }

    fn full_section() -> ChunkSection {
        let mut s = ChunkSection::new();
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    s.set(x, y, z, 1);
                }
            }
        }
        s
    }

    /// The non-air count is what makes the empty/full short-circuits O(1), so it
    /// has to stay exact through rewrites and through a copy-on-write split.
    #[test]
    fn the_non_air_count_tracks_every_write() {
        let mut s = ChunkSection::new();
        assert!(s.is_empty());
        assert_eq!(s.non_air(), 0);

        s.set(0, 0, 0, 3);
        assert!(!s.is_empty());
        assert_eq!(s.non_air(), 1);

        s.set(0, 0, 0, 7); // solid -> solid: count unchanged
        assert_eq!(s.non_air(), 1);

        s.set(0, 0, 0, 0); // solid -> air
        assert!(s.is_empty());
        assert_eq!(s.non_air(), 0);

        let mut full = full_section();
        assert!(full.is_full());
        let peer = full.clone();
        full.set(1, 1, 1, 0);
        assert!(!full.is_full());
        assert_eq!(full.non_air(), ChunkSection::VOLUME as u32 - 1);
        assert!(peer.is_full(), "the clone must not observe the write");
        assert_eq!(peer.non_air(), ChunkSection::VOLUME as u32);
    }

    /// Both short-circuits must produce exactly what the full pass produces:
    /// nothing. The `padded_mesher_reproduces_the_reference_exactly` test covers
    /// the general case against the old per-face implementation; this pins the
    /// two paths it would otherwise only reach through 640 random densities.
    #[test]
    fn air_and_enclosed_full_sections_short_circuit_to_nothing() {
        let mut scratch = MeshScratch::new();

        let air = ChunkSection::new();
        assert_eq!(build_section_mesh_into(&air, [0, 0, 0], &mut scratch), 0);
        assert!(scratch.vertices.is_empty(), "an air section draws nothing");
        assert!(scratch.indices.is_empty());

        let full = full_section();
        let solid = full_section();
        let enclosed = Neighbors {
            pos_x: Some(&solid),
            neg_x: Some(&solid),
            pos_y: Some(&solid),
            neg_y: Some(&solid),
            pos_z: Some(&solid),
            neg_z: Some(&solid),
        };
        assert_eq!(
            build_section_mesh_with_neighbors(&full, &enclosed, [0, 0, 0], &mut scratch),
            0,
            "a full section with six solid neighbours has no visible face"
        );
        assert!(scratch.vertices.is_empty());

        // The same section with no neighbours must still emit all six sides, so
        // the short-circuit cannot be swallowing faces.
        assert_eq!(
            build_section_mesh_into(&full, [0, 0, 0], &mut scratch),
            6 * 256,
            "six 16x16 faces"
        );

        // One missing neighbour (air) re-opens exactly one side.
        let one_open = Neighbors {
            pos_x: Some(&solid),
            neg_x: Some(&solid),
            pos_y: Some(&solid),
            neg_y: Some(&solid),
            pos_z: Some(&solid),
            neg_z: None,
        };
        assert_eq!(
            build_section_mesh_with_neighbors(&full, &one_open, [0, 0, 0], &mut scratch),
            256,
            "only the -Z face stays visible"
        );
    }

    #[test]
    fn single_block_emits_six_quads() {
        let s = solid_cube();
        let mesh = build_section_mesh(&s, [0, 0, 0]);
        assert_eq!(mesh.quad_count, 6);
        assert_eq!(mesh.vertices.len(), 24);
        assert_eq!(mesh.indices.len(), 36);
        assert_eq!(mesh.vertex_bytes(), 24 * Vertex::STRIDE);
    }

    #[test]
    fn adjacent_blocks_cull_the_shared_face() {
        let mut s = ChunkSection::new();
        s.set(0, 0, 0, 1);
        s.set(1, 0, 0, 1);
        let mesh = build_section_mesh(&s, [0, 0, 0]);
        // 6 + 6 faces minus the two internal ones.
        assert_eq!(mesh.quad_count, 10);
        assert_eq!(mesh.vertices.len(), 40);
    }

    #[test]
    fn empty_section_has_no_geometry() {
        let s = ChunkSection::new();
        let mesh = build_section_mesh(&s, [0, 0, 0]);
        assert!(mesh.is_empty());
        assert_eq!(mesh.quad_count, 0);
        assert_eq!(mesh.indices.len(), 0);
    }

    #[test]
    fn vertex_encoding_is_32_bytes_little_endian() {
        let v = Vertex {
            position: [1.0, 2.0, 3.0],
            normal: [0.0, 1.0, 0.0],
            uv: [0.5, 0.25],
        };
        let mut buf = [0u8; Vertex::STRIDE];
        v.write_le(&mut buf);
        assert_eq!(buf.len(), 32);
        let x = f32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
        let vv = f32::from_le_bytes([buf[28], buf[29], buf[30], buf[31]]);
        assert_eq!(x, 1.0);
        assert_eq!(vv, 0.25);
    }

    #[test]
    fn neighbor_culling_removes_boundary_faces() {
        let mut a = ChunkSection::new();
        a.set(15, 0, 0, 1);
        let mut b = ChunkSection::new();
        b.set(0, 0, 0, 1);

        let without = build_section_mesh(&a, [0, 0, 0]);
        assert_eq!(without.quad_count, 6);

        let neighbors = Neighbors {
            pos_x: Some(&b),
            neg_x: None,
            pos_y: None,
            neg_y: None,
            pos_z: None,
            neg_z: None,
        };
        let mut scratch = MeshScratch::new();
        let quads = build_section_mesh_with_neighbors(&a, &neighbors, [0, 0, 0], &mut scratch);
        assert_eq!(quads, 5, "the +X boundary face is culled");
    }

    #[test]
    fn frustum_culls_boxes_behind_the_plane() {
        // Camera looking down +Z from the origin; anything at z < 0 is behind.
        let frustum = Frustum::new([
            Plane { normal: [0.0, 0.0, 1.0], d: 0.0 }, // z >= 0
            Plane { normal: [0.0, 0.0, -1.0], d: 1000.0 },
            Plane { normal: [1.0, 0.0, 0.0], d: 1000.0 },
            Plane { normal: [-1.0, 0.0, 0.0], d: 1000.0 },
            Plane { normal: [0.0, 1.0, 0.0], d: 1000.0 },
            Plane { normal: [0.0, -1.0, 0.0], d: 1000.0 },
        ]);
        let in_front = Aabb::section([0, 0, 16]);
        let behind = Aabb::section([0, 0, -32]);
        assert!(frustum.intersects(&in_front));
        assert!(!frustum.intersects(&behind));
    }

    // --- Padded mesher: equivalence against the pre-optimisation code -------

    /// The per-face implementation, kept verbatim as the reference the padded
    /// mesher must reproduce exactly. Never used in production.
    fn reference_neighbor_solid(
        neighbors: &Neighbors<'_>,
        section: &ChunkSection,
        nx: i32,
        ny: i32,
        nz: i32,
    ) -> bool {
        let inside = |v: i32| (0..16).contains(&v);
        if inside(nx) && inside(ny) && inside(nz) {
            return section.is_solid(nx as usize, ny as usize, nz as usize);
        }
        let (in_x, in_y, in_z) = (inside(nx), inside(ny), inside(nz));
        if !in_x && in_y && in_z {
            return match nx {
                16 => neighbors
                    .pos_x
                    .is_some_and(|s| s.is_solid(0, ny as usize, nz as usize)),
                -1 => neighbors
                    .neg_x
                    .is_some_and(|s| s.is_solid(15, ny as usize, nz as usize)),
                _ => false,
            };
        }
        if in_x && !in_y && in_z {
            return match ny {
                16 => neighbors
                    .pos_y
                    .is_some_and(|s| s.is_solid(nx as usize, 0, nz as usize)),
                -1 => neighbors
                    .neg_y
                    .is_some_and(|s| s.is_solid(nx as usize, 15, nz as usize)),
                _ => false,
            };
        }
        if in_x && in_y && !in_z {
            return match nz {
                16 => neighbors
                    .pos_z
                    .is_some_and(|s| s.is_solid(nx as usize, ny as usize, 0)),
                -1 => neighbors
                    .neg_z
                    .is_some_and(|s| s.is_solid(nx as usize, ny as usize, 15)),
                _ => false,
            };
        }
        false
    }

    fn reference_mesh(
        section: &ChunkSection,
        neighbors: &Neighbors<'_>,
        origin: [i32; 3],
    ) -> (Vec<Vertex>, Vec<u32>) {
        let mut vertices = Vec::new();
        let mut indices = Vec::new();
        for y in 0..16usize {
            for z in 0..16usize {
                for x in 0..16usize {
                    if !section.is_solid(x, y, z) {
                        continue;
                    }
                    let block = section.get(x, y, z);
                    for (face_index, face) in FACES.iter().enumerate() {
                        let nx = x as i32 + face.normal[0] as i32;
                        let ny = y as i32 + face.normal[1] as i32;
                        let nz = z as i32 + face.normal[2] as i32;
                        if reference_neighbor_solid(neighbors, section, nx, ny, nz) {
                            continue;
                        }
                        let base = vertices.len() as u32;
                        let tile = atlas::tile_for_face(block, face_index);
                        for (corner, uv) in face.corners.iter().zip(UVS.iter()) {
                            vertices.push(Vertex {
                                position: [
                                    origin[0] as f32 + x as f32 + corner[0],
                                    origin[1] as f32 + y as f32 + corner[1],
                                    origin[2] as f32 + z as f32 + corner[2],
                                ],
                                normal: [
                                    face.normal[0] as f32,
                                    face.normal[1] as f32,
                                    face.normal[2] as f32,
                                ],
                                uv: atlas::tile_uv(tile, *uv),
                            });
                        }
                        indices.extend_from_slice(&[
                            base,
                            base + 1,
                            base + 2,
                            base,
                            base + 2,
                            base + 3,
                        ]);
                    }
                }
            }
        }
        (vertices, indices)
    }

    fn vertex_bytes(vertices: &[Vertex]) -> Vec<u8> {
        let mut out = vec![0u8; vertices.len() * Vertex::STRIDE];
        for (i, v) in vertices.iter().enumerate() {
            v.write_le(&mut out[i * Vertex::STRIDE..(i + 1) * Vertex::STRIDE]);
        }
        out
    }

    /// Deterministic pseudo-random section with the given density: one block per
    /// `modulus` cells is solid (`modulus == 0` means empty).
    fn random_section(state: &mut u64, modulus: u64) -> ChunkSection {
        let mut section = ChunkSection::new();
        for i in 0..ChunkSection::VOLUME {
            *state ^= *state << 13;
            *state ^= *state >> 7;
            *state ^= *state << 17;
            let r = *state;
            if modulus != 0 && r.is_multiple_of(modulus) {
                let (x, y, z) = (i & 15, (i >> 4) & 15, (i >> 8) & 15);
                section.set(x, y, z, 1 + (r % 5) as u16);
            }
        }
        section
    }

    #[test]
    fn padded_mesher_reproduces_the_reference_exactly() {
        let mut state = 0x9E37_79B9_7F4A_7C15u64;
        let dense = random_section(&mut state, 2);
        let sparse = random_section(&mut state, 29);
        let origin = [8, -16, 24];
        let mut cases = 0;
        // Empty, sparse, mixed, dense, fully solid, with every combination of
        // loaded/unloaded neighbours (and two different neighbour fillings).
        for modulus in [0u64, 101, 13, 3, 1] {
            let section = random_section(&mut state, modulus);
            for mask in 0..128u32 {
                let fill = if mask & 64 != 0 { &dense } else { &sparse };
                let pick = |bit: u32| {
                    if mask & (1 << bit) != 0 {
                        Some(fill)
                    } else {
                        None
                    }
                };
                let neighbors = Neighbors {
                    pos_x: pick(0),
                    neg_x: pick(1),
                    pos_y: pick(2),
                    neg_y: pick(3),
                    pos_z: pick(4),
                    neg_z: pick(5),
                };
                let (ref_v, ref_i) = reference_mesh(&section, &neighbors, origin);
                let mut scratch = MeshScratch::new();
                let quads = build_section_mesh_with_neighbors(
                    &section,
                    &neighbors,
                    origin,
                    &mut scratch,
                );
                assert_eq!(
                    vertex_bytes(&scratch.vertices),
                    vertex_bytes(&ref_v),
                    "vertex bytes differ (modulus={modulus}, mask={mask})"
                );
                assert_eq!(
                    scratch.indices, ref_i,
                    "indices differ (modulus={modulus}, mask={mask})"
                );
                assert_eq!(quads as usize * 4, scratch.vertices.len());
                assert_eq!(quads as usize * 6, scratch.indices.len());
                cases += 1;
            }
        }
        assert_eq!(cases, 5 * 128, "every density x neighbour combination ran");
    }

    #[test]
    fn reused_scratch_never_leaks_a_previous_section() {
        // The pad is reused across sections, so a dense section followed by an
        // empty one must still produce an empty mesh (stale solid cells would
        // otherwise show up as phantom faces).
        let mut scratch = MeshScratch::new();
        let dense = random_section(&mut 0xDEAD_BEEF_CAFE_F00Du64, 1);
        let quads = build_section_mesh_with_neighbors(
            &dense,
            &Neighbors::none(),
            [0, 0, 0],
            &mut scratch,
        );
        assert!(quads > 0);
        let empty = ChunkSection::new();
        let quads = build_section_mesh_with_neighbors(
            &empty,
            &Neighbors::none(),
            [0, 0, 0],
            &mut scratch,
        );
        assert_eq!(quads, 0);
        assert!(scratch.vertices.is_empty() && scratch.indices.is_empty());
    }

    /// Throughput report, not a CI gate: `cargo test -- --ignored --nocapture
    /// mesh_throughput_report`. Both implementations run on the same input so
    /// the ratio is meaningful on the host that runs it.
    #[test]
    #[ignore = "throughput report; run with --ignored --nocapture"]
    fn mesh_throughput_report() {
        use std::time::Instant;
        let mut state = 0x1234_5678_9ABC_DEF0u64;
        // A realistic column section: solid bottom half, sparse above.
        let mut section = ChunkSection::new();
        for y in 0..8 {
            for z in 0..16 {
                for x in 0..16 {
                    section.set(x, y, z, 1);
                }
            }
        }
        for y in 8..16 {
            for z in 0..16 {
                for x in 0..16 {
                    state ^= state << 13;
                    state ^= state >> 7;
                    state ^= state << 17;
                    if state.is_multiple_of(23) {
                        section.set(x, y, z, 2);
                    }
                }
            }
        }
        let neighbors = Neighbors::none();
        let iterations = 500;
        let time = |label: &str, f: &mut dyn FnMut() -> usize| {
            for _ in 0..50 {
                f();
            }
            let start = Instant::now();
            for _ in 0..iterations {
                f();
            }
            let ns = start.elapsed().as_nanos() as f64 / iterations as f64;
            println!("{label:<10} {ns:8.1} ns/section");
            ns
        };
        let mut scratch = MeshScratch::new();
        let padded = time(
            "padded",
            &mut || {
                build_section_mesh_with_neighbors(&section, &neighbors, [0, 0, 0], &mut scratch)
                    as usize
            },
        );
        let reference = time(
            "reference",
            &mut || reference_mesh(&section, &neighbors, [0, 0, 0]).0.len(),
        );
        println!(
            "padded is {:.2}x the reference throughput",
            reference / padded
        );
    }
}
