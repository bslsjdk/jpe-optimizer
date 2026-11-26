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
    public static final int KIND_REMESH_MESH = 4;

    /** Pipeline submit-result codes, mirroring {@code Submit} in Rust. */
    public static final int SUBMIT_NEW = 0;
    public static final int SUBMIT_MESH_REQUESTED = 1;
    public static final int SUBMIT_REMESH_REQUESTED = 2;
    public static final int SUBMIT_IN_FLIGHT = 3;

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

    /** A section the visibility pass may draw; mirrors {@code JpeRenderSection}. */
    public record RenderSection(long key, long mesh, int originX, int originY, int originZ,
                                int indexCount) {}

    /** Outcome of a visibility pass; mirrors {@code JpeRenderPlanStats}. */
    public record PlanStats(int considered, int visible, int culled, long visibleQuads,
                            long culledQuads) {}

    /** A mesh uploaded to the renderer, plus the index count to draw it. */
    public record MeshUpload(long mesh, int indexCount) {}

    /**
     * Pipeline per-frame budgets. Mirrors the 32-byte {@code JpePipelineConfig}.
     * A zero field means "use the Rust default", so callers only set what they
     * care about.
     */
    public record PipelineConfig(int terrainBudget, int meshBudget, int remeshBudget,
                                 int threads, int sectionYMin, int sectionYMax,
                                 int width, int height) {
        public static PipelineConfig defaults() {
            return new PipelineConfig(0, 0, 0, 0, 0, -1, 0, 0);
        }
    }

    /** Outcome of one {@code jpe_pipeline_frame}; mirrors the 88-byte struct. */
    public record FrameStats(long terrainJobs, long meshJobs, long remeshChunks, long published,
                             long replaced, long destroyed, long resident, long batches,
                             long considered, long visible, long culled) {}

    /** Lifetime pipeline counters; mirrors the 128-byte {@code JpePipelineStats}. */
    public record PipelineStats(long frames, long terrainJobs, long meshJobs, long remeshChunks,
                                long published, long replaced, long emptySections,
                                long meshesDestroyed, long chunksUnloaded, long sectionsUnloaded,
                                long cachedSections, long pendingMesh, long pendingRemesh,
                                long residentSections, long sourceSections,
                                long sourceMissingReads) {}

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
    private final MethodHandle hRendererSetCamera;
    private final MethodHandle hCameraOrtho;
    private final MethodHandle hRendererBeginFrame;
    private final MethodHandle hRendererUploadSection;
    private final MethodHandle hRendererDestroyMesh;
    private final MethodHandle hRendererDraw;
    private final MethodHandle hRendererDrawVisible;
    private final MethodHandle hRendererEndFrame;
    private final MethodHandle hRendererReadPixels;

    private final MethodHandle hPipelineCreate;
    private final MethodHandle hPipelineDestroy;
    private final MethodHandle hPipelineSubmitChunk;
    private final MethodHandle hPipelineFrame;
    private final MethodHandle hPipelineUnloadOutside;
    private final MethodHandle hPipelineSetPlayer;
    private final MethodHandle hPipelineTicket;
    private final MethodHandle hPipelineStats;
    private final MethodHandle hPipelineResidentKeys;
    private final MethodHandle hPipelineReset;

    public JpeWorldNative(Path library) {
        SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
        Linker linker = Linker.nativeLinker();
        ValueLayout.OfInt i32 = ValueLayout.JAVA_INT;
        ValueLayout.OfLong i64 = ValueLayout.JAVA_LONG;
        ValueLayout.OfFloat f32 = ValueLayout.JAVA_FLOAT;
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
        this.hRendererSetCamera = down(linker, lookup, "jpe_world_renderer_set_camera",
                FunctionDescriptor.of(i32, i64, ptr));
        this.hCameraOrtho = down(linker, lookup, "jpe_world_camera_ortho",
                FunctionDescriptor.of(i32, ptr, ptr, ptr, f32, f32, f32, f32, ptr));
        this.hRendererBeginFrame = down(linker, lookup, "jpe_world_renderer_begin_frame",
                FunctionDescriptor.of(i32, i64, i32, i32));
        this.hRendererUploadSection = down(linker, lookup, "jpe_world_renderer_upload_section",
                FunctionDescriptor.of(i32, i64, ptr, i32, i32, i32, ptr, ptr));
        this.hRendererDestroyMesh = down(linker, lookup, "jpe_world_renderer_destroy_mesh",
                FunctionDescriptor.of(i32, i64, i64));
        this.hRendererDraw = down(linker, lookup, "jpe_world_renderer_draw",
                FunctionDescriptor.of(i32, i64, i64, i32, i32));
        this.hRendererDrawVisible = down(linker, lookup, "jpe_world_renderer_draw_visible",
                FunctionDescriptor.of(i32, i64, ptr, ptr, ptr, i64, ptr));
        this.hRendererEndFrame = down(linker, lookup, "jpe_world_renderer_end_frame",
                FunctionDescriptor.of(i32, i64));
        this.hRendererReadPixels = down(linker, lookup, "jpe_world_renderer_read_pixels",
                FunctionDescriptor.of(i32, i64, ptr, i64, ptr, ptr));

        this.hPipelineCreate = down(linker, lookup, "jpe_pipeline_create",
                FunctionDescriptor.of(i64, i64, i64, i32, ptr));
        this.hPipelineDestroy = down(linker, lookup, "jpe_pipeline_destroy",
                FunctionDescriptor.of(i32, i64));
        this.hPipelineSubmitChunk = down(linker, lookup, "jpe_pipeline_submit_chunk",
                FunctionDescriptor.of(i32, i64, i32, i32, i32, i32, ptr));
        this.hPipelineFrame = down(linker, lookup, "jpe_pipeline_frame",
                FunctionDescriptor.of(i32, i64, ptr, ptr, ptr));
        this.hPipelineUnloadOutside = down(linker, lookup, "jpe_pipeline_unload_outside",
                FunctionDescriptor.of(i32, i64, i32, i32, i32, ptr));
        this.hPipelineSetPlayer = down(linker, lookup, "jpe_pipeline_set_player",
                FunctionDescriptor.of(i32, i64, i32, i32));
        this.hPipelineTicket = down(linker, lookup, "jpe_pipeline_ticket",
                FunctionDescriptor.of(i32, i64, i32, i32, i32));
        this.hPipelineStats = down(linker, lookup, "jpe_pipeline_stats",
                FunctionDescriptor.of(i32, i64, ptr));
        this.hPipelineResidentKeys = down(linker, lookup, "jpe_pipeline_resident_keys",
                FunctionDescriptor.of(i32, i64, ptr, i32));
        this.hPipelineReset = down(linker, lookup, "jpe_pipeline_reset",
                FunctionDescriptor.of(i32, i64, ptr));
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
     * Set the column-major view-projection matrix ({@code m.length == 16}) used
     * by every draw after this call.
     *
     * <p>The matrix uses the Vulkan 0..1 depth range on all three backends, so
     * callers do not need a per-API variant. Status codes as documented in
     * {@code src/ffi.rs}: {@link #RENDER_OK}, {@link #RENDER_UNKNOWN_HANDLE},
     * {@link #RENDER_INVALID_ARGUMENT} (null/non-finite), {@link #RENDER_API_ERROR},
     * {@link #RENDER_NO_FRAME} (backend has no camera) and
     * {@link #RENDER_WRONG_THREAD}.
     */
    public int rendererSetCamera(long handle, float[] m) {
        if (m == null || m.length != 16) {
            throw new IllegalArgumentException("expected a 4x4 matrix (16 floats)");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment src = a.allocate(64);
            for (int i = 0; i < 16; i++) {
                src.setAtIndex(ValueLayout.JAVA_FLOAT, i, m[i]);
            }
            return invokeInt(hRendererSetCamera, handle, src);
        }
    }

    /**
     * Build an orthographic look-at view-projection matrix in Rust, so no Java
     * code has to keep its own copy of the projection maths.
     *
     * <p>Returns the 16 column-major floats, or {@code null} when the inputs are
     * degenerate (zero-length basis vectors, non-positive extents, far &lt;= near).
     */
    public float[] cameraOrtho(float[] eye, float[] target, float[] up,
                               float halfWidth, float halfHeight, float near, float far) {
        if (eye == null || eye.length != 3 || target == null || target.length != 3
                || up == null || up.length != 3) {
            throw new IllegalArgumentException("eye, target and up each need 3 floats");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment e = floats3(a, eye);
            MemorySegment t = floats3(a, target);
            MemorySegment u = floats3(a, up);
            MemorySegment out = a.allocate(64);
            int status = invokeInt(hCameraOrtho, e, t, u, halfWidth, halfHeight, near, far, out);
            // -4 means the inputs do not define a camera (degenerate basis,
            // non-positive extents, far <= near); anything else is a contract bug.
            if (status == RENDER_INVALID_MESH) {
                return null;
            }
            if (status != RENDER_OK) {
                throw new IllegalStateException("cameraOrtho failed: status " + status);
            }
            float[] m = new float[16];
            for (int i = 0; i < 16; i++) {
                m[i] = out.getAtIndex(ValueLayout.JAVA_FLOAT, i);
            }
            return m;
        }
    }

    private static MemorySegment floats(Arena a, float[] v) {
        MemorySegment seg = a.allocate(4L * v.length);
        for (int i = 0; i < v.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, v[i]);
        }
        return seg;
    }

    /** Pack records into the 32-byte {@code JpeRenderSection} layout. */
    private static MemorySegment sections(Arena a, RenderSection[] records) {
        MemorySegment seg = a.allocate(32L * records.length);
        for (int i = 0; i < records.length; i++) {
            RenderSection r = records[i];
            long base = 32L * i;
            seg.set(ValueLayout.JAVA_LONG, base, r.key());
            seg.set(ValueLayout.JAVA_LONG, base + 8, r.mesh());
            seg.set(ValueLayout.JAVA_INT, base + 16, r.originX());
            seg.set(ValueLayout.JAVA_INT, base + 20, r.originY());
            seg.set(ValueLayout.JAVA_INT, base + 24, r.originZ());
            seg.set(ValueLayout.JAVA_INT, base + 28, r.indexCount());
        }
        return seg;
    }

    private static MemorySegment floats3(Arena a, float[] v) {
        MemorySegment seg = a.allocate(16);
        for (int i = 0; i < 3; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, v[i]);
        }
        return seg;
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

    /**
     * Cull {@code sections} against {@code viewProj} seen from {@code eye} and
     * queue only the visible batches on the already-begun frame. The same matrix
     * reaches the GPU, so culling and transforms cannot disagree.
     *
     * <p>Throws on any non-zero status; use {@link #rendererTryDrawVisible} when
     * the status itself is what is being checked.
     */
    public PlanStats rendererDrawVisible(long handle, float[] viewProj, float[] eye,
                                         RenderSection[] sections) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(32);
            int status = invokeInt(hRendererDrawVisible, handle, floats(a, viewProj),
                    floats(a, eye), sections(a, sections), (long) sections.length, out);
            if (status != RENDER_OK) {
                throw new IllegalStateException("rendererDrawVisible failed: status " + status);
            }
            return new PlanStats(
                    out.get(ValueLayout.JAVA_INT, 0),
                    out.get(ValueLayout.JAVA_INT, 4),
                    out.get(ValueLayout.JAVA_INT, 8),
                    out.get(ValueLayout.JAVA_LONG, 16),
                    out.get(ValueLayout.JAVA_LONG, 24));
        }
    }

    /**
     * Same call without reading stats, for contract checks. A {@code null}
     * {@code viewProj} or {@code eye} passes a null pointer straight through so the
     * ABI can prove it rejects one; a {@code null} section array is sent as an
     * empty plan (null pointer, count 0), which is legal.
     */
    public int rendererTryDrawVisible(long handle, float[] viewProj, float[] eye,
                                      RenderSection[] sections) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vp = viewProj == null ? MemorySegment.NULL : floats(a, viewProj);
            MemorySegment ev = eye == null ? MemorySegment.NULL : floats(a, eye);
            int count = sections == null ? 0 : sections.length;
            MemorySegment recs = count == 0 ? MemorySegment.NULL : sections(a, sections);
            return invokeInt(hRendererDrawVisible, handle, vp, ev, recs, (long) count,
                    MemorySegment.NULL);
        }
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

    // -----------------------------------------------------------------------
    // World pipeline (ABI v2): chunk lifecycle, GPU section registry, frame loop
    // -----------------------------------------------------------------------

    /**
     * Create a pipeline that draws through {@code renderer} (a handle from
     * {@link #rendererCreate}). Must be called on the thread that owns the
     * renderer, because that thread holds the GPU context.
     *
     * @return a non-zero handle, or 0 when {@code config} is invalid, the
     *         renderer handle is unknown, or the renderer belongs to another thread
     */
    public long pipelineCreate(long renderer, long worldEpoch, int queueCapacity,
                               PipelineConfig config) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(32);
            buf.set(ValueLayout.JAVA_INT, 0, config.terrainBudget());
            buf.set(ValueLayout.JAVA_INT, 4, config.meshBudget());
            buf.set(ValueLayout.JAVA_INT, 8, config.remeshBudget());
            buf.set(ValueLayout.JAVA_INT, 12, config.threads());
            buf.set(ValueLayout.JAVA_INT, 16, config.sectionYMin());
            buf.set(ValueLayout.JAVA_INT, 20, config.sectionYMax());
            buf.set(ValueLayout.JAVA_INT, 24, config.width());
            buf.set(ValueLayout.JAVA_INT, 28, config.height());
            return invokeLong(hPipelineCreate, renderer, worldEpoch, queueCapacity, buf);
        }
    }

    public int pipelineDestroy(long handle) {
        return invokeInt(hPipelineDestroy, handle);
    }

    /**
     * Hand one chunk of finished block data to the world engine: {@code blocks}
     * is {@code sectionCount * 4096} block ids in section order from
     * {@code baseSectionY} upwards, each indexed {@code (y<<8)|(z<<4)|x}.
     *
     * <p>Terrain generation is not part of this call. The Rust side takes over
     * scheduling, meshing, GPU upload, visibility and culling.
     *
     * @return one of the SUBMIT_* codes
     */
    public int pipelineSubmitChunk(long handle, int chunkX, int chunkZ, int baseSectionY,
                                   short[] blocks) {
        if (blocks == null || blocks.length == 0 || blocks.length % 4096 != 0) {
            throw new IllegalArgumentException("expected a whole number of 4096-block sections");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment data = a.allocate(2L * blocks.length);
            for (int i = 0; i < blocks.length; i++) {
                data.setAtIndex(ValueLayout.JAVA_SHORT, i, blocks[i]);
            }
            return invokeInt(hPipelineSubmitChunk, handle, chunkX, chunkZ, baseSectionY,
                    blocks.length / 4096, data);
        }
    }

    /**
     * Contract check: the status is returned instead of thrown, and the pointer
     * can be forced to null. The buffer is always large enough for the requested
     * section count, so an out-of-range count is rejected on its own argument
     * rather than by reading past an undersized allocation.
     */
    public int pipelineSubmitChunk(long handle, int chunkX, int chunkZ, int baseSectionY,
                                   int sectionCount, boolean nullBlocks) {
        try (Arena a = Arena.ofConfined()) {
            long bytes = nullBlocks ? 0L : 8192L * Math.max(1, Math.min(sectionCount, 24));
            MemorySegment data = nullBlocks ? MemorySegment.NULL : a.allocate(bytes);
            return invokeInt(hPipelineSubmitChunk, handle, chunkX, chunkZ, baseSectionY,
                    sectionCount, data);
        }
    }

    /**
     * Run one frame: pump terrain/mesh/remesh jobs within the budgets, upload and
     * publish geometry, cull the resident sections against {@code viewProj} and
     * draw the survivors through the pipeline's renderer.
     */
    public FrameStats pipelineFrame(long handle, float[] viewProj, float[] eye) {
        if (viewProj == null || viewProj.length != 16 || eye == null || eye.length != 3) {
            throw new IllegalArgumentException("expected a 4x4 matrix and a 3-float eye");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(88);
            int status = invokeInt(hPipelineFrame, handle, floats(a, viewProj), floats3(a, eye), out);
            if (status != RENDER_OK) {
                throw new IllegalStateException("pipelineFrame failed: status " + status);
            }
            return new FrameStats(
                    out.get(ValueLayout.JAVA_LONG, 0),
                    out.get(ValueLayout.JAVA_LONG, 8),
                    out.get(ValueLayout.JAVA_LONG, 16),
                    out.get(ValueLayout.JAVA_LONG, 24),
                    out.get(ValueLayout.JAVA_LONG, 32),
                    out.get(ValueLayout.JAVA_LONG, 40),
                    out.get(ValueLayout.JAVA_LONG, 48),
                    out.get(ValueLayout.JAVA_LONG, 56),
                    out.get(ValueLayout.JAVA_LONG, 64),
                    out.get(ValueLayout.JAVA_LONG, 72),
                    out.get(ValueLayout.JAVA_LONG, 80));
        }
    }

    /** Full call with a real output block, but the status returned, not thrown. */
    public int pipelineTryFrame(long handle, float[] viewProj, float[] eye) {
        try (Arena a = Arena.ofConfined()) {
            return invokeInt(hPipelineFrame, handle, floats(a, viewProj), floats3(a, eye),
                    a.allocate(88));
        }
    }

    public int pipelineFrameRaw(long handle, float[] viewProj, float[] eye) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vp = viewProj == null ? MemorySegment.NULL : floats(a, viewProj);
            MemorySegment ev = eye == null ? MemorySegment.NULL : floats3(a, eye);
            return invokeInt(hPipelineFrame, handle, vp, ev, MemorySegment.NULL);
        }
    }

    /** Unload every tracked chunk outside {@code radius}; returns chunks unloaded. */
    public long pipelineUnloadOutside(long handle, int centerX, int centerZ, int radius) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(4);
            int status = invokeInt(hPipelineUnloadOutside, handle, centerX, centerZ, radius, out);
            if (status != RENDER_OK) {
                throw new IllegalStateException("pipelineUnloadOutside failed: status " + status);
            }
            return Integer.toUnsignedLong(out.get(ValueLayout.JAVA_INT, 0));
        }
    }

    /** Status-only variant, for contract checks. */
    public int pipelineUnloadOutsideStatus(long handle, int centerX, int centerZ, int radius) {
        return invokeInt(hPipelineUnloadOutside, handle, centerX, centerZ, radius,
                MemorySegment.NULL);
    }

    /** Status-only variant: 0 means the stats block is readable. */
    public int pipelineStatsStatus(long handle) {
        try (Arena a = Arena.ofConfined()) {
            return invokeInt(hPipelineStats, handle, a.allocate(128));
        }
    }

    public int pipelineSetPlayer(long handle, int chunkX, int chunkZ) {
        return invokeInt(hPipelineSetPlayer, handle, chunkX, chunkZ);
    }

    /** Hold a chunk against unloading, or release one hold. Returns ticket count. */
    public int pipelineTicket(long handle, int chunkX, int chunkZ, boolean acquire) {
        return invokeInt(hPipelineTicket, handle, chunkX, chunkZ, acquire ? 1 : 0);
    }

    public PipelineStats pipelineStats(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(128);
            int status = invokeInt(hPipelineStats, handle, buf);
            if (status != RENDER_OK) {
                throw new IllegalStateException("pipelineStats failed: status " + status);
            }
            return new PipelineStats(
                    buf.get(ValueLayout.JAVA_LONG, 0),
                    buf.get(ValueLayout.JAVA_LONG, 8),
                    buf.get(ValueLayout.JAVA_LONG, 16),
                    buf.get(ValueLayout.JAVA_LONG, 24),
                    buf.get(ValueLayout.JAVA_LONG, 32),
                    buf.get(ValueLayout.JAVA_LONG, 40),
                    buf.get(ValueLayout.JAVA_LONG, 48),
                    buf.get(ValueLayout.JAVA_LONG, 56),
                    buf.get(ValueLayout.JAVA_LONG, 64),
                    buf.get(ValueLayout.JAVA_LONG, 72),
                    buf.get(ValueLayout.JAVA_LONG, 80),
                    buf.get(ValueLayout.JAVA_LONG, 88),
                    buf.get(ValueLayout.JAVA_LONG, 96),
                    buf.get(ValueLayout.JAVA_LONG, 104),
                    buf.get(ValueLayout.JAVA_LONG, 112),
                    buf.get(ValueLayout.JAVA_LONG, 120));
        }
    }

    /** Resident section keys (packed {@code SectionPos} longs), ascending. */
    public long[] pipelineResidentKeys(long handle, int max) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(8L * max);
            int written = invokeInt(hPipelineResidentKeys, handle, buf, max);
            if (written < 0) {
                throw new IllegalStateException("pipelineResidentKeys failed: status " + written);
            }
            long[] keys = new long[written];
            for (int i = 0; i < written; i++) {
                keys[i] = buf.getAtIndex(ValueLayout.JAVA_LONG, i);
            }
            return keys;
        }
    }

    /** Drop all GPU data and start a new world epoch. */
    public long pipelineReset(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(8);
            int status = invokeInt(hPipelineReset, handle, out);
            if (status != RENDER_OK) {
                throw new IllegalStateException("pipelineReset failed: status " + status);
            }
            return out.get(ValueLayout.JAVA_LONG, 0);
        }
    }

    /** Solid {@code sectionCount}-section block array helper for tests and demos. */
    public static short[] solidSections(int sectionCount, short block) {
        short[] blocks = new short[sectionCount * 4096];
        if (block != 0) {
            java.util.Arrays.fill(blocks, block);
        }
        return blocks;
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
