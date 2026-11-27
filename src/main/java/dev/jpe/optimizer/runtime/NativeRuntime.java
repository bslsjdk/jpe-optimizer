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
import dev.jpe.optimizer.mixin.DensityBufferAccessor;
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
    public static final int EXPECTED_ABI_VERSION = 8;
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
    private final MethodHandle densityGraphEvaluatePoints;
    private final MethodHandle densityGraphEvaluatePointsF32;
    private final MethodHandle densityGraphEvaluateMultiPointsF32;
    private final MethodHandle densityGraphRelease;
    private final MethodHandle aquiferGridCenters;
    private final MethodHandle aquiferNearestFour;
    private final MethodHandle aquiferSubstanceBatch;
    private final MethodHandle aquiferStatusCacheCreate;
    private final MethodHandle aquiferStatusCacheDestroy;
    private final MethodHandle aquiferStatusCacheLookup;
    private final MethodHandle aquiferStatusCacheInsert;
    private final MethodHandle aquiferContextCreate;
    private final MethodHandle aquiferContextDestroy;
    private final MethodHandle aquiferContextEvaluateSamplersF32;
    private final MethodHandle aquiferContextComputeFluidStatusBatch;
    private final MethodHandle aquiferContextConfigureGlobalFluidPicker;
    private final MethodHandle aquiferContextComputeFluidStatusPointsBatch;
    private final MethodHandle aquiferContextComputeSubstanceVolumeF32;
    private final MethodHandle aquiferContextComputeDensityAndSubstanceVolumeF32;
    private final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> densityGraphHandles = new ConcurrentHashMap<>();
    private final java.util.Set<Long> preparedStackHandles = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> aquiferStatusCacheHandles = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> aquiferContextHandles = ConcurrentHashMap.newKeySet();
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
    // Per-worldgen-worker FFM storage for Aquifer batch inputs and outputs.
    private final ConcurrentLinkedQueue<AquiferScratch> aquiferScratchArenas = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<AquiferScratch> aquiferScratch = ThreadLocal.withInitial(() -> {
        AquiferScratch scratch = new AquiferScratch();
        aquiferScratchArenas.add(scratch);
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

    private static final class AquiferScratch implements AutoCloseable {
        private final Arena arena = Arena.ofShared();
        private MemorySegment inputX, inputY, inputZ, outputX, outputY, outputZ, distances;
        private int inputCapacity, outputCapacity;
        private MemorySegment substanceInputs, substanceOutputs;
        private int substanceInputCapacity, substanceOutputCapacity;
        private MemorySegment statusKeys, statusValues, statusFound;
        private int statusCapacity;
        private MemorySegment pointX, pointY, pointZ, pointOutputF32, pointOutputF64;
        private int pointCapacity;
        private MemorySegment pointGraphHandles;
        private int pointGraphHandleCapacity;
        private MemorySegment packedLocations;
        private int packedLocationCapacity;
        private MemorySegment contextWorldFingerprint;
        private MemorySegment contextStatusKeys, contextStatusInputs, contextStatusOutputs;
        private int contextStatusCapacity;
        private MemorySegment contextPointStatusKeys, contextPointX, contextPointY, contextPointZ;
        private MemorySegment contextPointStatusOutputs;
        private int contextPointStatusCapacity;
        private final AtomicBoolean leased = new AtomicBoolean();

        private boolean tryAcquire() { return leased.compareAndSet(false, true); }
        private void release() {
            if (!leased.compareAndSet(true, false)) {
                throw new IllegalStateException("Aquifer scratch lease was not active");
            }
        }
        private MemorySegment[] ensureCapacity(int inputs) {
            if (inputCapacity < inputs) {
                int next = Math.max(256, inputCapacity);
                while (next < inputs) next = Math.min(1_048_576, Math.multiplyExact(next, 2));
                long bytes = Math.multiplyExact((long) next, ValueLayout.JAVA_INT.byteSize());
                inputX = arena.allocate(bytes, ValueLayout.JAVA_INT.byteAlignment());
                inputY = arena.allocate(bytes, ValueLayout.JAVA_INT.byteAlignment());
                inputZ = arena.allocate(bytes, ValueLayout.JAVA_INT.byteAlignment());
                inputCapacity = next;
            }
            int requiredOutputs = Math.multiplyExact(inputs, 4);
            if (outputCapacity < requiredOutputs) {
                int next = Math.max(1024, outputCapacity);
                while (next < requiredOutputs) next = Math.multiplyExact(next, 2);
                long bytes = Math.multiplyExact((long) next, ValueLayout.JAVA_INT.byteSize());
                outputX = arena.allocate(bytes, ValueLayout.JAVA_INT.byteAlignment());
                outputY = arena.allocate(bytes, ValueLayout.JAVA_INT.byteAlignment());
                outputZ = arena.allocate(bytes, ValueLayout.JAVA_INT.byteAlignment());
                distances = arena.allocate(bytes, ValueLayout.JAVA_INT.byteAlignment());
                outputCapacity = next;
            }
            return new MemorySegment[] {inputX, inputY, inputZ, outputX, outputY, outputZ, distances};
        }
        private MemorySegment[] ensureSubstanceCapacity(int count) {
            if (substanceInputCapacity < count) {
                int next = Math.max(128, substanceInputCapacity);
                while (next < count) next = Math.min(1_048_576, Math.multiplyExact(next, 2));
                long bytes = Math.multiplyExact((long) next, 88L);
                substanceInputs = arena.allocate(bytes, 8);
                substanceInputCapacity = next;
            }
            if (substanceOutputCapacity < count) {
                int next = Math.max(256, substanceOutputCapacity);
                while (next < count) next = Math.min(1_048_576, Math.multiplyExact(next, 2));
                long bytes = Math.multiplyExact((long) next, 12L);
                substanceOutputs = arena.allocate(bytes, 4);
                substanceOutputCapacity = next;
            }
            return new MemorySegment[] {substanceInputs, substanceOutputs};
        }
        private MemorySegment[] ensureStatusCapacity(int count) {
            if (statusCapacity < count) {
                int next = Math.max(128, statusCapacity);
                while (next < count) next = Math.min(1_048_576, Math.multiplyExact(next, 2));
                long keyBytes = Math.multiplyExact((long) next, ValueLayout.JAVA_LONG.byteSize());
                long statusBytes = Math.multiplyExact((long) next, 8L);
                statusKeys = arena.allocate(keyBytes, ValueLayout.JAVA_LONG.byteAlignment());
                statusValues = arena.allocate(statusBytes, ValueLayout.JAVA_INT.byteAlignment());
                statusFound = arena.allocate(next, ValueLayout.JAVA_BYTE.byteAlignment());
                statusCapacity = next;
            }
            return new MemorySegment[] {statusKeys, statusValues, statusFound};
        }
        private MemorySegment[] ensurePointCapacity(int count) {
            if (pointCapacity < count) {
                int next = Math.max(128, pointCapacity);
                while (next < count) next = Math.min(1_048_576, Math.multiplyExact(next, 2));
                long coordinateBytes = Math.multiplyExact((long) next, ValueLayout.JAVA_INT.byteSize());
                long outputF32Bytes = Math.multiplyExact((long) next, ValueLayout.JAVA_FLOAT.byteSize());
                long outputF64Bytes = Math.multiplyExact((long) next, ValueLayout.JAVA_DOUBLE.byteSize());
                pointX = arena.allocate(coordinateBytes, ValueLayout.JAVA_INT.byteAlignment());
                pointY = arena.allocate(coordinateBytes, ValueLayout.JAVA_INT.byteAlignment());
                pointZ = arena.allocate(coordinateBytes, ValueLayout.JAVA_INT.byteAlignment());
                pointOutputF32 = arena.allocate(outputF32Bytes, ValueLayout.JAVA_FLOAT.byteAlignment());
                pointOutputF64 = arena.allocate(outputF64Bytes, ValueLayout.JAVA_DOUBLE.byteAlignment());
                pointCapacity = next;
            }
            return new MemorySegment[] {pointX, pointY, pointZ, pointOutputF32, pointOutputF64};
        }
        private MemorySegment ensurePointGraphHandleCapacity(int count) {
            if (pointGraphHandleCapacity < count) {
                int next = Math.max(8, pointGraphHandleCapacity);
                while (next < count) next = Math.min(64, Math.multiplyExact(next, 2));
                long bytes = Math.multiplyExact((long) next, ValueLayout.JAVA_LONG.byteSize());
                pointGraphHandles = arena.allocate(bytes, ValueLayout.JAVA_LONG.byteAlignment());
                pointGraphHandleCapacity = next;
            }
            return pointGraphHandles;
        }
        private MemorySegment ensurePackedLocationCapacity(int count) {
            if (packedLocationCapacity < count) {
                int next = Math.max(256, packedLocationCapacity);
                while (next < count) next = Math.min(1_048_576, Math.multiplyExact(next, 2));
                long bytes = Math.multiplyExact((long) next, ValueLayout.JAVA_LONG.byteSize());
                packedLocations = arena.allocate(bytes, ValueLayout.JAVA_LONG.byteAlignment());
                packedLocationCapacity = next;
            }
            return packedLocations;
        }
        private MemorySegment ensureContextWorldFingerprint() {
            if (contextWorldFingerprint == null) {
                contextWorldFingerprint = arena.allocate(32L, ValueLayout.JAVA_BYTE.byteAlignment());
            }
            return contextWorldFingerprint;
        }
        private MemorySegment[] ensureContextStatusCapacity(int count) {
            if (count <= 0 || count > 65_536) {
                throw new IllegalArgumentException("Aquifer FluidStatus batch must be in [1, 65536]");
            }
            if (contextStatusCapacity < count) {
                int next = Math.max(64, contextStatusCapacity);
                while (next < count) next = Math.min(65_536, Math.multiplyExact(next, 2));
                long keyBytes = Math.multiplyExact((long) next, ValueLayout.JAVA_LONG.byteSize());
                long inputBytes = Math.multiplyExact((long) next, 292L);
                long outputBytes = Math.multiplyExact((long) next, 8L);
                contextStatusKeys = arena.allocate(keyBytes, ValueLayout.JAVA_LONG.byteAlignment());
                contextStatusInputs = arena.allocate(inputBytes, ValueLayout.JAVA_DOUBLE.byteAlignment());
                contextStatusOutputs = arena.allocate(outputBytes, ValueLayout.JAVA_LONG.byteAlignment());
                contextStatusCapacity = next;
            }
            return new MemorySegment[] {contextStatusKeys, contextStatusInputs, contextStatusOutputs};
        }
        private MemorySegment[] ensureContextPointStatusCapacity(int count) {
            if (count <= 0 || count > 65_536) {
                throw new IllegalArgumentException("Aquifer point-status batch must be in [1, 65536]");
            }
            if (contextPointStatusCapacity < count) {
                int next = Math.max(64, contextPointStatusCapacity);
                while (next < count) next = Math.min(65_536, Math.multiplyExact(next, 2));
                long keyBytes = Math.multiplyExact((long) next, ValueLayout.JAVA_LONG.byteSize());
                long coordinateBytes = Math.multiplyExact((long) next, ValueLayout.JAVA_INT.byteSize());
                long outputBytes = Math.multiplyExact((long) next, 8L);
                contextPointStatusKeys = arena.allocate(keyBytes, ValueLayout.JAVA_LONG.byteAlignment());
                contextPointX = arena.allocate(coordinateBytes, ValueLayout.JAVA_INT.byteAlignment());
                contextPointY = arena.allocate(coordinateBytes, ValueLayout.JAVA_INT.byteAlignment());
                contextPointZ = arena.allocate(coordinateBytes, ValueLayout.JAVA_INT.byteAlignment());
                contextPointStatusOutputs = arena.allocate(outputBytes, ValueLayout.JAVA_LONG.byteAlignment());
                contextPointStatusCapacity = next;
            }
            return new MemorySegment[] {
                    contextPointStatusKeys, contextPointX, contextPointY, contextPointZ, contextPointStatusOutputs
            };
        }
        @Override
        public void close() {
            if (leased.get()) throw new IllegalStateException("Cannot close a leased Aquifer scratch arena");
            arena.close();
        }
    }

    private AquiferScratch acquireAquiferScratch() {
        AquiferScratch scratch = aquiferScratch.get();
        if (scratch.tryAcquire()) return scratch;
        scratch = new AquiferScratch();
        aquiferScratch.set(scratch);
        aquiferScratchArenas.add(scratch);
        if (!scratch.tryAcquire()) throw new IllegalStateException("Unable to acquire Aquifer scratch arena");
        return scratch;
    }

    private NativeRuntime(MethodHandle configureWorkers, int workerCount,
                          MethodHandle add, MethodHandle perlin, MethodHandle perlinStack, MethodHandle submit, MethodHandle submitStack,
                          MethodHandle status, MethodHandle wait,
                          MethodHandle cancel, MethodHandle release,
                          MethodHandle preparePreparedStack, MethodHandle evaluatePreparedStack,
                          MethodHandle releasePreparedStack,
                          MethodHandle graphCompile, MethodHandle graphEvaluate, MethodHandle graphEvaluateF32,
                          MethodHandle graphEvaluatePoints, MethodHandle graphEvaluatePointsF32,
                          MethodHandle graphEvaluateMultiPointsF32, MethodHandle graphRelease,
                          MethodHandle aquiferGridCenters, MethodHandle aquiferNearestFour,
                          MethodHandle aquiferSubstanceBatch,
                          MethodHandle aquiferStatusCacheCreate, MethodHandle aquiferStatusCacheDestroy,
                          MethodHandle aquiferStatusCacheLookup, MethodHandle aquiferStatusCacheInsert,
                          MethodHandle aquiferContextCreate, MethodHandle aquiferContextDestroy,
                          MethodHandle aquiferContextEvaluateSamplersF32,
                          MethodHandle aquiferContextComputeFluidStatusBatch,
                          MethodHandle aquiferContextConfigureGlobalFluidPicker,
                          MethodHandle aquiferContextComputeFluidStatusPointsBatch,
                          MethodHandle aquiferContextComputeSubstanceVolumeF32,
                          MethodHandle aquiferContextComputeDensityAndSubstanceVolumeF32) {
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
        this.densityGraphEvaluatePoints = graphEvaluatePoints;
        this.densityGraphEvaluatePointsF32 = graphEvaluatePointsF32;
        this.densityGraphEvaluateMultiPointsF32 = graphEvaluateMultiPointsF32;
        this.densityGraphRelease = graphRelease;
        this.aquiferGridCenters = aquiferGridCenters;
        this.aquiferNearestFour = aquiferNearestFour;
        this.aquiferSubstanceBatch = aquiferSubstanceBatch;
        this.aquiferStatusCacheCreate = aquiferStatusCacheCreate;
        this.aquiferStatusCacheDestroy = aquiferStatusCacheDestroy;
        this.aquiferStatusCacheLookup = aquiferStatusCacheLookup;
        this.aquiferStatusCacheInsert = aquiferStatusCacheInsert;
        this.aquiferContextCreate = aquiferContextCreate;
        this.aquiferContextDestroy = aquiferContextDestroy;
        this.aquiferContextEvaluateSamplersF32 = aquiferContextEvaluateSamplersF32;
        this.aquiferContextComputeFluidStatusBatch = aquiferContextComputeFluidStatusBatch;
        this.aquiferContextConfigureGlobalFluidPicker = aquiferContextConfigureGlobalFluidPicker;
        this.aquiferContextComputeFluidStatusPointsBatch = aquiferContextComputeFluidStatusPointsBatch;
        this.aquiferContextComputeSubstanceVolumeF32 = aquiferContextComputeSubstanceVolumeF32;
        this.aquiferContextComputeDensityAndSubstanceVolumeF32 =
                aquiferContextComputeDensityAndSubstanceVolumeF32;
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
            MethodHandle graphEvaluatePoints = lookup.find("jpe_runtime_density_graph_evaluate_points")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle graphEvaluatePointsF32 = lookup.find("jpe_runtime_density_graph_evaluate_points_f32")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle graphEvaluateMultiPointsF32 = lookup.find("jpe_runtime_density_graph_evaluate_multi_points_f32")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle graphRelease = lookup.find("jpe_runtime_density_graph_release")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle aquiferGridCenters = lookup.find("jpe_runtime_aquifer_grid_centers")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS)))
                    .orElse(null);
            MethodHandle aquiferNearestFour = lookup.find("jpe_runtime_aquifer_nearest_four")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS)))
                    .orElse(null);
            MethodHandle aquiferSubstanceBatch = lookup.find("jpe_runtime_aquifer_compute_substance_batch")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
                    .orElse(null);
            MethodHandle aquiferStatusCacheCreate = lookup.find("jpe_runtime_aquifer_status_cache_create")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)))
                    .orElse(null);
            MethodHandle aquiferStatusCacheDestroy = lookup.find("jpe_runtime_aquifer_status_cache_destroy")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle aquiferStatusCacheLookup = lookup.find("jpe_runtime_aquifer_status_cache_lookup_batch")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS)))
                    .orElse(null);
            MethodHandle aquiferStatusCacheInsert = lookup.find("jpe_runtime_aquifer_status_cache_insert_batch")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT)))
                    .orElse(null);
            MethodHandle aquiferContextCreate = lookup.find("jpe_runtime_aquifer_context_create")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)))
                    .orElse(null);
            MethodHandle aquiferContextDestroy = lookup.find("jpe_runtime_aquifer_context_destroy")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle aquiferContextEvaluateSamplersF32 = lookup.find(
                    "jpe_runtime_aquifer_context_evaluate_samplers_f32")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)))
                    .orElse(null);
            MethodHandle aquiferContextComputeFluidStatusBatch = lookup.find(
                    "jpe_runtime_aquifer_context_compute_fluid_status_batch")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS)))
                    .orElse(null);
            MethodHandle aquiferContextConfigureGlobalFluidPicker = lookup.find(
                    "jpe_runtime_aquifer_context_configure_global_fluid_picker")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)))
                    .orElse(null);
            MethodHandle aquiferContextComputeFluidStatusPointsBatch = lookup.find(
                    "jpe_runtime_aquifer_context_compute_fluid_status_points_batch")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
                    .orElse(null);
            MethodHandle aquiferContextComputeSubstanceVolumeF32 = lookup.find(
                    "jpe_runtime_aquifer_context_compute_substance_volume_f32")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
                    .orElse(null);
            MethodHandle aquiferContextComputeDensityAndSubstanceVolumeF32 = lookup.find(
                    "jpe_runtime_aquifer_context_compute_density_and_substance_volume_f32")
                    .map(symbol -> linker.downcallHandle(symbol,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS)))
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
                    graphCompile, graphEvaluate, graphEvaluateF32, graphEvaluatePoints, graphEvaluatePointsF32,
                    graphEvaluateMultiPointsF32, graphRelease, aquiferGridCenters, aquiferNearestFour,
                    aquiferSubstanceBatch, aquiferStatusCacheCreate, aquiferStatusCacheDestroy,
                    aquiferStatusCacheLookup, aquiferStatusCacheInsert,
                    aquiferContextCreate, aquiferContextDestroy, aquiferContextEvaluateSamplersF32,
                    aquiferContextComputeFluidStatusBatch, aquiferContextConfigureGlobalFluidPicker,
                    aquiferContextComputeFluidStatusPointsBatch,
                    aquiferContextComputeSubstanceVolumeF32,
                    aquiferContextComputeDensityAndSubstanceVolumeF32);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to load JPE native runtime from " + absolutePath, failure);
        }
    }

    /** Number of native workers selected during runtime initialization. */
    public int workerCount() { return workerCount; }

    /** True when the C++ runtime can own six world-scoped Aquifer sampler plans. */
    public boolean supportsAquiferContext() {
        return densityGraphCompile != null && densityGraphRelease != null
                && aquiferContextCreate != null && aquiferContextDestroy != null
                && aquiferContextEvaluateSamplersF32 != null
                && aquiferContextComputeFluidStatusBatch != null
                && aquiferContextConfigureGlobalFluidPicker != null
                && aquiferContextComputeFluidStatusPointsBatch != null
                && aquiferContextComputeSubstanceVolumeF32 != null;
    }

    /** True when the runtime can fuse final-density evaluation with Aquifer decisions. */
    public boolean supportsFusedDensityAquifer() {
        return aquiferContextComputeDensityAndSubstanceVolumeF32 != null;
    }

    /**
     * Creates a native Aquifer context. The C++ side retains each density graph
     * independently, so the caller may release its original graph references
     * after this method returns.
     */
    public long createAquiferContext(
            long[] graphHandles, byte[] worldFingerprint, long semanticFingerprint,
            long seedLo, long seedHi, int randomMode,
            int airToken, int waterToken, int lavaToken, int maxStatusCacheEntries
    ) {
        Objects.requireNonNull(graphHandles, "graphHandles");
        Objects.requireNonNull(worldFingerprint, "worldFingerprint");
        if (!supportsAquiferContext()) {
            throw new IllegalStateException("Native Aquifer context ABI is unavailable");
        }
        if (graphHandles.length != 6 || worldFingerprint.length != 32
                || semanticFingerprint == 0L || randomMode < 0 || randomMode > 1
                || airToken == waterToken || airToken == lavaToken || waterToken == lavaToken
                || maxStatusCacheEntries <= 0 || maxStatusCacheEntries > 1_048_576) {
            throw new IllegalArgumentException("Invalid native Aquifer context descriptor");
        }
        boolean fingerprintIsZero = true;
        for (byte value : worldFingerprint) fingerprintIsZero &= value == 0;
        if (fingerprintIsZero) throw new IllegalArgumentException("World fingerprint must not be all zero");
        for (long handle : graphHandles) {
            if (handle == 0L) throw new IllegalArgumentException("Aquifer sampler graph handles must be non-zero");
            if (!densityGraphHandles.containsKey(handle)) {
                throw new IllegalArgumentException(
                        "Aquifer sampler graph handle is not owned by this NativeRuntime: " + handle);
            }
        }

        lifecycle.readLock().lock();
        try {
            ensureOpen();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(128L, 8L);
                for (int i = 0; i < 6; i++) {
                    info.set(ValueLayout.JAVA_LONG, (long) i * 8L, graphHandles[i]);
                }
                MemorySegment.copy(worldFingerprint, 0, info, ValueLayout.JAVA_BYTE, 48L, 32);
                info.set(ValueLayout.JAVA_LONG, 80L, semanticFingerprint);
                info.set(ValueLayout.JAVA_LONG, 88L, seedLo);
                info.set(ValueLayout.JAVA_LONG, 96L, seedHi);
                info.set(ValueLayout.JAVA_INT, 104L, randomMode);
                info.set(ValueLayout.JAVA_INT, 108L, airToken);
                info.set(ValueLayout.JAVA_INT, 112L, waterToken);
                info.set(ValueLayout.JAVA_INT, 116L, lavaToken);
                info.set(ValueLayout.JAVA_INT, 120L, maxStatusCacheEntries);
                info.set(ValueLayout.JAVA_INT, 124L, 0);
                long handle = (long) aquiferContextCreate.invokeExact(info);
                if (handle == 0L) {
                    throw new IllegalStateException("Native Aquifer context creation was rejected");
                }
                aquiferContextHandles.add(handle);
                return handle;
            } catch (Throwable failure) {
                throw rethrow("Unable to create native Aquifer context", failure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Evaluates the six context-owned sampler graphs in one FFM call.
     * Coordinates and outputs are sampler-major, with an independent point set
     * for barrier, floodedness, spread, lava, exclusion, and surface.
     */
    public void evaluateAquiferSamplersF32(
            long contextHandle, byte[] worldFingerprint, long semanticFingerprint,
            int[][] pointX, int[][] pointY, int[][] pointZ, float[][] output
    ) {
        Objects.requireNonNull(worldFingerprint, "worldFingerprint");
        Objects.requireNonNull(pointX, "pointX");
        Objects.requireNonNull(pointY, "pointY");
        Objects.requireNonNull(pointZ, "pointZ");
        Objects.requireNonNull(output, "output");
        if (!supportsAquiferContext()) throw new IllegalStateException("Native Aquifer context ABI is unavailable");
        if (worldFingerprint.length != 32 || semanticFingerprint == 0L) {
            throw new IllegalArgumentException("Aquifer world and semantic fingerprints must be valid");
        }
        if (contextHandle == 0L || !aquiferContextHandles.contains(contextHandle)
                || pointX.length != 6 || pointY.length != 6 || pointZ.length != 6 || output.length != 6) {
            throw new IllegalArgumentException("Invalid native Aquifer sampler batch");
        }
        int pointsPerSampler = Objects.requireNonNull(pointX[0], "pointX[0]").length;
        long total = Math.multiplyExact(6L, pointsPerSampler);
        if (pointsPerSampler == 0 || total > 1_048_576L) {
            throw new IllegalArgumentException("Aquifer sampler point count must be in [1, 174762]");
        }
        for (int sampler = 0; sampler < 6; sampler++) {
            if (Objects.requireNonNull(pointX[sampler], "pointX row").length != pointsPerSampler
                    || Objects.requireNonNull(pointY[sampler], "pointY row").length != pointsPerSampler
                    || Objects.requireNonNull(pointZ[sampler], "pointZ row").length != pointsPerSampler
                    || Objects.requireNonNull(output[sampler], "output row").length < pointsPerSampler) {
                throw new IllegalArgumentException("Aquifer sampler rows must have equal point counts");
            }
        }

        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensurePointCapacity(Math.toIntExact(total));
            MemorySegment x = buffers[0], y = buffers[1], z = buffers[2], values = buffers[3];
            MemorySegment expectedFingerprint = scratch.ensureContextWorldFingerprint();
            MemorySegment.copy(worldFingerprint, 0, expectedFingerprint,
                    ValueLayout.JAVA_BYTE, 0L, worldFingerprint.length);
            for (int sampler = 0; sampler < 6; sampler++) {
                long baseBytes = Math.multiplyExact(
                        Math.multiplyExact((long) sampler, pointsPerSampler),
                        ValueLayout.JAVA_INT.byteSize());
                MemorySegment.copy(pointX[sampler], 0, x, ValueLayout.JAVA_INT, baseBytes, pointsPerSampler);
                MemorySegment.copy(pointY[sampler], 0, y, ValueLayout.JAVA_INT, baseBytes, pointsPerSampler);
                MemorySegment.copy(pointZ[sampler], 0, z, ValueLayout.JAVA_INT, baseBytes, pointsPerSampler);
            }
            int status = (int) aquiferContextEvaluateSamplersF32.invokeExact(
                    contextHandle, expectedFingerprint, semanticFingerprint,
                    x, y, z, pointsPerSampler, values, total);
            checkStatus(status, "aquifer-context-evaluate-samplers-f32");
            for (int sampler = 0; sampler < 6; sampler++) {
                long baseBytes = Math.multiplyExact(
                        Math.multiplyExact((long) sampler, pointsPerSampler),
                        ValueLayout.JAVA_FLOAT.byteSize());
                MemorySegment.copy(values, ValueLayout.JAVA_FLOAT, baseBytes,
                        output[sampler], 0, pointsPerSampler);
            }
        } catch (Throwable failure) {
            throw rethrow("Unable to evaluate native Aquifer sampler batch", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Configures the world-specific vanilla 26.3 global FluidPicker once per context.
     * Repeating the same configuration is idempotent; changing any value is rejected.
     */
    public void configureAquiferGlobalFluidPicker(
            long contextHandle, byte[] worldFingerprint, long semanticFingerprint,
            int seaLevel, int minY, int defaultFluidType,
            boolean defaultFluidIsAir, boolean debugDisableFluidGeneration
    ) {
        Objects.requireNonNull(worldFingerprint, "worldFingerprint");
        if (contextHandle == 0L || !aquiferContextHandles.contains(contextHandle)
                || worldFingerprint.length != 32 || semanticFingerprint == 0L) {
            throw new IllegalArgumentException("Invalid Aquifer global FluidPicker configuration");
        }
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment fingerprint = arena.allocate(32L, ValueLayout.JAVA_BYTE.byteAlignment());
                MemorySegment.copy(worldFingerprint, 0, fingerprint,
                        ValueLayout.JAVA_BYTE, 0L, worldFingerprint.length);
                int status = (int) aquiferContextConfigureGlobalFluidPicker.invokeExact(
                        contextHandle, fingerprint, semanticFingerprint, seaLevel, minY,
                        defaultFluidType, defaultFluidIsAir ? 1 : 0,
                        debugDisableFluidGeneration ? 1 : 0);
                checkStatus(status, "aquifer-context-configure-global-fluid-picker");
            } catch (Throwable failure) {
                throw rethrow("Unable to configure native Aquifer global FluidPicker", failure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Computes cell-center FluidStatus using all six C++-owned sampler graphs and
     * the world's vanilla global FluidPicker. Coordinates and packed BlockPos keys
     * are sent in one FFM call; Java performs no per-cell noise or FluidStatus math.
     */
    public void computeAquiferFluidStatusPointsBatch(
            long contextHandle, byte[] worldFingerprint, long semanticFingerprint,
            long[] cacheKeys, int[] pointX, int[] pointY, int[] pointZ,
            int[] outputFluidLevels, int[] outputFluidTypes
    ) {
        Objects.requireNonNull(worldFingerprint, "worldFingerprint");
        Objects.requireNonNull(cacheKeys, "cacheKeys");
        Objects.requireNonNull(pointX, "pointX");
        Objects.requireNonNull(pointY, "pointY");
        Objects.requireNonNull(pointZ, "pointZ");
        Objects.requireNonNull(outputFluidLevels, "outputFluidLevels");
        Objects.requireNonNull(outputFluidTypes, "outputFluidTypes");
        int count = cacheKeys.length;
        if (!supportsAquiferContext()) {
            throw new IllegalStateException("Native Aquifer points API is unavailable");
        }
        if (contextHandle == 0L || !aquiferContextHandles.contains(contextHandle)
                || worldFingerprint.length != 32 || semanticFingerprint == 0L
                || count == 0 || count > 65_536
                || pointX.length != count || pointY.length != count || pointZ.length != count
                || outputFluidLevels.length < count || outputFluidTypes.length < count) {
            throw new IllegalArgumentException("Invalid native Aquifer point-status dimensions");
        }

        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensureContextPointStatusCapacity(count);
            MemorySegment keys = buffers[0], x = buffers[1], y = buffers[2], z = buffers[3], output = buffers[4];
            MemorySegment expectedFingerprint = scratch.ensureContextWorldFingerprint();
            MemorySegment.copy(worldFingerprint, 0, expectedFingerprint,
                    ValueLayout.JAVA_BYTE, 0L, worldFingerprint.length);
            MemorySegment.copy(cacheKeys, 0, keys, ValueLayout.JAVA_LONG, 0L, count);
            MemorySegment.copy(pointX, 0, x, ValueLayout.JAVA_INT, 0L, count);
            MemorySegment.copy(pointY, 0, y, ValueLayout.JAVA_INT, 0L, count);
            MemorySegment.copy(pointZ, 0, z, ValueLayout.JAVA_INT, 0L, count);
            try {
                int status = (int) aquiferContextComputeFluidStatusPointsBatch.invokeExact(
                        contextHandle, expectedFingerprint, semanticFingerprint,
                        keys, x, y, z, count, output);
                checkStatus(status, "aquifer-context-compute-fluid-status-points-batch");
                for (int i = 0; i < count; i++) {
                    long offset = (long) i * 8L;
                    outputFluidLevels[i] = output.get(ValueLayout.JAVA_INT, offset);
                    outputFluidTypes[i] = output.get(ValueLayout.JAVA_INT, offset + 4L);
                }
            } catch (Throwable failure) {
                throw rethrow("Unable to compute native Aquifer FluidStatus points", failure);
            }
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Computes or retrieves a batch of native FluidStatus values using BlockPos.asLong
     * cache keys. Each FluidPicker probe is passed as a pre-resolved opaque token plus
     * its isAir trait; Java does no per-voxel FluidStatus math in this bridge.
     *
     * The input is sampler-derived data in the exact 292-byte native ABI layout.
     * All output arrays remain unchanged if validation, fingerprint checks, or cache
     * insertion fails.
     */
    public void computeAquiferFluidStatusBatch(
            long contextHandle,
            byte[] worldFingerprint,
            long semanticFingerprint,
            long[] cacheKeys,
            int[] blockY,
            int[] globalFluidLevels,
            int[] globalFluidTypes,
            int[][] surfaceLevels,
            int[][] globalFluidAtSurfaceLevels,
            int[][] globalFluidAtSurfaceTypes,
            boolean[][] globalFluidProbeValid,
            boolean[][] globalFluidProbeIsAir,
            float[] exclusionNoise,
            float[] floodednessNoise,
            float[] fluidLevelSpreadNoise,
            float[] lavaNoise,
            int lavaType,
            int[] outputFluidLevels,
            int[] outputFluidTypes
    ) {
        Objects.requireNonNull(worldFingerprint, "worldFingerprint");
        Objects.requireNonNull(cacheKeys, "cacheKeys");
        Objects.requireNonNull(blockY, "blockY");
        Objects.requireNonNull(globalFluidLevels, "globalFluidLevels");
        Objects.requireNonNull(globalFluidTypes, "globalFluidTypes");
        Objects.requireNonNull(surfaceLevels, "surfaceLevels");
        Objects.requireNonNull(globalFluidAtSurfaceLevels, "globalFluidAtSurfaceLevels");
        Objects.requireNonNull(globalFluidAtSurfaceTypes, "globalFluidAtSurfaceTypes");
        Objects.requireNonNull(globalFluidProbeValid, "globalFluidProbeValid");
        Objects.requireNonNull(globalFluidProbeIsAir, "globalFluidProbeIsAir");
        Objects.requireNonNull(exclusionNoise, "exclusionNoise");
        Objects.requireNonNull(floodednessNoise, "floodednessNoise");
        Objects.requireNonNull(fluidLevelSpreadNoise, "fluidLevelSpreadNoise");
        Objects.requireNonNull(lavaNoise, "lavaNoise");
        Objects.requireNonNull(outputFluidLevels, "outputFluidLevels");
        Objects.requireNonNull(outputFluidTypes, "outputFluidTypes");

        if (!supportsAquiferContext()) {
            throw new IllegalStateException("Native Aquifer FluidStatus context ABI is unavailable");
        }
        int count = cacheKeys.length;
        if (contextHandle == 0L || !aquiferContextHandles.contains(contextHandle)
                || worldFingerprint.length != 32 || semanticFingerprint == 0L
                || count == 0 || count > 65_536
                || blockY.length != count || globalFluidLevels.length != count
                || globalFluidTypes.length != count || surfaceLevels.length != count
                || globalFluidAtSurfaceLevels.length != count
                || globalFluidAtSurfaceTypes.length != count
                || globalFluidProbeValid.length != count || globalFluidProbeIsAir.length != count
                || exclusionNoise.length != count || floodednessNoise.length != count
                || fluidLevelSpreadNoise.length != count || lavaNoise.length != count
                || outputFluidLevels.length < count || outputFluidTypes.length < count) {
            throw new IllegalArgumentException("Invalid native Aquifer FluidStatus batch dimensions");
        }
        for (int i = 0; i < count; i++) {
            if (Objects.requireNonNull(surfaceLevels[i], "surfaceLevels row").length != 13
                    || Objects.requireNonNull(globalFluidAtSurfaceLevels[i], "globalFluidAtSurfaceLevels row").length != 13
                    || Objects.requireNonNull(globalFluidAtSurfaceTypes[i], "globalFluidAtSurfaceTypes row").length != 13
                    || Objects.requireNonNull(globalFluidProbeValid[i], "globalFluidProbeValid row").length != 13
                    || Objects.requireNonNull(globalFluidProbeIsAir[i], "globalFluidProbeIsAir row").length != 13) {
                throw new IllegalArgumentException("Aquifer FluidStatus rows must contain exactly 13 surface probes");
            }
        }

        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensureContextStatusCapacity(count);
            MemorySegment keys = buffers[0];
            MemorySegment inputs = buffers[1];
            MemorySegment outputs = buffers[2];
            MemorySegment expectedFingerprint = scratch.ensureContextWorldFingerprint();
            MemorySegment.copy(worldFingerprint, 0, expectedFingerprint,
                    ValueLayout.JAVA_BYTE, 0L, worldFingerprint.length);

            for (int i = 0; i < count; i++) {
                long keyOffset = (long) i * 8L;
                keys.set(ValueLayout.JAVA_LONG, keyOffset, cacheKeys[i]);

                long base = (long) i * 292L;
                inputs.set(ValueLayout.JAVA_INT, base, blockY[i]);
                inputs.set(ValueLayout.JAVA_INT, base + 4L, globalFluidLevels[i]);
                inputs.set(ValueLayout.JAVA_INT, base + 8L, globalFluidTypes[i]);
                for (int probe = 0; probe < 13; probe++) {
                    inputs.set(ValueLayout.JAVA_INT, base + 12L + (long) probe * 4L,
                            surfaceLevels[i][probe]);
                    long probeBase = base + 64L + (long) probe * 16L;
                    inputs.set(ValueLayout.JAVA_INT, probeBase, globalFluidAtSurfaceLevels[i][probe]);
                    inputs.set(ValueLayout.JAVA_INT, probeBase + 4L, globalFluidAtSurfaceTypes[i][probe]);
                    inputs.set(ValueLayout.JAVA_INT, probeBase + 8L,
                            globalFluidProbeValid[i][probe] ? 1 : 0);
                    inputs.set(ValueLayout.JAVA_INT, probeBase + 12L,
                            globalFluidProbeIsAir[i][probe] ? 1 : 0);
                }
                inputs.set(ValueLayout.JAVA_FLOAT, base + 272L, exclusionNoise[i]);
                inputs.set(ValueLayout.JAVA_FLOAT, base + 276L, floodednessNoise[i]);
                inputs.set(ValueLayout.JAVA_FLOAT, base + 280L, fluidLevelSpreadNoise[i]);
                inputs.set(ValueLayout.JAVA_FLOAT, base + 284L, lavaNoise[i]);
                inputs.set(ValueLayout.JAVA_INT, base + 288L, lavaType);
            }

            int status = (int) aquiferContextComputeFluidStatusBatch.invokeExact(
                    contextHandle, expectedFingerprint, semanticFingerprint,
                    keys, inputs, count, outputs);
            checkStatus(status, "aquifer-context-compute-fluid-status-batch");

            // Publish only after the complete native batch succeeds.
            for (int i = 0; i < count; i++) {
                long offset = (long) i * 8L;
                outputFluidLevels[i] = outputs.get(ValueLayout.JAVA_INT, offset);
                outputFluidTypes[i] = outputs.get(ValueLayout.JAVA_INT, offset + 4L);
            }
        } catch (Throwable failure) {
            throw rethrow("Unable to compute native Aquifer FluidStatus batch", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Runs the complete Aquifer decision kernel for one DensityVolume in a single
     * native call. The returned view owns the leased worker scratch until close().
     */
    public AquiferSubstanceVolumeResult computeAquiferSubstanceVolumeF32(
            long contextHandle,
            byte[] worldFingerprint,
            long semanticFingerprint,
            int skipSamplingAboveY,
            net.minecraft.world.level.levelgen.densityfunction.DensityVolume volume,
            net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer density
    ) {
        Objects.requireNonNull(worldFingerprint, "worldFingerprint");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(density, "density");
        if (!supportsAquiferContext()) {
            throw new IllegalStateException("Native full-volume Aquifer ABI is unavailable");
        }
        if (contextHandle == 0L || !aquiferContextHandles.contains(contextHandle)
                || worldFingerprint.length != 32 || semanticFingerprint == 0L) {
            throw new IllegalArgumentException("Invalid native Aquifer volume context");
        }
        long totalLong = Math.multiplyExact(
                Math.multiplyExact((long) volume.sizeX(), volume.sizeY()), volume.sizeZ());
        if (volume.sizeX() <= 0 || volume.sizeY() <= 0 || volume.sizeZ() <= 0
                || volume.stepBlockX() <= 0 || volume.stepBlockY() <= 0 || volume.stepBlockZ() <= 0
                || totalLong <= 0 || totalLong > 1_048_576L || density.size() != totalLong) {
            throw new IllegalArgumentException("Invalid native Aquifer DensityVolume geometry or buffer size");
        }
        int count = Math.toIntExact(totalLong);

        ReentrantReadWriteLock.ReadLock volumeReadLock = lifecycle.readLock();
        volumeReadLock.lock();
        AquiferScratch scratch = null;
        boolean transferred = false;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] pointBuffers = scratch.ensurePointCapacity(count);
            MemorySegment densityValues = pointBuffers[3];
            MemorySegment outputValues = scratch.ensureSubstanceCapacity(count)[1];
            MemorySegment expectedFingerprint = scratch.ensureContextWorldFingerprint();
            MemorySegment.copy(worldFingerprint, 0, expectedFingerprint,
                    ValueLayout.JAVA_BYTE, 0L, worldFingerprint.length);
            float[] densitySamples = ((DensityBufferAccessor) (Object) density).jpe$values();
            if (densitySamples.length < count) {
                throw new IllegalArgumentException("Scoped density buffer backing array is smaller than the volume");
            }
            MemorySegment.copy(densitySamples, 0, densityValues,
                    ValueLayout.JAVA_FLOAT, 0L, count);

            int status = (int) aquiferContextComputeSubstanceVolumeF32.invokeExact(
                    contextHandle, expectedFingerprint, semanticFingerprint,
                    skipSamplingAboveY,
                    volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                    densityValues, count, outputValues);
            checkStatus(status, "aquifer-context-compute-substance-volume-f32");
            AquiferSubstanceVolumeResult result =
                    new AquiferSubstanceVolumeResult(scratch, outputValues, count, volumeReadLock);
            transferred = true;
            return result;
        } catch (Throwable failure) {
            throw rethrow("Unable to compute native full-volume Aquifer decisions", failure);
        } finally {
            if (!transferred) {
                if (scratch != null) scratch.release();
                volumeReadLock.unlock();
            }
        }
    }

    /**
     * Evaluates final density and the complete Aquifer decision volume in one native call.
     * No Java density buffer is allocated or copied across the FFM boundary.
     */
    public AquiferSubstanceVolumeResult computeDensityAndAquiferSubstanceVolumeF32(
            long contextHandle,
            byte[] worldFingerprint,
            long semanticFingerprint,
            long finalDensityGraphHandle,
            int skipSamplingAboveY,
            net.minecraft.world.level.levelgen.densityfunction.DensityVolume volume
    ) {
        Objects.requireNonNull(worldFingerprint, "worldFingerprint");
        Objects.requireNonNull(volume, "volume");
        if (!supportsAquiferContext()) {
            throw new IllegalStateException("Fused native density/Aquifer ABI is unavailable");
        }
        if (contextHandle == 0L || !aquiferContextHandles.contains(contextHandle)
                || finalDensityGraphHandle == 0L || !densityGraphHandles.containsKey(finalDensityGraphHandle)
                || worldFingerprint.length != 32 || semanticFingerprint == 0L) {
            throw new IllegalArgumentException("Invalid fused native density/Aquifer handles or fingerprint");
        }
        long totalLong = Math.multiplyExact(
                Math.multiplyExact((long) volume.sizeX(), volume.sizeY()), volume.sizeZ());
        if (volume.sizeX() <= 0 || volume.sizeY() <= 0 || volume.sizeZ() <= 0
                || volume.stepBlockX() <= 0 || volume.stepBlockY() <= 0 || volume.stepBlockZ() <= 0
                || totalLong <= 0 || totalLong > 1_048_576L) {
            throw new IllegalArgumentException("Invalid fused native DensityVolume geometry");
        }
        int count = Math.toIntExact(totalLong);

        ReentrantReadWriteLock.ReadLock volumeReadLock = lifecycle.readLock();
        volumeReadLock.lock();
        AquiferScratch scratch = null;
        boolean transferred = false;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment outputValues = scratch.ensureSubstanceCapacity(count)[1];
            MemorySegment expectedFingerprint = scratch.ensureContextWorldFingerprint();
            MemorySegment.copy(worldFingerprint, 0, expectedFingerprint,
                    ValueLayout.JAVA_BYTE, 0L, worldFingerprint.length);

            int status = (int) aquiferContextComputeDensityAndSubstanceVolumeF32.invokeExact(
                    contextHandle, expectedFingerprint, semanticFingerprint, finalDensityGraphHandle,
                    skipSamplingAboveY,
                    volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                    outputValues);
            checkStatus(status, "aquifer-context-compute-density-and-substance-volume-f32");
            AquiferSubstanceVolumeResult result =
                    new AquiferSubstanceVolumeResult(scratch, outputValues, count, volumeReadLock);
            transferred = true;
            return result;
        } catch (Throwable failure) {
            throw rethrow("Unable to compute fused native density and Aquifer volume", failure);
        } finally {
            if (!transferred) {
                if (scratch != null) scratch.release();
                volumeReadLock.unlock();
            }
        }
    }

    /** A zero-allocation view over native volume results; must be closed before reuse. */
    public static final class AquiferSubstanceVolumeResult implements AutoCloseable {
        private final AquiferScratch scratch;
        private final MemorySegment values;
        private final int count;
        private final ReentrantReadWriteLock.ReadLock volumeReadLock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AquiferSubstanceVolumeResult(
                AquiferScratch scratch, MemorySegment values, int count,
                ReentrantReadWriteLock.ReadLock volumeReadLock
        ) {
            this.scratch = scratch;
            this.values = values;
            this.count = count;
            this.volumeReadLock = volumeReadLock;
        }

        public int size() { ensureOpen(); return count; }

        /**
         * Reads one voxel result without repeating lifecycle and bounds checks.
         * Bits 0..31 contain the block-state token, bit 32 indicates a state,
         * and bit 33 indicates a scheduled fluid update. This is a primitive
         * return value and does not allocate in the per-voxel fill loop.
         */
        public long packedResult(int index) {
            ensureIndex(index);
            return values.get(ValueLayout.JAVA_LONG, (long) index * Long.BYTES);
        }

        private void ensureIndex(int index) {
            ensureOpen();
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
        }

        private void ensureOpen() {
            if (closed.get()) throw new IllegalStateException("Native Aquifer volume result is already closed");
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                scratch.release();
            } finally {
                volumeReadLock.unlock();
            }
        }
    }

    /** Releases the context, its FluidStatus cache, and its six retained graph references. */
    public void destroyAquiferContext(long contextHandle) {
        if (contextHandle == 0L) throw new IllegalArgumentException("Aquifer context handle must be non-zero");
        lifecycle.writeLock().lock();
        try {
            ensureOpen();
            if (!aquiferContextHandles.contains(contextHandle)) {
                throw new IllegalArgumentException("Unknown or already destroyed Aquifer context");
            }
            try {
                int status = (int) aquiferContextDestroy.invokeExact(contextHandle);
                checkStatus(status, "aquifer-context-destroy");
                aquiferContextHandles.remove(contextHandle);
            } catch (Throwable failure) {
                throw rethrow("Unable to destroy native Aquifer context", failure);
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /** True when the loaded native runtime exposes the experimental Aquifer cache prefill kernel. */
    public boolean supportsAquiferGridCenters() {
        return aquiferGridCenters != null;
    }

    /**
     * Builds the complete packed Aquifer location cache in native code, then commits it
     * to the caller's existing Java cache only after the native call succeeds.
     */
    public void aquiferGridCenters(int randomMode, long seedLo, long seedHi,
                                   int minGridX, int minGridY, int minGridZ,
                                   int gridSizeX, int gridSizeZ, long[] packedLocations) {
        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            Objects.requireNonNull(packedLocations, "packedLocations");
            if (!supportsAquiferGridCenters()) {
                throw new IllegalStateException("Native Aquifer grid-center API is unavailable");
            }
            if (randomMode < 0 || randomMode > 1 || gridSizeX <= 0 || gridSizeZ <= 0
                    || packedLocations.length == 0 || packedLocations.length > 1_048_576) {
                throw new IllegalArgumentException("Invalid Aquifer grid-cache dimensions or random mode");
            }
            long layerSize = Math.multiplyExact((long) gridSizeX, gridSizeZ);
            if (layerSize > packedLocations.length || packedLocations.length % layerSize != 0) {
                throw new IllegalArgumentException("Aquifer grid-cache length is not an integral number of layers");
            }
            scratch = acquireAquiferScratch();
            MemorySegment nativeLocations = scratch.ensurePackedLocationCapacity(packedLocations.length);
            try {
                int status = (int) aquiferGridCenters.invokeExact(
                        randomMode, seedLo, seedHi,
                        minGridX, minGridY, minGridZ,
                        gridSizeX, gridSizeZ, packedLocations.length,
                        nativeLocations);
                checkStatus(status, "aquifer-grid-centers");
                // Commit only after the complete native calculation succeeds.
                MemorySegment.copy(nativeLocations, ValueLayout.JAVA_LONG, 0L,
                        packedLocations, 0, packedLocations.length);
            } catch (Throwable failure) {
                throw rethrow("Native Aquifer grid-center prefill failed", failure);
            }
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    public record AquiferNearestFour(
            int[] centerX, int[] centerY, int[] centerZ, int[] squaredDistances) {}

    /** True when the loaded native runtime exposes the whole-batch nearest-four kernel. */
    public boolean supportsAquiferNearestFour() {
        return aquiferNearestFour != null;
    }

    private static int validateAquiferNearestFour(int randomMode, int[] blockX, int[] blockY, int[] blockZ) {
        Objects.requireNonNull(blockX, "blockX");
        Objects.requireNonNull(blockY, "blockY");
        Objects.requireNonNull(blockZ, "blockZ");
        if (randomMode != 0 && randomMode != 1) {
            throw new IllegalArgumentException("Aquifer random mode must be 0 (XOROSHIRO) or 1 (LEGACY)");
        }
        if (blockX.length == 0 || blockY.length != blockX.length || blockZ.length != blockX.length
                || blockX.length > 1_048_576) {
            throw new IllegalArgumentException("Aquifer coordinate arrays must have equal lengths in [1, 1048576]");
        }
        return blockX.length;
    }

    /**
     * Executes the exact 12-cell Aquifer nearest-four search for a whole coordinate
     * batch in one FFM call. Returned arrays are sample-major, four neighbors per voxel.
     */
    public AquiferNearestFour aquiferNearestFour(int randomMode, long seedLo, long seedHi,
                                                  int[] blockX, int[] blockY, int[] blockZ) {
        if (!supportsAquiferNearestFour()) {
            throw new UnsupportedOperationException("Native runtime does not export Aquifer nearest-four support");
        }
        int count = validateAquiferNearestFour(randomMode, blockX, blockY, blockZ);
        int outputLength = Math.multiplyExact(count, 4);
        int[] centerX = new int[outputLength];
        int[] centerY = new int[outputLength];
        int[] centerZ = new int[outputLength];
        int[] distances = new int[outputLength];
        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensureCapacity(count);
            MemorySegment inputX = buffers[0], inputY = buffers[1], inputZ = buffers[2];
            MemorySegment outputX = buffers[3], outputY = buffers[4], outputZ = buffers[5], outputDistances = buffers[6];
            MemorySegment.copy(blockX, 0, inputX, ValueLayout.JAVA_INT, 0, count);
            MemorySegment.copy(blockY, 0, inputY, ValueLayout.JAVA_INT, 0, count);
            MemorySegment.copy(blockZ, 0, inputZ, ValueLayout.JAVA_INT, 0, count);
            try {
                int status = (int) aquiferNearestFour.invokeExact(randomMode, seedLo, seedHi,
                        inputX, inputY, inputZ, count, outputX, outputY, outputZ, outputDistances);
                checkStatus(status, "aquifer-nearest-four");
                MemorySegment.copy(outputX, ValueLayout.JAVA_INT, 0, centerX, 0, outputLength);
                MemorySegment.copy(outputY, ValueLayout.JAVA_INT, 0, centerY, 0, outputLength);
                MemorySegment.copy(outputZ, ValueLayout.JAVA_INT, 0, centerZ, 0, outputLength);
                MemorySegment.copy(outputDistances, ValueLayout.JAVA_INT, 0, distances, 0, outputLength);
            } catch (Throwable failure) {
                throw rethrow("Unable to evaluate Native Aquifer nearest-four batch", failure);
            }
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
        return new AquiferNearestFour(centerX, centerY, centerZ, distances);
    }

    public record AquiferSubstanceBatch(
            boolean[] hasBlockState, int[] blockStateTypes, boolean[] shouldScheduleFluidUpdate) {}

    /** True when the native runtime exposes the allocation-free batch substance kernel. */
    public boolean supportsAquiferSubstanceBatch() {
        return aquiferSubstanceBatch != null;
    }

    /**
     * Evaluates a contiguous batch of Aquifer substance decisions in one FFM call.
     * Fluid status inputs are sample-major with four neighbors per sample.
     *
     * The input arrays are data preparation only; density, pressure, barrier tests,
     * fluid decision, and update scheduling all execute in native code.
     */
    public AquiferSubstanceBatch aquiferSubstanceBatch(
            int[] blockY, double[] density, float[] barrierNoise,
            int[] distanceSquared, int[] closestFluidLevels, int[] closestFluidTypes,
            int[] globalFluidBelowTypes, int airType, int waterType, int lavaType,
            boolean debugDisableFluidGeneration) {
        if (aquiferSubstanceBatch == null) {
            throw new UnsupportedOperationException("Native runtime does not export Aquifer substance batch support");
        }
        Objects.requireNonNull(blockY, "blockY");
        Objects.requireNonNull(density, "density");
        Objects.requireNonNull(barrierNoise, "barrierNoise");
        Objects.requireNonNull(distanceSquared, "distanceSquared");
        Objects.requireNonNull(closestFluidLevels, "closestFluidLevels");
        Objects.requireNonNull(closestFluidTypes, "closestFluidTypes");
        Objects.requireNonNull(globalFluidBelowTypes, "globalFluidBelowTypes");
        int count = blockY.length;
        if (count == 0 || count > 1_048_576 || density.length != count
                || barrierNoise.length != count || globalFluidBelowTypes.length != count) {
            throw new IllegalArgumentException("Aquifer per-sample arrays must have equal lengths in [1, 1048576]");
        }
        int neighborLength = Math.multiplyExact(count, 4);
        if (distanceSquared.length != neighborLength || closestFluidLevels.length != neighborLength
                || closestFluidTypes.length != neighborLength) {
            throw new IllegalArgumentException("Aquifer nearest-four arrays must contain exactly four entries per sample");
        }
        if (airType == waterType || airType == lavaType || waterType == lavaType) {
            throw new IllegalArgumentException("Air, water, and lava registry tokens must be distinct");
        }

        boolean[] hasBlockState = new boolean[count];
        int[] blockStateTypes = new int[count];
        boolean[] shouldSchedule = new boolean[count];
        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensureSubstanceCapacity(count);
            MemorySegment inputs = buffers[0];
            MemorySegment outputs = buffers[1];
            final long inputStride = 88L;
            final long outputStride = 12L;
            for (int i = 0; i < count; i++) {
                long base = i * inputStride;
                inputs.set(ValueLayout.JAVA_INT, base, blockY[i]);
                inputs.set(ValueLayout.JAVA_DOUBLE, base + 8L, density[i]);
                inputs.set(ValueLayout.JAVA_FLOAT, base + 16L, barrierNoise[i]);
                int neighborBase = i * 4;
                for (int neighbor = 0; neighbor < 4; neighbor++) {
                    long statusOffset = base + 36L + neighbor * 8L;
                    inputs.set(ValueLayout.JAVA_INT, base + 20L + neighbor * 4L,
                            distanceSquared[neighborBase + neighbor]);
                    inputs.set(ValueLayout.JAVA_INT, statusOffset,
                            closestFluidLevels[neighborBase + neighbor]);
                    inputs.set(ValueLayout.JAVA_INT, statusOffset + 4L,
                            closestFluidTypes[neighborBase + neighbor]);
                }
                inputs.set(ValueLayout.JAVA_INT, base + 68L, globalFluidBelowTypes[i]);
                inputs.set(ValueLayout.JAVA_INT, base + 72L, airType);
                inputs.set(ValueLayout.JAVA_INT, base + 76L, waterType);
                inputs.set(ValueLayout.JAVA_INT, base + 80L, lavaType);
                inputs.set(ValueLayout.JAVA_INT, base + 84L, debugDisableFluidGeneration ? 1 : 0);
            }
            try {
                int status = (int) aquiferSubstanceBatch.invokeExact(inputs, count, outputs);
                checkStatus(status, "aquifer-substance-batch");
                for (int i = 0; i < count; i++) {
                    long base = i * outputStride;
                    hasBlockState[i] = outputs.get(ValueLayout.JAVA_INT, base) != 0;
                    blockStateTypes[i] = outputs.get(ValueLayout.JAVA_INT, base + 4L);
                    shouldSchedule[i] = outputs.get(ValueLayout.JAVA_INT, base + 8L) != 0;
                }
            } catch (Throwable failure) {
                throw rethrow("Unable to evaluate Native Aquifer substance batch", failure);
            }
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
        return new AquiferSubstanceBatch(hasBlockState, blockStateTypes, shouldSchedule);
    }

    public record AquiferStatusBatch(int[] fluidLevels, int[] fluidTypes, boolean[] found) {}

    /** True when all native FluidStatus cache operations are exported by the loaded runtime. */
    public boolean supportsAquiferStatusCache() {
        return aquiferStatusCacheCreate != null && aquiferStatusCacheDestroy != null
                && aquiferStatusCacheLookup != null && aquiferStatusCacheInsert != null;
    }

    /**
     * Creates a fixed-capacity cache scoped to one NoiseBasedAquifer instance.
     * Keys are packed BlockPos.asLong values for the corresponding aquifer cell centers.
     */
    public long createAquiferStatusCache(int maximumEntries) {
        if (!supportsAquiferStatusCache()) {
            throw new UnsupportedOperationException("Native runtime does not export Aquifer FluidStatus cache support");
        }
        if (maximumEntries <= 0 || maximumEntries > 1_048_576) {
            throw new IllegalArgumentException("Aquifer FluidStatus cache capacity must be in [1, 1048576]");
        }
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            long handle = (long) aquiferStatusCacheCreate.invokeExact(maximumEntries);
            if (handle == 0L) throw new IllegalStateException("Native Aquifer FluidStatus cache creation failed");
            aquiferStatusCacheHandles.add(handle);
            return handle;
        } catch (Throwable failure) {
            throw rethrow("Unable to create native Aquifer FluidStatus cache", failure);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Looks up many packed cell-center keys in one FFM call. Missing rows are marked found=false. */
    public AquiferStatusBatch lookupAquiferStatuses(long handle, long[] keys) {
        if (!supportsAquiferStatusCache()) {
            throw new UnsupportedOperationException("Native runtime does not export Aquifer FluidStatus cache support");
        }
        Objects.requireNonNull(keys, "keys");
        if (handle == 0L || !aquiferStatusCacheHandles.contains(handle)
                || keys.length == 0 || keys.length > 1_048_576) {
            throw new IllegalArgumentException("Invalid native Aquifer status-cache handle or lookup batch size");
        }
        int[] levels = new int[keys.length];
        int[] types = new int[keys.length];
        boolean[] found = new boolean[keys.length];
        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensureStatusCapacity(keys.length);
            MemorySegment keyBuffer = buffers[0], statusBuffer = buffers[1], foundBuffer = buffers[2];
            MemorySegment.copy(keys, 0, keyBuffer, ValueLayout.JAVA_LONG, 0, keys.length);
            int status = (int) aquiferStatusCacheLookup.invokeExact(
                    handle, keyBuffer, keys.length, statusBuffer, foundBuffer);
            checkStatus(status, "aquifer-status-cache-lookup");
            for (int i = 0; i < keys.length; i++) {
                long base = (long) i * 8L;
                levels[i] = statusBuffer.get(ValueLayout.JAVA_INT, base);
                types[i] = statusBuffer.get(ValueLayout.JAVA_INT, base + 4L);
                found[i] = foundBuffer.get(ValueLayout.JAVA_BYTE, i) != 0;
            }
        } catch (Throwable failure) {
            throw rethrow("Unable to query native Aquifer FluidStatus cache", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
        return new AquiferStatusBatch(levels, types, found);
    }

    /**
     * Inserts only missing entries in one FFM call. Existing keys are first-writer-wins,
     * matching vanilla's compute-if-absent status-cache behavior.
     */
    public void insertAquiferStatuses(long handle, long[] keys, int[] fluidLevels, int[] fluidTypes) {
        if (!supportsAquiferStatusCache()) {
            throw new UnsupportedOperationException("Native runtime does not export Aquifer FluidStatus cache support");
        }
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(fluidLevels, "fluidLevels");
        Objects.requireNonNull(fluidTypes, "fluidTypes");
        if (handle == 0L || !aquiferStatusCacheHandles.contains(handle)
                || keys.length == 0 || keys.length > 1_048_576
                || fluidLevels.length != keys.length || fluidTypes.length != keys.length) {
            throw new IllegalArgumentException("Invalid Aquifer status-cache insert arrays or handle");
        }
        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensureStatusCapacity(keys.length);
            MemorySegment keyBuffer = buffers[0], statusBuffer = buffers[1];
            MemorySegment.copy(keys, 0, keyBuffer, ValueLayout.JAVA_LONG, 0, keys.length);
            for (int i = 0; i < keys.length; i++) {
                long base = (long) i * 8L;
                statusBuffer.set(ValueLayout.JAVA_INT, base, fluidLevels[i]);
                statusBuffer.set(ValueLayout.JAVA_INT, base + 4L, fluidTypes[i]);
            }
            int status = (int) aquiferStatusCacheInsert.invokeExact(
                    handle, keyBuffer, statusBuffer, keys.length);
            checkStatus(status, "aquifer-status-cache-insert");
        } catch (Throwable failure) {
            throw rethrow("Unable to insert into native Aquifer FluidStatus cache", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /** Destroys a cache created by this runtime. */
    public void destroyAquiferStatusCache(long handle) {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            if (!aquiferStatusCacheHandles.contains(handle)) {
                throw new IllegalArgumentException("Unknown native Aquifer FluidStatus cache handle");
            }
            int status = (int) aquiferStatusCacheDestroy.invokeExact(handle);
            checkStatus(status, "aquifer-status-cache-destroy");
            aquiferStatusCacheHandles.remove(handle);
        } catch (Throwable failure) {
            throw rethrow("Unable to destroy native Aquifer FluidStatus cache", failure);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

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

    /** Rehydrates a portable Perlin descriptor stack without Minecraft object references. */
    public long preparePerlinStack(List<PerlinLayer> sourceLayers) {
        if (!supportsPreparedPerlinStack()) return 0L;
        lifecycle.readLock().lock();
        PerlinScratch scratch = null;
        try {
            ensureOpen();
            Objects.requireNonNull(sourceLayers, "sourceLayers");
            if (sourceLayers.isEmpty() || sourceLayers.size() > 64) {
                throw new IllegalArgumentException("Perlin stack must contain between 1 and 64 layers");
            }
            scratch = acquirePerlinScratch();
            scratch.ensureStackCapacity(sourceLayers.size(), 1);
            writePerlinLayers(sourceLayers, scratch);
            long handle = (long) preparePerlinStack.invokeExact(scratch.layerDescriptors, sourceLayers.size());
            if (handle != 0L) preparedStackHandles.add(handle);
            return handle;
        } catch (Throwable failure) {
            throw rethrow("Unable to rehydrate portable native Perlin stack", failure);
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
        public static DensityNode squeeze(int input) { return new DensityNode(23, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode log(int input) { return new DensityNode(29, input, -1, 0, 0.0, 0.0, 0.0); }
        public static DensityNode distanceToPoint(int x, int y, int z, int metric) {
            if (metric < 0 || metric > 3) throw new IllegalArgumentException("Invalid distance metric");
            return new DensityNode(30, metric, -1, 0, x, y, z);
        }
        public static DensityNode round(int input, int multiple, int type) {
            if (type < 0 || type > 3) throw new IllegalArgumentException("Invalid density round type");
            return new DensityNode(28, input, multiple, type, 0.0, 0.0, 0.0);
        }
        public static DensityNode lerp(int alpha, int first, int second) {
            return new DensityNode(24, alpha, first, second, 0.0, 0.0, 0.0);
        }
        public static DensityNode rangeChoice(int input, float minimumInclusive, float maximumExclusive,
                                                  int whenInRange, int whenOutOfRange) {
            if (!Float.isFinite(minimumInclusive) || !Float.isFinite(maximumExclusive)
                    || minimumInclusive >= maximumExclusive) {
                throw new IllegalArgumentException("Invalid density range choice");
            }
            return new DensityNode(27, input, whenInRange, whenOutOfRange,
                    minimumInclusive, maximumExclusive, 0.0);
        }
        public static DensityNode rangeChoiceBelow(int input, float maximumExclusive,
                                                       int whenBelow, int whenAtOrAbove) {
            if (!Float.isFinite(maximumExclusive)) {
                throw new IllegalArgumentException("Invalid interval-select threshold");
            }
            return new DensityNode(27, input, whenBelow, whenAtOrAbove,
                    Double.NEGATIVE_INFINITY, maximumExclusive, 0.0);
        }
        public static DensityNode interpolated(int input, int cellSizeXz, int cellSizeY) {
            if (input < 0 || cellSizeXz <= 0 || cellSizeY <= 0) {
                throw new IllegalArgumentException("Invalid interpolated density node");
            }
            return new DensityNode(26, input, cellSizeXz, cellSizeY, 0.0, 0.0, 0.0);
        }
        public static DensityNode gradient(int axis, int tiling, int fromCoordinate, int toCoordinate,
                                            float fromValue, float toValue) {
            if (axis < 0 || axis > 2 || tiling < 0 || tiling > 2
                    || fromCoordinate == toCoordinate
                    || !Float.isFinite(fromValue) || !Float.isFinite(toValue)) {
                throw new IllegalArgumentException("Invalid density gradient");
            }
            return new DensityNode(25, axis, tiling, Float.floatToRawIntBits(fromValue),
                    fromCoordinate, toCoordinate, toValue);
        }
        public static DensityNode gradient(int axis, int fromCoordinate, int toCoordinate,
                                            float fromValue, float toValue) {
            return gradient(axis, 0, fromCoordinate, toCoordinate, fromValue, toValue);
        }
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
        public static DensityNode perlinStackWarped(long preparedHandle, double xzScale, double yScale,
                                                     int shiftX, int shiftY, int shiftZ) {
            if (preparedHandle <= 0 || preparedHandle > 9_007_199_254_740_991L
                    || !Double.isFinite(xzScale) || !Double.isFinite(yScale)
                    || shiftX < 0 || shiftY < 0 || shiftZ < 0) {
                throw new IllegalArgumentException("Invalid warped prepared Perlin stack parameters");
            }
            return new DensityNode(31, shiftX, shiftY, shiftZ,
                    (double) preparedHandle, xzScale, yScale);
        }
        public static DensityNode slice(int input, int axis, int coordinate) {
            if (input < 0 || axis < 0 || axis > 2) {
                throw new IllegalArgumentException("Invalid density slice parameters");
            }
            return new DensityNode(32, input, axis, 0, coordinate, 0.0, 0.0);
        }
    }

    public boolean supportsDensityGraph() {
        return densityGraphCompile != null && densityGraphEvaluate != null && densityGraphRelease != null;
    }

    /** Minecraft 26.3's density pipeline uses float at every intermediate step. */
    public boolean supportsDensityGraphF32() {
        return densityGraphCompile != null && densityGraphEvaluateF32 != null && densityGraphRelease != null;
    }

    /** True when arbitrary-point density graph sampling is available in both precisions. */
    public boolean supportsDensityGraphPoints() {
        return densityGraphCompile != null && densityGraphEvaluatePoints != null
                && densityGraphEvaluatePointsF32 != null && densityGraphRelease != null;
    }

    /** True when one FFM call can evaluate many immutable sampler graphs over their own point sets. */
    public boolean supportsDensityGraphMultiPointsF32() {
        return densityGraphCompile != null && densityGraphEvaluateMultiPointsF32 != null
                && densityGraphRelease != null;
    }

    /** Identifies each independently resolved symbol in the sparse-point ABI. */
    public String densityGraphPointsDiagnostic() {
        return "compile=" + (densityGraphCompile != null)
                + " release=" + (densityGraphRelease != null)
                + " evaluatePointsF64=" + (densityGraphEvaluatePoints != null)
                + " evaluatePointsF32=" + (densityGraphEvaluatePointsF32 != null);
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

    /** Evaluates sparse/non-grid-aligned coordinates in one native call using double intermediates. */
    public void evaluateDensityGraphPoints(long handle,
                                           int[] pointX, int[] pointY, int[] pointZ,
                                           double[] output) {
        if (!supportsDensityGraphPoints()) {
            throw new IllegalStateException("Native arbitrary-point density graph API is unavailable");
        }
        Objects.requireNonNull(pointX, "pointX");
        Objects.requireNonNull(pointY, "pointY");
        Objects.requireNonNull(pointZ, "pointZ");
        Objects.requireNonNull(output, "output");
        int count = pointX.length;
        if (handle == 0L || count == 0 || count > 1_048_576
                || pointY.length != count || pointZ.length != count || output.length < count) {
            throw new IllegalArgumentException("Invalid arbitrary-point density graph arrays");
        }
        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensurePointCapacity(count);
            MemorySegment x = buffers[0], y = buffers[1], z = buffers[2], values = buffers[4];
            MemorySegment.copy(pointX, 0, x, ValueLayout.JAVA_INT, 0, count);
            MemorySegment.copy(pointY, 0, y, ValueLayout.JAVA_INT, 0, count);
            MemorySegment.copy(pointZ, 0, z, ValueLayout.JAVA_INT, 0, count);
            int status = (int) densityGraphEvaluatePoints.invokeExact(
                    handle, x, y, z, count, values, (long) count);
            checkStatus(status, "density-graph-evaluate-points");
            MemorySegment.copy(values, ValueLayout.JAVA_DOUBLE, 0, output, 0, count);
        } catch (Throwable failure) {
            throw rethrow("Unable to evaluate native density graph points", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /** Evaluates sparse/non-grid-aligned coordinates in one native call using float intermediates. */
    public void evaluateDensityGraphPointsF32(long handle,
                                              int[] pointX, int[] pointY, int[] pointZ,
                                              float[] output) {
        if (!supportsDensityGraphPoints()) {
            throw new IllegalStateException("Native arbitrary-point density graph API is unavailable");
        }
        Objects.requireNonNull(pointX, "pointX");
        Objects.requireNonNull(pointY, "pointY");
        Objects.requireNonNull(pointZ, "pointZ");
        Objects.requireNonNull(output, "output");
        int count = pointX.length;
        if (handle == 0L || count == 0 || count > 1_048_576
                || pointY.length != count || pointZ.length != count || output.length < count) {
            throw new IllegalArgumentException("Invalid arbitrary-point density graph arrays");
        }
        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensurePointCapacity(count);
            MemorySegment x = buffers[0], y = buffers[1], z = buffers[2], values = buffers[3];
            MemorySegment.copy(pointX, 0, x, ValueLayout.JAVA_INT, 0, count);
            MemorySegment.copy(pointY, 0, y, ValueLayout.JAVA_INT, 0, count);
            MemorySegment.copy(pointZ, 0, z, ValueLayout.JAVA_INT, 0, count);
            int status = (int) densityGraphEvaluatePointsF32.invokeExact(
                    handle, x, y, z, count, values, (long) count);
            checkStatus(status, "density-graph-evaluate-points-f32");
            MemorySegment.copy(values, ValueLayout.JAVA_FLOAT, 0, output, 0, count);
        } catch (Throwable failure) {
            throw rethrow("Unable to evaluate native density graph points (f32)", failure);
        } finally {
            if (scratch != null) scratch.release();
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Evaluates several compiled sampler graphs in one native call.
     * Coordinates and results are sampler-major: [sampler][point]. Each sampler
     * supplies its own point set, so callers do not need to align unlike noise samplers.
     */
    public void evaluateDensityGraphMultiPointsF32(
            long[] graphHandles, int[][] pointX, int[][] pointY, int[][] pointZ, float[][] output
    ) {
        if (!supportsDensityGraphMultiPointsF32()) {
            throw new IllegalStateException("Native multi-sampler density graph ABI is unavailable");
        }
        Objects.requireNonNull(graphHandles, "graphHandles");
        Objects.requireNonNull(pointX, "pointX");
        Objects.requireNonNull(pointY, "pointY");
        Objects.requireNonNull(pointZ, "pointZ");
        Objects.requireNonNull(output, "output");
        int graphCount = graphHandles.length;
        if (graphCount == 0 || graphCount > 64 || pointX.length != graphCount
                || pointY.length != graphCount || pointZ.length != graphCount
                || output.length != graphCount) {
            throw new IllegalArgumentException("Multi-sampler density graph array counts must match and be in [1, 64]");
        }
        int pointsPerGraph = Objects.requireNonNull(pointX[0], "pointX[0]").length;
        if (pointsPerGraph == 0) throw new IllegalArgumentException("Each sampler needs at least one point");
        long total = Math.multiplyExact((long) graphCount, pointsPerGraph);
        if (total > 1_048_576L) throw new IllegalArgumentException("Multi-sampler point volume exceeds native limit");
        for (int graphIndex = 0; graphIndex < graphCount; graphIndex++) {
            if (graphHandles[graphIndex] == 0L) {
                throw new IllegalArgumentException("Null native density graph handle at sampler " + graphIndex);
            }
            if (Objects.requireNonNull(pointX[graphIndex], "pointX row").length != pointsPerGraph
                    || Objects.requireNonNull(pointY[graphIndex], "pointY row").length != pointsPerGraph
                    || Objects.requireNonNull(pointZ[graphIndex], "pointZ row").length != pointsPerGraph
                    || Objects.requireNonNull(output[graphIndex], "output row").length < pointsPerGraph) {
                throw new IllegalArgumentException("Multi-sampler coordinate/output rows must have equal point counts");
            }
        }

        lifecycle.readLock().lock();
        AquiferScratch scratch = null;
        try {
            ensureOpen();
            scratch = acquireAquiferScratch();
            MemorySegment[] buffers = scratch.ensurePointCapacity(Math.toIntExact(total));
            MemorySegment handleBuffer = scratch.ensurePointGraphHandleCapacity(graphCount);
            MemorySegment x = buffers[0], y = buffers[1], z = buffers[2], values = buffers[3];
            MemorySegment.copy(graphHandles, 0, handleBuffer, ValueLayout.JAVA_LONG, 0, graphCount);
            for (int graphIndex = 0; graphIndex < graphCount; graphIndex++) {
                // MemorySegment offsets are bytes, while the source offsets above are array elements.
                // Keep every int row aligned even when pointsPerGraph is not a multiple of four.
                long baseBytes = Math.multiplyExact(
                        Math.multiplyExact((long) graphIndex, pointsPerGraph),
                        ValueLayout.JAVA_INT.byteSize());
                MemorySegment.copy(pointX[graphIndex], 0, x, ValueLayout.JAVA_INT, baseBytes, pointsPerGraph);
                MemorySegment.copy(pointY[graphIndex], 0, y, ValueLayout.JAVA_INT, baseBytes, pointsPerGraph);
                MemorySegment.copy(pointZ[graphIndex], 0, z, ValueLayout.JAVA_INT, baseBytes, pointsPerGraph);
            }
            int status = (int) densityGraphEvaluateMultiPointsF32.invokeExact(
                    handleBuffer, graphCount, x, y, z, pointsPerGraph, values, total);
            checkStatus(status, "density-graph-evaluate-multi-points-f32");
            for (int graphIndex = 0; graphIndex < graphCount; graphIndex++) {
                long base = (long) graphIndex * pointsPerGraph * ValueLayout.JAVA_FLOAT.byteSize();
                MemorySegment.copy(values, ValueLayout.JAVA_FLOAT, base,
                        output[graphIndex], 0, pointsPerGraph);
            }
        } catch (Throwable failure) {
            throw rethrow("Unable to evaluate multiple native density samplers", failure);
        } finally {
            if (scratch != null) scratch.release();
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
        if (aquiferContextDestroy != null) {
            for (Long handle : aquiferContextHandles) {
                try {
                    int status = (int) aquiferContextDestroy.invokeExact(handle.longValue());
                    if (status != 0) throw new IllegalStateException("Aquifer context destroy status=" + status);
                } catch (Throwable failure) {
                    throw rethrow("Unable to destroy Aquifer context during runtime close", failure);
                }
            }
            aquiferContextHandles.clear();
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
        if (aquiferStatusCacheDestroy != null) {
            for (Long handle : aquiferStatusCacheHandles) {
                try {
                    int status = (int) aquiferStatusCacheDestroy.invokeExact(handle.longValue());
                    if (status != 0) throw new IllegalStateException("Aquifer status-cache destroy status=" + status);
                } catch (Throwable failure) {
                    throw rethrow("Unable to destroy Aquifer status cache during runtime close", failure);
                }
            }
            aquiferStatusCacheHandles.clear();
        }
        AquiferScratch aquifer;
        while ((aquifer = aquiferScratchArenas.poll()) != null) {
            aquifer.close();
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