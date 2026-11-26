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

        try (JpeWorldNative world = new JpeWorldNative(library)) {
            abi(world);
            schedulingAndLifecycle(world);
            meshing(world);
            epochInvalidation(world);
        }

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
