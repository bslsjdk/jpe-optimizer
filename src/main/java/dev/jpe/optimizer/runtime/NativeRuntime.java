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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import dev.jpe.optimizer.mixin.GradientNoiseAccessor;
import dev.jpe.optimizer.mixin.NoiseStackLayerAccessor;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Optional Java 25 FFM bridge. Native worldgen is not loaded or enabled automatically.
 *
 * Async jobs borrow leased shared FFM buffers until terminal status and explicit release.
 * Completed jobs must be explicitly released before this runtime can be closed.
 */
public final class NativeRuntime implements AutoCloseable {
    public static final int EXPECTED_ABI_VERSION = 7;
    private static final long PERLIN_LAYER_SIZE = 56L;
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
    private final MethodHandle releaseDensityGraph;
    private final MethodHandle loadDensityGraphCache;
    private final MethodHandle saveDensityGraphCache;
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
    private final ThreadLocal<PerlinScratch> perlinScratch = ThreadLocal.withInitial(() -> {
        PerlinScratch scratch = new PerlinScratch();
        perlinScratchArenas.add(scratch);
        return scratch;
    });
    private volatile boolean closed;

    private static final class DensityScratch implements AutoCloseable {
        private final Arena arena = Arena.ofShared();
        private MemorySegment encoded;
        private int capacity;
        private MemorySegment output;
        private int outputCapacity;
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
        @Override public void close() {
            if (leased.get()) throw new IllegalStateException("Cannot close a leased density scratch arena");
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

    private NativeRuntime(MethodHandle configureWorkers, int workerCount,
                          MethodHandle add, MethodHandle perlin, MethodHandle perlinStack, MethodHandle submit, MethodHandle submitStack,
                          MethodHandle status, MethodHandle wait,
                          MethodHandle cancel, MethodHandle release,
                          MethodHandle compileDensityGraph, MethodHandle evaluateDensityGraph,
                          MethodHandle releaseDensityGraph, MethodHandle loadDensityGraphCache,
                          MethodHandle saveDensityGraphCache) {
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
        this.releaseDensityGraph = releaseDensityGraph;
        this.loadDensityGraphCache = loadDensityGraphCache;
        this.saveDensityGraphCache = saveDensityGraphCache;
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
            MethodHandle releaseDensityGraph = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_release").orElseThrow(() -> new IllegalStateException("Missing density graph release symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            MethodHandle loadDensityGraphCache = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_load_cache").orElseThrow(() -> new IllegalStateException("Missing native density cache load symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle saveDensityGraphCache = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_save_cache").orElseThrow(() -> new IllegalStateException("Missing native density cache save symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            int actual = (int) version.invokeExact();
            if (actual != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException("Native ABI mismatch: expected " + EXPECTED_ABI_VERSION + ", got " + actual);
            }
            int requestedWorkers = requestedNativeWorkers();
            int actualWorkers = (int) configureWorkers.invokeExact(requestedWorkers);
            if (actualWorkers < 1) throw new IllegalStateException("Unable to configure native workers: " + actualWorkers);
            return new NativeRuntime(configureWorkers, actualWorkers, add, perlin, perlinStack, submit, submitStack, status, wait, cancel, release,
                    compileDensityGraph, evaluateDensityGraph, releaseDensityGraph,
                    loadDensityGraphCache, saveDensityGraphCache);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to load JPE native runtime from " + absolutePath, failure);
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
            if (layerNoise == null || layerNoise.getClass() != PerlinNoise.class) {
                throw new IllegalArgumentException("Native Perlin stack only accepts exact PerlinNoise layers");
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
        }
    }

    public record PerlinLayer(byte[] permutation, double offsetX, double offsetY, double offsetZ,
                              double xzScale, double yScale, float amplitude) {}

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
