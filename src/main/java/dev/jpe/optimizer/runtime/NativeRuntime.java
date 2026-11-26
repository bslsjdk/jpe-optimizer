package dev.jpe.optimizer.runtime;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import dev.jpe.optimizer.mixin.GradientNoiseAccessor;
import dev.jpe.optimizer.mixin.NoiseStackLayerAccessor;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;
import dev.jpe.optimizer.mixin.SmearedPerlinNoiseAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import dev.jpe.optimizer.mixin.BlenderAccessor;
import dev.jpe.optimizer.mixin.BlendingDataAccessor;

/**
 * Optional Java 25 FFM bridge. Native worldgen is not loaded or enabled automatically.
 *
 * Async jobs borrow leased shared FFM buffers until terminal status and explicit release.
 * Completed jobs must be explicitly released before this runtime can be closed.
 */
public final class NativeRuntime implements AutoCloseable {
    public static final int EXPECTED_ABI_VERSION = 8;
    private static final long PERLIN_LAYER_SIZE = 72L;
    private static final MemoryLayout BLENDING_DATA_VIEW_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            MemoryLayout.sequenceLayout(16, ValueLayout.ADDRESS)
    );
    private static final long BLENDING_DATA_VIEW_SIZE = BLENDING_DATA_VIEW_LAYOUT.byteSize();
    private static final long BLENDING_DATA_POINTERS_OFFSET = 4L * ValueLayout.JAVA_INT.byteSize();
    private static final MemoryLayout DENSITY_NODE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_DOUBLE
    );

    public static final int JOB_COMPLETE = 0;
    public static final int JOB_PENDING = 1;
    public static final int JOB_RUNNING = 2;
    public static final int JOB_FAILED = 3;
    public static final int JOB_CANCELLED = 4;

    // Rust's scheduler owns process-lifetime worker threads, so the native library
    // must remain loaded until JVM exit even when an individual bridge is closed.
    private static final Arena LIBRARY_ARENA = Arena.global();
    private final MethodHandle configureWorkers;
    private final int workerCount;
    private final MethodHandle addF32;
    private final MethodHandle perlinAddVolume;
    private final MethodHandle perlinAddStackHandle;
    private final MethodHandle submitPerlinVolume;
    private final MethodHandle submitPerlinStack;
    private final MethodHandle jobStatus;
    private final MethodHandle waitJob;
    private final MethodHandle cancelJob;
    private final MethodHandle releaseJob;
    private final MethodHandle compileDensityGraph;
    private final MethodHandle evaluateDensityGraph;
    private final MethodHandle evaluateDensityGraphWithInputs;
    private final MethodHandle evaluateDensityGraphMultiWithInputs;
    private final MethodHandle releaseDensityGraph;
    private final MethodHandle loadDensityGraphCache;
    private final MethodHandle saveDensityGraphCache;
    private final MethodHandle blendDensityVolume;
    private final ConcurrentHashMap<Long, NativeJob> jobs = new ConcurrentHashMap<>();
    private final java.util.Set<NativeDensityGraph> densityGraphs = ConcurrentHashMap.newKeySet();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    // Keep one reusable FFM scratch arena per calling worldgen worker. Only
    // permutation and output buffers remain after axis-coordinate generation moved native-side.
    private final ConcurrentLinkedQueue<PerlinScratch> perlinScratchArenas = new ConcurrentLinkedQueue<>();
    // Reuses the node-wire buffer across graph compilations on each worldgen thread.
    private final ConcurrentLinkedQueue<DensityScratch> densityScratchArenas = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<DensityScratch> densityScratch = ThreadLocal.withInitial(() -> {
        DensityScratch scratch = new DensityScratch();
        densityScratchArenas.add(scratch);
        return scratch;
    });
    // Reuses Blender descriptors, copied legacy-density slabs, and volume buffers per worker.
    private final ConcurrentLinkedQueue<BlenderScratch> blenderScratchArenas = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<BlenderScratch> blenderScratch = ThreadLocal.withInitial(() -> {
        BlenderScratch scratch = new BlenderScratch();
        blenderScratchArenas.add(scratch);
        return scratch;
    });
    private final ThreadLocal<PerlinScratch> perlinScratch = ThreadLocal.withInitial(() -> {
        PerlinScratch scratch = new PerlinScratch();
        perlinScratchArenas.add(scratch);
        return scratch;
    });
    private static final LongAdder BLENDER_TIMED_CALLS = new LongAdder();
    private static final LongAdder BLENDER_CAPTURE_NANOS = new LongAdder();
    private static final LongAdder BLENDER_ENCODE_NANOS = new LongAdder();
    private static final LongAdder BLENDER_INPUT_COPY_NANOS = new LongAdder();
    private static final LongAdder BLENDER_NATIVE_NANOS = new LongAdder();
    private static final LongAdder BLENDER_OUTPUT_COPY_NANOS = new LongAdder();
    private static final LongAdder BLENDER_SNAPSHOT_CACHE_HITS = new LongAdder();
    private static final LongAdder BLENDER_SNAPSHOT_CACHE_MISSES = new LongAdder();
    private static final LongAdder BLENDER_SNAPSHOT_BYTES = new LongAdder();

    public static String blenderDensityMetrics() {
        return "timedCalls=" + BLENDER_TIMED_CALLS.sum()
                + " captureMs=" + nanosToMillis(BLENDER_CAPTURE_NANOS.sum())
                + " encodeMs=" + nanosToMillis(BLENDER_ENCODE_NANOS.sum())
                + " inputCopyMs=" + nanosToMillis(BLENDER_INPUT_COPY_NANOS.sum())
                + " nativeMs=" + nanosToMillis(BLENDER_NATIVE_NANOS.sum())
                + " outputCopyMs=" + nanosToMillis(BLENDER_OUTPUT_COPY_NANOS.sum())
                + " snapshotCacheHits=" + BLENDER_SNAPSHOT_CACHE_HITS.sum()
                + " snapshotCacheMisses=" + BLENDER_SNAPSHOT_CACHE_MISSES.sum()
                + " snapshotBytes=" + BLENDER_SNAPSHOT_BYTES.sum();
    }

    private static double nanosToMillis(long nanos) { return nanos / 1_000_000.0; }

    private volatile boolean closed;

    private static final class DensityScratch implements AutoCloseable {
        private final Arena arena = Arena.ofShared();
        private MemorySegment encoded;
        private int capacity;
        private MemorySegment output;
        private int outputCapacity;
        private MemorySegment inputVolumes;
        private long inputCapacityFloats;
        private MemorySegment outputRoots;
        private int outputRootCapacity;
        private final AtomicBoolean leased = new AtomicBoolean();

        private boolean tryAcquire() { return leased.compareAndSet(false, true); }
        private void release() {
            if (!leased.compareAndSet(true, false)) throw new IllegalStateException("Density scratch lease was not active");
        }
        private MemorySegment ensureCapacity(int requiredNodes) {
            if (capacity < requiredNodes) {
                int next = Math.max(256, capacity);
                while (next < requiredNodes) next = Math.min(DensityGraphIr.MAX_NODES, Math.multiplyExact(next, 2));
                encoded = arena.allocate(Math.multiplyExact((long) next, DENSITY_NODE_LAYOUT.byteSize()),
                        DENSITY_NODE_LAYOUT.byteAlignment());
                capacity = next;
            }
            return encoded;
        }
        private MemorySegment ensureOutputCapacity(int requiredFloats) {
            if (outputCapacity < requiredFloats) {
                int next = Math.max(1024, outputCapacity);
                while (next < requiredFloats) next = Math.multiplyExact(next, 2);
                output = arena.allocate(Math.multiplyExact((long) next, ValueLayout.JAVA_FLOAT.byteSize()),
                        ValueLayout.JAVA_FLOAT.byteAlignment());
                outputCapacity = next;
            }
            return output;
        }
        private MemorySegment ensureInputCapacity(long requiredFloats) {
            if (requiredFloats == 0) return MemorySegment.NULL;
            if (inputCapacityFloats < requiredFloats) {
                long next = Math.max(1024L, inputCapacityFloats);
                while (next < requiredFloats) next = Math.multiplyExact(next, 2L);
                inputVolumes = arena.allocate(Math.multiplyExact(next, ValueLayout.JAVA_FLOAT.byteSize()),
                        ValueLayout.JAVA_FLOAT.byteAlignment());
                inputCapacityFloats = next;
            }
            return inputVolumes;
        }
        private MemorySegment ensureOutputRootCapacity(int requiredRoots) {
            if (outputRootCapacity < requiredRoots) {
                int next = Math.max(8, outputRootCapacity);
                while (next < requiredRoots) next = Math.multiplyExact(next, 2);
                outputRoots = arena.allocate(Math.multiplyExact((long) next, ValueLayout.JAVA_INT.byteSize()),
                        ValueLayout.JAVA_INT.byteAlignment());
                outputRootCapacity = next;
            }
            return outputRoots;
        }
        @Override public void close() {
            if (leased.get()) throw new IllegalStateException("Cannot close a leased density scratch arena");
            arena.close();
        }
    }

    private static final class RawBlendingScratch {
        private MemorySegment views;
        private int viewCapacity;
        private MemorySegment density;
        private long densityCapacityFloats;

        private MemorySegment ensureViews(Arena arena, int requiredViews) {
            if (requiredViews == 0) return MemorySegment.NULL;
            if (viewCapacity < requiredViews) {
                int next = Math.max(8, viewCapacity);
                while (next < requiredViews) next = Math.multiplyExact(next, 2);
                views = arena.allocate(Math.multiplyExact(BLENDING_DATA_VIEW_SIZE, next),
                        BLENDING_DATA_VIEW_LAYOUT.byteAlignment());
                viewCapacity = next;
            }
            return views;
        }

        private MemorySegment ensureDensity(Arena arena, long requiredFloats) {
            if (requiredFloats == 0L) return MemorySegment.NULL;
            if (densityCapacityFloats < requiredFloats) {
                long next = Math.max(1024L, densityCapacityFloats);
                while (next < requiredFloats) next = Math.multiplyExact(next, 2L);
                density = arena.allocate(Math.multiplyExact(next, ValueLayout.JAVA_FLOAT.byteSize()),
                        ValueLayout.JAVA_FLOAT.byteAlignment());
                densityCapacityFloats = next;
            }
            return density;
        }
    }

    private static final class NativeBlendingSnapshot {
        private final int minSectionY;
        private final int sectionCount;
        private final MemorySegment[] columns;

        private NativeBlendingSnapshot(int minSectionY, int sectionCount, MemorySegment[] columns) {
            this.minSectionY = minSectionY;
            this.sectionCount = sectionCount;
            this.columns = columns;
        }
    }

    private static final class BlenderScratch implements AutoCloseable {
        private static final int MAX_CACHED_BLENDING_DATA = 64;
        private final Arena arena = Arena.ofShared();
        private Arena snapshotArena = Arena.ofShared();
        private final IdentityHashMap<BlendingData, NativeBlendingSnapshot> snapshots = new IdentityHashMap<>();
        private final RawBlendingScratch height = new RawBlendingScratch();
        private final RawBlendingScratch density = new RawBlendingScratch();
        private MemorySegment noise;
        private MemorySegment output;
        private int volumeCapacity;
        private final AtomicBoolean leased = new AtomicBoolean();

        private boolean tryAcquire() { return leased.compareAndSet(false, true); }
        private void release() {
            if (!leased.compareAndSet(true, false)) {
                throw new IllegalStateException("Blender scratch lease was not active");
            }
        }
        private NativeBlendingSnapshot snapshot(BlendingData data, int minSectionY, int sectionCount) {
            NativeBlendingSnapshot cached = snapshots.get(data);
            if (cached != null) {
                BLENDER_SNAPSHOT_CACHE_HITS.increment();
                return cached;
            }
            if (snapshots.size() >= MAX_CACHED_BLENDING_DATA) {
                snapshots.clear();
                snapshotArena.close();
                snapshotArena = Arena.ofShared();
            }

            float[][] sourceColumns = ((BlendingDataAccessor) data).jpe$densities();
            int requiredColumnLength = Math.multiplyExact(sectionCount, 2);
            long totalFloats = 0L;
            for (int column = 0; column < 16; ++column) {
                float[] values = sourceColumns != null && column < sourceColumns.length
                        ? sourceColumns[column] : null;
                if (values == null || values.length == 0) continue;
                if (values.length < requiredColumnLength) {
                    throw new IllegalArgumentException("Calculated BlendingData density column is shorter than its section range");
                }
                totalFloats = Math.addExact(totalFloats, requiredColumnLength);
            }

            MemorySegment slab = totalFloats == 0L ? MemorySegment.NULL
                    : snapshotArena.allocate(Math.multiplyExact(totalFloats, ValueLayout.JAVA_FLOAT.byteSize()),
                            ValueLayout.JAVA_FLOAT.byteAlignment());
            MemorySegment[] columns = new MemorySegment[16];
            long offsetFloats = 0L;
            for (int column = 0; column < 16; ++column) {
                float[] values = sourceColumns != null && column < sourceColumns.length
                        ? sourceColumns[column] : null;
                if (values == null || values.length == 0) {
                    columns[column] = MemorySegment.NULL;
                    continue;
                }
                long byteOffset = Math.multiplyExact(offsetFloats, ValueLayout.JAVA_FLOAT.byteSize());
                long byteLength = Math.multiplyExact((long) requiredColumnLength, ValueLayout.JAVA_FLOAT.byteSize());
                MemorySegment target = slab.asSlice(byteOffset, byteLength);
                MemorySegment.copy(values, 0, target, ValueLayout.JAVA_FLOAT, 0, requiredColumnLength);
                columns[column] = target;
                offsetFloats = Math.addExact(offsetFloats, requiredColumnLength);
            }
            if (offsetFloats != totalFloats) {
                throw new IllegalStateException("Calculated BlendingData snapshot size changed while copying");
            }
            NativeBlendingSnapshot result = new NativeBlendingSnapshot(minSectionY, sectionCount, columns);
            snapshots.put(data, result);
            BLENDER_SNAPSHOT_CACHE_MISSES.increment();
            BLENDER_SNAPSHOT_BYTES.add(Math.multiplyExact(totalFloats, ValueLayout.JAVA_FLOAT.byteSize()));
            return result;
        }
        private void ensureVolumeCapacity(int requiredFloats) {
            if (volumeCapacity < requiredFloats) {
                int next = Math.max(1024, volumeCapacity);
                while (next < requiredFloats) next = Math.multiplyExact(next, 2);
                long bytes = Math.multiplyExact((long) next, ValueLayout.JAVA_FLOAT.byteSize());
                noise = arena.allocate(bytes, ValueLayout.JAVA_FLOAT.byteAlignment());
                output = arena.allocate(bytes, ValueLayout.JAVA_FLOAT.byteAlignment());
                volumeCapacity = next;
            }
        }
        @Override public void close() {
            if (leased.get()) throw new IllegalStateException("Cannot close a leased Blender scratch arena");
            snapshots.clear();
            snapshotArena.close();
            arena.close();
        }
    }

    private static final class PerlinScratch implements AutoCloseable {
        private final Arena arena = Arena.ofShared();
        private MemorySegment permutation;
        private MemorySegment output;
        private int permutationCapacity;
        private int outputCapacity;
        private MemorySegment layerDescriptors;
        private MemorySegment layerPermutations;
        private MemorySegment[] layerPermutationSegments;
        private byte[][] layerPermutationSources;
        private int layerCapacity;
        private final AtomicBoolean leased = new AtomicBoolean();

        private boolean tryAcquire() { return leased.compareAndSet(false, true); }
        private void release() {
            if (!leased.compareAndSet(true, false)) {
                throw new IllegalStateException("Perlin scratch lease was not active");
            }
        }

        private void ensureCapacity(int permutationLength, int outputLength) {
            if (permutationCapacity < permutationLength) {
                permutation = allocateBytes(arena, permutationLength);
                permutationCapacity = permutationLength;
            }
            if (outputCapacity < outputLength) {
                output = allocateFloats(arena, outputLength);
                outputCapacity = outputLength;
            }
        }

        private void ensureStackCapacity(int layerCount, int outputLength) {
            ensureCapacity(256, outputLength);
            if (layerCapacity < layerCount) {
                layerDescriptors = arena.allocate(Math.multiplyExact((long) layerCount, PERLIN_LAYER_SIZE), 8);
                layerPermutations = allocateBytes(arena, Math.multiplyExact(layerCount, 256));
                layerPermutationSegments = new MemorySegment[layerCount];
                layerPermutationSources = new byte[layerCount][];
                for (int i = 0; i < layerCount; ++i) {
                    layerPermutationSegments[i] = layerPermutations.asSlice((long) i * 256L, 256L);
                }
                layerCapacity = layerCount;
            }
        }

        @Override
        public void close() {
            if (leased.get()) throw new IllegalStateException("Cannot close a leased Perlin scratch arena");
            arena.close();
        }
    }

    private DensityScratch acquireDensityScratch() {
        DensityScratch scratch = densityScratch.get();
        if (scratch.tryAcquire()) return scratch;
        scratch = new DensityScratch();
        densityScratch.set(scratch);
        densityScratchArenas.add(scratch);
        if (!scratch.tryAcquire()) throw new IllegalStateException("Unable to acquire density scratch arena");
        return scratch;
    }

    private PerlinScratch acquirePerlinScratch() {
        PerlinScratch scratch = perlinScratch.get();
        if (scratch.tryAcquire()) return scratch;
        scratch = new PerlinScratch();
        perlinScratch.set(scratch);
        perlinScratchArenas.add(scratch);
        if (!scratch.tryAcquire()) throw new IllegalStateException("Unable to acquire Perlin scratch arena");
        return scratch;
    }

    private BlenderScratch acquireBlenderScratch() {
        BlenderScratch scratch = blenderScratch.get();
        if (scratch.tryAcquire()) return scratch;
        scratch = new BlenderScratch();
        blenderScratch.set(scratch);
        blenderScratchArenas.add(scratch);
        if (!scratch.tryAcquire()) throw new IllegalStateException("Unable to acquire Blender scratch arena");
        return scratch;
    }

    private NativeRuntime(MethodHandle configureWorkers, int workerCount,
                          MethodHandle add, MethodHandle perlin, MethodHandle perlinStack, MethodHandle submit, MethodHandle submitStack,
                          MethodHandle status, MethodHandle wait,
                          MethodHandle cancel, MethodHandle release,
                          MethodHandle compileDensityGraph, MethodHandle evaluateDensityGraph,
                          MethodHandle evaluateDensityGraphWithInputs, MethodHandle evaluateDensityGraphMultiWithInputs,
                          MethodHandle releaseDensityGraph, MethodHandle loadDensityGraphCache,
                          MethodHandle saveDensityGraphCache, MethodHandle blendDensityVolume) {
        this.configureWorkers = configureWorkers;
        this.workerCount = workerCount;
        this.addF32 = add;
        this.perlinAddVolume = perlin;
        this.perlinAddStackHandle = perlinStack;
        this.submitPerlinVolume = submit;
        this.submitPerlinStack = submitStack;
        this.jobStatus = status;
        this.waitJob = wait;
        this.cancelJob = cancel;
        this.releaseJob = release;
        this.compileDensityGraph = compileDensityGraph;
        this.evaluateDensityGraph = evaluateDensityGraph;
        this.evaluateDensityGraphWithInputs = evaluateDensityGraphWithInputs;
        this.evaluateDensityGraphMultiWithInputs = evaluateDensityGraphMultiWithInputs;
        this.releaseDensityGraph = releaseDensityGraph;
        this.loadDensityGraphCache = loadDensityGraphCache;
        this.saveDensityGraphCache = saveDensityGraphCache;
        this.blendDensityVolume = blendDensityVolume;
    }

    public static NativeRuntime load(Path libraryPath) throws IOException {
        Objects.requireNonNull(libraryPath, "libraryPath");
        Path absolutePath = libraryPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolutePath)) {
            throw new IOException("Native runtime library does not exist: " + absolutePath);
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup(absolutePath, LIBRARY_ARENA);
            MethodHandle version = linker.downcallHandle(
                    lookup.find("jpe_runtime_abi_version").orElseThrow(() -> new IllegalStateException("Missing ABI version symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            MethodHandle configureWorkers = linker.downcallHandle(
                    lookup.find("jpe_runtime_configure_workers").orElseThrow(() -> new IllegalStateException("Missing worker configuration symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            MethodHandle add = linker.downcallHandle(
                    lookup.find("jpe_runtime_add_f32").orElseThrow(() -> new IllegalStateException("Missing bulk kernel symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle perlin = linker.downcallHandle(
                    lookup.find("jpe_runtime_perlin_add_volume").orElseThrow(() -> new IllegalStateException("Missing Perlin volume symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_FLOAT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            MethodHandle perlinStack = linker.downcallHandle(
                    lookup.find("jpe_runtime_perlin_add_stack").orElseThrow(() -> new IllegalStateException("Missing synchronous Perlin stack symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            MethodHandle submit = linker.downcallHandle(
                    lookup.find("jpe_runtime_submit_perlin_volume").orElseThrow(() -> new IllegalStateException("Missing async Perlin submit symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_FLOAT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            MethodHandle submitStack = lookup.find("jpe_runtime_submit_perlin_stack")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)))
                    .orElse(null);
            MethodHandle status = linker.downcallHandle(
                    lookup.find("jpe_runtime_job_status").orElseThrow(() -> new IllegalStateException("Missing async job status symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            MethodHandle wait = linker.downcallHandle(
                    lookup.find("jpe_runtime_wait_job").orElseThrow(() -> new IllegalStateException("Missing async job wait symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            MethodHandle cancel = linker.downcallHandle(
                    lookup.find("jpe_runtime_cancel_job").orElseThrow(() -> new IllegalStateException("Missing async cancel symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            MethodHandle release = linker.downcallHandle(
                    lookup.find("jpe_runtime_release_job").orElseThrow(() -> new IllegalStateException("Missing async release symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            MethodHandle compileDensityGraph = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_compile").orElseThrow(() -> new IllegalStateException("Missing density graph compile symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            MethodHandle evaluateDensityGraph = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_evaluate").orElseThrow(() -> new IllegalStateException("Missing density graph evaluate symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MethodHandle evaluateDensityGraphWithInputs = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_evaluate_with_inputs").orElseThrow(() -> new IllegalStateException("Missing density graph external-input evaluate symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MethodHandle evaluateDensityGraphMultiWithInputs = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_evaluate_multi_with_inputs").orElseThrow(() -> new IllegalStateException("Missing density graph multi-output evaluate symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MethodHandle releaseDensityGraph = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_release").orElseThrow(() -> new IllegalStateException("Missing density graph release symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            MethodHandle loadDensityGraphCache = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_load_cache").orElseThrow(() -> new IllegalStateException("Missing native density cache load symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle saveDensityGraphCache = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_save_cache").orElseThrow(() -> new IllegalStateException("Missing native density cache save symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle blendDensityVolume = lookup.find("jpe_runtime_blend_density_volume")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)))
                    .orElse(null);

            int actual = (int) version.invokeExact();
            if (actual != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException("Native ABI mismatch: expected " + EXPECTED_ABI_VERSION + ", got " + actual);
            }
            int requestedWorkers = requestedNativeWorkers();
            int actualWorkers = (int) configureWorkers.invokeExact(requestedWorkers);
            if (actualWorkers < 1) throw new IllegalStateException("Unable to configure native workers: " + actualWorkers);
            return new NativeRuntime(configureWorkers, actualWorkers, add, perlin, perlinStack, submit, submitStack, status, wait, cancel, release,
                    compileDensityGraph, evaluateDensityGraph, evaluateDensityGraphWithInputs, evaluateDensityGraphMultiWithInputs, releaseDensityGraph,
                    loadDensityGraphCache, saveDensityGraphCache, blendDensityVolume);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to load JPE native runtime from " + absolutePath, failure);
        }
    }


    /**
     * Encodes a Blender map directly into one native descriptor array and density slab.
     * Two ordered fastutil passes avoid allocating an intermediate List plus one record
     * per legacy chunk on every density volume call. The source map's iteration order is
     * preserved because C++ accumulation must match vanilla floating-point order.
     */
    private static MemorySegment encodeRawBlendingData(
            BlenderScratch scratch, RawBlendingScratch target,
            Long2ObjectOpenHashMap<BlendingData> source) {
        int viewCount = source.size();
        if (viewCount == 0) return MemorySegment.NULL;

        long[] totalFloats = {0L};
        source.forEach((long packedChunk, BlendingData data) -> {
            BlendingDataAccessor accessor = (BlendingDataAccessor) data;
            int sectionCount = accessor.jpe$areaWithOldGeneration().getSectionsCount();
            if (sectionCount <= 0 || sectionCount > 128) {
                throw new IllegalArgumentException("BlendingData section count is outside the native limit");
            }
            float[][] columns = accessor.jpe$densities();
            int requiredColumnLength = Math.multiplyExact(sectionCount, 2);
            for (int column = 0; column < 16; ++column) {
                float[] values = columns != null && column < columns.length ? columns[column] : null;
                if (values == null || values.length == 0) continue;
                if (values.length < requiredColumnLength) {
                    throw new IllegalArgumentException("BlendingData density column is shorter than its section range");
                }
                totalFloats[0] = Math.addExact(totalFloats[0], requiredColumnLength);
            }
        });

        MemorySegment encoded = target.ensureViews(scratch.arena, viewCount);
        // Worst-case capacity is retained for uncached/uninitialized entries. Calculated
        // vanilla BlendingData objects use stable per-worker snapshots instead.
        MemorySegment densitySlab = target.ensureDensity(scratch.arena, totalFloats[0]);
        long[] densityOffsetFloats = {0L};
        int[] viewIndex = {0};
        source.forEach((long packedChunk, BlendingData data) -> {
            int index = viewIndex[0]++;
            long base = Math.multiplyExact((long) index, BLENDING_DATA_VIEW_SIZE);
            BlendingDataAccessor accessor = (BlendingDataAccessor) data;
            LevelHeightAccessor height = accessor.jpe$areaWithOldGeneration();
            int sectionCount = height.getSectionsCount();
            NativeBlendingSnapshot snapshot = accessor.jpe$hasCalculatedData()
                    ? scratch.snapshot(data, height.getMinSectionY(), sectionCount) : null;
            encoded.set(ValueLayout.JAVA_INT, base, ChunkPos.getX(packedChunk));
            encoded.set(ValueLayout.JAVA_INT, base + 4L, ChunkPos.getZ(packedChunk));
            encoded.set(ValueLayout.JAVA_INT, base + 8L,
                    snapshot == null ? height.getMinSectionY() : snapshot.minSectionY);
            encoded.set(ValueLayout.JAVA_INT, base + 12L,
                    snapshot == null ? sectionCount : snapshot.sectionCount);

            float[][] columns = snapshot == null ? accessor.jpe$densities() : null;
            int requiredColumnLength = Math.multiplyExact(sectionCount, 2);
            for (int column = 0; column < 16; ++column) {
                long pointerOffset = base + BLENDING_DATA_POINTERS_OFFSET
                        + (long) column * ValueLayout.ADDRESS.byteSize();
                if (snapshot != null) {
                    encoded.set(ValueLayout.ADDRESS, pointerOffset, snapshot.columns[column]);
                    continue;
                }
                float[] values = columns != null && column < columns.length ? columns[column] : null;
                if (values == null || values.length == 0) {
                    encoded.set(ValueLayout.ADDRESS, pointerOffset, MemorySegment.NULL);
                    continue;
                }
                if (values.length < requiredColumnLength) {
                    throw new IllegalArgumentException("BlendingData density column changed during native encoding");
                }
                long columnBytes = Math.multiplyExact((long) requiredColumnLength,
                        ValueLayout.JAVA_FLOAT.byteSize());
                long byteOffset = Math.multiplyExact(densityOffsetFloats[0],
                        ValueLayout.JAVA_FLOAT.byteSize());
                MemorySegment rawColumn = densitySlab.asSlice(byteOffset, columnBytes);
                MemorySegment.copy(values, 0, rawColumn, ValueLayout.JAVA_FLOAT, 0, requiredColumnLength);
                encoded.set(ValueLayout.ADDRESS, pointerOffset, rawColumn);
                densityOffsetFloats[0] = Math.addExact(densityOffsetFloats[0], requiredColumnLength);
            }
        });

        if (viewIndex[0] != viewCount || densityOffsetFloats[0] > totalFloats[0]) {
            throw new IllegalStateException("BlendingData changed during native encoding");
        }
        return encoded;
    }

    /**
     * Executes Blender over a complete DensityVolume in one synchronous Native call.
     * Java only exposes raw BlendingData arrays and volume metadata; all cell mapping,
     * fixed lookup, spatial indexing, neighbor search, weighting, and interpolation are C++.
     */
    public boolean supportsBlenderDensityVolume() { return blendDensityVolume != null; }

    public void blendDensityVolume(Blender blender, DensityVolume volume,
                                   float[] noiseValues, float[] outputValues) {
        lifecycle.readLock().lock();
        BlenderScratch scratch = null;
        try {
            ensureOpen();
            if (blendDensityVolume == null) throw new UnsupportedOperationException("Native runtime does not export Blender density volume support");
            Objects.requireNonNull(blender, "blender");
            Objects.requireNonNull(volume, "volume");
            Objects.requireNonNull(noiseValues, "noiseValues");
            Objects.requireNonNull(outputValues, "outputValues");
            int sizeX = volume.sizeX(), sizeY = volume.sizeY(), sizeZ = volume.sizeZ();
            int length = Math.toIntExact(Math.multiplyExact(
                    Math.multiplyExact((long) sizeX, sizeY), sizeZ));
            if (length <= 0 || length > 1_048_576 || volume.size() != length
                    || noiseValues.length < length || outputValues.length < length) {
                throw new IllegalArgumentException("Invalid Blender density volume buffers or dimensions");
            }
            scratch = acquireBlenderScratch();
            scratch.ensureVolumeCapacity(length);
            boolean measure = Boolean.getBoolean("jpe.nativeBlenderTiming");
            long stageStarted = measure ? System.nanoTime() : 0L;
            BlenderAccessor blenderAccessor = (BlenderAccessor) blender;
            Long2ObjectOpenHashMap<BlendingData> heightData =
                    blenderAccessor.jpe$heightAndBiomeBlendingData();
            Long2ObjectOpenHashMap<BlendingData> densityData =
                    blenderAccessor.jpe$densityBlendingData();
            if (measure) {
                BLENDER_CAPTURE_NANOS.add(System.nanoTime() - stageStarted);
                stageStarted = System.nanoTime();
            }
            MemorySegment nativeHeightViews = encodeRawBlendingData(scratch, scratch.height, heightData);
            MemorySegment nativeDensityViews = encodeRawBlendingData(scratch, scratch.density, densityData);
            if (measure) {
                BLENDER_ENCODE_NANOS.add(System.nanoTime() - stageStarted);
                stageStarted = System.nanoTime();
            }
            MemorySegment nativeNoise = scratch.noise;
            MemorySegment nativeOutput = noiseValues == outputValues ? nativeNoise : scratch.output;
            MemorySegment.copy(noiseValues, 0, nativeNoise, ValueLayout.JAVA_FLOAT, 0, length);
            if (measure) {
                BLENDER_INPUT_COPY_NANOS.add(System.nanoTime() - stageStarted);
                stageStarted = System.nanoTime();
            }
            int minX = volume.minBlockX(), minY = volume.minBlockY(), minZ = volume.minBlockZ();
            int stepX = sizeX > 1 ? volume.blockX(1) - minX : 1;
            int stepY = sizeY > 1 ? volume.blockY(1) - minY : 1;
            int stepZ = sizeZ > 1 ? volume.blockZ(1) - minZ : 1;
            int status = (int) blendDensityVolume.invokeExact(
                    nativeHeightViews, heightData.size(), nativeDensityViews, densityData.size(),
                    sizeX, sizeY, sizeZ, minX, minY, minZ, stepX, stepY, stepZ,
                    nativeNoise, nativeOutput, (long) length);
            if (measure) {
                BLENDER_NATIVE_NANOS.add(System.nanoTime() - stageStarted);
                stageStarted = System.nanoTime();
            }
            checkStatus(status, "blend-density-volume");
            MemorySegment.copy(nativeOutput, ValueLayout.JAVA_FLOAT, 0L,
                    outputValues, 0, length);
            if (measure) {
                BLENDER_OUTPUT_COPY_NANOS.add(System.nanoTime() - stageStarted);
                BLENDER_TIMED_CALLS.increment();
            }
        } catch (Throwable failure) {
            throw rethrow("Native Blender density volume call failed", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /** Number of native workers selected during runtime initialization. */
    public int workerCount() { return workerCount; }

    private static int requestedNativeWorkers() {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        String configured = System.getProperty("jpe.nativeWorkers", "auto").trim();
        if (configured.equalsIgnoreCase("auto") || configured.equals("0")) {
            int reserve = Math.max(0, Integer.getInteger("jpe.nativeWorkerReserve", 1));
            return Math.max(1, available - reserve);
        }
        final int requested;
        try {
            requested = Integer.parseInt(configured);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("jpe.nativeWorkers must be 'auto' or a positive integer", invalid);
        }
        if (requested < 1) throw new IllegalArgumentException("jpe.nativeWorkers must be 'auto' or a positive integer");
        return Math.min(requested, available);
    }

    /** Compiles or reuses an immutable native density graph. */
    /**
     * Startup/pre-world-creation preparation. On a cache hit this never invokes the
     * Java DensityFunction capture supplier: it reads the persisted IR and uploads it
     * once to the native runtime. Keep the returned executable alive for worldgen.
     */
    /**
     * Startup/pre-world-creation preparation. First try loading the persisted native
     * plan directly in C++; only a cache miss invokes Java graph capture and FFM node
     * serialization. The returned handle is ready to execute during world generation.
     */
    public PreparedDensityGraph prepareDensityGraph(Path cacheDirectory,
                                                     DensityGraphCache.Identity identity,
                                                     Supplier<DensityGraphIr> capture,
                                                     boolean experimental) throws IOException {
        Objects.requireNonNull(identity, "identity");
        return prepareDensityGraph(cacheDirectory, identity.stableKey(), capture, experimental);
    }

    public PreparedDensityGraph prepareDensityGraph(Path cacheDirectory, String cacheKey,
                                                     Supplier<DensityGraphIr> capture,
                                                     boolean experimental) throws IOException {
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Objects.requireNonNull(cacheKey, "cacheKey");
        Objects.requireNonNull(capture, "capture");
        if (cacheKey.isBlank()) throw new IllegalArgumentException("Density graph cache key must not be blank");
        long preparationStarted = System.nanoTime();
        long nativeLoadNanos = 0L;
        long irLoadOrCaptureNanos = 0L;
        long nativeCompileNanos = 0L;
        long nativeSaveNanos = 0L;
        String modeKey = cacheKey + "|jpe-abi=" + EXPECTED_ABI_VERSION
                + "|jpe-ir=" + DensityGraphIr.VERSION + "|jpe-native-plan=1"
                + (experimental ? "|jpe-mode=experimental" : "|jpe-mode=strict");
        Files.createDirectories(cacheDirectory);
        Path nativeFile = DensityGraphCache.nativeCachePath(cacheDirectory, modeKey);
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pathSegment = arena.allocateFrom(nativeFile.toAbsolutePath().normalize().toString());
                MemorySegment keySegment = arena.allocateFrom(modeKey);
                long nativeLoadStarted = System.nanoTime();
                long cachedHandle = (long) loadDensityGraphCache.invokeExact(pathSegment, keySegment);
                nativeLoadNanos = System.nanoTime() - nativeLoadStarted;
                if (cachedHandle != 0L) {
                    NativeDensityGraph executable = new NativeDensityGraph(this, cachedHandle);
                    densityGraphs.add(executable);
                    PreparationMetrics metrics = new PreparationMetrics(nativeLoadNanos, 0L, 0L, 0L,
                            System.nanoTime() - preparationStarted);
                    return new PreparedDensityGraph(executable, true, false, nativeFile, -1, -1, metrics);
                }
            } catch (Throwable failure) {
                throw rethrow("Unable to load persistent native density plan", failure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }

        long irStarted = System.nanoTime();
        DensityGraphCache.LoadResult loaded = DensityGraphCache.loadOrBuild(
                cacheDirectory, modeKey, capture);
        irLoadOrCaptureNanos = System.nanoTime() - irStarted;
        long compileStarted = System.nanoTime();
        NativeDensityGraph executable = experimental
                ? compileDensityGraphExperimental(loaded.graph())
                : compileDensityGraph(loaded.graph());
        nativeCompileNanos = System.nanoTime() - compileStarted;
        try {
            long saveStarted = System.nanoTime();
            lifecycle.readLock().lock();
            try {
                ensureOpen();
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment pathSegment = arena.allocateFrom(nativeFile.toAbsolutePath().normalize().toString());
                    MemorySegment keySegment = arena.allocateFrom(modeKey);
                    int status = (int) saveDensityGraphCache.invokeExact(
                            executable.handle, pathSegment, keySegment);
                    if (status != 0) {
                        throw new IOException("Native density plan cache save failed with status " + status);
                    }
                } catch (IOException failure) {
                    throw failure;
                } catch (Throwable failure) {
                    throw rethrow("Unable to persist native density plan", failure);
                }
            } finally {
                lifecycle.readLock().unlock();
            }
            nativeSaveNanos = System.nanoTime() - saveStarted;
        } catch (Throwable failure) {
            executable.close();
            if (failure instanceof IOException io) throw io;
            throw rethrow("Unable to prepare persistent native density plan", failure);
        }
        int opaqueCount = (int) loaded.graph().nodes().stream()
                .filter(node -> node.opcode() == DensityGraphIr.OPAQUE).count();
        PreparationMetrics metrics = new PreparationMetrics(nativeLoadNanos, irLoadOrCaptureNanos,
                nativeCompileNanos, nativeSaveNanos, System.nanoTime() - preparationStarted);
        return new PreparedDensityGraph(executable, false, loaded.cacheHit(), nativeFile,
                loaded.graph().nodes().size(), opaqueCount, metrics);
    }

    public record PreparationMetrics(long nativeLoadNanos, long irLoadOrCaptureNanos,
                                     long nativeCompileNanos, long nativeSaveNanos,
                                     long totalNanos) {}

    public record PreparedDensityGraph(NativeDensityGraph executable, boolean nativeCacheHit,
                                       boolean irCacheHit, Path cacheFile,
                                       int nodeCount, int opaqueNodeCount,
                                       PreparationMetrics metrics) implements AutoCloseable {
        @Override public void close() { executable.close(); }
    }

    /** Compiles only strict graphs; experimental sentinels require the explicit method below. */
    public NativeDensityGraph compileDensityGraph(DensityGraphIr graph) {
        return compileDensityGraphInternal(graph, false);
    }

    /** Executes the experimental compiler path. OPAQUE nodes evaluate to NaN and must not feed worldgen. */
    public NativeDensityGraph compileDensityGraphExperimental(DensityGraphIr graph) {
        return compileDensityGraphInternal(graph, true);
    }

    private NativeDensityGraph compileDensityGraphInternal(DensityGraphIr graph, boolean allowExperimental) {
        Objects.requireNonNull(graph, "graph");
        if (graph.experimental() && !allowExperimental) {
            throw new IllegalArgumentException("Experimental density graph requires compileDensityGraphExperimental");
        }
        lifecycle.readLock().lock();
        DensityScratch scratch = null;
        try {
            ensureOpen();
            List<DensityGraphIr.Node> nodes = graph.nodes();
            scratch = acquireDensityScratch();
            MemorySegment encoded = scratch.ensureCapacity(nodes.size());
            for (int i = 0; i < nodes.size(); ++i) {
                DensityGraphIr.Node node = nodes.get(i);
                long base = Math.multiplyExact((long) i, DENSITY_NODE_LAYOUT.byteSize());
                encoded.set(ValueLayout.JAVA_INT, base, node.opcode());
                encoded.set(ValueLayout.JAVA_INT, base + 4L, node.inputA() < 0 ? 0xffff_ffff : node.inputA());
                encoded.set(ValueLayout.JAVA_INT, base + 8L, node.inputB() < 0 ? 0xffff_ffff : node.inputB());
                encoded.set(ValueLayout.JAVA_INT, base + 12L, node.opcode() == DensityGraphIr.OPAQUE ? 1 : 0);
                encoded.set(ValueLayout.JAVA_DOUBLE, base + 16L, node.immediate());
            }
            try {
                long handle = (long) compileDensityGraph.invokeExact(
                        encoded, nodes.size(), graph.rootNode(), DensityGraphIr.VERSION, graph.configurationFingerprint());
                if (handle == 0L) throw new IllegalStateException("Native density graph compiler rejected the IR");
                NativeDensityGraph compiled = new NativeDensityGraph(this, handle);
                densityGraphs.add(compiled);
                return compiled;
            } catch (Throwable failure) {
                throw rethrow("Unable to compile native density graph", failure);
            }
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    private static int validateDensityVolume(int sizeX, int sizeY, int sizeZ,
                                             int stepX, int stepY, int stepZ, float[] output) {
        Objects.requireNonNull(output, "output");
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                || stepX == 0 || stepY == 0 || stepZ == 0) {
            throw new IllegalArgumentException("Invalid density volume dimensions or axis steps");
        }
        long elements = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        if (elements > 1_048_576L || elements > output.length) {
            throw new IllegalArgumentException("Density output is too small or exceeds the native volume limit");
        }
        return Math.toIntExact(elements);
    }

    private void evaluateDensityGraph(NativeDensityGraph graph,
                                      int sizeX, int sizeY, int sizeZ,
                                      int minX, int minY, int minZ,
                                      int stepX, int stepY, int stepZ,
                                      float[] output) {
        lifecycle.readLock().lock();
        DensityScratch scratch = null;
        try {
            ensureOpen();
            int length = validateDensityVolume(sizeX, sizeY, sizeZ, stepX, stepY, stepZ, output);
            if (graph.closed) throw new IllegalStateException("Native density graph has been released");
            scratch = acquireDensityScratch();
            MemorySegment nativeOutput = scratch.ensureOutputCapacity(length);
            try {
                int status = (int) evaluateDensityGraph.invokeExact(
                        graph.handle, sizeX, sizeY, sizeZ,
                        minX, minY, minZ, stepX, stepY, stepZ,
                        nativeOutput, (long) length);
                checkStatus(status, "density-graph-evaluate");
                MemorySegment.copy(nativeOutput, ValueLayout.JAVA_FLOAT, 0L, output, 0, length);
            } catch (Throwable failure) {
                throw rethrow("Unable to evaluate native density graph", failure);
            }
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    private void evaluateDensityGraphWithInputs(NativeDensityGraph graph,
                                      int sizeX, int sizeY, int sizeZ,
                                      int minX, int minY, int minZ,
                                      int stepX, int stepY, int stepZ,
                                      float[][] inputs, float[] output) {
        lifecycle.readLock().lock();
        DensityScratch scratch = null;
        try {
            ensureOpen();
            int length = validateDensityVolume(sizeX, sizeY, sizeZ, stepX, stepY, stepZ, output);
            if (graph.closed) throw new IllegalStateException("Native density graph has been released");
            Objects.requireNonNull(inputs, "inputs");
            if (inputs.length > 256) throw new IllegalArgumentException("At most 256 external density inputs are supported");
            long totalFloats = Math.multiplyExact((long) inputs.length, length);
            scratch = acquireDensityScratch();
            MemorySegment nativeInputs = scratch.ensureInputCapacity(totalFloats);
            for (int i = 0; i < inputs.length; ++i) {
                if (inputs[i] == null || inputs[i].length < length) {
                    throw new IllegalArgumentException("External input volume " + i + " is null or too short");
                }
                MemorySegment.copy(inputs[i], 0, nativeInputs, ValueLayout.JAVA_FLOAT,
                        Math.multiplyExact((long) i * length, ValueLayout.JAVA_FLOAT.byteSize()), length);
            }
            MemorySegment nativeOutput = scratch.ensureOutputCapacity(length);
            try {
                int status = (int) evaluateDensityGraphWithInputs.invokeExact(
                        graph.handle, sizeX, sizeY, sizeZ,
                        minX, minY, minZ, stepX, stepY, stepZ,
                        nativeInputs, inputs.length, nativeOutput, (long) length);
                checkStatus(status, "density-graph-evaluate-with-inputs");
                MemorySegment.copy(nativeOutput, ValueLayout.JAVA_FLOAT, 0L, output, 0, length);
            } catch (Throwable failure) {
                throw rethrow("Unable to evaluate native density graph with external inputs", failure);
            }
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    private void evaluateDensityGraphMultiWithInputs(NativeDensityGraph graph,
                                       int sizeX, int sizeY, int sizeZ,
                                       int minX, int minY, int minZ,
                                       int stepX, int stepY, int stepZ,
                                       float[][] inputs, int[] outputNodes, float[][] outputs) {
        lifecycle.readLock().lock();
        DensityScratch scratch = null;
        try {
            ensureOpen();
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(outputNodes, "outputNodes");
            Objects.requireNonNull(outputs, "outputs");
            if (outputs.length == 0 || outputs.length > 256 || outputNodes.length != outputs.length) {
                throw new IllegalArgumentException("Multi-output evaluation requires 1..256 matching roots and output arrays");
            }
            int length = validateDensityVolume(sizeX, sizeY, sizeZ, stepX, stepY, stepZ, outputs[0]);
            if (graph.closed) throw new IllegalStateException("Native density graph has been released");
            long totalOutputFloats = Math.multiplyExact((long) outputs.length, length);
            long totalInputFloats = Math.multiplyExact((long) inputs.length, length);
            if (Math.addExact(totalOutputFloats, totalInputFloats) > 16_777_216L) {
                throw new IllegalArgumentException("Combined density input/output staging exceeds the 64 MiB safety budget");
            }
            for (float[] output : outputs) {
                if (output == null || output.length < length) {
                    throw new IllegalArgumentException("A multi-output destination is null or too short");
                }
            }
            if (inputs.length > 256) throw new IllegalArgumentException("At most 256 external density inputs are supported");
            scratch = acquireDensityScratch();
            MemorySegment nativeInputs = scratch.ensureInputCapacity(totalInputFloats);
            for (int i = 0; i < inputs.length; ++i) {
                if (inputs[i] == null || inputs[i].length < length) {
                    throw new IllegalArgumentException("External input volume " + i + " is null or too short");
                }
                MemorySegment.copy(inputs[i], 0, nativeInputs, ValueLayout.JAVA_FLOAT,
                        Math.multiplyExact((long) i * length, ValueLayout.JAVA_FLOAT.byteSize()), length);
            }
            MemorySegment nativeRoots = scratch.ensureOutputRootCapacity(outputNodes.length);
            for (int i = 0; i < outputNodes.length; ++i) {
                if (outputNodes[i] < 0) throw new IllegalArgumentException("Negative native output root");
                nativeRoots.setAtIndex(ValueLayout.JAVA_INT, i, outputNodes[i]);
            }
            MemorySegment nativeOutputs = scratch.ensureOutputCapacity(Math.toIntExact(totalOutputFloats));
            try {
                int status = (int) evaluateDensityGraphMultiWithInputs.invokeExact(
                        graph.handle, sizeX, sizeY, sizeZ,
                        minX, minY, minZ, stepX, stepY, stepZ,
                        nativeInputs, inputs.length, nativeRoots, outputNodes.length,
                        nativeOutputs, totalOutputFloats);
                checkStatus(status, "density-graph-evaluate-multi-with-inputs");
                for (int i = 0; i < outputs.length; ++i) {
                    MemorySegment.copy(nativeOutputs, ValueLayout.JAVA_FLOAT,
                            Math.multiplyExact((long) i * length, ValueLayout.JAVA_FLOAT.byteSize()),
                            outputs[i], 0, length);
                }
            } catch (Throwable failure) {
                throw rethrow("Unable to evaluate native density graph with multiple outputs", failure);
            }
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    private void releaseDensityGraph(NativeDensityGraph graph) {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            if (graph.closed) return;
            try {
                int status = (int) releaseDensityGraph.invokeExact(graph.handle);
                checkStatus(status, "density-graph-release");
                graph.closed = true;
                densityGraphs.remove(graph);
            } catch (Throwable failure) {
                throw rethrow("Unable to release native density graph", failure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public static final class NativeDensityGraph implements AutoCloseable {
        private final NativeRuntime runtime;
        private final long handle;
        private final ReentrantReadWriteLock graphLifecycle = new ReentrantReadWriteLock();
        private volatile boolean closed;

        private NativeDensityGraph(NativeRuntime runtime, long handle) {
            this.runtime = runtime;
            this.handle = handle;
        }

        public void evaluate(int sizeX, int sizeY, int sizeZ,
                             int minX, int minY, int minZ,
                             int stepX, int stepY, int stepZ,
                             float[] output) {
            graphLifecycle.readLock().lock();
            try {
                if (closed) throw new IllegalStateException("Native density graph has been released");
                runtime.evaluateDensityGraph(this, sizeX, sizeY, sizeZ,
                        minX, minY, minZ, stepX, stepY, stepZ, output);
            } finally {
                graphLifecycle.readLock().unlock();
            }
        }

        public void evaluateWithInputs(int sizeX, int sizeY, int sizeZ,
                                       int minX, int minY, int minZ,
                                       int stepX, int stepY, int stepZ,
                                       float[][] inputs, float[] output) {
            graphLifecycle.readLock().lock();
            try {
                if (closed) throw new IllegalStateException("Native density graph has been released");
                runtime.evaluateDensityGraphWithInputs(this, sizeX, sizeY, sizeZ,
                        minX, minY, minZ, stepX, stepY, stepZ, inputs, output);
            } finally {
                graphLifecycle.readLock().unlock();
            }
        }

        public void evaluateMultiWithInputs(int sizeX, int sizeY, int sizeZ,
                                             int minX, int minY, int minZ,
                                             int stepX, int stepY, int stepZ,
                                             float[][] inputs, int[] outputNodes, float[][] outputs) {
            graphLifecycle.readLock().lock();
            try {
                if (closed) throw new IllegalStateException("Native density graph has been released");
                runtime.evaluateDensityGraphMultiWithInputs(this, sizeX, sizeY, sizeZ,
                        minX, minY, minZ, stepX, stepY, stepZ, inputs, outputNodes, outputs);
            } finally {
                graphLifecycle.readLock().unlock();
            }
        }

        @Override
        public void close() {
            graphLifecycle.writeLock().lock();
            try {
                if (!closed) runtime.releaseDensityGraph(this);
            } finally {
                graphLifecycle.writeLock().unlock();
            }
        }
    }

    public float[] addEachValueToItself(float[] input) {
        lifecycle.readLock().lock();
        try {
        ensureOpen();
        Objects.requireNonNull(input, "input");
        try (Arena arena = Arena.ofConfined()) {
            long bytes = Math.multiplyExact((long) input.length, ValueLayout.JAVA_FLOAT.byteSize());
            MemorySegment in = arena.allocate(bytes, ValueLayout.JAVA_FLOAT.byteAlignment());
            MemorySegment out = arena.allocate(bytes, ValueLayout.JAVA_FLOAT.byteAlignment());
            MemorySegment.copy(input, 0, in, ValueLayout.JAVA_FLOAT, 0L, input.length);
            int status = (int) addF32.invokeExact(in, out, input.length);
            checkStatus(status, "add-f32");
            float[] result = new float[input.length];
            MemorySegment.copy(out, ValueLayout.JAVA_FLOAT, 0L, result, 0, result.length);
            return result;
        } catch (Throwable failure) {
            throw rethrow("Native bulk call failed", failure);
        }
    
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Synchronous bulk Perlin entry; axis coordinates are generated natively from DensityVolume metadata. */
    public void perlinAddVolume(byte[] permutation,
                                int sizeX, int sizeY, int sizeZ,
                                int minX, int minY, int minZ,
                                int stepX, int stepY, int stepZ,
                                double offsetX, double offsetY, double offsetZ,
                                double xzScale, double yScale, float amplitude,
                                float[] output, boolean farLands) {
        lifecycle.readLock().lock();
        PerlinScratch scratch = null;
        try {
            ensureOpen();
            int outputLength = validatePerlinInputs(permutation, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, output);
            scratch = acquirePerlinScratch();
            scratch.ensureCapacity(permutation.length, outputLength);
            copy(permutation, scratch.permutation);
            copyPrefix(output, outputLength, scratch.output);
            int status = (int) perlinAddVolume.invokeExact(
                    scratch.permutation, sizeX, sizeY, sizeZ,
                    minX, minY, minZ, stepX, stepY, stepZ,
                    offsetX, offsetY, offsetZ, xzScale, yScale, amplitude,
                    scratch.output, (long) outputLength, farLands ? 1 : 0);
            checkStatus(status, "perlin-add-volume");
            copyPrefix(scratch.output, output, outputLength);
        } catch (Throwable failure) {
            throw rethrow("Native Perlin volume call failed", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /** Executes an ordered Perlin stack synchronously on the calling Minecraft worldgen worker. */
    public void perlinAddStack(Object[] sourceLayers,
                               double xzScale, double yScale, float amplitude,
                               int sizeX, int sizeY, int sizeZ,
                               int minX, int minY, int minZ,
                               int stepX, int stepY, int stepZ,
                               float[] output, boolean farLands) {
        lifecycle.readLock().lock();
        PerlinScratch scratch = null;
        try {
            ensureOpen();
            Objects.requireNonNull(sourceLayers, "sourceLayers");
            Objects.requireNonNull(output, "output");
            if (sourceLayers.length == 0 || sourceLayers.length > 64) {
                throw new IllegalArgumentException("Perlin stack must contain between 1 and 64 layers");
            }
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || stepX == 0 || stepY == 0 || stepZ == 0) {
                throw new IllegalArgumentException("Invalid Perlin volume dimensions or axis steps");
            }
            int outputLength = Math.toIntExact(Math.multiplyExact(
                    Math.multiplyExact((long) sizeX, sizeY), sizeZ));
            if (outputLength > output.length || outputLength > 1_048_576) {
                throw new IllegalArgumentException("Perlin output buffer is too small or volume exceeds native limit");
            }
            scratch = acquirePerlinScratch();
            scratch.ensureStackCapacity(sourceLayers.length, outputLength);
            copyPrefix(output, outputLength, scratch.output);
            writePerlinLayers(sourceLayers, xzScale, yScale, amplitude, scratch);
            int status = (int) perlinAddStackHandle.invokeExact(
                    scratch.layerDescriptors, sourceLayers.length,
                    sizeX, sizeY, sizeZ,
                    minX, minY, minZ, stepX, stepY, stepZ,
                    scratch.output, (long) outputLength, farLands ? 1 : 0);
            checkStatus(status, "perlin-add-stack");
            copyPrefix(scratch.output, output, outputLength);
        } catch (Throwable failure) {
            throw rethrow("Native Perlin stack call failed", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /** Submits one complete volume; C++ reconstructs all axis coordinates from metadata. */
    public NativeJob submitPerlinVolume(byte[] permutation,
                                        int sizeX, int sizeY, int sizeZ,
                                        int minX, int minY, int minZ,
                                        int stepX, int stepY, int stepZ,
                                        double offsetX, double offsetY, double offsetZ,
                                        double xzScale, double yScale, float amplitude,
                                        float[] initialOutput, boolean farLands) {
        lifecycle.readLock().lock();
        PerlinScratch scratch = null;
        boolean transferred = false;
        try {
            ensureOpen();
            int outputLength = validatePerlinInputs(permutation, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, initialOutput);
            scratch = acquirePerlinScratch();
            scratch.ensureCapacity(permutation.length, outputLength);
            copy(permutation, scratch.permutation);
            copyPrefix(initialOutput, outputLength, scratch.output);
            // Rust borrows this shared FFM output buffer until the job reaches a terminal state.
            long id = (long) submitPerlinVolume.invokeExact(
                    scratch.permutation, sizeX, sizeY, sizeZ,
                    minX, minY, minZ, stepX, stepY, stepZ,
                    offsetX, offsetY, offsetZ, xzScale, yScale, amplitude,
                    scratch.output, (long) outputLength, farLands ? 1 : 0);
            if (id == 0L) return null;
            NativeJob job = new NativeJob(this, id, outputLength, scratch);
            jobs.put(id, job);
            transferred = true;
            return job;
        } catch (Throwable failure) {
            throw rethrow("Unable to submit native Perlin job", failure);
        } finally {
            if (scratch != null && !transferred) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Submits the live Minecraft NoiseStack layer array directly. This avoids constructing
     * a Java List and one PerlinLayer record per octave for every generated density volume.
     */
    public NativeJob submitPerlinStack(Object[] sourceLayers,
                                       double xzScale, double yScale, float amplitude,
                                       int sizeX, int sizeY, int sizeZ,
                                       int minX, int minY, int minZ,
                                       int stepX, int stepY, int stepZ,
                                       float[] initialOutput, boolean farLands) {
        if (submitPerlinStack == null) return null;
        lifecycle.readLock().lock();
        PerlinScratch scratch = null;
        boolean transferred = false;
        try {
            ensureOpen();
            Objects.requireNonNull(sourceLayers, "sourceLayers");
            Objects.requireNonNull(initialOutput, "initialOutput");
            if (sourceLayers.length == 0 || sourceLayers.length > 64) {
                throw new IllegalArgumentException("Perlin stack must contain between 1 and 64 layers");
            }
            int outputLength = validatePerlinStackDimensions(sizeX, sizeY, sizeZ,
                    stepX, stepY, stepZ, initialOutput);
            scratch = acquirePerlinScratch();
            scratch.ensureStackCapacity(sourceLayers.length, outputLength);
            copyPrefix(initialOutput, outputLength, scratch.output);
            writePerlinLayers(sourceLayers, xzScale, yScale, amplitude, scratch);
            long id = (long) submitPerlinStack.invokeExact(
                    scratch.layerDescriptors, sourceLayers.length,
                    sizeX, sizeY, sizeZ,
                    minX, minY, minZ, stepX, stepY, stepZ,
                    scratch.output, (long) outputLength, farLands ? 1 : 0);
            if (id == 0L) return null;
            NativeJob job = new NativeJob(this, id, outputLength, scratch);
            jobs.put(id, job);
            transferred = true;
            return job;
        } catch (Throwable failure) {
            throw rethrow("Unable to submit native Perlin stack job", failure);
        } finally {
            if (scratch != null && !transferred) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /** Submits a complete Perlin stack with one descriptor array and one shared output buffer. */
    public boolean supportsPerlinStack() { return submitPerlinStack != null; }

    public NativeJob submitPerlinStack(List<PerlinLayer> layers,
                                       int sizeX, int sizeY, int sizeZ,
                                       int minX, int minY, int minZ,
                                       int stepX, int stepY, int stepZ,
                                       float[] initialOutput, boolean farLands) {
        if (submitPerlinStack == null) return null;
        lifecycle.readLock().lock();
        PerlinScratch scratch = null;
        boolean transferred = false;
        try {
            ensureOpen();
            int outputLength = validatePerlinStackInputs(layers, sizeX, sizeY, sizeZ,
                    stepX, stepY, stepZ, initialOutput);
            scratch = acquirePerlinScratch();
            scratch.ensureStackCapacity(layers.size(), outputLength);
            copyPrefix(initialOutput, outputLength, scratch.output);
            writePerlinLayers(layers, scratch);
            long id = (long) submitPerlinStack.invokeExact(
                    scratch.layerDescriptors, layers.size(),
                    sizeX, sizeY, sizeZ,
                    minX, minY, minZ, stepX, stepY, stepZ,
                    scratch.output, (long) outputLength, farLands ? 1 : 0);
            if (id == 0L) return null;
            NativeJob job = new NativeJob(this, id, outputLength, scratch);
            jobs.put(id, job);
            transferred = true;
            return job;
        } catch (Throwable failure) {
            throw rethrow("Unable to submit native Perlin stack job", failure);
        } finally {
            if (scratch != null && !transferred) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    private static void writePerlinLayers(Object[] sourceLayers,
                                          double xzScale, double yScale, float amplitude,
                                          PerlinScratch scratch) {
        for (int i = 0; i < sourceLayers.length; ++i) {
            NoiseStackLayerAccessor layer = (NoiseStackLayerAccessor) Objects.requireNonNull(sourceLayers[i], "layer");
            Noise layerNoise = layer.jpe$noise();
            if (layerNoise == null || (layerNoise.getClass() != PerlinNoise.class
                    && layerNoise.getClass() != SmearedPerlinNoise.class)) {
                throw new IllegalArgumentException("Native Perlin stack only accepts exact PerlinNoise or SmearedPerlinNoise layers");
            }
            GradientNoiseAccessor noise = (GradientNoiseAccessor) layerNoise;
            byte[] permutation = noise.jpe$perms();
            if (permutation == null || permutation.length != 256) {
                throw new IllegalArgumentException("Each Perlin layer permutation must contain 256 bytes");
            }
            if (scratch.layerPermutationSources[i] != permutation) {
                MemorySegment.copy(permutation, 0, scratch.layerPermutations, ValueLayout.JAVA_BYTE,
                        (long) i * 256L, 256);
                scratch.layerPermutationSources[i] = permutation;
            }
            double frequency = layer.jpe$frequency();
            long base = Math.multiplyExact((long) i, PERLIN_LAYER_SIZE);
            scratch.layerDescriptors.set(ValueLayout.ADDRESS, base, scratch.layerPermutationSegments[i]);
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 8L, noise.jpe$offsetX());
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 16L, noise.jpe$offsetY());
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 24L, noise.jpe$offsetZ());
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 32L, xzScale * frequency);
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 40L, yScale * frequency);
            scratch.layerDescriptors.set(ValueLayout.JAVA_FLOAT, base + 48L, amplitude * layer.jpe$amplitude());
            boolean smeared = layerNoise.getClass() == SmearedPerlinNoise.class;
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 56L,
                    smeared ? ((SmearedPerlinNoiseAccessor) layerNoise).jpe$fudgeYScale() : 0.0);
            scratch.layerDescriptors.set(ValueLayout.JAVA_INT, base + 64L, smeared ? 1 : 0);
            scratch.layerDescriptors.set(ValueLayout.JAVA_INT, base + 68L, 0);
        }
    }


    // Compatibility path for asynchronous jobs and diagnostics. The synchronous
    // hot path uses Object[] directly and reuses cached permutation segments.
    private static void writePerlinLayers(List<PerlinLayer> layers, PerlinScratch scratch) {
        for (int i = 0; i < layers.size(); ++i) {
            PerlinLayer layer = Objects.requireNonNull(layers.get(i), "layer");
            byte[] permutation = layer.permutation();
            if (permutation == null || permutation.length != 256) {
                throw new IllegalArgumentException("Each Perlin layer permutation must contain 256 bytes");
            }
            if (scratch.layerPermutationSources[i] != permutation) {
                MemorySegment.copy(permutation, 0, scratch.layerPermutations, ValueLayout.JAVA_BYTE,
                        (long) i * 256L, 256);
                scratch.layerPermutationSources[i] = permutation;
            }
            long base = Math.multiplyExact((long) i, PERLIN_LAYER_SIZE);
            scratch.layerDescriptors.set(ValueLayout.ADDRESS, base, scratch.layerPermutationSegments[i]);
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 8L, layer.offsetX());
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 16L, layer.offsetY());
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 24L, layer.offsetZ());
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 32L, layer.xzScale());
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 40L, layer.yScale());
            scratch.layerDescriptors.set(ValueLayout.JAVA_FLOAT, base + 48L, layer.amplitude());
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 56L, 0.0);
            scratch.layerDescriptors.set(ValueLayout.JAVA_INT, base + 64L, 0);
            scratch.layerDescriptors.set(ValueLayout.JAVA_INT, base + 68L, 0);
        }
    }

    public record PerlinLayer(byte[] permutation, double offsetX, double offsetY, double offsetZ,
                              double xzScale, double yScale, float amplitude) {}

    private static int validatePerlinStackDimensions(int sizeX, int sizeY, int sizeZ,
                                                       int stepX, int stepY, int stepZ,
                                                       float[] output) {
        Objects.requireNonNull(output, "output");
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                || stepX == 0 || stepY == 0 || stepZ == 0) {
            throw new IllegalArgumentException("Invalid Perlin volume dimensions or axis steps");
        }
        long expected = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        if (expected > output.length || expected > 1_048_576L) {
            throw new IllegalArgumentException("Perlin output buffer is too small or volume exceeds native limit");
        }
        return Math.toIntExact(expected);
    }

    static int validatePerlinStackInputs(List<PerlinLayer> layers,
                                         int sizeX, int sizeY, int sizeZ,
                                         int stepX, int stepY, int stepZ,
                                         float[] output) {
        Objects.requireNonNull(layers, "layers");
        Objects.requireNonNull(output, "output");
        if (layers.isEmpty() || layers.size() > 64) {
            throw new IllegalArgumentException("Perlin stack must contain between 1 and 64 layers");
        }
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("Perlin dimensions must be non-zero");
        }
        if (stepX == 0 || stepY == 0 || stepZ == 0) {
            throw new IllegalArgumentException("Perlin axis steps must be non-zero");
        }
        for (PerlinLayer layer : layers) {
            Objects.requireNonNull(layer, "layer");
            if (layer.permutation() == null || layer.permutation().length != 256) {
                throw new IllegalArgumentException("Each Perlin layer permutation must contain 256 bytes");
            }
        }
        long expected = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        if (expected > output.length) throw new IllegalArgumentException("Output buffer is smaller than the requested volume");
        if (expected > 1_048_576L) throw new IllegalArgumentException("Perlin volume exceeds native 1,048,576-element limit");
        return Math.toIntExact(expected);
    }

    static int validatePerlinInputs(byte[] permutation,
                                    int sizeX, int sizeY, int sizeZ,
                                    int stepX, int stepY, int stepZ,
                                    float[] output) {
        Objects.requireNonNull(permutation, "permutation");
        Objects.requireNonNull(output, "output");
        if (permutation.length != 256) throw new IllegalArgumentException("Permutation table must contain 256 bytes");
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("Perlin dimensions must be non-zero");
        if (stepX == 0 || stepY == 0 || stepZ == 0) throw new IllegalArgumentException("Perlin axis steps must be non-zero");
        long expected = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        if (expected > output.length) throw new IllegalArgumentException("Output buffer is smaller than the requested volume");
        if (expected > 1_048_576L) throw new IllegalArgumentException("Perlin volume exceeds native 1,048,576-element limit");
        return Math.toIntExact(expected);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Native runtime is closed");
    }

    static int validatePerlinInputs(byte[] permutation, int[] blockX, int[] blockY,
                                             int[] blockZ, float[] output) {
        Objects.requireNonNull(permutation, "permutation");
        Objects.requireNonNull(blockX, "blockX");
        Objects.requireNonNull(blockY, "blockY");
        Objects.requireNonNull(blockZ, "blockZ");
        Objects.requireNonNull(output, "output");
        if (permutation.length != 256) throw new IllegalArgumentException("Permutation table must contain 256 bytes");
        if (blockX.length == 0 || blockY.length == 0 || blockZ.length == 0) {
            throw new IllegalArgumentException("Perlin dimensions must be non-zero");
        }
        long expected = Math.multiplyExact(Math.multiplyExact((long) blockX.length, blockY.length), blockZ.length);
        if (expected > output.length) throw new IllegalArgumentException("Output buffer is smaller than the requested volume");
        if (expected > 1_048_576L) throw new IllegalArgumentException("Perlin volume exceeds native 1,048,576-element limit");
        return Math.toIntExact(expected);
    }

    private static MemorySegment allocateBytes(Arena arena, int length) {
        return arena.allocate(length, ValueLayout.JAVA_BYTE.byteAlignment());
    }
    private static MemorySegment allocateFloats(Arena arena, int length) {
        return arena.allocate(Math.multiplyExact((long) length, ValueLayout.JAVA_FLOAT.byteSize()), ValueLayout.JAVA_FLOAT.byteAlignment());
    }
    private static void copy(byte[] source, MemorySegment target) {
        MemorySegment.copy(source, 0, target, ValueLayout.JAVA_BYTE, 0L, source.length);
    }
    private static void copy(float[] source, MemorySegment target) {
        MemorySegment.copy(source, 0, target, ValueLayout.JAVA_FLOAT, 0L, source.length);
    }
    private static void copy(MemorySegment source, float[] target) {
        MemorySegment.copy(source, ValueLayout.JAVA_FLOAT, 0L, target, 0, target.length);
    }
    private static void copyPrefix(float[] source, int length, MemorySegment target) {
        MemorySegment.copy(source, 0, target, ValueLayout.JAVA_FLOAT, 0L, length);
    }
    private static void copyPrefix(MemorySegment source, float[] target, int length) {
        MemorySegment.copy(source, ValueLayout.JAVA_FLOAT, 0L, target, 0, length);
    }
    private static void checkStatus(int status, String operation) {
        if (status != 0) throw new IllegalStateException("Native " + operation + " failed with status " + status);
    }
    private static RuntimeException rethrow(String message, Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, failure);
    }

    private void released(NativeJob job, long id) {
        jobs.remove(id, job);
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
        if (closed) return;
        if (!jobs.isEmpty()) {
            throw new IllegalStateException("Cannot unload native library while " + jobs.size()
                    + " jobs are still owned; cancel, await completion, and release each job first");
        }
        if (!densityGraphs.isEmpty()) {
            throw new IllegalStateException("Cannot unload native library while " + densityGraphs.size()
                    + " compiled density graphs are still retained; close each graph first");
        }
        closed = true;
        PerlinScratch scratch;
        while ((scratch = perlinScratchArenas.poll()) != null) {
            scratch.close();
        }
        DensityScratch density;
        while ((density = densityScratchArenas.poll()) != null) density.close();
        BlenderScratch blender;
        while ((blender = blenderScratchArenas.poll()) != null) blender.close();
        // The global FFM arena intentionally keeps the Rust library loaded until JVM exit.
    
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    public static final class NativeJob implements AutoCloseable {
        private final NativeRuntime runtime;
        private final long id;
        private final int outputLength;
        private final PerlinScratch scratch;
        private volatile boolean released;

        private NativeJob(NativeRuntime runtime, long id, int outputLength, PerlinScratch scratch) {
            this.runtime = runtime;
            this.id = id;
            this.outputLength = outputLength;
            this.scratch = scratch;
        }

        public long id() { return id; }
        public int outputLength() { return outputLength; }

        public synchronized int status() {
            ensureUsable();
            try {
                return (int) runtime.jobStatus.invokeExact(id);
            } catch (Throwable failure) {
                throw rethrow("Unable to query native job status", failure);
            }
        }

        /** Blocks until the job reaches a terminal state or the timeout expires. */
        public synchronized int waitForStatus(long timeoutNanos) {
            ensureUsable();
            if (timeoutNanos < 0L) throw new IllegalArgumentException("timeoutNanos must be non-negative");
            try {
                return (int) runtime.waitJob.invokeExact(id, timeoutNanos);
            } catch (Throwable failure) {
                throw rethrow("Unable to wait for native job", failure);
            }
        }

        public boolean isDone() {
            int status = status();
            return status == JOB_COMPLETE || status == JOB_FAILED || status == JOB_CANCELLED;
        }

        public synchronized boolean cancel() {
            ensureUsable();
            try {
                int result = (int) runtime.cancelJob.invokeExact(id);
                checkStatus(result, "cancel-job");
                return true;
            } catch (Throwable failure) {
                throw rethrow("Unable to cancel native job", failure);
            }
        }

        public float[] copyOutput() {
            float[] result = new float[outputLength];
            copyOutputInto(result);
            return result;
        }

        public synchronized void copyOutputInto(float[] destination) {
            ensureUsable();
            Objects.requireNonNull(destination, "destination");
            if (destination.length < outputLength) {
                throw new IllegalArgumentException("Destination buffer is smaller than " + outputLength);
            }
            try {
                int current = status();
                if (current != JOB_COMPLETE) {
                    throw new IllegalStateException("Native job output is unavailable; status=" + current);
                }
                copyPrefix(scratch.output, destination, outputLength);
            } catch (Throwable failure) {
                throw rethrow("Unable to copy native job output", failure);
            }
        }

        private void ensureUsable() {
            runtime.ensureOpen();
            if (released) throw new IllegalStateException("Native job has been released");
        }

        @Override
        public synchronized void close() {
            if (released) return;
            ensureUsable();
            try {
                int result = (int) runtime.releaseJob.invokeExact(id);
                if (result == JOB_RUNNING || result == JOB_PENDING) {
                    throw new IllegalStateException("Cannot release a native job before it reaches a terminal state");
                }
                checkStatus(result, "release-job");
                released = true;
                runtime.released(this, id);
                scratch.release();
            } catch (Throwable failure) {
                throw rethrow("Unable to release native job", failure);
            }
        }
    }
}
