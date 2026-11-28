//! End-to-end proof that a world can be built with **no host-supplied blocks**.
//!
//! `native_world` proves the render stack works, but it feeds the pipeline with
//! `PushSource`: the blocks are generated first and pushed in, which is exactly
//! the round-trip this change exists to remove. This example feeds the pipeline
//! from `TerrainSource::Native`, where `section()` calls the C++ generator
//! directly, and then renders it. If it draws a non-empty frame, the whole
//! chain -- pipeline -> native source -> C++ generator -> mesher -> GPU -- works
//! without the host generating a single column.
//!
//! It deliberately does not use `PushSource` anywhere, and it does not fall back
//! when no render backend is available. A fallback would let a broken native
//! path still "pass", which is the failure mode this file exists to rule out.
//!
//! Declared with `required-features = ["cpp-bridge"]` in Cargo.toml, because
//! `terrain_path` does not exist without the feature; a default-feature build
//! must skip this example rather than fail on a missing module.
//!
//! Exit status is the contract: 0 only if a non-empty frame was drawn.

use jpe_world::coords::{ChunkPos, MIN_SECTION_Y};
use jpe_world::pipeline::{Camera, PipelineConfig, WorldPipeline};
use jpe_world::render::{create_best_headless, mat4_mul, Mat4, Renderer};
use jpe_world::terrain_gen::TerrainConfig;
use jpe_world::terrain_path::{TerrainPath, TerrainSource};

/// Perspective view-projection with a 0..1 depth range (Vulkan convention).
///
/// Local to this example, matching `native_world`'s helper: `Mat4` is a bare
/// `[f32; 16]` and the crate exposes only `mat4_mul`, so there is no shared
/// camera builder to call.
fn perspective_look_at(
    eye: [f32; 3],
    target: [f32; 3],
    up: [f32; 3],
    fov_y_degrees: f32,
    aspect: f32,
    near: f32,
    far: f32,
) -> Option<Mat4> {
    let sub = |a: [f32; 3], b: [f32; 3]| [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
    let dot = |a: [f32; 3], b: [f32; 3]| a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    let cross = |a: [f32; 3], b: [f32; 3]| {
        [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ]
    };
    let normalize = |v: [f32; 3]| {
        let len = dot(v, v).sqrt();
        if len <= f32::EPSILON {
            None
        } else {
            Some([v[0] / len, v[1] / len, v[2] / len])
        }
    };

    let f = normalize(sub(target, eye))?;
    let s = normalize(cross(f, up))?;
    let u = cross(s, f);
    if aspect <= 0.0 || far <= near || fov_y_degrees <= 0.0 || fov_y_degrees >= 180.0 {
        return None;
    }

    let view: Mat4 = [
        s[0], u[0], -f[0], 0.0, //
        s[1], u[1], -f[1], 0.0, //
        s[2], u[2], -f[2], 0.0, //
        -dot(s, eye), -dot(u, eye), dot(f, eye), 1.0,
    ];

    let f_scale = 1.0 / (fov_y_degrees.to_radians() * 0.5).tan();
    let proj: Mat4 = [
        f_scale / aspect, 0.0, 0.0, 0.0, //
        0.0, f_scale, 0.0, 0.0, //
        0.0, 0.0, far / (near - far), -1.0, //
        0.0, 0.0, far * near / (near - far), 0.0,
    ];
    Some(mat4_mul(&proj, &view))
}

fn main() {
    let config = TerrainConfig {
        seed: 424242,
        ..TerrainConfig::default()
    };
    println!(
        "generator: {} blocks/column, {} sections",
        config.blocks_per_chunk(),
        config.sections()
    );

    // The switch, not a constructor choice: this is what a launcher sets.
    let path = TerrainPath::Native;
    let source = match TerrainSource::for_path(path, config) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("FAIL: could not build the native terrain source: {e}");
            std::process::exit(1);
        }
    };
    println!("terrain path: {:?}", source.path());

    let mut pipeline_config = PipelineConfig::default();
    pipeline_config.extent = [128, 128];
    pipeline_config.terrain_budget = 8;
    pipeline_config.mesh_budget = 4;
    // A few sections of real ground, not the whole 384-block column: this is a
    // smoke test for the seam, not a render benchmark.
    pipeline_config.section_y = (MIN_SECTION_Y, MIN_SECTION_Y + 5);

    let mut pipeline = WorldPipeline::new(1, 8192, pipeline_config, source);

    let radius = 2;
    let queued = pipeline.load_area(ChunkPos::new(0, 0), radius);
    println!("queued {queued} columns within radius {radius}");
    if queued == 0 {
        eprintln!("FAIL: load_area queued nothing");
        std::process::exit(1);
    }

    // No fallback: without a backend the claim under test cannot be made.
    let mut renderer: Box<dyn Renderer> = match create_best_headless() {
        Ok(r) => r,
        Err(e) => {
            eprintln!("FAIL: no render backend ({e:?}) -- cannot prove the path renders");
            std::process::exit(1);
        }
    };
    println!("backend: {:?}", renderer.capabilities().backend);

    let eye = [40.0f32, 90.0, 40.0];
    let view_proj = match perspective_look_at(eye, [0.0, 40.0, 0.0], [0.0, 1.0, 0.0], 60.0, 1.0, 0.1, 2000.0) {
        Some(m) => m,
        None => {
            eprintln!("FAIL: could not build the camera");
            std::process::exit(1);
        }
    };
    let cam = Camera {
        view_proj,
        eye: [0.0, 96.0, 0.0],
    };

    let mut frames = 0u32;
    // Enough frames for the disc to finish generating and meshing at the
    // configured budgets. Bounded so a stall fails instead of hanging.
    for _ in 0..4000 {
        if let Err(e) = pipeline.frame(&mut *renderer, cam) {
            eprintln!("FAIL: frame failed: {e:?}");
            std::process::exit(1);
        }
        frames += 1;
        if pipeline.scheduler().queue_len() == 0 && pipeline.stats().pending_mesh == 0 {
            break;
        }
    }

    let stats = pipeline.stats();
    println!(
        "frames={frames} queue={} pending_mesh={}",
        pipeline.scheduler().queue_len(),
        stats.pending_mesh
    );

    let pixels = match renderer.read_pixels() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("FAIL: readback failed: {e:?}");
            std::process::exit(1);
        }
    };
    let lit = pixels.count_non_background([0, 0, 0, 255]);
    let total = (pixels.width as usize) * (pixels.height as usize);
    println!(
        "lit_pixels={lit}/{total} ({:.1}%) {}x{}",
        if total == 0 { 0.0 } else { lit as f64 / total as f64 * 100.0 },
        pixels.width,
        pixels.height
    );

    // The assertion that matters: the native path produced geometry the GPU
    // drew. An empty frame means the seam is not connected, no matter how many
    // unit tests pass.
    if lit == 0 {
        eprintln!("FAIL: frame is empty -- the native terrain path rendered nothing");
        std::process::exit(1);
    }

    println!("OK: native terrain generated and rendered without host-supplied blocks");
}