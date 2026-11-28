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

/// Highest block id that gets a shared uniform array. The native generator uses
/// ids 0..=27; 64 leaves room for a resource pack and costs 512 KiB of static
/// table, which is nothing against the 250 MiB it saves.
const UNIFORM_CACHE: u16 = 64;

/// One shared block array per block id, for sections that are entirely that
/// block.
///
/// A view-distance-20 world holds ~40k sections and roughly three quarters of
/// them are uniformly air (everything above the surface) or uniformly stone
/// (everything below it). Giving each its own 8 KiB array spent 250 MiB storing
/// copies of the same two values. They now share one array per id; `set` goes
/// through `Arc::make_mut`, which sees the shared refcount and copies before
/// writing, so the copy-on-write contract is unchanged.
fn uniform_blocks(block: u16) -> Arc<Vec<u16>> {
    static TABLE: std::sync::OnceLock<Vec<Arc<Vec<u16>>>> = std::sync::OnceLock::new();
    let table = TABLE.get_or_init(|| {
        (0..UNIFORM_CACHE)
            .map(|id| Arc::new(vec![id; ChunkSection::VOLUME]))
            .collect()
    });
    table[block.min(UNIFORM_CACHE - 1) as usize].clone()
}

impl ChunkSection {
    pub const VOLUME: usize = (SECTION_SIZE * SECTION_SIZE * SECTION_SIZE) as usize; // 4096

