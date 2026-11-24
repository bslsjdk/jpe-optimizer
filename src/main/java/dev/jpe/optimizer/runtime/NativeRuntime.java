package dev.jpe.optimizer.runtime;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Optional Java 25 FFM bridge. Native worldgen is not loaded or enabled automatically.
 *
 * Async submission copies all arrays into native-owned memory before returning.
 * Completed jobs must be explicitly released before this runtime can be closed.
 */
public final class NativeRuntime implements AutoCloseable {
    public static final int EXPECTED_ABI_VERSION = 2;

    public static final int JOB_COMPLETE = 0;
    public static final int JOB_PENDING = 1;
    public static final int JOB_RUNNING = 2;
    public static final int JOB_FAILED = 3;
    public static final int JOB_CANCELLED = 4;

    private final Arena libraryArena;
    private final MethodHandle addF32;
    private final MethodHandle perlinAddVolume;
    private final MethodHandle submitPerlinVolume;
    private final MethodHandle jobStatus;
    private final MethodHandle copyJobOutput;
    private final MethodHandle cancelJob;
    private final MethodHandle releaseJob;
    private final ConcurrentHashMap<Long, NativeJob> jobs = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    // Reuse FFM buffers on the existing Minecraft worldgen workers. Keep the
    // retained native scratch bounded even on machines with large worker pools.
    private static final int MAX_REUSABLE_PERLIN_WORKERS = 8;
    private final AtomicInteger perlinScratchCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<PerlinScratch> perlinScratchArenas = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<PerlinScratch> perlinScratch = ThreadLocal.withInitial(() -> {
        if (perlinScratchCount.getAndIncrement() >= MAX_REUSABLE_PERLIN_WORKERS) {
            perlinScratchCount.decrementAndGet();
            return null;
        }
        PerlinScratch scratch = new PerlinScratch();
        perlinScratchArenas.add(scratch);
        return scratch;
    });
    private volatile boolean closed;

    private static final class PerlinScratch implements AutoCloseable {
        private final Arena arena = Arena.ofShared();
        private MemorySegment permutation;
        private MemorySegment blockX;
        private MemorySegment blockY;
        private MemorySegment blockZ;
        private MemorySegment output;
        private int permutationCapacity;
        private int xCapacity;
        private int yCapacity;
        private int zCapacity;
        private int outputCapacity;

