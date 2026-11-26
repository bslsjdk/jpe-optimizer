package dev.jpe.world;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * End-to-end harness for the temporary Java to Rust integration path.
 *
 * <p>It drives the Rust world engine through the C ABI using Java 25 FFM:
 * scheduler priority ordering, chunk lifecycle transitions, GPU-upload handoff,
 * stats accounting, section meshing and world-epoch invalidation. It requires no
 * Minecraft install, no NeoForge and no C++ library; the only native dependency
 * is {@code libjpe_world}.
 *
 * <p>Run via {@code java/run_harness.sh <path-to-libjpe_world>}. Exits non-zero on
 * the first failed check.
 */
public final class WorldEngineHarness {

    private static int checks;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: WorldEngineHarness <path-to-libjpe_world>");
            System.exit(2);
        }
        Path library = Path.of(args[0]).toAbsolutePath();
        System.out.println("[harness] library = " + library);

        // Deliberately not try-with-resources: closing the FFM arena unloads
        // libjpe_world. Once a GPU backend has initialised a driver, Mesa
        // keeps process-wide teardown state whose code lives in that module,
        // so unloading it makes the JVM crash on exit. Production loads the
        // library once and never unloads it; the harness mirrors that.
        JpeWorldNative world = new JpeWorldNative(library);
        abi(world);
        schedulingAndLifecycle(world);
        meshing(world);
        epochInvalidation(world);
        renderers(world);
        visibility(world);

        System.out.println("[harness] all " + checks + " checks passed");
    }

    private static void abi(JpeWorldNative world) {
        eq("abi version", 1, world.abiVersion());
        eq("max section quads", 2048 * 6, world.maxSectionQuads());
    }

    private static void schedulingAndLifecycle(JpeWorldNative world) {
        long h = world.create(1L, 16);
        check("create returns a handle", h != 0);

        eq("set player", 0, world.setPlayer(h, 0, 0));

        // Three requests at deliberate distances: urgent (radius <= 4),
        // near (<= 12) and far (> 12).
        long idNear = world.requestTerrain(h, 2, 0);
        long idMid = world.requestTerrain(h, 8, 0);
        long idFar = world.requestTerrain(h, 40, 0);
        check("job ids are distinct",
                idNear != idMid && idMid != idFar && idNear != idFar);

        // Priority bands must come out in Urgent, Near, Far order.
        JpeWorldNative.Job j1 = requireJob(world, h, "first");
        eq("1st kind = generate terrain", JpeWorldNative.KIND_GENERATE_TERRAIN, j1.kind());
        eq("1st priority = urgent", 0, j1.priority());
        eq("1st chunk x", 2, j1.chunkX());
        eq("1st chunk z", 0, j1.chunkZ());
        eq("1st epoch", 1L, j1.epoch());
        // Chunk (2,0) center block (40,8) vs player center (8,8): dx=32 -> 1024.
        eq("1st distance^2", 32L * 32, j1.distanceSq());

        JpeWorldNative.Job j2 = requireJob(world, h, "second");
        eq("2nd priority = near", 1, j2.priority());
        eq("2nd chunk x", 8, j2.chunkX());

        JpeWorldNative.Job j3 = requireJob(world, h, "third");
        eq("3rd priority = far", 2, j3.priority());
        eq("3rd chunk x", 40, j3.chunkX());

        check("queue drained", world.takeJob(h) == null);

        // The chunks are now in "generating" state; finish the work.
        eq("chunk (2,0) generating", JpeWorldNative.STATE_TERRAIN_GENERATING,
                world.chunkState(h, 2, 0));

        eq("complete near", JpeWorldNative.COMPLETE_COMMITTED,
                world.complete(h, j1.epoch(), j1.kind(), j1.chunkX(), j1.chunkZ()));
        eq("complete mid", JpeWorldNative.COMPLETE_COMMITTED,
                world.complete(h, j2.epoch(), j2.kind(), j2.chunkX(), j2.chunkZ()));
        eq("complete far", JpeWorldNative.COMPLETE_COMMITTED,
                world.complete(h, j3.epoch(), j3.kind(), j3.chunkX(), j3.chunkZ()));

        eq("chunk (2,0) terrain ready", JpeWorldNative.STATE_TERRAIN_READY,
                world.chunkState(h, 2, 0));

        // Mesh the nearest chunk: request -> take -> complete -> upload.
        world.requestMesh(h, 2, 0);
        JpeWorldNative.Job meshJob = requireJob(world, h, "mesh");
        eq("mesh kind", JpeWorldNative.KIND_BUILD_MESH, meshJob.kind());
        check("mesh priority promoted to at least near", meshJob.priority() <= 1);
        eq("complete mesh", JpeWorldNative.COMPLETE_COMMITTED,
                world.complete(h, meshJob.epoch(), meshJob.kind(),
                        meshJob.chunkX(), meshJob.chunkZ()));
        eq("chunk (2,0) mesh ready", JpeWorldNative.STATE_MESH_READY,
                world.chunkState(h, 2, 0));
        eq("mark uploaded", 0, world.markUploaded(h, 2, 0));
        eq("chunk (2,0) uploaded", JpeWorldNative.STATE_UPLOADED,
                world.chunkState(h, 2, 0));

        JpeWorldNative.Stats s = world.stats(h);
        eq("stats submitted", 4L, s.submitted());
        eq("stats started", 4L, s.started());
        eq("stats completed", 4L, s.completed());
        eq("stats stale", 0L, s.staleDiscarded());
        eq("stats rejected", 0L, s.rejectedFull());
        eq("stats evicted", 0L, s.evicted());
        eq("stats epoch bumps", 0L, s.epochBumps());

        eq("unload uploaded chunk", 0, world.unload(h, 2, 0));
        eq("chunk (2,0) untracked after unload", -3, world.chunkState(h, 2, 0));

        eq("destroy", 0, world.destroy(h));
        eq("destroy unknown handle", -1, world.destroy(h));
    }

    private static void meshing(JpeWorldNative world) {
        // A single solid block at section origin yields 6 faces = 24 vertices,
        // 6 quads. The first emitted vertex is the +X face corner at x = 1.
        short[] blocks = new short[4096];
        blocks[0] = 1;
        JpeWorldNative.Mesh mesh = world.meshSection(blocks, 0, 0, 0);
        eq("single block vertices", 24, mesh.vertexCount());
        eq("single block quads", 6, mesh.quadCount());
        check("single block first vertex x == 1.0",
                Float.compare(1.0f, mesh.firstVertexX()) == 0);

        // An empty section produces no geometry.
        JpeWorldNative.Mesh empty = world.meshSection(new short[4096], 10, 20, 30);
        eq("empty section vertices", 0, empty.vertexCount());
        eq("empty section quads", 0, empty.quadCount());

        // A full 16x16 solid layer: 2 caps + 4 sides = 16*16*2 + 4*16 quads.
        short[] layer = new short[4096];
        Arrays.fill(layer, 0, 256, (short) 1); // y=0 plane, all x,z
        JpeWorldNative.Mesh solid = world.meshSection(layer, 0, 0, 0);
        int expectedQuads = 16 * 16 * 2 + 4 * 16;
        eq("solid layer quads", expectedQuads, solid.quadCount());
        eq("solid layer vertices", expectedQuads * 4, solid.vertexCount());
    }

    private static void epochInvalidation(JpeWorldNative world) {
        long h = world.create(1L, 16);
        world.setPlayer(h, 0, 0);
        world.requestTerrain(h, 1, 1);
        JpeWorldNative.Job job = requireJob(world, h, "pre-reset");

        long newEpoch = world.reset(h);
        eq("reset bumps epoch", 2L, newEpoch);

        // A result computed in the old epoch must be discarded as stale.
        eq("old-epoch completion is stale", JpeWorldNative.COMPLETE_STALE,
                world.complete(h, job.epoch(), job.kind(), job.chunkX(), job.chunkZ()));
        eq("reset clears the store", -3, world.chunkState(h, 1, 1));

        JpeWorldNative.Stats s = world.stats(h);
        eq("reset counted an epoch bump", 1L, s.epochBumps());
        eq("stale completion counted", 1L, s.staleDiscarded());

        world.destroy(h);
    }

    /**
     * Drives a native GPU backend end to end: create, capabilities, frame,
     * upload a meshed section, draw, read the frame back and check the pixels.
     *
     * <p>If no driver is present the section is skipped, unless
     * {@code JPE_REQUIRE_RENDER_BACKENDS} is set, in which case it fails so CI
     * cannot silently lose GPU coverage.
     */
    private static void renderers(JpeWorldNative world) {
        long r = world.rendererCreate(JpeWorldNative.BACKEND_AUTO);
        if (r == 0) {
            if (System.getenv("JPE_REQUIRE_RENDER_BACKENDS") != null) {
                throw new AssertionError("check failed: render backend required but none available");
            }
            System.out.println("[harness] no native render backend here, skipping renderer checks");
            return;
        }

        JpeWorldNative.RendererCaps caps = world.rendererCapabilities(r);
        System.out.println("[harness] renderer backend=" + caps.backend()
                + " api=" + caps.apiMajor() + "." + caps.apiMinor()
                + " maxTex=" + caps.maxTextureSize());
        check("renderer reports vulkan, gl or gles",
                caps.backend() >= JpeWorldNative.BACKEND_VULKAN
                        && caps.backend() <= JpeWorldNative.BACKEND_GLES);
        check("renderer api major >= 1", caps.apiMajor() >= 1);
        check("renderer max texture size >= 256", caps.maxTextureSize() >= 256);

        // Camera maths lives in Rust so Java never keeps a second copy of the
        // projection code; the renderer takes a finished column-major matrix.
        float[] viewProj = world.cameraOrtho(new float[]{2.4f, 2.0f, 2.8f},
                new float[]{0.5f, 0.5f, 0.5f}, new float[]{0.0f, 1.0f, 0.0f},
                0.9f, 0.9f, 0.1f, 20.0f);
        check("camera matrix built", viewProj != null && viewProj.length == 16);
        check("degenerate camera is rejected",
                world.cameraOrtho(new float[]{1f, 1f, 1f}, new float[]{1f, 1f, 1f},
                        new float[]{0f, 1f, 0f}, 1f, 1f, 0.1f, 20f) == null);
        float[] nanMatrix = new float[16];
        nanMatrix[0] = Float.NaN;
        nanMatrix[15] = 1f;
        eq("renderer rejects a non-finite camera",
                JpeWorldNative.RENDER_INVALID_MESH, world.rendererSetCamera(r, nanMatrix));
        eq("renderer accepts the camera", 0, world.rendererSetCamera(r, viewProj));

        eq("renderer begin frame", 0, world.rendererBeginFrame(r, 128, 128));

        short[] blocks = new short[4096];
        blocks[0] = 1;
        JpeWorldNative.MeshUpload mesh = world.rendererUploadSection(r, blocks, 0, 0, 0);
        check("renderer mesh handle non-zero", mesh.mesh() != 0);
        eq("renderer index count", 36, mesh.indexCount());

        eq("renderer draw", 0, world.rendererDraw(r, mesh.mesh(), mesh.indexCount(), 1));
        eq("renderer end frame", 0, world.rendererEndFrame(r));

        byte[] pixels = world.rendererReadPixels(r, 128, 128);
        eq("renderer frame byte size", 128 * 128 * 4, pixels.length);

        int bgR = pixels[0] & 0xff;
        int bgG = pixels[1] & 0xff;
        int bgB = pixels[2] & 0xff;
        check("cleared corner is the dark blue background",
                bgR <= 16 && bgG <= 16 && bgB >= 18 && bgB <= 34);

        int lit = 0;
        java.util.HashSet<String> tones = new java.util.HashSet<>();
        for (int i = 0; i < pixels.length; i += 4) {
            int cr = pixels[i] & 0xff;
            int cg = pixels[i + 1] & 0xff;
            int cb = pixels[i + 2] & 0xff;
            int ca = pixels[i + 3] & 0xff;
            if (cr == bgR && cg == bgG && cb == bgB) {
                continue;
            }
            lit++;
            // Alpha is part of the contract: the framebuffer must stay opaque.
            if (ca != 255) {
                tones.add("alpha<255");
            }
            tones.add((cr / 16) + "," + (cg / 16) + "," + (cb / 16));
        }
        check("geometry covers pixels (" + lit + ")", lit > 100);
        // One flat colour would mean the atlas, the normals or the camera never
        // reached the GPU. Vulkan, OpenGL and OpenGL ES must all shade here.
        check("atlas and directional shading produce distinct tones (" + tones.size() + ")",
                tones.size() >= 3 && !tones.contains("alpha<255"));
        System.out.println("[harness] frame: " + lit + " lit pixels, " + tones.size() + " tones");

        // A renderer is thread-affine; the wrong thread must be rejected rather
        // than touching a context it does not own.
        int[] wrongThread = new int[1];
        Thread t = new Thread(() -> wrongThread[0] = world.rendererBeginFrame(r, 8, 8));
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        eq("renderer call from another thread is rejected",
                JpeWorldNative.RENDER_WRONG_THREAD, wrongThread[0]);

        int[] wrongCameraThread = new int[1];
        Thread ct = new Thread(() -> wrongCameraThread[0] = world.rendererSetCamera(r, viewProj));
        ct.start();
        try {
            ct.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        eq("camera call from another thread is rejected",
                JpeWorldNative.RENDER_WRONG_THREAD, wrongCameraThread[0]);

        eq("renderer destroy mesh", 0, world.rendererDestroyMesh(r, mesh.mesh()));
        eq("renderer destroy", 0, world.rendererDestroy(r));
        eq("renderer destroy unknown handle", JpeWorldNative.RENDER_UNKNOWN_HANDLE,
                world.rendererDestroy(r));
        eq("unknown backend code is unavailable", 0, world.rendererAvailable(99));
    }

    /**
     * Visibility through the C ABI: the plan must reject off-screen sections and
     * the rejection must show up in the rendered frame, not only in the stats.
     */
    private static void visibility(JpeWorldNative world) {
        long r = world.rendererCreate(JpeWorldNative.BACKEND_AUTO);
        if (r == 0) {
            if (System.getenv("JPE_REQUIRE_RENDER_BACKENDS") != null) {
                throw new AssertionError("check failed: render backend required for visibility");
            }
            System.out.println("[harness] no native render backend here, skipping visibility checks");
            return;
        }

        // One block near the middle of each section, so whatever is drawn really
        // sits inside the box the plan culls against.
        short[] blocks = new short[4096];
        blocks[(8 << 8) | (8 << 4) | 8] = 1;
        int[][] origins = {
            {0, 0, 0},       // in front
            {0, 0, 32},      // in front, nearer
            {200, 0, 0},     // far off to +X
            {-200, 0, 0},    // far off to -X
            {0, 200, 0},     // far above
            {0, 0, 200},     // behind the camera
        };
        JpeWorldNative.RenderSection[] sections = new JpeWorldNative.RenderSection[origins.length];
        for (int i = 0; i < origins.length; i++) {
            JpeWorldNative.MeshUpload up = world.rendererUploadSection(
                    r, blocks, origins[i][0], origins[i][1], origins[i][2]);
            sections[i] = new JpeWorldNative.RenderSection(
                    i, up.mesh(), origins[i][0], origins[i][1], origins[i][2], up.indexCount());
        }

        float[] eye = {8.0f, 8.0f, 64.0f};
        float[] up3 = {0.0f, 1.0f, 0.0f};
        float[] viewProj = world.cameraOrtho(eye, new float[]{8.0f, 8.0f, 0.0f}, up3,
                2.0f, 2.0f, 0.5f, 100.0f);
        check("visibility camera built", viewProj != null);

        // Null pointers and an unknown handle must be refused, and a non-finite
        // matrix must be refused *before* anything is queued.
        eq("null view matrix rejected", JpeWorldNative.RENDER_INVALID_ARGUMENT,
                world.rendererTryDrawVisible(r, null, eye, sections));
        eq("null eye rejected", JpeWorldNative.RENDER_INVALID_ARGUMENT,
                world.rendererTryDrawVisible(r, viewProj, null, sections));
        eq("unknown handle rejected", JpeWorldNative.RENDER_UNKNOWN_HANDLE,
                world.rendererTryDrawVisible(999_999L, viewProj, eye, sections));
        // A zero-length plan is legal (nothing to queue); a null pointer with a
        // non-zero count is the case the Rust side rejects, covered by
        // ffi::tests::renderer_over_c_abi_culls_invisible_sections.
        eq("empty section list accepted", 0,
                world.rendererTryDrawVisible(r, viewProj, eye, new JpeWorldNative.RenderSection[0]));

        float[] nanMatrix = viewProj.clone();
        nanMatrix[5] = Float.NaN;
        eq("renderer begin frame (contract pass)", 0, world.rendererBeginFrame(r, 128, 128));
        eq("non-finite camera refused before queueing", JpeWorldNative.RENDER_INVALID_MESH,
                world.rendererTryDrawVisible(r, nanMatrix, eye, sections));
        eq("renderer end frame (contract pass)", 0, world.rendererEndFrame(r));

        eq("renderer begin frame (visible pass)", 0, world.rendererBeginFrame(r, 128, 128));
        JpeWorldNative.PlanStats stats = world.rendererDrawVisible(r, viewProj, eye, sections);
        eq("sections considered", 6, stats.considered());
        eq("sections visible", 2, stats.visible());
        eq("sections culled", 4, stats.culled());
        eq("visible quads", 12L, stats.visibleQuads());
        eq("culled quads", 24L, stats.culledQuads());
        eq("renderer end frame (visible pass)", 0, world.rendererEndFrame(r));
        byte[] pixels = world.rendererReadPixels(r, 128, 128);
        int lit = countLit(pixels);
        check("visible sections covered pixels (" + lit + ")", lit > 100);

        // Look the other way: every section is behind the camera, so nothing is
        // queued and the frame has to come back completely empty.
        float[] awayEye = {8.0f, 8.0f, -600.0f};
        float[] awayMatrix = world.cameraOrtho(awayEye, new float[]{8.0f, 8.0f, -700.0f}, up3,
                2.0f, 2.0f, 0.5f, 100.0f);
        check("away camera built", awayMatrix != null);
        eq("renderer begin frame (culled pass)", 0, world.rendererBeginFrame(r, 128, 128));
        JpeWorldNative.PlanStats culled = world.rendererDrawVisible(r, awayMatrix, awayEye, sections);
        eq("culled pass considered", 6, culled.considered());
        eq("culled pass visible", 0, culled.visible());
        eq("culled pass culled", 6, culled.culled());
        eq("culled pass quads", 0L, culled.visibleQuads());
        eq("renderer end frame (culled pass)", 0, world.rendererEndFrame(r));
        int leftover = countLit(world.rendererReadPixels(r, 128, 128));
        eq("fully culled camera produces an empty frame", 0, leftover);
        System.out.println("[harness] visibility: 2/6 visible -> " + lit
                + " lit pixels, 0/6 visible -> " + leftover + " lit pixels");

        for (JpeWorldNative.RenderSection section : sections) {
            eq("renderer destroy mesh", 0, world.rendererDestroyMesh(r, section.mesh()));
        }
        eq("renderer destroy (visibility)", 0, world.rendererDestroy(r));
    }

    /** Non-background pixels of a frame, using the cleared top-left corner. */
    private static int countLit(byte[] pixels) {
        int bgR = pixels[0] & 0xff;
        int bgG = pixels[1] & 0xff;
        int bgB = pixels[2] & 0xff;
        int lit = 0;
        for (int i = 0; i + 3 < pixels.length; i += 4) {
            if ((pixels[i] & 0xff) != bgR || (pixels[i + 1] & 0xff) != bgG
                    || (pixels[i + 2] & 0xff) != bgB) {
                lit++;
            }
        }
        return lit;
    }

    private static JpeWorldNative.Job requireJob(JpeWorldNative world, long handle, String label) {
        JpeWorldNative.Job job = world.takeJob(handle);
        check("expected a " + label + " job", job != null);
        return job;
    }

    private static void check(String message, boolean condition) {
        checks++;
        if (!condition) {
            throw new AssertionError("check failed: " + message);
        }
    }

    private static void eq(String message, long expected, long actual) {
        checks++;
        if (expected != actual) {
            throw new AssertionError(
                    "check failed: " + message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private WorldEngineHarness() {}
}
