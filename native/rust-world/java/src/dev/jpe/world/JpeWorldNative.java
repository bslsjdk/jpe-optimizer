package dev.jpe.world;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Java 25 FFM binding to the Rust world engine ({@code libjpe_world}).
 *
 * <p>This is the temporary integration path for this phase: Java calls the
 * Rust scheduler and mesher directly, with no C++ involvement. Status codes are
 * the ones documented on the Rust side in {@code src/ffi.rs}.
 *
 * <p>The library is opened from an explicit path so the same class works in a
 * NeoForge dev run, in CI, or from a standalone harness with no mod installed.
 */
public final class JpeWorldNative implements AutoCloseable {

    /** Chunk state codes, mirroring {@code ChunkState} in Rust. */
    public static final int STATE_EMPTY = 0;
    public static final int STATE_TERRAIN_QUEUED = 1;
    public static final int STATE_TERRAIN_GENERATING = 2;
    public static final int STATE_TERRAIN_READY = 3;
    public static final int STATE_MESH_QUEUED = 4;
    public static final int STATE_MESHING = 5;
    public static final int STATE_MESH_READY = 6;
    public static final int STATE_UPLOADED = 7;
    public static final int STATE_UNLOADING = 8;

    /** Job kind codes. */
    public static final int KIND_GENERATE_TERRAIN = 0;
    public static final int KIND_BUILD_MESH = 1;
    public static final int KIND_UPLOAD_MESH = 2;
    public static final int KIND_UNLOAD = 3;

    /** Completion codes. */
    public static final int COMPLETE_COMMITTED = 0;
    public static final int COMPLETE_STALE = 1;
    public static final int COMPLETE_MISSING = 2;

    /** Renderer backend selector codes, mirroring {@code src/ffi.rs}. */
    public static final int BACKEND_AUTO = 0;
    public static final int BACKEND_VULKAN = 1;
    public static final int BACKEND_GL = 2;
    public static final int BACKEND_GLES = 3;

    /** Renderer status codes (negative = failure). */
    public static final int RENDER_OK = 0;
    public static final int RENDER_UNKNOWN_HANDLE = -1;
    public static final int RENDER_INVALID_ARGUMENT = -2;
    public static final int RENDER_UNAVAILABLE = -3;
    public static final int RENDER_INVALID_MESH = -4;
    public static final int RENDER_API_ERROR = -5;
    public static final int RENDER_NO_FRAME = -6;
    public static final int RENDER_WRONG_THREAD = -7;

    public record Job(long id, int kind, int priority, int chunkX, int chunkZ, long epoch,
                      long distanceSq) {}

    public record Stats(long submitted, long started, long completed, long staleDiscarded,
                        long rejectedFull, long evicted, long epochBumps) {}

    public record Mesh(int vertexCount, int quadCount, float firstVertexX) {}

    /** Native renderer capabilities. {@code backend} is one of the BACKEND_* codes. */
    public record RendererCaps(int backend, int apiMajor, int apiMinor, int maxTextureSize,
                               boolean supportsCompute) {}

    /** A mesh uploaded to the renderer, plus the index count to draw it. */
    public record MeshUpload(long mesh, int indexCount) {}

    private final Arena arena = Arena.ofShared();

    private final MethodHandle hAbiVersion;
    private final MethodHandle hMaxQuads;
    private final MethodHandle hCreate;
    private final MethodHandle hDestroy;
    private final MethodHandle hSetPlayer;
    private final MethodHandle hRequestTerrain;
    private final MethodHandle hRequestMesh;
    private final MethodHandle hTakeJob;
    private final MethodHandle hComplete;
    private final MethodHandle hMarkUploaded;
    private final MethodHandle hUnload;
    private final MethodHandle hChunkState;
    private final MethodHandle hStats;
    private final MethodHandle hReset;
    private final MethodHandle hMeshSection;

    private final MethodHandle hRendererCreate;
    private final MethodHandle hRendererAvailable;
    private final MethodHandle hRendererDestroy;
    private final MethodHandle hRendererCaps;
    private final MethodHandle hRendererBeginFrame;
    private final MethodHandle hRendererUploadSection;
    private final MethodHandle hRendererDestroyMesh;
    private final MethodHandle hRendererDraw;
    private final MethodHandle hRendererEndFrame;
    private final MethodHandle hRendererReadPixels;

