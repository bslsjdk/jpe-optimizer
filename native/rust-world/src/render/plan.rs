//! Camera-driven draw list: registered sections plus a view-projection matrix
//! become an ordered batch list.
//!
//! This is the World Engine's visibility job — the backends in [`super::vulkan`],
//! [`super::gl`] and [`super::gles`] only ever receive the batches that survive
//! here. Nothing in this module touches a GPU, so the whole culling and ordering
//! path is unit-testable without a driver.

use super::{DrawBatch, Mat4, MeshHandle, frustum_from_view_proj};
use crate::mesh::Aabb;

/// One resident section mesh the renderer can draw.
#[derive(Copy, Clone, Debug)]
pub struct SectionRecord {
    /// Engine-side identity (packed chunk position, or any key the caller uses).
    pub key: u64,
    /// Section origin in block coordinates; the box is the 16x16x16 it anchors.
    pub origin: [i32; 3],
    /// Mesh handle as returned by [`super::Renderer::create_mesh`].
    pub mesh: MeshHandle,
    /// Index count to draw; zero means "nothing to draw" and is culled.
    pub index_count: u32,
}

/// What a planning pass did. Reported rather than implied, so the Java layer can
/// distinguish "drew everything" from "culled almost everything".
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct PlanStats {
    pub considered: u32,
    pub visible: u32,
    pub culled: u32,
    pub visible_quads: u64,
    pub culled_quads: u64,
}

/// Quad count of an indexed section mesh: four indices per triangle, six per
/// quad, and the mesher only ever emits quads.
pub fn quads_of(index_count: u32) -> u64 {
    (index_count / 6) as u64
}

/// Cull `records` against the camera and return the surviving batches, sorted
/// front to back by section-centre distance.
///
/// Front-to-back is deliberate: the chunk pipeline is opaque geometry with depth
/// writes on, so drawing near sections first lets the depth buffer reject later
/// ones. Transparent passes would need the opposite order and are not part of
/// this contract yet.
pub fn plan_draws(
    records: &[SectionRecord],
    view_proj: &Mat4,
    eye: [f32; 3],
) -> (Vec<DrawBatch>, PlanStats) {
    let frustum = frustum_from_view_proj(view_proj);
    let mut stats = PlanStats {
        considered: records.len() as u32,
        ..PlanStats::default()
    };
    let mut batches = Vec::new();

    KEPT.with(|cell| {
        // Reused across frames: the planner runs once per frame and the visible
        // set is the same order of magnitude every time, so this stops being an
        // allocation in the steady state.
        let mut kept = cell.borrow_mut();
        kept.clear();

        for (index, record) in records.iter().enumerate() {
            let quads = quads_of(record.index_count);
            let inside = record.index_count > 0
                && frustum.intersects(&Aabb::section(record.origin));
            if inside {
                stats.visible += 1;
                stats.visible_quads += quads;
                let centre = section_centre(record.origin);
                kept.push((
                    distance_key(distance_sq(centre, eye)),
                    record.key,
                    index as u32,
                ));
            } else {
                stats.culled += 1;
                stats.culled_quads += quads;
            }
        }

        // Integer keys, so the ordering is a plain lexicographic compare on a
        // 24-byte tuple: no f32 `partial_cmp`, no comparison closure, and an
        // unstable sort that never allocates. Distance then key is a total order
        // (keys are unique), so the result does not depend on the registry's
        // iteration order. Front-to-back order is unchanged.
        kept.sort_unstable();

        batches.reserve(kept.len());
        for &(_, _, index) in kept.iter() {
            let record = &records[index as usize];
            batches.push(DrawBatch {
                mesh: record.mesh,
                index_count: record.index_count,
                instance_count: 1,
            });
        }
    });

    (batches, stats)
}

thread_local! {
    /// Visible-set scratch for [`plan_draws`], reused per frame and per thread.
    static KEPT: std::cell::RefCell<Vec<KeptEntry>> =
        const { std::cell::RefCell::new(Vec::new()) };
}

/// One visible section, as the planner sorts it: (distance key, section key,
/// index into the caller's record slice).
type KeptEntry = (u32, u64, u32);

/// Monotonic f32 -> u32 map so integer comparison matches numeric order for the
/// non-negative, finite squared distances the planner produces. NaN sorts last.
#[inline]
fn distance_key(distance_sq: f32) -> u32 {
    let bits = distance_sq.to_bits();
    if bits & 0x8000_0000 != 0 {
        !bits
    } else {
        bits ^ 0x8000_0000
    }
}

fn section_centre(origin: [i32; 3]) -> [f32; 3] {
    [
        origin[0] as f32 + 8.0,
        origin[1] as f32 + 8.0,
        origin[2] as f32 + 8.0,
    ]
}

