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
    public static final int EXPECTED_ABI_VERSION = 6;
    private static final long PERLIN_LAYER_SIZE = 56L;

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
    private final MethodHandle preparePerlinStack;
    private final MethodHandle evaluatePreparedPerlinStack;
    private final MethodHandle releasePreparedPerlinStack;
    private final MethodHandle densityGraphCompile;
    private final MethodHandle densityGraphEvaluate;
    private final MethodHandle densityGraphEvaluateF32;
    private final MethodHandle densityGraphRelease;
    private final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> densityGraphHandles = new ConcurrentHashMap<>();
    private final java.util.Set<Long> preparedStackHandles = ConcurrentHashMap.newKeySet();
    private final MethodHandle jobStatus;
    private final MethodHandle waitJob;
    private final MethodHandle cancelJob;
    private final MethodHandle releaseJob;
    private final ConcurrentHashMap<Long, NativeJob> jobs = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    // Keep one reusable FFM scratch arena per calling worldgen worker. Only
    // permutation and output buffers remain after axis-coordinate generation moved native-side.
    private final ConcurrentLinkedQueue<PerlinScratch> perlinScratchArenas = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<PerlinScratch> perlinScratch = ThreadLocal.withInitial(() -> {
        PerlinScratch scratch = new PerlinScratch();
        perlinScratchArenas.add(scratch);
        return scratch;
    });
    private volatile boolean closed;

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
                          MethodHandle preparePreparedStack, MethodHandle evaluatePreparedStack,
                          MethodHandle releasePreparedStack,
                          MethodHandle graphCompile, MethodHandle graphEvaluate, MethodHandle graphEvaluateF32, MethodHandle graphRelease) {
        this.configureWorkers = configureWorkers;
        this.workerCount = workerCount;
        this.addF32 = add;
        this.perlinAddVolume = perlin;
        this.perlinAddStackHandle = perlinStack;
        this.submitPerlinVolume = submit;
        this.submitPerlinStack = submitStack;
        this.preparePerlinStack = preparePreparedStack;
        this.evaluatePreparedPerlinStack = evaluatePreparedStack;
        this.releasePreparedPerlinStack = releasePreparedStack;
        this.densityGraphCompile = graphCompile;
        this.densityGraphEvaluate = graphEvaluate;
        this.densityGraphEvaluateF32 = graphEvaluateF32;
        this.densityGraphRelease = graphRelease;
        this.jobStatus = status;
        this.waitJob = wait;
        this.cancelJob = cancel;
        this.releaseJob = release;
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
            MethodHandle preparePreparedStack = lookup.find("jpe_runtime_prepare_perlin_stack")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
                    .orElse(null);
            MethodHandle evaluatePreparedStack = lookup.find("jpe_runtime_evaluate_prepared_perlin_stack")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                                    ValueLayout.JAVA_FLOAT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)))
                    .orElse(null);
            MethodHandle releasePreparedStack = lookup.find("jpe_runtime_release_prepared_perlin_stack")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            if (preparePreparedStack == null || evaluatePreparedStack == null || releasePreparedStack == null) {
                preparePreparedStack = null;
                evaluatePreparedStack = null;
                releasePreparedStack = null;
            }

            MethodHandle graphCompile = lookup.find("jpe_runtime_density_graph_compile")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle graphEvaluate = lookup.find("jpe_runtime_density_graph_evaluate")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle graphEvaluateF32 = lookup.find("jpe_runtime_density_graph_evaluate_f32")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle graphRelease = lookup.find("jpe_runtime_density_graph_release")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            if (graphCompile == null || graphEvaluate == null || graphRelease == null) {
                graphCompile = null;
                graphEvaluate = null;
                graphRelease = null;
            }
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

            int actual = (int) version.invokeExact();
            if (actual != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException("Native ABI mismatch: expected " + EXPECTED_ABI_VERSION + ", got " + actual);
            }
            int requestedWorkers = requestedNativeWorkers();
            int actualWorkers = (int) configureWorkers.invokeExact(requestedWorkers);
            if (actualWorkers < 1) throw new IllegalStateException("Unable to configure native workers: " + actualWorkers);
            return new NativeRuntime(configureWorkers, actualWorkers, add, perlin, perlinStack, submit, submitStack, status, wait, cancel, release,
                    preparePreparedStack, evaluatePreparedStack, releasePreparedStack,
                    graphCompile, graphEvaluate, graphEvaluateF32, graphRelease);
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
    /** True when the loaded native runtime supports immutable prepared NoiseStack handles. */
    public boolean supportsPreparedPerlinStack() {
        return preparePerlinStack != null && evaluatePreparedPerlinStack != null
                && releasePreparedPerlinStack != null;
    }

    /** Copies a NoiseStack's immutable octave data into C++ exactly once. */
    public long preparePerlinStack(Object[] sourceLayers) {
        if (!supportsPreparedPerlinStack()) return 0L;
        lifecycle.readLock().lock();
        PerlinScratch scratch = null;
        try {
            ensureOpen();
            Objects.requireNonNull(sourceLayers, "sourceLayers");
            if (sourceLayers.length == 0 || sourceLayers.length > 64) {
                throw new IllegalArgumentException("Perlin stack must contain between 1 and 64 layers");
            }
            scratch = acquirePerlinScratch();
            scratch.ensureStackCapacity(sourceLayers.length, 1);
            writePerlinBaseLayers(sourceLayers, scratch);
            long handle = (long) preparePerlinStack.invokeExact(scratch.layerDescriptors, sourceLayers.length);
            if (handle != 0L) preparedStackHandles.add(handle);
            return handle;
        } catch (Throwable failure) {
            throw rethrow("Unable to prepare native Perlin stack", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /** Evaluates a prepared C++ NoiseStack without resending layer descriptors or permutations. */
    public void evaluatePreparedPerlinStack(long handle,
                                            double xzScale, double yScale, float amplitude,
                                            int sizeX, int sizeY, int sizeZ,
                                            int minX, int minY, int minZ,
                                            int stepX, int stepY, int stepZ,
                                            float[] output, boolean farLands) {
        if (!supportsPreparedPerlinStack()) throw new IllegalStateException("Prepared Perlin API is unavailable");
        lifecycle.readLock().lock();
        PerlinScratch scratch = null;
        try {
            ensureOpen();
            Objects.requireNonNull(output, "output");
            if (handle == 0L || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                    || stepX == 0 || stepY == 0 || stepZ == 0) {
                throw new IllegalArgumentException("Invalid prepared Perlin handle or volume");
            }
            int outputLength = Math.toIntExact(Math.multiplyExact(
                    Math.multiplyExact((long) sizeX, sizeY), sizeZ));
            if (outputLength > output.length || outputLength > 1_048_576) {
                throw new IllegalArgumentException("Prepared Perlin output buffer is too small or volume exceeds native limit");
            }
            scratch = acquirePerlinScratch();
            scratch.ensureCapacity(256, outputLength);
            copyPrefix(output, outputLength, scratch.output);
            int status = (int) evaluatePreparedPerlinStack.invokeExact(
                    handle, xzScale, yScale, amplitude,
                    sizeX, sizeY, sizeZ, minX, minY, minZ,
                    stepX, stepY, stepZ,
                    scratch.output, (long) outputLength, farLands ? 1 : 0);
            checkStatus(status, "evaluate-prepared-perlin-stack");
            copyPrefix(scratch.output, output, outputLength);
        } catch (Throwable failure) {
            throw rethrow("Unable to evaluate prepared native Perlin stack", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    public void releasePreparedPerlinStack(long handle) {
        if (handle == 0L || releasePreparedPerlinStack == null) return;
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            if (!preparedStackHandles.remove(handle)) return;
            int status = (int) releasePreparedPerlinStack.invokeExact(handle);
            checkStatus(status, "release-prepared-perlin-stack");
        } catch (Throwable failure) {
            throw rethrow("Unable to release prepared native Perlin stack", failure);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

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

    private static void writePerlinBaseLayers(Object[] sourceLayers, PerlinScratch scratch) {
        for (int i = 0; i < sourceLayers.length; ++i) {
            NoiseStackLayerAccessor layer = (NoiseStackLayerAccessor) Objects.requireNonNull(sourceLayers[i], "layer");
            Noise layerNoise = layer.jpe$noise();
            if (layerNoise == null || layerNoise.getClass() != PerlinNoise.class) {
                throw new IllegalArgumentException("Native prepared stack only accepts exact PerlinNoise layers");
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
            // Prepared API interprets these as immutable per-layer frequencies/amplitudes.
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 32L, frequency);
            scratch.layerDescriptors.set(ValueLayout.JAVA_DOUBLE, base + 40L, frequency);
            scratch.layerDescriptors.set(ValueLayout.JAVA_FLOAT, base + 48L, layer.jpe$amplitude());
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

    /** Density graph opcodes mirror JPE_DENSITY_* in jpe_native_api.h. */
    public record DensityNode(int opcode, int inputA, int inputB, int reserved,
                              double immediate, double auxiliary, double tertiary) {
        public static DensityNode constant(double value) {
            return new DensityNode(1, -1, -1, 0, value, 0.0, 0.0);
        }
        public static DensityNode x() { return new DensityNode(2, -1, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode y() { return new DensityNode(3, -1, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode z() { return new DensityNode(4, -1, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode add(int a, int b) { return new DensityNode(5, a, b, 0, 0.0, 0.0, 0.0); }
        public static DensityNode multiply(int a, int b) { return new DensityNode(6, a, b, 0, 0.0, 0.0, 0.0); }
        public static DensityNode subtract(int a, int b) { return new DensityNode(8, a, b, 0, 0.0, 0.0, 0.0); }
        public static DensityNode divide(int a, int b) { return new DensityNode(9, a, b, 0, 0.0, 0.0, 0.0); }
        public static DensityNode min(int a, int b) { return new DensityNode(10, a, b, 0, 0.0, 0.0, 0.0); }
        public static DensityNode max(int a, int b) { return new DensityNode(11, a, b, 0, 0.0, 0.0, 0.0); }
        public static DensityNode abs(int input) { return new DensityNode(12, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode negate(int input) { return new DensityNode(13, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode square(int input) { return new DensityNode(14, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode cube(int input) { return new DensityNode(15, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode sqrt(int input) { return new DensityNode(16, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode halfNegative(int input) { return new DensityNode(17, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode quarterNegative(int input) { return new DensityNode(18, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode reciprocal(int input) { return new DensityNode(19, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode sign(int input) { return new DensityNode(20, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode pow(int a, int b) { return new DensityNode(21, a, b, 0, 0.0, 0.0, 0.0); }
        public static DensityNode clamp(int input, float minimum, float maximum) {
            if (!Float.isFinite(minimum) || !Float.isFinite(maximum) || minimum > maximum) {
                throw new IllegalArgumentException("Invalid density clamp bounds");
            }
            return new DensityNode(22, input, Float.floatToRawIntBits(minimum),
                    Float.floatToRawIntBits(maximum), 0.0, 0.0, 0.0);
        }
        public static DensityNode perlinStack(long preparedHandle, double xzScale, double yScale, float amplitude) {
            if (preparedHandle <= 0 || preparedHandle > 9_007_199_254_740_991L
                    || !Double.isFinite(xzScale) || !Double.isFinite(yScale) || !Float.isFinite(amplitude)) {
                throw new IllegalArgumentException("Invalid prepared Perlin stack parameters");
            }
            return new DensityNode(7, -1, -1, Float.floatToRawIntBits(amplitude),
                    (double) preparedHandle, xzScale, yScale);
        }
    }

    public boolean supportsDensityGraph() {
        return densityGraphCompile != null && densityGraphEvaluate != null && densityGraphRelease != null;
    }

    /** Minecraft 26.3's density pipeline uses float at every intermediate step. */
    public boolean supportsDensityGraphF32() {
        return densityGraphCompile != null && densityGraphEvaluateF32 != null && densityGraphRelease != null;
    }

    /** Compiles a topologically ordered native DAG. PERLIN_STACK nodes retain their C++ plans. */
    public long compileDensityGraph(List<DensityNode> nodes, int rootNode, long configurationFingerprint) {
        if (!supportsDensityGraph()) throw new IllegalStateException("Native density graph API is unavailable");
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.isEmpty() || nodes.size() > 65_536 || rootNode < 0 || rootNode >= nodes.size()) {
            throw new IllegalArgumentException("Invalid density graph node count or root");
        }
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment encoded = arena.allocate(Math.multiplyExact((long) nodes.size(), 40L), 8);
                for (int i = 0; i < nodes.size(); ++i) {
                    DensityNode node = Objects.requireNonNull(nodes.get(i), "node");
                    long offset = (long) i * 40L;
                    encoded.set(ValueLayout.JAVA_INT, offset, node.opcode());
                    encoded.set(ValueLayout.JAVA_INT, offset + 4L, node.inputA());
                    encoded.set(ValueLayout.JAVA_INT, offset + 8L, node.inputB());
                    encoded.set(ValueLayout.JAVA_INT, offset + 12L, node.reserved());
                    encoded.set(ValueLayout.JAVA_DOUBLE, offset + 16L, node.immediate());
                    encoded.set(ValueLayout.JAVA_DOUBLE, offset + 24L, node.auxiliary());
                    encoded.set(ValueLayout.JAVA_DOUBLE, offset + 32L, node.tertiary());
                }
                long handle = (long) densityGraphCompile.invokeExact(
                        encoded, nodes.size(), rootNode, configurationFingerprint);
                if (handle == 0L) throw new IllegalArgumentException("Native density graph rejected the plan");
                densityGraphHandles.computeIfAbsent(handle,
                        ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                return handle;
            } catch (Throwable failure) {
                throw rethrow("Unable to compile native density graph", failure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Evaluates one complete volume; output ordering is ((z * sizeX + x) * sizeY + y). */
    public void evaluateDensityGraph(long handle,
                                     int sizeX, int sizeY, int sizeZ,
                                     int minX, int minY, int minZ,
                                     int stepX, int stepY, int stepZ,
                                     double[] output) {
        if (!supportsDensityGraph()) throw new IllegalStateException("Native density graph API is unavailable");
        Objects.requireNonNull(output, "output");
        long length = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        if (handle == 0L || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                || stepX == 0 || stepY == 0 || stepZ == 0
                || length > output.length || length > 1_048_576L) {
            throw new IllegalArgumentException("Invalid native density graph volume");
        }
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment nativeOutput = arena.allocate(Math.multiplyExact(length, 8L), 8);
                int status = (int) densityGraphEvaluate.invokeExact(
                        handle, sizeX, sizeY, sizeZ, minX, minY, minZ,
                        stepX, stepY, stepZ, nativeOutput, length);
                checkStatus(status, "density-graph-evaluate");
                MemorySegment.copy(nativeOutput, ValueLayout.JAVA_DOUBLE, 0L, output, 0, Math.toIntExact(length));
            } catch (Throwable failure) {
                throw rethrow("Unable to evaluate native density graph", failure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Evaluates a complete volume with IEEE-754 single-precision intermediates. */
    public void evaluateDensityGraphF32(long handle,
                                        int sizeX, int sizeY, int sizeZ,
                                        int minX, int minY, int minZ,
                                        int stepX, int stepY, int stepZ,
                                        float[] output) {
        if (!supportsDensityGraphF32()) throw new IllegalStateException("Native f32 density graph API is unavailable");
        Objects.requireNonNull(output, "output");
        long length = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        if (handle == 0L || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                || stepX == 0 || stepY == 0 || stepZ == 0
                || length > output.length || length > 1_048_576L) {
            throw new IllegalArgumentException("Invalid native f32 density graph volume");
        }
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment nativeOutput = arena.allocate(Math.multiplyExact(length, 4L), 4);
                int status = (int) densityGraphEvaluateF32.invokeExact(
                        handle, sizeX, sizeY, sizeZ, minX, minY, minZ,
                        stepX, stepY, stepZ, nativeOutput, length);
                checkStatus(status, "density-graph-evaluate-f32");
                MemorySegment.copy(nativeOutput, ValueLayout.JAVA_FLOAT, 0L, output, 0, Math.toIntExact(length));
            } catch (Throwable failure) {
                throw rethrow("Unable to evaluate native f32 density graph", failure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void releaseDensityGraph(long handle) {
        if (handle == 0L || densityGraphRelease == null) return;
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            java.util.concurrent.atomic.AtomicInteger references = densityGraphHandles.get(handle);
            if (references == null) return;
            int status;
            try {
                status = (int) densityGraphRelease.invokeExact(handle);
            } catch (Throwable failure) {
                throw rethrow("Unable to release native density graph", failure);
            }
            checkStatus(status, "density-graph-release");
            if (references.decrementAndGet() == 0) densityGraphHandles.remove(handle, references);
        } finally {
            lifecycle.readLock().unlock();
        }
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
        closed = true;
        if (densityGraphRelease != null) {
            for (var entry : densityGraphHandles.entrySet()) {
                for (int i = 0; i < entry.getValue().get(); ++i) {
                    try {
                        int status = (int) densityGraphRelease.invokeExact(entry.getKey().longValue());
                        if (status != 0) throw new IllegalStateException("Density graph release status=" + status);
                    } catch (Throwable failure) {
                        throw rethrow("Unable to release density graph during runtime close", failure);
                    }
                }
            }
            densityGraphHandles.clear();
        }
        if (releasePreparedPerlinStack != null) {
            for (Long handle : preparedStackHandles) {
                try {
                    int status = (int) releasePreparedPerlinStack.invokeExact(handle.longValue());
                    if (status != 0) throw new IllegalStateException("Prepared stack release status=" + status);
                } catch (Throwable failure) {
                    throw rethrow("Unable to release prepared stack during runtime close", failure);
                }
            }
            preparedStackHandles.clear();
        }
        PerlinScratch scratch;
        while ((scratch = perlinScratchArenas.poll()) != null) {
            scratch.close();
        }
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