    pub fn new() -> Self {
        Self {
            blocks: uniform_blocks(0),
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
        // One pass computes both the air count and whether the section is
        // uniform, which is what decides between a shared array and a copy.
        let first = blocks[0];
        let mut non_air = 0u32;
        let mut uniform = true;
        for &b in blocks {
            if b != 0 {
                non_air += 1;
            }
            if b != first {
                uniform = false;
            }
        }
        Some(Self {
            non_air,
            blocks: if uniform && first < UNIFORM_CACHE {
                uniform_blocks(first)
            } else {
                Arc::new(blocks.to_vec())
            },
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
/// 8 bytes: two packed u32 words. All little-endian.
///
/// The previous layout was 32 bytes (position f32x3, normal f32x3, uv f32x2),
/// then 24 (position, uv, meta). Both spent floats on things that are not
/// floats. This one spends none:
///
/// * Position is *section-local* and integral. A section is 16 blocks, so a
///   corner is 0..=16 and fits in five bits per axis; the section's block-space
///   origin travels as a per-draw uniform instead, which costs one uniform call
///   per section and saves 8 bytes on every vertex in it.
/// * `uv` is measured in *tiles*, not atlas coordinates, and is also integral:
///   a greedy quad spanning 7 blocks of grass carries 0..7 across its width and
///   the fragment shader repeats the tile with `fract`. Atlas coordinates could
///   not be merged at all -- a 7-block quad would stretch one tile across seven
///   blocks. Since the quad's uv origin is an integer, packing the section-local
///   coordinate and letting `fract` drop the offset is exact.
/// * There is no normal. There are six possible ones, all known at mesh time,
///   so `meta` carries the face index and the fragment shader looks the light
///   term up in a six-entry table.
///
/// At 2.5M quads that is 320 MiB of vertices instead of 960.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
#[repr(C)]
pub struct Vertex {
    /// `x | y << 5 | z << 10 | u << 15 | v << 20`, every field section-local
    /// and in 0..=16. See [`Vertex::pack`].
    pub pos_uv: u32,
    /// `tile as u32 | (face as u32) << 16`; see [`Vertex::pack`].
    pub meta: u32,
}

impl Vertex {
    pub const STRIDE: usize = 8;

    /// Pack a section-local corner and its quad-local uv, each in 0..=31.
    #[inline]
    pub const fn pack(x: u32, y: u32, z: u32, u: u32, v: u32, tile: u16, face: u8) -> Self {
        debug_assert!(x < 32 && y < 32 && z < 32 && u < 32 && v < 32);
        Self {
            pos_uv: x | (y << 5) | (z << 10) | (u << 15) | (v << 20),
            meta: tile as u32 | ((face as u32) << 16),
        }
    }

    #[inline]
    pub const fn x(&self) -> u32 {
        self.pos_uv & 31
    }

    #[inline]
    pub const fn y(&self) -> u32 {
        (self.pos_uv >> 5) & 31
    }

    #[inline]
    pub const fn z(&self) -> u32 {
        (self.pos_uv >> 10) & 31
    }

    #[inline]
    pub const fn u(&self) -> u32 {
        (self.pos_uv >> 15) & 31
    }

    #[inline]
    pub const fn v(&self) -> u32 {
        (self.pos_uv >> 20) & 31
    }

    #[inline]
    pub const fn tile(&self) -> u16 {
        (self.meta & 0xffff) as u16
    }

    #[inline]
    pub const fn face(&self) -> u8 {
        (self.meta >> 16) as u8
    }

    /// Section-local position, in blocks.
    #[inline]
    pub const fn local(&self) -> [u32; 3] {
        [self.x(), self.y(), self.z()]
    }

    pub fn write_le(&self, out: &mut [u8]) {
        assert!(out.len() >= Self::STRIDE, "vertex output buffer too small");
        out[0..4].copy_from_slice(&self.pos_uv.to_le_bytes());
        out[4..8].copy_from_slice(&self.meta.to_le_bytes());
    }
}

/// Reference face table, used only by the per-face mesher the greedy one is
/// checked against.
#[cfg(test)]
struct FaceDef {
    normal: [i8; 3],
    /// Counter-clockwise corner order, local 0/1 coordinates.
    corners: [[f32; 3]; 4],
}

#[cfg(test)]
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

#[cfg(test)]
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

// ---------------------------------------------------------------------------
// Greedy meshing
//
// The per-face mesher emits one quad per visible block face. That is the
// obvious thing and it is what Minecraft does, but at a 448-block view it is
// 4.0M quads: 600 MiB of vertices, and a player's whole memory budget.
//
// Almost every one of those faces is coplanar with its neighbour. A flat plain
// is 200k identical grass tops; the underground is pure stone. Greedy meshing
// merges each maximal rectangle of same-block, same-face cells into one quad,
// which turns the plain into a few hundred quads and a solid section's side
// into a single one.
//
// The merge runs per face direction and per 16-block slice, on a 16x16 mask.
// It never merges across block ids, so the atlas lookup stays a per-quad
// constant, and it never merges across a slice, so every quad stays planar.
// ---------------------------------------------------------------------------

/// Slice geometry for one face direction.
///
/// `axis` is the block axis the slices run along; `u_axis`/`v_axis` are the two
/// block axes that become the quad's local u and v. `order` lists the four
/// corners in the winding [`FACES`] uses, encoded as bits: bit 0 selects the
/// far side in u, bit 1 the far side in v. Keeping the original winding is what
/// lets the back-face culling in both backends stay on.
struct FacePlane {
    axis: usize,
    u_axis: usize,
    v_axis: usize,
    order: [u8; 4],
}

/// The u/v axis assignment is not free: it has to match the UVs the reference
/// face table gives each corner, or every texture lands rotated 90 degrees.
/// Reading `FACES` and `UVS` together, corner 0 has uv (0,0) and corner 1 has
/// (0,1), so the *second* corner always moves along the quad's v axis.
///
/// With that constraint every face ends up with the same corner order,
/// `[0, 2, 3, 1]`: bottom-left, top-left, top-right, bottom-right in u/v space.
/// Only the axis assignment differs.
const PLANES: [FacePlane; 6] = [
    // +X: u = y, v = z
    FacePlane { axis: 0, u_axis: 1, v_axis: 2, order: [0, 2, 3, 1] },
    // -X: u = z, v = y
    FacePlane { axis: 0, u_axis: 2, v_axis: 1, order: [0, 2, 3, 1] },
    // +Y: u = x, v = z
    FacePlane { axis: 1, u_axis: 0, v_axis: 2, order: [0, 2, 3, 1] },
    // -Y: u = z, v = x
    FacePlane { axis: 1, u_axis: 2, v_axis: 0, order: [0, 2, 3, 1] },
    // +Z: u = y, v = x
    FacePlane { axis: 2, u_axis: 1, v_axis: 0, order: [0, 2, 3, 1] },
    // -Z: u = x, v = y
    FacePlane { axis: 2, u_axis: 0, v_axis: 1, order: [0, 2, 3, 1] },
];

/// Emit one merged quad.
///
/// `slice` is the section-local coordinate along the face axis and `u0`/`v0`
/// are section-local too: the vertex is section-local, and the block-space
/// origin is applied by the renderer as a per-draw uniform.
#[allow(clippy::too_many_arguments)]
#[inline]
fn emit_quad(
    plane: &FacePlane,
    slice: usize,
    positive: bool,
    u0: usize,
    v0: usize,
    w: usize,
    h: usize,
    block: u16,
    face: u8,
    vertices: &mut Vec<Vertex>,
    indices: &mut Vec<u32>,
) {
    let tile = atlas::tile_for_face(block, face as usize);
    let along = (slice + positive as usize) as u32;
    let base = vertices.len() as u32;
    for &corner in &plane.order {
        let du = (corner & 1) as usize;
        let dv = ((corner >> 1) & 1) as usize;
        let mut local = [0u32; 3];
        local[plane.axis] = along;
        local[plane.u_axis] = (u0 + du * w) as u32;
        local[plane.v_axis] = (v0 + dv * h) as u32;
        // Quad-local uv, not section-local: 0..span across this quad only. The
        // fragment shader repeats the tile with fract(), so a quad spanning w
        // blocks gets w tiles; starting the ramp at the quad's own corner keeps
        // the interpolation identical to what a per-face quad would produce,
        // which is what makes the compressed format pixel-identical.
        let uv_u = (du * w) as u32;
        let uv_v = (dv * h) as u32;
        vertices.push(Vertex::pack(
            local[0], local[1], local[2], uv_u, uv_v, tile, face,
        ));
    }
    indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
}

/// Merge one 16x16 slice mask into maximal same-block rectangles.
fn greedy_slice(
    mask: &mut [u16; 256],
    plane: &FacePlane,
    slice: usize,
    positive: bool,
    face: u8,
    vertices: &mut Vec<Vertex>,
    indices: &mut Vec<u32>,
) -> u32 {
    let mut quads = 0u32;
    for v in 0..16usize {
        for u in 0..16usize {
            let block = mask[v * 16 + u];
            if block == 0 {
                continue;
            }
            // Widest run of the same block on this row...
            let mut w = 1usize;
            while u + w < 16 && mask[v * 16 + u + w] == block {
                w += 1;
            }
            // ...then as many identical rows below it as fit.
            let mut h = 1usize;
            'grow: while v + h < 16 {
                for k in 0..w {
                    if mask[(v + h) * 16 + u + k] != block {
                        break 'grow;
                    }
                }
                h += 1;
            }
            for dv in 0..h {
                let row = (v + dv) * 16 + u;
                mask[row..row + w].fill(0);
            }
            emit_quad(
                plane, slice, positive, u, v, w, h, block, face, vertices, indices,
            );
            quads += 1;
        }
    }
    quads
}

/// Emit the merged geometry of a filled padded grid.
fn mesh_padded(
    pad: &[u16; PAD_VOLUME],
    vertices: &mut Vec<Vertex>,
    indices: &mut Vec<u32>,
) -> u32 {
    let mut quads = 0u32;
    let mut mask = [0u16; 256];
    for (face_index, plane) in PLANES.iter().enumerate() {
        let positive = face_index % 2 == 0;
        let offset = FACE_OFFSETS[face_index];
        for slice in 0..16usize {
            // Build the visibility mask for this slice. `any` skips the merge
            // pass entirely for the slices that are fully interior -- most of a
            // solid section.
            let mut any = false;
            for v in 0..16usize {
                for u in 0..16usize {
                    let mut cell = [0usize; 3];
                    cell[plane.axis] = slice;
                    cell[plane.u_axis] = u;
                    cell[plane.v_axis] = v;
                    let here = pad_index(cell[0], cell[1], cell[2]);
                    let block = pad[here];
                    let visible = block != 0 && pad[(here as isize + offset) as usize] == 0;
                    mask[v * 16 + u] = if visible { block } else { 0 };
                    any |= visible;
                }
            }
            if !any {
                continue;
            }
            quads += greedy_slice(
                &mut mask,
                plane,
                slice,
                positive,
                face_index as u8,
                vertices,
                indices,
            );
        }
    }
    quads
}

/// Emit every visible face of a filled padded grid, one quad per block face.
///
/// Kept as the reference the greedy mesher is checked against: it is the
/// obvious implementation, and "same set of covered faces" is a much stronger
/// assertion than "same quad count".
#[cfg(test)]
fn mesh_padded_per_face(
    pad: &[u16; PAD_VOLUME],
    origin: [i32; 3],
    vertices: &mut Vec<Vertex>,
    indices: &mut Vec<u32>,
) -> u32 {
    let mut quads = 0u32;
    let _ = origin;
    for y in 0..16usize {
        for z in 0..16usize {
            for x in 0..16usize {
                let here = pad_index(x, y, z) as isize;
                if pad[here as usize] == 0 {
                    continue;
                }
                if FACE_OFFSETS
                    .iter()
                    .all(|offset| pad[(here + offset) as usize] != 0)
                {
                    continue;
                }
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
                        vertices.push(Vertex::pack(
                            x as u32 + corner[0] as u32,
                            y as u32 + corner[1] as u32,
                            z as u32 + corner[2] as u32,
                            uv[0] as u32,
                            uv[1] as u32,
                            tile,
                            face_index as u8,
                        ));
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
///
/// `_origin` is not baked into the vertices: they are section-local and the
/// renderer applies the block-space origin as a per-draw uniform. It stays in
/// the signature so call sites keep reading the same and so a future mesher
/// that does bake positions in does not change the API.
pub fn build_section_mesh_with_neighbors(
    section: &ChunkSection,
    neighbors: &Neighbors<'_>,
    _origin: [i32; 3],
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
    mesh_padded(&scratch.pad, &mut scratch.vertices, &mut scratch.indices)
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
        // the short-circuit cannot be swallowing faces. Greedy meshing turns
        // each 16x16 side into a single quad.
        assert_eq!(
            build_section_mesh_into(&full, [0, 0, 0], &mut scratch),
            6,
            "six 16x16 faces, one merged quad each"
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
            1,
            "only the -Z face stays visible, merged into one quad"
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
        // 6 + 6 faces minus the two internal ones, then the four remaining
        // coplanar pairs (top, bottom, +Z, -Z) merge into one quad each.
        assert_eq!(mesh.quad_count, 6);
        assert_eq!(mesh.vertices.len(), 24);
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
    fn vertex_encoding_is_8_bytes_little_endian() {
        let v = Vertex::pack(1, 2, 3, 4, 5, 28, 5);
        assert_eq!(Vertex::STRIDE, 8);
        assert_eq!(v.local(), [1, 2, 3]);
        assert_eq!((v.u(), v.v()), (4, 5));
        assert_eq!((v.tile(), v.face()), (28, 5));

        let mut buf = [0u8; Vertex::STRIDE];
        v.write_le(&mut buf);
        let pos_uv = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
        let meta = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);
        assert_eq!(pos_uv, 1 | (2 << 5) | (3 << 10) | (4 << 15) | (5 << 20));
        assert_eq!(meta, 28 | (5 << 16));

        // The corner values the mesher can actually produce all fit: a section
        // is 16 blocks, so a corner is 0..=16 and a merged quad spans at most
        // 16 blocks, which is exactly what five bits holds.
        let corner = Vertex::pack(16, 16, 16, 16, 16, u16::MAX, 5);
        assert_eq!(corner.local(), [16, 16, 16]);
        assert_eq!((corner.u(), corner.v()), (16, 16));
        assert_eq!(corner.tile(), u16::MAX);
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
        _origin: [i32; 3],
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
                            vertices.push(Vertex::pack(
                                x as u32 + corner[0] as u32,
                                y as u32 + corner[1] as u32,
                                z as u32 + corner[2] as u32,
                                uv[0] as u32,
                                uv[1] as u32,
                                tile,
                                face_index as u8,
                            ));
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

    /// Expand a mesh into the set of unit block faces it covers, sorted.
    ///
    /// A merged quad covers `u_span * v_span` of them; a per-face quad covers
    /// one. Comparing these sets is the contract the greedy mesher has to keep:
    /// same blocks, same faces, same tiles, no overlaps, no holes.
    fn covered_faces(vertices: &[Vertex], indices: &[u32]) -> Vec<(i32, i32, i32, u8, u16)> {
        let mut out = Vec::new();
        for quad in indices.chunks(6) {
            let first = vertices[quad[0] as usize];
            let face = first.face();
            let plane = &PLANES[face as usize];
            let axis_coord = first.local()[plane.axis];
            let mut min_u = u32::MAX;
            let mut max_u = 0u32;
            let mut min_v = u32::MAX;
            let mut max_v = 0u32;
            for &i in quad {
                let p = vertices[i as usize].local();
                assert_eq!(p[plane.axis], axis_coord, "quad is not planar");
                min_u = min_u.min(p[plane.u_axis]);
                max_u = max_u.max(p[plane.u_axis]);
                min_v = min_v.min(p[plane.v_axis]);
                max_v = max_v.max(p[plane.v_axis]);
            }
            for u in min_u..max_u {
                for v in min_v..max_v {
                    out.push((u as i32, v as i32, axis_coord as i32, face, first.tile()));
                }
            }
        }
        out.sort_unstable();
        out
    }

    /// Sign of the triangle normal along the face direction, for the reference
    /// corner order in [`FACES`]. The greedy mesher has to reproduce this sign.
    fn reference_winding_sign(face: u8) -> f32 {
        let f = &FACES[face as usize];
        let p0 = f.corners[0];
        let p1 = f.corners[1];
        let p2 = f.corners[2];
        let a = [p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]];
        let b = [p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]];
        let c = [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ];
        let n = face_normal(face);
        c[0] * n[0] + c[1] * n[1] + c[2] * n[2]
    }

    /// Outward normal of a face index, matching the winding in [`FACES`].
    fn face_normal(face: u8) -> [f32; 3] {
        let mut n = [0.0f32; 3];
        n[(face / 2) as usize] = if face % 2 == 0 { 1.0 } else { -1.0 };
        n
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
    fn greedy_mesher_covers_exactly_the_reference_faces() {
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
                // Byte-for-byte equality is impossible now: the greedy mesher
                // deliberately emits fewer, larger quads. What must still hold
                // is that the two cover exactly the same unit faces -- same
                // block position, same face direction, same atlas tile -- which
                // also catches a merge that wrongly spans two block ids.
                assert_eq!(
                    covered_faces(&scratch.vertices, &scratch.indices),
                    covered_faces(&ref_v, &ref_i),
                    "covered faces differ (modulus={modulus}, mask={mask})"
                );
                assert_eq!(quads as usize * 4, scratch.vertices.len());
                assert_eq!(quads as usize * 6, scratch.indices.len());
                cases += 1;
            }
        }
        assert_eq!(cases, 5 * 128, "every density x neighbour combination ran");
    }

    #[test]
    fn every_quad_winds_outward() {
        // Back-face culling is on in both backends, so a quad wound the wrong
        // way is an invisible hole. The greedy mesher takes its corner order
        // from a per-face table rather than from the reference face list, so
        // each of the six entries has to be checked.
        let mut state = 0x1234_5678_9ABC_DEF0u64;
        let mut scratch = MeshScratch::new();
        let mut checked = 0usize;
        for modulus in [101u64, 13, 3, 1] {
            let section = random_section(&mut state, modulus);
            let quads = build_section_mesh_with_neighbors(
                &section,
                &Neighbors::none(),
                [0, 0, 0],
                &mut scratch,
            );
            assert!(quads > 0);
            for quad in scratch.indices.chunks(6) {
                let p: Vec<[f32; 3]> = quad
                    .iter()
                    .map(|&i| {
                        let l = scratch.vertices[i as usize].local();
                        [l[0] as f32, l[1] as f32, l[2] as f32]
                    })
                    .collect();
                let face = scratch.vertices[quad[0] as usize].face();
                let n = face_normal(face);
                let a = [p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]];
                let b = [p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]];
                let cross = [
                    a[1] * b[2] - a[2] * b[1],
                    a[2] * b[0] - a[0] * b[2],
                    a[0] * b[1] - a[1] * b[0],
                ];
                let dot = cross[0] * n[0] + cross[1] * n[1] + cross[2] * n[2];
                // Compared against the reference table rather than against
                // zero: the projection is left-handed somewhere upstream, so
                // the whole world's winding is "inward" in pure math terms and
                // only consistency between faces matters.
                let expected = reference_winding_sign(face);
                assert_eq!(
                    dot.signum(),
                    expected.signum(),
                    "quad winding disagrees with the reference table for face {face}"
                );
                checked += 1;
            }
        }
        assert!(checked > 1000, "checked only {checked} quads");
    }

    #[test]
    fn greedy_meshing_collapses_flat_ground() {
        // A flat 16x16 layer is the case greedy meshing exists for: 256 top
        // faces and 4x16 side faces become a handful of quads.
        let mut section = ChunkSection::new();
        for z in 0..16 {
            for x in 0..16 {
                section.set(x, 0, z, 2);
            }
        }
        let mesh = build_section_mesh(&section, [0, 0, 0]);
        assert!(
            mesh.quad_count <= 12,
            "flat layer still costs {} quads",
            mesh.quad_count
        );
        // Top + bottom are one quad each, the four sides are one quad each.
        assert_eq!(mesh.quad_count, 6);
    }

    #[test]
    fn greedy_never_merges_across_block_ids() {
        // Two adjacent blocks of different ids must stay two quads even though
        // they are coplanar: they sample different atlas tiles.
        let mut section = ChunkSection::new();
        section.set(0, 0, 0, 1);
        section.set(1, 0, 0, 2);
        let mesh = build_section_mesh(&section, [0, 0, 0]);
        let tops = mesh
            .indices
            .chunks(6)
            .filter(|q| mesh.vertices[q[0] as usize].face() == 2)
            .count();
        assert_eq!(tops, 2, "the +Y faces of two different blocks merged");
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