    public JpeWorldNative(Path library) {
        SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
        Linker linker = Linker.nativeLinker();
        ValueLayout.OfInt i32 = ValueLayout.JAVA_INT;
        ValueLayout.OfLong i64 = ValueLayout.JAVA_LONG;
        var ptr = ValueLayout.ADDRESS;

        this.hAbiVersion = down(linker, lookup, "jpe_world_abi_version",
                FunctionDescriptor.of(i32));
        this.hMaxQuads = down(linker, lookup, "jpe_world_max_section_quads",
                FunctionDescriptor.of(i32));
        this.hCreate = down(linker, lookup, "jpe_world_create",
                FunctionDescriptor.of(i64, i64, i32));
        this.hDestroy = down(linker, lookup, "jpe_world_destroy",
                FunctionDescriptor.of(i32, i64));
        this.hSetPlayer = down(linker, lookup, "jpe_world_set_player",
                FunctionDescriptor.of(i32, i64, i32, i32));
        this.hRequestTerrain = down(linker, lookup, "jpe_world_request_terrain",
                FunctionDescriptor.of(i32, i64, i32, i32, ptr));
        this.hRequestMesh = down(linker, lookup, "jpe_world_request_mesh",
                FunctionDescriptor.of(i32, i64, i32, i32, ptr));
        this.hTakeJob = down(linker, lookup, "jpe_world_take_job",
                FunctionDescriptor.of(i32, i64, ptr, ptr, ptr, ptr, ptr, ptr, ptr));
        this.hComplete = down(linker, lookup, "jpe_world_complete",
                FunctionDescriptor.of(i32, i64, i64, i32, i32, i32));
        this.hMarkUploaded = down(linker, lookup, "jpe_world_mark_uploaded",
                FunctionDescriptor.of(i32, i64, i32, i32));
        this.hUnload = down(linker, lookup, "jpe_world_unload",
                FunctionDescriptor.of(i32, i64, i32, i32));
        this.hChunkState = down(linker, lookup, "jpe_world_chunk_state",
                FunctionDescriptor.of(i32, i64, i32, i32));
        this.hStats = down(linker, lookup, "jpe_world_stats",
                FunctionDescriptor.of(i32, i64, ptr));
        this.hReset = down(linker, lookup, "jpe_world_reset",
                FunctionDescriptor.of(i32, i64, ptr));
        this.hMeshSection = down(linker, lookup, "jpe_world_mesh_section",
                FunctionDescriptor.of(i64, ptr, i32, i32, i32, ptr, i64, ptr, i64, ptr));

        this.hRendererCreate = down(linker, lookup, "jpe_world_renderer_create",
                FunctionDescriptor.of(i64, i32));
        this.hRendererAvailable = down(linker, lookup, "jpe_world_renderer_available",
                FunctionDescriptor.of(i32, i32));
        this.hRendererDestroy = down(linker, lookup, "jpe_world_renderer_destroy",
                FunctionDescriptor.of(i32, i64));
        this.hRendererCaps = down(linker, lookup, "jpe_world_renderer_capabilities",
                FunctionDescriptor.of(i32, i64, ptr));
        this.hRendererBeginFrame = down(linker, lookup, "jpe_world_renderer_begin_frame",
                FunctionDescriptor.of(i32, i64, i32, i32));
        this.hRendererUploadSection = down(linker, lookup, "jpe_world_renderer_upload_section",
                FunctionDescriptor.of(i32, i64, ptr, i32, i32, i32, ptr, ptr));
        this.hRendererDestroyMesh = down(linker, lookup, "jpe_world_renderer_destroy_mesh",
                FunctionDescriptor.of(i32, i64, i64));
        this.hRendererDraw = down(linker, lookup, "jpe_world_renderer_draw",
                FunctionDescriptor.of(i32, i64, i64, i32, i32));
        this.hRendererEndFrame = down(linker, lookup, "jpe_world_renderer_end_frame",
                FunctionDescriptor.of(i32, i64));
        this.hRendererReadPixels = down(linker, lookup, "jpe_world_renderer_read_pixels",
                FunctionDescriptor.of(i32, i64, ptr, i64, ptr, ptr));
    }

