package dev.jpe.optimizer.world;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jpe.world.JpeWorldNative;

/**
 * A continuously-running Java host that drives the Rust world engine from the live
 * Minecraft client.
 *
 * <p>This is the "system" form of the Stage 6 test rig: instead of a bounded GameTest
 * window, the host is ticked by real client events. Every client tick it reads the
 * camera entity's chunk position, tells the engine where the player is, streams the
 * block data of newly-loaded chunks around it through {@link MinecraftChunkReader},
 * and drops chunks that fall outside a working radius. Every render frame it builds a
 * real perspective view-projection (see {@link WorldCamera}, Vulkan {@code 0..=1}
 * depth) and runs one full engine frame — scheduling, meshing, GPU upload, visibility
 * and culling — through the native backend.
 *
 * <p><b>Honest scope.</b> This class only loads on the physical client (see the dist
 * guard in {@code JPEOptimizer}). The engine renders into its own offscreen target, not
 * Minecraft's window or swapchain — presentation/context sharing is the next stage and
 * needs a real GPU host, so this file has never been exercised against an on-screen
 * client in this environment. Terrain generation still comes from Minecraft; the block
 * source is {@link MinecraftChunkReader}, not the C++ terrain engine; nothing here takes
 * over vanilla rendering.
 */
public final class WorldEngineHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("JPEOptimizer/world-host");

    /** Tunables for the continuous host. */
    public record Config(
            int streamRadius,      // chunks streamed around the player (square radius)
            int unloadMargin,      // extra chunks kept before unloadOutside prunes them
            int terrainBudget,
            int meshBudget,
            int threads,
            int targetWidth,       // offscreen extent
            int targetHeight,
            int renderDistanceBlocks, // far plane
            int logEveryFrames) {

        /** A modest default: 4-chunk working radius, 256x144 offscreen target. */
        public static Config defaults() {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            return new Config(4, 2, 16, 16, Math.min(4, cores), 256, 144, 128, 120);
        }
    }

    private final RustWorldBridge bridge;
    private final Config config;
    private final Minecraft minecraft;

    /** Packed chunk positions already handed to the engine (and still resident). */
    private final Set<Long> submitted = new HashSet<>();

    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private int tickCounter;
    private long framesRun;
    private boolean ready;
    private boolean failed;

    private WorldEngineHost(RustWorldBridge bridge, Config config, Minecraft minecraft) {
        this.bridge = bridge;
        this.config = config;
        this.minecraft = minecraft;
    }

    /** Explicit opt-in for the continuous host, separate from the library path. */
    public static final String ENABLE_PROPERTY = "jpe.worldHost";

    /**
     * Register the host's client event listeners. Must be called only on the client
     * dist, from a branch the dedicated server never executes, so the client-only
     * classes referenced here are never loaded on a server.
     *
     * <p>Two independent guards, so a validated path is never disturbed by this class:
     * {@code jpe.worldHost} must be {@code true}, and {@code jpe.worldLibrary} must be
     * set. The GameTest harness sets the library (for its own bounded rig) but not the
     * host flag, so it stays completely unaffected by the host.
     *
     * @return the host, or {@code null} when disabled.
     */
    public static WorldEngineHost initOnClient() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            // Do not touch Minecraft.getInstance() at all here: this branch also runs
            // under gameTestServer (whose dist may report CLIENT), and returning before
            // any client call keeps that path identical to before this class existed.
            LOGGER.info("world host disabled (set -D{}=true to attach it to the client)", ENABLE_PROPERTY);
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        java.util.Optional<Path> library = RustWorldBridge.configuredLibrary();
        if (library.isEmpty()) {
            if (RustWorldBridge.required()) {
                LOGGER.error("jpe.worldRequired=true but jpe.worldLibrary is unset; world host disabled");
            } else {
                LOGGER.info("no jpe.worldLibrary configured; world host idle (set -Djpe.worldLibrary to enable)");
            }
            return null;
        }
        WorldEngineHost host;
        try {
            host = create(library.get(), Config.defaults(), minecraft);
        } catch (RuntimeException | Error construction) {
            LOGGER.error("world host failed to start; continuing without it", construction);
            return null;
        }

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, host::onClientTick);
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, event -> host.onRenderFrame());
        LOGGER.info("Rust world host attached: backend={} target={}x{} radius={}",
                host.bridge.backend().name(), host.config.targetWidth(),
                host.config.targetHeight(), host.config.streamRadius());
        return host;
    }

    private static WorldEngineHost create(Path library, Config config, Minecraft minecraft) {
        // sectionYMin/YMax are overridden per chunk by the reader's column height; the
        // pipeline window covers the whole overworld column here.
        RustWorldBridge.Options options = new RustWorldBridge.Options(
                JpeWorldNative.BACKEND_AUTO,
                config.targetWidth(), config.targetHeight(),
                config.terrainBudget(), config.meshBudget(), config.meshBudget(),
                config.threads(), -4, 23, 1L, 4096);
        RustWorldBridge bridge = RustWorldBridge.open(library, options);
        return new WorldEngineHost(bridge, config, minecraft);
    }

    // --- client tick: player tracking + chunk streaming ---------------------

    private void onClientTick(ClientTickEvent.Post event) {
        if (failed) {
            return;
        }
        ClientLevel level = minecraft.level;
        Entity camera = minecraft.getCameraEntity();
        if (level == null || camera == null) {
            return;
        }
        int chunkX = (int) Math.floor(camera.getX() / 16.0);
        int chunkZ = (int) Math.floor(camera.getZ() / 16.0);
        boolean moved = chunkX != lastChunkX || chunkZ != lastChunkZ;
        boolean periodic = ++tickCounter % 20 == 0;
        if (!moved && !periodic) {
            return;
        }
        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
        try {
            bridge.setPlayer(chunkX, chunkZ);
            streamLoadedChunks(level, chunkX, chunkZ);
            if (moved) {
                int keep = config.streamRadius() + config.unloadMargin();
                bridge.unloadOutside(chunkX, chunkZ, keep);
                pruneSubmitted(chunkX, chunkZ, keep);
            }
            ready = true;
        } catch (RuntimeException streaming) {
            failed = true;
            LOGGER.error("world host tick failed; disabling the host for this session", streaming);
        }
    }

    /** Submit every loaded chunk inside the working radius not already resident. */
    private void streamLoadedChunks(ClientLevel level, int centerChunkX, int centerChunkZ) {
        int r = config.streamRadius();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int cx = centerChunkX + dx;
                int cz = centerChunkZ + dz;
                long key = pack(cx, cz);
                if (submitted.contains(key)) {
                    continue;
                }
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                ChunkAccess chunk = level.getChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                LevelColumn column = readColumn(chunk);
                if (column == null) {
                    continue;
                }
                int status = bridge.submitChunk(cx, cz, column.baseSectionY, column.blocks);
                // Any accepted code (new, mesh/remesh requested, or already in flight)
                // means the chunk is now tracked by the engine; remember it so we do not
                // resubmit every tick. Transient rejections are retried next pass.
                if (status == JpeWorldNative.SUBMIT_NEW
                        || status == JpeWorldNative.SUBMIT_MESH_REQUESTED
                        || status == JpeWorldNative.SUBMIT_REMESH_REQUESTED
                        || status == JpeWorldNative.SUBMIT_IN_FLIGHT) {
                    submitted.add(key);
                }
            }
        }
    }

    /** Read a chunk's whole block column into the engine hand-off shape. */
    private static LevelColumn readColumn(ChunkAccess chunk) {
        int baseSectionY = chunk.getMinY() >> 4;
        int sectionCount = chunk.getSections().length;
        if (sectionCount <= 0) {
            return null;
        }
        MinecraftChunkReader reader = new MinecraftChunkReader();
        short[] blocks = reader.read(chunk, baseSectionY, sectionCount);
        return new LevelColumn(baseSectionY, blocks);
    }

    private record LevelColumn(int baseSectionY, short[] blocks) {
    }

    // --- render frame: real perspective camera + one engine frame -----------

    private void onRenderFrame() {
        if (!ready || failed) {
            return;
        }
        ClientLevel level = minecraft.level;
        Entity camera = minecraft.getCameraEntity();
        if (level == null || camera == null) {
            return;
        }
        double ex = camera.getX();
        double ey = camera.getY();
        double ez = camera.getZ();
        try {
            float[] viewProj = WorldCamera.perspectiveViewProj(
                    ex, ey, ez,
                    camera.getViewYRot(0.0f), camera.getViewXRot(0.0f),
                    70.0f, // fov: a fixed transitional value; reading the live FOV option
                            // is a Stage 5 refinement once we render to Minecraft's viewport
                    (float) config.targetWidth() / (float) config.targetHeight(),
                    0.1f, (float) config.renderDistanceBlocks());
            float[] eye = {(float) ex, (float) ey, (float) ez};
            JpeWorldNative.FrameStats last = bridge.frame(viewProj, eye);
            if (++framesRun % config.logEveryFrames() == 0) {
                LOGGER.info("world host frame={} residentChunks={} resident={} visible={} culled={} "
                                + "batches={} terrain={} mesh={} remesh={}",
                        framesRun, submitted.size(), last.resident(), last.visible(), last.culled(),
                        last.batches(), last.terrainJobs(), last.meshJobs(), last.remeshChunks());
            }
        } catch (RuntimeException frame) {
            failed = true;
            LOGGER.error("world host render frame failed; disabling the host", frame);
        }
    }

    /** Release the GPU handles but keep the native library mapped (see {@code releaseHandles}). */
    public void shutdown() {
        if (!failed && ready) {
            LOGGER.info("world host shutting down after {} frames ({} chunks resident)",
                    framesRun, submitted.size());
        }
        ready = false;
        bridge.releaseHandles();
    }

    public boolean isReady() {
        return ready;
    }

    public int residentChunkCount() {
        return submitted.size();
    }

    // --- helpers ------------------------------------------------------------

    private static long pack(int x, int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    /** Drop submitted keys that fell outside {@code keep} so re-approach resubmits. */
    private void pruneSubmitted(int centerChunkX, int centerChunkZ, int keep) {
        Iterator<Long> it = submitted.iterator();
        while (it.hasNext()) {
            long key = it.next();
            int cx = (int) (key << 32 >> 32);
            int cz = (int) (key >> 32);
            if (Math.max(Math.abs(cx - centerChunkX), Math.abs(cz - centerChunkZ)) > keep) {
                it.remove();
            }
        }
    }
}