fn distance_sq(a: [f32; 3], b: [f32; 3]) -> f32 {
    let (x, y, z) = (a[0] - b[0], a[1] - b[1], a[2] - b[2]);
    x * x + y * y + z * z
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::render::ortho_look_at;

    fn record(key: u64, origin: [i32; 3], index_count: u32) -> SectionRecord {
        SectionRecord { key, origin, mesh: MeshHandle(key + 100), index_count }
    }

    /// Camera at (8, 8, 64) looking towards -Z, wide enough to cover the section
    /// column at x=8, y=8 out to z=0.
    fn camera() -> (Mat4, [f32; 3]) {
        let eye = [8.0f32, 8.0, 64.0];
        let view_proj = ortho_look_at(eye, [8.0, 8.0, 0.0], [0.0, 1.0, 0.0], 24.0, 24.0, 0.5, 100.0)
            .expect("non-degenerate camera");
        (view_proj, eye)
    }

    #[test]
    fn empty_input_produces_empty_plan() {
        let (view_proj, eye) = camera();
        let (batches, stats) = plan_draws(&[], &view_proj, eye);
        assert!(batches.is_empty());
        assert_eq!(stats, PlanStats::default());
    }

    #[test]
    fn zero_index_count_is_culled_not_drawn() {
        let (view_proj, eye) = camera();
        let records = [record(0, [0, 0, 0], 0)];
        let (batches, stats) = plan_draws(&records, &view_proj, eye);
        assert!(batches.is_empty(), "an empty mesh must never be queued");
        assert_eq!((stats.considered, stats.visible, stats.culled), (1, 0, 1));
    }

    #[test]
    fn frustum_rejects_behind_beyond_and_off_to_the_side() {
        let (view_proj, eye) = camera();
        let records = [
            record(0, [0, 0, 8], 36),   // in front, inside the extents
            record(1, [0, 0, 32], 36),  // in front
            record(2, [0, 0, 80], 36),  // behind the camera (eye z = 64)
            record(3, [0, 0, -600], 36), // far beyond the far plane
            record(4, [200, 0, 8], 36), // way off to +X
            record(5, [0, 200, 8], 36), // way off to +Y
            record(6, [0, -200, 8], 36), // way off to -Y
        ];
        let (batches, stats) = plan_draws(&records, &view_proj, eye);
        assert_eq!(
            batches.len(),
            2,
            "only the two sections in front should survive, got {batches:?}"
        );
        assert_eq!(stats.considered, 7);
        assert_eq!(stats.visible, 2);
        assert_eq!(stats.culled, 5);
        // Handles carry the record key, so the surviving set is identifiable.
        let kept: Vec<u64> = batches.iter().map(|b| b.mesh.0 - 100).collect();
        // z=32 is nearer to the eye at z=64 than z=8, so key 1 comes first.
        assert_eq!(kept, vec![1, 0], "front-to-back order of the visible set");
        assert_eq!((stats.visible_quads, stats.culled_quads), (12, 30));
    }

    #[test]
    fn draw_list_is_ordered_front_to_back() {
        let (view_proj, eye) = camera();
        // Spread along the view axis; the plan must return them near-first even
        // though the input is in a different (reverse) order.
        let depths: [i32; 6] = [0, 16, 32, 48, 16, 32];
        let records: Vec<SectionRecord> = depths
            .iter()
            .enumerate()
            .map(|(i, z)| record(i as u64, [0, 0, *z], 36))
            .collect();
        let (batches, stats) = plan_draws(&records, &view_proj, eye);
        assert_eq!(stats.visible, 6, "all sections are inside the frustum");
        let order: Vec<u64> = batches.iter().map(|b| b.mesh.0 - 100).collect();
        // Eye is at z=64, so nearest first: z=48 (key 3), then the pair at z=32
        // (keys 2, 5), then the pair at z=16 (keys 1, 4), then z=0 (key 0).
        // Ties break on key, which makes the order deterministic.
        assert_eq!(order, vec![3, 2, 5, 1, 4, 0], "unexpected draw order: {order:?}");
    }

    #[test]
    fn stats_account_for_every_record() {
        let (view_proj, eye) = camera();
        let records: Vec<SectionRecord> = (0..64)
            .map(|i| {
                let i = i as i32;
                record(i as u64, [i % 8 * 16 - 64, i / 8 * 16 - 64, i % 5 * 24 - 48], 360)
            })
            .collect();
        let (batches, stats) = plan_draws(&records, &view_proj, eye);
        assert_eq!(stats.considered, 64);
        assert_eq!(stats.considered, stats.visible + stats.culled);
        assert_eq!(batches.len() as u32, stats.visible);
        assert_eq!(
            stats.visible_quads + stats.culled_quads,
            64 * quads_of(360),
            "every quad must be accounted for"
        );
    }

    /// Throughput report, not a CI gate:
    /// `cargo test --release -- --ignored --nocapture plan_throughput_report`.
    ///
    /// A 17x17 column ring of 8-section chunks — 2312 resident sections, the
    /// shape a render distance of 8 produces — planned once per frame.
    #[test]
    #[ignore = "throughput report; run with --ignored --nocapture"]
    fn plan_throughput_report() {
        use std::time::Instant;
        let mut records = Vec::new();
        let mut key = 0u64;
        for cx in -8..=8 {
            for cz in -8..=8 {
                for cy in 0..8 {
                    records.push(record(
                        key,
                        [cx * 16, cy * 16 - 32, cz * 16],
                        6 * 24,
                    ));
                    key += 1;
                }
            }
        }
        let eye = [0.0f32, 8.0, 200.0];
        let view_proj = ortho_look_at(eye, [0.0, 8.0, 0.0], [0.0, 1.0, 0.0], 360.0, 360.0, 0.5, 500.0)
            .expect("camera");
        let iterations = 200;
        for _ in 0..20 {
            plan_draws(&records, &view_proj, eye);
        }
        let start = Instant::now();
        let mut visible = 0;
        for _ in 0..iterations {
            visible += plan_draws(&records, &view_proj, eye).0.len();
        }
        let ns = start.elapsed().as_nanos() as f64 / iterations as f64;
        println!(
            "sections={} visible~{} {ns:8.1} ns/frame",
            records.len(),
            visible / iterations
        );
    }
}