    private static MethodHandle down(Linker linker, SymbolLookup lookup, String name,
                                     FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("missing native symbol: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    public int abiVersion() {
        return invokeInt(hAbiVersion);
    }

    public int maxSectionQuads() {
        return invokeInt(hMaxQuads);
    }

    /** Allocate a scheduler. Returns a non-zero handle. */
    public long create(long worldEpoch, int queueCapacity) {
        return invokeLong(hCreate, worldEpoch, queueCapacity);
    }

    public int destroy(long handle) {
        return invokeInt(hDestroy, handle);
    }

    public int setPlayer(long handle, int chunkX, int chunkZ) {
        return invokeInt(hSetPlayer, handle, chunkX, chunkZ);
    }

    public long requestTerrain(long handle, int chunkX, int chunkZ) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            int status = invokeInt(hRequestTerrain, handle, chunkX, chunkZ, out);
            if (status != 0) {
                throw new IllegalStateException("requestTerrain(" + chunkX + "," + chunkZ
                        + ") failed: status " + status);
            }
            return out.get(ValueLayout.JAVA_LONG, 0);
        }
    }

    public long requestMesh(long handle, int chunkX, int chunkZ) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            int status = invokeInt(hRequestMesh, handle, chunkX, chunkZ, out);
            if (status != 0) {
                throw new IllegalStateException("requestMesh(" + chunkX + "," + chunkZ
                        + ") failed: status " + status);
            }
            return out.get(ValueLayout.JAVA_LONG, 0);
        }
    }

    /** Pop the next job, or {@code null} if the queue is empty. */
    public Job takeJob(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(64);
            int status = invokeInt(hTakeJob, handle,
                    buf.asSlice(0, 8),   // id u64
                    buf.asSlice(8, 4),   // kind u32
                    buf.asSlice(12, 4),  // priority u32
                    buf.asSlice(16, 4),  // chunkX i32
                    buf.asSlice(20, 4),  // chunkZ i32
                    buf.asSlice(24, 8),  // epoch u64
                    buf.asSlice(32, 8)); // distance u64
            if (status == 0) {
                return null;
            }
            if (status != 1) {
                throw new IllegalStateException("takeJob failed: status " + status);
            }
            return new Job(
                    buf.get(ValueLayout.JAVA_LONG, 0),
                    buf.get(ValueLayout.JAVA_INT, 8),
                    buf.get(ValueLayout.JAVA_INT, 12),
                    buf.get(ValueLayout.JAVA_INT, 16),
                    buf.get(ValueLayout.JAVA_INT, 20),
                    buf.get(ValueLayout.JAVA_LONG, 24),
                    buf.get(ValueLayout.JAVA_LONG, 32));
        }
    }

    public int complete(long handle, long epoch, int kind, int chunkX, int chunkZ) {
        return invokeInt(hComplete, handle, epoch, kind, chunkX, chunkZ);
    }

    public int markUploaded(long handle, int chunkX, int chunkZ) {
        return invokeInt(hMarkUploaded, handle, chunkX, chunkZ);
    }

    public int unload(long handle, int chunkX, int chunkZ) {
        return invokeInt(hUnload, handle, chunkX, chunkZ);
    }

    public int chunkState(long handle, int chunkX, int chunkZ) {
        return invokeInt(hChunkState, handle, chunkX, chunkZ);
    }

    public Stats stats(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(56);
            int status = invokeInt(hStats, handle, buf);
            if (status != 0) {
                throw new IllegalStateException("stats failed: status " + status);
            }
            return new Stats(
                    buf.get(ValueLayout.JAVA_LONG, 0),
                    buf.get(ValueLayout.JAVA_LONG, 8),
                    buf.get(ValueLayout.JAVA_LONG, 16),
                    buf.get(ValueLayout.JAVA_LONG, 24),
                    buf.get(ValueLayout.JAVA_LONG, 32),
                    buf.get(ValueLayout.JAVA_LONG, 40),
                    buf.get(ValueLayout.JAVA_LONG, 48));
        }
    }

    /** Start a new world epoch; returns the new epoch. */
    public long reset(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            int status = invokeInt(hReset, handle, out);
            if (status != 0) {
                throw new IllegalStateException("reset failed: status " + status);
            }
            return out.get(ValueLayout.JAVA_LONG, 0);
        }
    }

    /**
     * Build a section mesh from 4096 block ids indexed {@code (y<<8)|(z<<4)|x}.
     * Returns the vertex count, quad count and the first vertex X for a cheap
     * sanity check.
     */
    public Mesh meshSection(short[] blocks, int originX, int originY, int originZ) {
        if (blocks.length != 4096) {
            throw new IllegalArgumentException("expected 4096 block ids, got " + blocks.length);
        }
        int maxQuads = maxSectionQuads();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment section = a.allocate(4096L * 2);
            for (int i = 0; i < blocks.length; i++) {
                section.setAtIndex(ValueLayout.JAVA_SHORT, i, blocks[i]);
            }
            long vertexCapacity = (long) maxQuads * 4 * 32;
            long indexCapacity = (long) maxQuads * 6 * 4;
            MemorySegment vertices = a.allocate(vertexCapacity);
            MemorySegment indices = a.allocate(indexCapacity);
            MemorySegment quadOut = a.allocate(ValueLayout.JAVA_INT);

            long vertexCount = invokeLong(hMeshSection, section, originX, originY, originZ,
                    vertices, vertexCapacity, indices, indexCapacity, quadOut);
            if (vertexCount < 0) {
                throw new IllegalStateException("meshSection failed: status " + vertexCount);
            }
            float firstX = vertexCount == 0 ? 0.0f : vertices.get(ValueLayout.JAVA_FLOAT, 0);
            return new Mesh((int) vertexCount, quadOut.get(ValueLayout.JAVA_INT, 0), firstX);
        }
    }

    // ------------------------------------------------------------------
    // Renderer (native GPU backends)
    // ------------------------------------------------------------------

    /**
     * Create a renderer for {@code backend} (use {@link #BACKEND_AUTO} to take
     * the best available one). Returns 0 when that backend cannot run here.
     *
     * <p>A renderer is thread-affine: every later call must come from the
     * thread that created it (the render thread in production).
     */
    public long rendererCreate(int backend) {
        return invokeLong(hRendererCreate, backend);
    }

    /** 1 if the backend can be created here, else 0. */
    public int rendererAvailable(int backend) {
        return invokeInt(hRendererAvailable, backend);
    }

    public int rendererDestroy(long handle) {
        return invokeInt(hRendererDestroy, handle);
    }

    public RendererCaps rendererCapabilities(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(24);
            int status = invokeInt(hRendererCaps, handle, buf);
            if (status != 0) {
                throw new IllegalStateException("rendererCapabilities failed: status " + status);
            }
            return new RendererCaps(
                    buf.get(ValueLayout.JAVA_INT, 0),
                    buf.get(ValueLayout.JAVA_INT, 4),
                    buf.get(ValueLayout.JAVA_INT, 8),
                    buf.get(ValueLayout.JAVA_INT, 12),
                    buf.get(ValueLayout.JAVA_INT, 16) != 0);
        }
    }

    public int rendererBeginFrame(long handle, int width, int height) {
        return invokeInt(hRendererBeginFrame, handle, width, height);
    }

    /**
     * Mesh a 16^3 section (4096 block ids indexed {@code (y<<8)|(z<<4)|x}) and
     * upload it to the renderer in one call.
     */
    public MeshUpload rendererUploadSection(long handle, short[] blocks,
                                            int originX, int originY, int originZ) {
        if (blocks.length != 4096) {
            throw new IllegalArgumentException("expected 4096 block ids, got " + blocks.length);
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment section = a.allocate(4096L * 2);
            for (int i = 0; i < blocks.length; i++) {
                section.setAtIndex(ValueLayout.JAVA_SHORT, i, blocks[i]);
            }
            MemorySegment out = a.allocate(16);
            int status = invokeInt(hRendererUploadSection, handle, section,
                    originX, originY, originZ, out.asSlice(0, 8), out.asSlice(8, 4));
            if (status != 0) {
                throw new IllegalStateException("rendererUploadSection failed: status " + status);
            }
            return new MeshUpload(out.get(ValueLayout.JAVA_LONG, 0),
                    out.get(ValueLayout.JAVA_INT, 8));
        }
    }

    public int rendererDestroyMesh(long handle, long mesh) {
        return invokeInt(hRendererDestroyMesh, handle, mesh);
    }

    public int rendererDraw(long handle, long mesh, int indexCount, int instanceCount) {
        return invokeInt(hRendererDraw, handle, mesh, indexCount, instanceCount);
    }

    public int rendererEndFrame(long handle) {
        return invokeInt(hRendererEndFrame, handle);
    }

    /**
     * Read the last completed frame back as tightly packed RGBA8 with a
     * top-left origin.
     */
    public byte[] rendererReadPixels(long handle, int width, int height) {
        int size = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment dst = a.allocate(size);
            MemorySegment dims = a.allocate(8);
            int status = invokeInt(hRendererReadPixels, handle, dst, (long) size,
                    dims.asSlice(0, 4), dims.asSlice(4, 4));
            if (status != 0) {
                throw new IllegalStateException("rendererReadPixels failed: status " + status);
            }
            int w = dims.get(ValueLayout.JAVA_INT, 0);
            int h = dims.get(ValueLayout.JAVA_INT, 4);
            if (w != width || h != height) {
                throw new IllegalStateException("frame is " + w + "x" + h
                        + ", expected " + width + "x" + height);
            }
            return dst.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    /**
     * Unload the native module.
     *
     * <p>Only safe if no GPU backend has been created through this instance:
     * after a driver is initialised, Mesa keeps process-wide teardown state and
     * unloading the module makes the JVM crash on exit. Production keeps the
     * library loaded for the lifetime of the game.
     */
    @Override
    public void close() {
        arena.close();
    }

    private static int invokeInt(MethodHandle mh, Object... args) {
        try {
            return (int) mh.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("native call failed", t);
        }
    }

    private static long invokeLong(MethodHandle mh, Object... args) {
        try {
            return (long) mh.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("native call failed", t);
        }
    }
}
