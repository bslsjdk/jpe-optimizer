// End-to-end native world: C++ generates the terrain, Rust meshes it, a native
// GPU backend draws it, and the frame is written to disk.
//
//   cargo run --release --features cpp-bridge --example native_world -- [seed] [radius] [out.ppm]
//
// No Java, no Minecraft, no resource pack: the only inputs are the seed and the
// chunk radius. The image is the proof that the whole chain runs.

use std::time::Instant;

use jpe_world::coords::ChunkPos;
use jpe_world::pipeline::{PipelineConfig, PushSource, WorldPipeline};
use jpe_world::render::{create_best_headless, mat4_mul, FramePixels, Mat4, Renderer};
use jpe_world::terrain_gen::{sections_from_chunk, TerrainConfig};

/// Perspective view-projection with a 0..1 depth range (Vulkan convention,
/// which is also what `frustum_from_view_proj` expects).
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

fn write_ppm(path: &str, px: &FramePixels) -> std::io::Result<()> {
    use std::io::Write;
    let mut out = Vec::with_capacity((px.width * px.height * 3 + 32) as usize);
    out.extend_from_slice(format!("P6\n{} {}\n255\n", px.width, px.height).as_bytes());
    for chunk in px.rgba8.chunks_exact(4) {
        out.extend_from_slice(&chunk[..3]);
    }
    std::fs::File::create(path)?.write_all(&out)
}

/// Generator selection: native C++ when the feature is on, deterministic
/// height field otherwise. Both expose the same three calls.
enum Source {
    #[cfg(feature = "cpp-bridge")]
    Native(jpe_world::terrain_gen::NativeGenerator),
    Flat(jpe_world::terrain_gen::FlatGenerator),
}

impl Source {
    fn blocks_per_chunk(&self) -> usize {
        match self {
            #[cfg(feature = "cpp-bridge")]
            Source::Native(g) => g.blocks_per_chunk(),
            Source::Flat(g) => g.blocks_per_chunk(),
        }
    }

    fn generate(&self, coords: &[(i32, i32)], out: &mut [u16], threads: u32) {
        match self {
            #[cfg(feature = "cpp-bridge")]
            Source::Native(g) => {
                g.generate_batch(coords, out, threads).expect("native generate");
            }
            Source::Flat(g) => {
                let per = g.blocks_per_chunk();
                for (i, (cx, cz)) in coords.iter().enumerate() {
                    g.generate_chunk(*cx, *cz, &mut out[i * per..(i + 1) * per]);
                }
            }
        }
    }

    fn column_info(&self, cx: i32, cz: i32) -> [jpe_world::terrain_gen::ColumnInfo; 256] {
        match self {
            #[cfg(feature = "cpp-bridge")]
            Source::Native(g) => g.column_info(cx, cz),
            Source::Flat(_) => [jpe_world::terrain_gen::ColumnInfo::default(); 256],
        }
    }

    fn heightmap(&self, cx: i32, cz: i32) -> [i32; 256] {
        match self {
            #[cfg(feature = "cpp-bridge")]
            Source::Native(g) => g.heightmap(cx, cz),
            Source::Flat(g) => g.heightmap(cx, cz),
        }
    }