        private void ensureCapacity(int permutationLength, int xLength, int yLength,
                                    int zLength, int outputLength) {
            if (permutationCapacity < permutationLength) {
                permutation = allocateBytes(arena, permutationLength);
                permutationCapacity = permutationLength;
            }
            if (xCapacity < xLength) {
                blockX = allocateInts(arena, xLength);
                xCapacity = xLength;
            }
            if (yCapacity < yLength) {
                blockY = allocateInts(arena, yLength);
                yCapacity = yLength;
            }
            if (zCapacity < zLength) {
                blockZ = allocateInts(arena, zLength);
                zCapacity = zLength;
            }
            if (outputCapacity < outputLength) {
                output = allocateFloats(arena, outputLength);
                outputCapacity = outputLength;
            }
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    private NativeRuntime(Arena arena, MethodHandle add, MethodHandle perlin,
                          MethodHandle submit, MethodHandle status, MethodHandle copy,
                          MethodHandle cancel, MethodHandle release) {
        this.libraryArena = arena;
        this.addF32 = add;
        this.perlinAddVolume = perlin;
        this.submitPerlinVolume = submit;
        this.jobStatus = status;
        this.copyJobOutput = copy;
        this.cancelJob = cancel;
        this.releaseJob = release;
    }

    public static NativeRuntime load(Path libraryPath) throws IOException {
        Objects.requireNonNull(libraryPath, "libraryPath");
        Path absolutePath = libraryPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolutePath)) {
            throw new IOException("Native runtime library does not exist: " + absolutePath);
        }
        Arena arena = Arena.ofShared();
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup(absolutePath, arena);
            MethodHandle version = linker.downcallHandle(
                    lookup.find("jpe_runtime_abi_version").orElseThrow(() -> new IllegalStateException("Missing ABI version symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            MethodHandle add = linker.downcallHandle(
                    lookup.find("jpe_runtime_add_f32").orElseThrow(() -> new IllegalStateException("Missing bulk kernel symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle perlin = linker.downcallHandle(
                    lookup.find("jpe_runtime_perlin_add_volume").orElseThrow(() -> new IllegalStateException("Missing Perlin volume symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_FLOAT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            MethodHandle submit = linker.downcallHandle(
                    lookup.find("jpe_runtime_submit_perlin_volume").orElseThrow(() -> new IllegalStateException("Missing async Perlin submit symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_FLOAT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            MethodHandle status = linker.downcallHandle(
                    lookup.find("jpe_runtime_job_status").orElseThrow(() -> new IllegalStateException("Missing async job status symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            MethodHandle copy = linker.downcallHandle(
                    lookup.find("jpe_runtime_copy_job_output").orElseThrow(() -> new IllegalStateException("Missing async output copy symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
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
            return new NativeRuntime(arena, add, perlin, submit, status, copy, cancel, release);
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Unable to load JPE native runtime from " + absolutePath, failure);
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

    /** Synchronous bulk Perlin entry; live mode runs it on Minecraft's existing worldgen worker. */
    public void perlinAddVolume(byte[] permutation, int[] blockX, int[] blockY, int[] blockZ,
                                double offsetX, double offsetY, double offsetZ,
                                double xzScale, double yScale, float amplitude,
                                float[] output, boolean farLands) {
        lifecycle.readLock().lock();
        try {
        ensureOpen();
        int outputLength = validatePerlinInputs(permutation, blockX, blockY, blockZ, output);
        try {
            PerlinScratch scratch = perlinScratch.get();
            if (scratch == null) {
                // More than eight distinct worldgen workers: keep the memory cap
                // and use a temporary confined arena for the overflow workers.
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment p = allocateBytes(arena, permutation.length);
                    MemorySegment x = allocateInts(arena, blockX.length);
                    MemorySegment y = allocateInts(arena, blockY.length);
                    MemorySegment z = allocateInts(arena, blockZ.length);
                    MemorySegment out = allocateFloats(arena, outputLength);
                    copy(permutation, p);
                    copy(blockX, x);
                    copy(blockY, y);
                    copy(blockZ, z);
                    copyPrefix(output, outputLength, out);
                    invokePerlin(p, x, y, z, blockX.length, blockY.length, blockZ.length,
                            offsetX, offsetY, offsetZ, xzScale, yScale, amplitude, out, output, outputLength, farLands);
                }
            } else {
                scratch.ensureCapacity(permutation.length, blockX.length, blockY.length,
                        blockZ.length, output.length);
                copy(permutation, scratch.permutation);
                copy(blockX, scratch.blockX);
                copy(blockY, scratch.blockY);
                copy(blockZ, scratch.blockZ);
                copyPrefix(output, outputLength, scratch.output);
                invokePerlin(scratch.permutation, scratch.blockX, scratch.blockY, scratch.blockZ,
                        blockX.length, blockY.length, blockZ.length,
                        offsetX, offsetY, offsetZ, xzScale, yScale, amplitude,
                        scratch.output, output, outputLength, farLands);
            }
        } catch (Throwable failure) {
            throw rethrow("Native Perlin volume call failed", failure);
        }
    
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private void invokePerlin(MemorySegment p, MemorySegment x, MemorySegment y, MemorySegment z,
                              int sizeX, int sizeY, int sizeZ,
                              double offsetX, double offsetY, double offsetZ,
                              double xzScale, double yScale, float amplitude,
                              MemorySegment nativeOutput, float[] javaOutput, int outputLength, boolean farLands) throws Throwable {
        int status = (int) perlinAddVolume.invokeExact(p, x, y, z,
                sizeX, sizeY, sizeZ, offsetX, offsetY, offsetZ,
                xzScale, yScale, amplitude, nativeOutput, (long) outputLength,
                farLands ? 1 : 0);
        checkStatus(status, "perlin-add-volume");
        copyPrefix(nativeOutput, javaOutput, outputLength);
    }

    /**
     * Submits a bulk Perlin volume to the Rust worker pool.
     * The native side copies every input before this method returns.
     * Returns null when the bounded native queue has no capacity.
     */
    public NativeJob submitPerlinVolume(byte[] permutation, int[] blockX, int[] blockY, int[] blockZ,
                                        double offsetX, double offsetY, double offsetZ,
                                        double xzScale, double yScale, float amplitude,
                                        float[] initialOutput, boolean farLands) {
        lifecycle.readLock().lock();
        try {
        ensureOpen();
        int outputLength = validatePerlinInputs(permutation, blockX, blockY, blockZ, initialOutput);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = allocateBytes(arena, permutation.length);
            MemorySegment x = allocateInts(arena, blockX.length);
            MemorySegment y = allocateInts(arena, blockY.length);
            MemorySegment z = allocateInts(arena, blockZ.length);
            MemorySegment initial = allocateFloats(arena, outputLength);
            copy(permutation, p);
            copy(blockX, x);
            copy(blockY, y);
            copy(blockZ, z);
            copyPrefix(initialOutput, outputLength, initial);
            long id = (long) submitPerlinVolume.invokeExact(
                    p, x, y, z, blockX.length, blockY.length, blockZ.length,
                    offsetX, offsetY, offsetZ, xzScale, yScale, amplitude,
                    initial, (long) outputLength, farLands ? 1 : 0);
            if (id == 0L) return null;
            NativeJob job = new NativeJob(this, id, outputLength);
            jobs.put(id, job);
            return job;
        } catch (Throwable failure) {
            throw rethrow("Unable to submit native Perlin job", failure);
        }
    
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Native runtime is closed");
    }

    private static void validatePerlinInputs(byte[] permutation, int[] blockX, int[] blockY,
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
    private static MemorySegment allocateInts(Arena arena, int length) {
        return arena.allocate(Math.multiplyExact((long) length, ValueLayout.JAVA_INT.byteSize()), ValueLayout.JAVA_INT.byteAlignment());
    }
    private static MemorySegment allocateFloats(Arena arena, int length) {
        return arena.allocate(Math.multiplyExact((long) length, ValueLayout.JAVA_FLOAT.byteSize()), ValueLayout.JAVA_FLOAT.byteAlignment());
    }
    private static void copy(byte[] source, MemorySegment target) {
        MemorySegment.copy(source, 0, target, ValueLayout.JAVA_BYTE, 0L, source.length);
    }
    private static void copy(int[] source, MemorySegment target) {
        MemorySegment.copy(source, 0, target, ValueLayout.JAVA_INT, 0L, source.length);
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
        PerlinScratch scratch;
        while ((scratch = perlinScratchArenas.poll()) != null) {
            scratch.close();
        }
        libraryArena.close();
    
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    public static final class NativeJob implements AutoCloseable {
        private final NativeRuntime runtime;
        private final long id;
        private final int outputLength;
        private volatile boolean released;

        private NativeJob(NativeRuntime runtime, long id, int outputLength) {
            this.runtime = runtime;
            this.id = id;
            this.outputLength = outputLength;
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
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = allocateFloats(arena, outputLength);
                int result = (int) runtime.copyJobOutput.invokeExact(id, out, (long) outputLength);
                if (result == JOB_PENDING || result == JOB_RUNNING) {
                    throw new IllegalStateException("Native job is not complete yet; status=" + result);
                }
                checkStatus(result, "copy-job-output");
                copyPrefix(out, destination, outputLength);
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
            } catch (Throwable failure) {
                throw rethrow("Unable to release native job", failure);
            }
        }
    }
}
