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

    public record Job(long id, int kind, int priority, int chunkX, int chunkZ, long epoch,
                      long distanceSq) {}

    public record Stats(long submitted, long started, long completed, long staleDiscarded,
                        long rejectedFull, long evicted, long epochBumps) {}

    public record Mesh(int vertexCount, int quadCount, float firstVertexX) {}

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
