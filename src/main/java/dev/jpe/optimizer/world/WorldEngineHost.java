package dev.jpe.optimizer.world;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
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
 * block data of every loaded chunk inside the render distance through
 * {@link MinecraftChunkReader}, re-signatures a bounded batch of resident chunks to catch
 * block edits and re-submit the ones that changed, and drops chunks that fall outside a
 * working radius. Every render frame it builds a real perspective view-projection from
 * the interpolated camera and the live FOV option (see {@link WorldCamera}, Vulkan
 * {@code 0..=1} depth) and runs one full engine frame — scheduling, meshing, GPU upload,
 * visibility and culling — through the native backend.
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

    /**
     * Tunables for the continuous host.
     *
     * @param streamRadius       positive = fixed radius in chunks; {@code <= 0} = follow
     *                           the live render-distance option (full render distance)
     * @param unloadMargin       extra chunks kept before {@code unloadOutside} prunes them
     * @param radiusCap          hard memory cap in chunks (the outermost bound on streaming)
     * @param dirtyBudget        chunks re-signatured per tick to catch block edits
     */
    public record Config(
            int streamRadius,
            int unloadMargin,
            int radiusCap,
            int terrainBudget,
            int meshBudget,
            int threads,
            int targetWidth,
            int targetHeight,
            int minFarBlocks,
            int logEveryFrames,
            int dirtyBudget) {

        /**
         * Follow the live render distance, capped at 8 chunks (≈56&nbsp;MB of column
         * block data plus the Rust mirror) so a 12-plus-chunk client does not balloon;
         * raise {@link #radiusCap} if the host has memory to spare.
         */
        public static Config defaults() {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            return new Config(0, 2, 8, 16, 16, Math.min(4, cores),
                    256, 144, 128, 120, 8);
        }
    }

    private final RustWorldBridge bridge;
    private final Config config;
    private final Minecraft minecraft;

    /**
     * One reader for the whole host. Sharing it keeps the {@code BlockState -> u16 id}
     * mapping identical across every chunk in the session, so the mesher culls shared
     * faces at chunk borders consistently and a chunk's signature is comparable across
     * ticks.
     */
    private final MinecraftChunkReader reader = new MinecraftChunkReader();

    /** Packed chunk positions already handed to the engine, in insertion order. */
    private final LinkedHashSet<Long> submitted = new LinkedHashSet<>();
    /** Same keys as {@link #submitted}, indexable for the round-robin dirty pass. */
    private final ArrayList<Long> residentOrder = new ArrayList<>();
    /** Last observed content signature per resident chunk. */
    private final Map<Long, Long> signatures = new HashMap<>();

    private int dirtyCursor;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private int lastRadius = -1;
    private int tickCounter;
    private long framesRun;
    private boolean ready;
    private boolean failed;
    /** True once the on-screen present path is active (renderer lives on this thread). */
    private boolean presenting;
    /** True when on-screen present was requested but the backend cannot share a context. */
    private boolean presentUnsupported;

    private WorldEngineHost(RustWorldBridge bridge, Config config, Minecraft minecraft) {
        this.bridge = bridge;
        this.config = config;
        this.minecraft = minecraft;
    }

    /** Explicit opt-in for the continuous host, separate from the library path. */
    public static final String ENABLE_PROPERTY = "jpe.worldHost";

    /**
     * Opt-in for the on-screen present path. When true, the renderer is created on the
     * calling (render) thread over Minecraft's already-current GL context and the engine
     * draws into that context's default framebuffer, presenting with a flush each frame;
     * Minecraft performs the buffer swap. This is a takeover demo, not an overlay: the
     * engine clears the framebuffer it draws into, and on-screen output has never been
     * verified in this headless environment. Off by default so normal play is untouched.
     */
    public static final String PRESENT_PROPERTY = "jpe.worldPresent";

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
        Optional<Path> library = RustWorldBridge.configuredLibrary();
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
        LOGGER.info("Rust world host attached: backend={} target={}x{} radiusPolicy={} cap={} dirtyBudget={}",
                host.bridge.backend().name(), host.config.targetWidth(),
                host.config.targetHeight(), host.config.streamRadius() <= 0
                        ? "render-distance" : host.config.streamRadius(),
                host.config.radiusCap(), host.config.dirtyBudget());
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
        boolean presentRequested = Boolean.getBoolean(PRESENT_PROPERTY);
        RustWorldBridge bridge = RustWorldBridge.open(library, options, presentRequested);
        WorldEngineHost host = new WorldEngineHost(bridge, config, minecraft);
        if (presentRequested) {
            int support = bridge.presentSupport();
            if (support == JpeWorldNative.PRESENT_EXTERNAL_GL) {
                host.presenting = true;
                LOGGER.info("world host presenting into Minecraft's GL context (takeover demo; "
                        + "the engine clears the framebuffer it draws into)");
            } else {
                host.presentUnsupported = true;
                LOGGER.warn("jpe.worldPresent=true but backend={} reports present support {}; "
                        + "continuing offscreen-only", bridge.backend().name(), support);
            }
        }
        return host;
    }

    // --- client tick: player tracking + chunk streaming + dirty resubmit ----

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
        int radius = currentRadius();
        boolean moved = chunkX != lastChunkX || chunkZ != lastChunkZ;
        boolean radiusChanged = radius != lastRadius;
        boolean periodic = ++tickCounter % 20 == 0;
        try {
            if (moved || periodic || radiusChanged) {
                lastChunkX = chunkX;
                lastChunkZ = chunkZ;
                bridge.setPlayer(chunkX, chunkZ);
                streamLoadedChunks(level, chunkX, chunkZ, radius);
            }
            if (moved || radiusChanged) {
                int keep = radius + config.unloadMargin();
                bridge.unloadOutside(chunkX, chunkZ, keep);
                pruneSubmitted(chunkX, chunkZ, keep);
                lastRadius = radius;
            }
            // Bounded block-edit detection runs every tick (resident chunks change even
            // when the player stands still), re-meshing only the chunks whose signature
            // moved.
            recheckDirty(level);
            ready = true;
        } catch (RuntimeException streaming) {
            failed = true;
            LOGGER.error("world host tick failed; disabling the host for this session", streaming);
        }
    }

    /** Streaming radius for this tick: the live render distance, capped for memory. */
    private int currentRadius() {
        int renderDistance = minecraft.options.renderDistance().get();
        return WorldHostMath.effectiveStreamRadius(config.streamRadius(), renderDistance, config.radiusCap());
    }

    /** Submit every loaded chunk inside the working radius not already resident. */
    private void streamLoadedChunks(ClientLevel level, int centerChunkX, int centerChunkZ, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
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
                int baseSectionY = chunk.getMinY() >> 4;
                int sectionCount = chunk.getSections().length;
                if (sectionCount <= 0) {
                    continue;
                }
                short[] blocks = reader.read(chunk, baseSectionY, sectionCount);
                int status = bridge.submitChunk(cx, cz, baseSectionY, blocks);
                // Any accepted code (new, mesh/remesh requested, or already in flight)
                // means the chunk is now tracked by the engine; remember it so we do not
                // resubmit every tick. Transient rejections are retried next pass.
                if (accepted(status)) {
                    addResident(key);
                    signatures.put(key, BlockSignature.of(blocks));
                }
            }
        }
    }

    /**
     * Re-signature up to {@link Config#dirtyBudget()} resident chunks this tick and
     * re-submit the ones whose blocks changed. The signature fold allocates nothing, so
     * a recheck is one section walk with no column array; only genuinely changed chunks
     * pay for a fresh {@code read} on the resubmit path.
     */
    private void recheckDirty(ClientLevel level) {
        int n = residentOrder.size();
        if (n == 0 || config.dirtyBudget() <= 0) {
            return;
        }
        int budget = Math.min(config.dirtyBudget(), n);
        int start = dirtyCursor % n;
        for (int k = 0; k < budget; k++) {
            long key = residentOrder.get((start + k) % n);
            int cx = (int) (key << 32 >> 32);
            int cz = (int) (key >> 32);
            if (!level.hasChunk(cx, cz)) {
                continue;
            }
            ChunkAccess chunk = level.getChunk(cx, cz);
            if (chunk == null) {
                continue;
            }
            int baseSectionY = chunk.getMinY() >> 4;
            int sectionCount = chunk.getSections().length;
            if (sectionCount <= 0) {
                continue;
            }
            long signature = reader.signature(chunk, baseSectionY, sectionCount);
            Long previous = signatures.get(key);
            if (previous == null) {
                signatures.put(key, signature);
                continue;
            }
            if (previous.longValue() == signature) {
                continue;
            }
            // Content changed: hand the new data over; Rust replaces the stored sections
            // and remeshes this chunk plus its loaded horizontal neighbours.
            int status = bridge.submitChunk(cx, cz, baseSectionY,
                    reader.read(chunk, baseSectionY, sectionCount));
            if (accepted(status)) {
                signatures.put(key, signature);
            }
        }
        dirtyCursor = (start + budget) % n;
    }

    // --- render frame: interpolated live-FOV camera + one engine frame -----

    private void onRenderFrame() {
        if (!ready || failed) {
            return;
        }
        Entity camera = minecraft.getCameraEntity();
        if (minecraft.level == null || camera == null) {
            return;
        }
        // Sub-tick interpolation so motion looks smooth at render rate rather than snapping
        // to the 20 Hz tick, matching how vanilla positions the camera.
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eyePos = camera.getEyePosition(partialTick);
        int radius = lastRadius > 0 ? lastRadius : currentRadius();
        float far = WorldHostMath.farPlane(radius, config.minFarBlocks());
        float fov = WorldHostMath.clampFov(minecraft.options.fov().get());
        try {
            float[] viewProj = WorldCamera.perspectiveViewProj(
                    eyePos.x, eyePos.y, eyePos.z,
                    camera.getViewYRot(partialTick), camera.getViewXRot(partialTick),
                    fov,
                    (float) config.targetWidth() / (float) config.targetHeight(),
                    0.1f, far);
            float[] eye = {(float) eyePos.x, (float) eyePos.y, (float) eyePos.z};
            JpeWorldNative.FrameStats last = bridge.frame(viewProj, eye);
            if (presenting) {
                // The engine already drew into the context's default framebuffer; flush so
                // the driver hands the finished frame over, then Minecraft swaps it. A
                // negative code means the backend dropped its surface; stop presenting and
                // fall back to offscreen rather than failing the whole host.
                int present = bridge.present();
                if (present < 0) {
                    presenting = false;
                    presentUnsupported = true;
                    LOGGER.warn("on-screen present failed (code {}); continuing offscreen-only", present);
                }
            }
            if (++framesRun % config.logEveryFrames() == 0) {
                LOGGER.info("world host frame={} radius={} residentChunks={} resident={} visible={} "
                                + "culled={} batches={} terrain={} mesh={} remesh={} fov={}",
                        framesRun, radius, submitted.size(), last.resident(), last.visible(),
                        last.culled(), last.batches(), last.terrainJobs(), last.meshJobs(),
                        last.remeshChunks(), fov);
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

    private static boolean accepted(int status) {
        return status == JpeWorldNative.SUBMIT_NEW
                || status == JpeWorldNative.SUBMIT_MESH_REQUESTED
                || status == JpeWorldNative.SUBMIT_REMESH_REQUESTED
                || status == JpeWorldNative.SUBMIT_IN_FLIGHT;
    }

    private static long pack(int x, int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    private void addResident(long key) {
        if (submitted.add(key)) {
            residentOrder.add(key);
        }
    }

    /** Drop submitted keys outside {@code keep} so re-approach resubmits (and free signatures). */
    private void pruneSubmitted(int centerChunkX, int centerChunkZ, int keep) {
        ArrayList<Long> kept = new ArrayList<>(residentOrder.size());
        submitted.clear();
        for (long key : residentOrder) {
            int cx = (int) (key << 32 >> 32);
            int cz = (int) (key >> 32);
            if (Math.max(Math.abs(cx - centerChunkX), Math.abs(cz - centerChunkZ)) > keep) {
                signatures.remove(key);
            } else {
                kept.add(key);
                submitted.add(key);
            }
        }
        residentOrder.clear();
        residentOrder.addAll(kept);
        dirtyCursor = 0;
    }
}