//! Chunk section mesh construction and frustum culling.
//!
//! This is a deterministic CPU mesher over a 16x16x16 section. It is not the
//! final renderer: greedy meshing, ambient occlusion and lighting are future
//! work. It exists so the world engine has a real, testable geometric contract
//! (vertex format + index buffer) that all three GPU backends must consume
//! unchanged.

use crate::coords::SECTION_SIZE;

/// One 16x16x16 section. `0` is air; every non-zero value is opaque for now.
#[derive(Clone, Debug)]
pub struct ChunkSection {
    blocks: Vec<u16>,
}

impl ChunkSection {
    pub const VOLUME: usize = (SECTION_SIZE * SECTION_SIZE * SECTION_SIZE) as usize; // 4096

    pub fn new() -> Self {
        Self {
            blocks: vec![0; Self::VOLUME],
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

    #[inline]
    pub fn set(&mut self, x: usize, y: usize, z: usize, block: u16) {
        self.blocks[Self::index(x, y, z)] = block;
    }

    #[inline]
    pub fn is_solid(&self, x: usize, y: usize, z: usize) -> bool {
        self.get(x, y, z) != 0
    }

    pub fn is_empty(&self) -> bool {
        self.blocks.iter().all(|&b| b == 0)
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
            blocks: blocks.to_vec(),
        })
    }

    /// The block indices in the same order [`ChunkSection::from_indices`] expects.
    pub fn indices(&self) -> &[u16] {
        &self.blocks
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
#[derive(Default, Debug)]
pub struct MeshScratch {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
}

impl MeshScratch {
    pub fn new() -> Self {
        Self::default()
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
    scratch.clear();
    let mut quads = 0u32;

    for y in 0..16usize {
        for z in 0..16usize {
            for x in 0..16usize {
                if !section.is_solid(x, y, z) {
                    continue;
                }
                for face in FACES.iter() {
                    let nx = x as i32 + face.normal[0] as i32;
                    let ny = y as i32 + face.normal[1] as i32;
                    let nz = z as i32 + face.normal[2] as i32;
                    let neighbor_solid = (0..16).contains(&nx)
                        && (0..16).contains(&ny)
                        && (0..16).contains(&nz)
                        && section.is_solid(nx as usize, ny as usize, nz as usize);
                    if neighbor_solid {
                        continue;
                    }
                    let base = scratch.vertices.len() as u32;
                    for (corner, uv) in face.corners.iter().zip(UVS.iter()) {
                        scratch.vertices.push(Vertex {
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
                            uv: *uv,
                        });
                    }
                    scratch
                        .indices
                        .extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
                    quads += 1;
                }
            }
        }
    }
    quads
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

/// Like [`build_section_mesh_into`] but culls boundary faces against loaded
/// neighbouring sections. Missing neighbours are treated as air.
pub fn build_section_mesh_with_neighbors(
    section: &ChunkSection,
    neighbors: &Neighbors<'_>,
    origin: [i32; 3],
    scratch: &mut MeshScratch,
) -> u32 {
    scratch.clear();
    let mut quads = 0u32;

    for y in 0..16usize {
        for z in 0..16usize {
            for x in 0..16usize {
                if !section.is_solid(x, y, z) {
                    continue;
                }
                for face in FACES.iter() {
                    let nx = x as i32 + face.normal[0] as i32;
                    let ny = y as i32 + face.normal[1] as i32;
                    let nz = z as i32 + face.normal[2] as i32;
                    let neighbor_solid = boundary_neighbor_solid(neighbors, section, nx, ny, nz);
                    if neighbor_solid {
                        continue;
                    }
                    let base = scratch.vertices.len() as u32;
                    for (corner, uv) in face.corners.iter().zip(UVS.iter()) {
                        scratch.vertices.push(Vertex {
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
                            uv: *uv,
                        });
                    }
                    scratch
                        .indices
                        .extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
                    quads += 1;
                }
            }
        }
    }
    quads
}

fn boundary_neighbor_solid(
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
    let in_x = inside(nx);
    let in_y = inside(ny);
    let in_z = inside(nz);
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

    fn solid_cube() -> ChunkSection {
        let mut s = ChunkSection::new();
        s.set(3, 3, 3, 1);
        s
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
}