    fn name(&self) -> &'static str {
        match self {
            #[cfg(feature = "cpp-bridge")]
            Source::Native(_) => "native cpp-world",
            Source::Flat(_) => "flat fallback",
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let seed: i64 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(20261128);
    let radius: i32 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(6);
    let out_path = args.get(3).cloned().unwrap_or_else(|| "world.ppm".to_string());

    let config = TerrainConfig {
        seed,
        threads: 8,
        ..TerrainConfig::default()
    };

    #[cfg(feature = "cpp-bridge")]
    let source = Source::Native(
        jpe_world::terrain_gen::NativeGenerator::new(config).expect("native generator"),
    );
    #[cfg(not(feature = "cpp-bridge"))]
    let source = Source::Flat(jpe_world::terrain_gen::FlatGenerator::new(config, 72, 12));

    println!(
        "generator: {} seed={} radius={} ({} chunks)",
        source.name(),
        seed,
        radius,
        (radius * 2 + 1).pow(2)
    );

    // --- Generate ---------------------------------------------------------
    let coords: Vec<(i32, i32)> = (-radius..=radius)
        .flat_map(|z| (-radius..=radius).map(move |x| (x, z)))
        .collect();

    let per_chunk = source.blocks_per_chunk();
    let batch = 64usize;
    let mut generated = 0usize;
    let mut gen_ms = 0.0f64;
    let mut buffers: Vec<Vec<u16>> = Vec::new();
    let t_gen = Instant::now();
    for window in coords.chunks(batch) {
        let mut data = vec![0u16; per_chunk * window.len()];
        let t0 = Instant::now();
        source.generate(window, &mut data, 8);
        gen_ms += t0.elapsed().as_secs_f64() * 1000.0;
        generated += window.len();
        buffers.push(data);
    }
    let gen_total = t_gen.elapsed().as_secs_f64() * 1000.0;
    println!(
        "generated {generated} chunks in {gen_total:.1} ms ({:.2} ms/chunk, {:.0} chunks/s)",
        gen_total / generated as f64,
        generated as f64 * 1000.0 / gen_total
    );
    let _ = gen_ms;

    // --- Pipeline ---------------------------------------------------------
    let extent = [1280u32, 720u32];
    let pipeline_config = PipelineConfig {
        terrain_budget: 128,
        mesh_budget: 96,
        remesh_budget: 32,
        threads: 8,
        section_y: (config.base_section_y(), config.base_section_y() + 23),
        extent,
    };
    let mut pipeline = WorldPipeline::new(1, 8192, pipeline_config, PushSource::new());
    pipeline.set_player(ChunkPos::new(0, 0));

    let mut submitted = 0usize;
    for (window, data) in coords.chunks(batch).zip(buffers.iter()) {
        for (i, (cx, cz)) in window.iter().enumerate() {
            let column = &data[i * per_chunk..(i + 1) * per_chunk];
            let sections = sections_from_chunk(column, config.sections());
            pipeline.submit_chunk(
                ChunkPos::new(*cx, *cz),
                config.base_section_y(),
                &sections,
            );
            submitted += 1;
        }
    }
    println!("submitted {submitted} chunks x {} sections", config.sections());
    drop(buffers);

    let mut renderer: Box<dyn Renderer> = match create_best_headless() {
        Ok(r) => r,
        Err(e) => {
            eprintln!("no render backend: {e:?}");
            return;
        }
    };
    println!("backend: {:?}", renderer.capabilities().backend);

    // --- Height statistics: drive the camera, do not guess it -------------
    let mut hmin = i32::MAX;
    let mut hmax = i32::MIN;
    let mut hsum: i64 = 0;
    let mut peak = (0.0f32, 0.0f32);
    for (cx, cz) in &coords {
        for (i, h) in source.heightmap(*cx, *cz).into_iter().enumerate() {
            hmin = hmin.min(h);
            hmax = hmax.max(h);
            hsum += h as i64;
            if h == hmax {
                peak = (
                    (*cx * 16 + (i % 16) as i32) as f32,
                    (*cz * 16 + (i / 16) as i32) as f32,
                );
            }
        }
    }
    let columns = coords.len() * 256;
    let hmean = (hsum as f64 / columns as f64) as f32;
    println!(
        "heightmap: min={hmin} max={hmax} mean={hmean:.1} (sea={})",
        config.sea_level
    );

    // --- Top-down map -----------------------------------------------------
    // Written straight from the generator's heightmap + biome ids, so it shows
    // the generator's own view of the world without the renderer in the way.
    {
        let side = ((radius * 2 + 1) * 16) as usize;
        let mut rgb = vec![0u8; (side * side * 3) as usize];
        for (cx, cz) in &coords {
            let hm = source.heightmap(*cx, *cz);
            let infos = source.column_info(*cx, *cz);
            for z in 0..16 {
                for x in 0..16 {
                    let wx = (cx * 16 + x as i32 + radius * 16) as usize;
                    let wz = (cz * 16 + z as i32 + radius * 16) as usize;
                    let h = hm[z * 16 + x];
                    let biome = infos[z * 16 + x].biome;
                    let sea = config.sea_level;
                    let mix = |a: (u8, u8, u8), b: (u8, u8, u8), t: f32| -> (u8, u8, u8) {
                        let t = t.clamp(0.0, 1.0);
                        (
                            (a.0 as f32 + (b.0 as f32 - a.0 as f32) * t) as u8,
                            (a.1 as f32 + (b.1 as f32 - a.1 as f32) * t) as u8,
                            (a.2 as f32 + (b.2 as f32 - a.2 as f32) * t) as u8,
                        )
                    };
                    // Continuous ramp, not thresholds: a hard cut at a height
                    // turns every flat area into a solid colour block.
                    let (r, g, b) = if h < sea {
                        let d = ((sea - h) as f32 / 40.0).clamp(0.0, 1.0);
                        mix((70, 130, 190), (16, 40, 96), d)
                    } else {
                        let t = ((h - sea) as f32 / 150.0).clamp(0.0, 1.0);
                        // One continuous ramp for every biome. A per-biome
                        // colour switch draws its own rectangle around each
                        // biome, which at map scale looks like a rendering bug
                        // rather than a landscape.
                        let _ = biome;
                        let land = mix((96, 148, 76), (128, 118, 96), (t * 1.6).min(1.0));
                        mix(land, (232, 236, 240), ((t - 0.62) * 2.4).max(0.0))
                    };
                    let o = (wz * side + wx) * 3;
                    rgb[o] = r;
                    rgb[o + 1] = g;
                    rgb[o + 2] = b;
                }
            }
        }
        let map_path = out_path.replace(".ppm", "_map.ppm");
        let mut out = Vec::with_capacity(rgb.len() + 32);
        out.extend_from_slice(format!("P6\n{side} {side}\n255\n").as_bytes());
        out.extend_from_slice(&rgb);
        match std::fs::File::create(&map_path).and_then(|mut f| {
            use std::io::Write;
            f.write_all(&out)
        }) {
            Ok(()) => println!("wrote {map_path} ({side}x{side})"),
            Err(e) => eprintln!("map write failed: {e}"),
        }
    }

    // Look at the highest column from inside the region: the ridge occludes the
    // generated-area boundary, so the frame shows terrain rather than the edge.
    // Diorama view: high oblique from outside the generated area, so the whole
    // region reads as one piece of terrain instead of a wall of cut blocks.
    let span = (radius * 16) as f32;
    let target = [peak.0 * 0.5, hmean, peak.1 * 0.5];
    let eye = [target[0] + span * 0.10, hmax as f32 + span * 0.75, target[2] - span * 1.05];
    let view_proj = perspective_look_at(
        eye,
        target,
        [0.0, 1.0, 0.0],
        58.0,
        extent[0] as f32 / extent[1] as f32,
        0.5,
        4000.0,
    )
    .expect("camera");
    let camera = jpe_world::pipeline::Camera { view_proj, eye };

    // --- Frames -----------------------------------------------------------
    let t_frames = Instant::now();
    let mut frames = 0usize;
    let mut last_resident = 0usize;
    let mut idle = 0;
    let mut lit = 0usize;
    let mut colours = 0usize;
    let mut visible = 0usize;
    let mut batches = 0usize;
    let mut outcome_last = None;
    for _ in 0..600 {
        let outcome = match pipeline.frame(&mut *renderer, camera) {
            Ok(o) => o,
            Err(e) => {
                eprintln!("frame failed: {e:?}");
                return;
            }
        };
        frames += 1;
        outcome_last = Some(outcome);
        last_resident = outcome.resident;
        visible = outcome.plan.visible as usize;
        batches = outcome.batches;
        if outcome.terrain_jobs == 0 && outcome.mesh_jobs == 0 && outcome.remesh_chunks == 0 {
            idle += 1;
            if idle >= 3 {
                break;
            }
        } else {
            idle = 0;
        }
    }
    let frame_ms = t_frames.elapsed().as_secs_f64() * 1000.0;

    let pixels = match renderer.read_pixels() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("readback failed: {e:?}");
            return;
        }
    };
    lit = pixels.count_non_background([0, 0, 0, 255]);
    {
        use std::collections::HashSet;
        let mut set = HashSet::new();
        for chunk in pixels.rgba8.chunks_exact(4) {
            set.insert([chunk[0], chunk[1], chunk[2], chunk[3]]);
            if set.len() > 4096 {
                break;
            }
        }
        colours = set.len();
    }
    if let Err(e) = write_ppm(&out_path, &pixels) {
        eprintln!("write failed: {e}");
    }

    println!("frames={frames} in {frame_ms:.1} ms ({:.2} ms/frame)", frame_ms / frames as f64);
    if let Some(o) = outcome_last {
        println!(
            "visible_quads={} considered={} culled={}",
            o.plan.visible_quads, o.plan.considered, o.plan.culled
        );
    }
    {
        let t = pipeline.timing();
        let ms = |ns: u64| ns as f64 / 1e6;
        println!(
            "phases over {} frames: pump={:.0} targets={:.0} build={:.0} publish={:.0} draw={:.0} ms",
            t.samples, ms(t.pump_ns), ms(t.targets_ns), ms(t.build_ns), ms(t.publish_ns), ms(t.draw_ns)
        );
    }
    println!(
        "resident_sections={last_resident} visible={visible} batches={batches} \
         lit_pixels={lit}/{} distinct_colours={colours}",
        extent[0] * extent[1]
    );
    println!("wrote {out_path} ({}x{})", pixels.width, pixels.height);

}
