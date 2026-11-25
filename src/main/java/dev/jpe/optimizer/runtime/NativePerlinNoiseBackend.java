package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import dev.jpe.optimizer.mixin.GradientNoiseAccessor;
import dev.jpe.optimizer.mixin.NoiseStackLayerAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in bridge that runs Perlin volume work on the Rust worker pool / C++ backend. */
public final class NativePerlinNoiseBackend {
    private static volatile Field noiseStackLayersField;
    private static final ThreadLocal<Boolean> LAST_CALL_NATIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static volatile NativeRuntime runtime;
    private static volatile boolean loadAttempted;
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder COMPLETED = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final LongAdder SAMPLES = new LongAdder();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final LongAdder SYNCHRONOUS_CALLS = new LongAdder();
    private static final LongAdder INVALID_DIMENSIONS = new LongAdder();
    private static final LongAdder AXIS_VOLUME_MISMATCHES = new LongAdder();
    private static final LongAdder BUFFER_VOLUME_MISMATCHES = new LongAdder();
    private static final LongAdder OVERSIZED_VOLUMES = new LongAdder();
    private static final LongAdder RUNTIME_UNAVAILABLE = new LongAdder();
    private static final LongAdder ASYNC_QUEUE_REJECTIONS = new LongAdder();
    private static final LongAdder NATIVE_JOB_FAILURES = new LongAdder();
    private static final LongAdder NATIVE_EXCEPTIONS = new LongAdder();
    private static final AtomicLong PROFILE_CALLS = new AtomicLong();
    public record Metrics(long attempts, long completed, long fallbacks, long samples,
                          long totalNanos, long synchronousCalls,
                          long invalidDimensions, long axisVolumeMismatches, long bufferVolumeMismatches,
                          long oversizedVolumes, long runtimeUnavailable, long asyncQueueRejections,
                          long nativeJobFailures, long nativeExceptions, int nativeWorkers) {}

    public static Metrics metricsSnapshot() {
        NativeRuntime current = runtime;
        return new Metrics(ATTEMPTS.sum(), COMPLETED.sum(), FALLBACKS.sum(), SAMPLES.sum(),
                TOTAL_NANOS.sum(), SYNCHRONOUS_CALLS.sum(), INVALID_DIMENSIONS.sum(),
                AXIS_VOLUME_MISMATCHES.sum(), BUFFER_VOLUME_MISMATCHES.sum(), OVERSIZED_VOLUMES.sum(),
                RUNTIME_UNAVAILABLE.sum(), ASYNC_QUEUE_REJECTIONS.sum(), NATIVE_JOB_FAILURES.sum(),
                NATIVE_EXCEPTIONS.sum(), current == null ? 0 : current.workerCount());
    }

    public static String mode() {
        return Boolean.getBoolean("jpe.nativePerlinSynchronous")
                ? "native-perlin-sync" : "native-perlin-async";
    }

    private NativePerlinNoiseBackend() {}

    public static boolean enabled() {
        String path = System.getProperty("jpe.nativeLibraryPath");
        return Boolean.getBoolean("jpe.nativePerlinEnabled") && path != null && !path.isBlank();
    }

    public static boolean wasLastCallNative() {
        return LAST_CALL_NATIVE.get();
    }

    public static void setBypass(boolean value) {
        BYPASS.set(value);
    }

    public static boolean bypassed() {
        return BYPASS.get();
    }

    /**
     * Batches the exact 26.3 NoiseStack.Perlin layer list into one Rust job.
     * Unsupported layer implementations deliberately fall back to vanilla dispatch.
     */
    public static boolean tryAddToStack(NoiseStack stack, DensityBuffer buffer, DensityVolume volume,
                                        double xzScale, double yScale, float amplitude) {
        LAST_CALL_NATIVE.set(false);
        if (BYPASS.get() || !enabled()) return false;
        ATTEMPTS.increment();
        SAMPLES.add(volume.size());
        long started = System.nanoTime();
        boolean success = false;
        NativeRuntime.NativeJob job = null;
        boolean interrupted = false;
        boolean terminal = false;
        try {
            Object[] sourceLayers = readNoiseStackLayers(stack);
            if (sourceLayers.length == 0 || sourceLayers.length > 64) {
                INVALID_DIMENSIONS.increment();
                return false;
            }
            // Validate the exact layer types without allocating a new List and one
            // PerlinLayer record per octave on every density-buffer invocation.
            for (Object sourceLayer : sourceLayers) {
                NoiseStackLayerAccessor layer = (NoiseStackLayerAccessor) sourceLayer;
                Noise layerNoise = layer.jpe$noise();
                if (layerNoise == null || layerNoise.getClass() != PerlinNoise.class) return false;
            }

            int sizeX = volume.sizeX();
            int sizeY = volume.sizeY();
            int sizeZ = volume.sizeZ();
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                INVALID_DIMENSIONS.increment();
                return false;
            }
            long expectedElements = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
            if (expectedElements != volume.size()) {
                AXIS_VOLUME_MISMATCHES.increment();
                return false;
            }
            if (expectedElements > 1_048_576L) {
                OVERSIZED_VOLUMES.increment();
                return false;
            }
            if (Boolean.getBoolean("jpe.nativePerlinProfile")) {
                profilePerlinYRuns(layers, volume);
            }
            float[] target = ((DensityBufferAccessor) buffer).jpe$values();
            if (target.length < expectedElements) {
                BUFFER_VOLUME_MISMATCHES.increment();
                return false;
            }

            NativeRuntime nativeRuntime = runtime();
            if (nativeRuntime == null) {
                RUNTIME_UNAVAILABLE.increment();
                return false;
            }
            if (Boolean.getBoolean("jpe.nativePerlinSynchronous")) {
                nativeRuntime.perlinAddStack(sourceLayers, xzScale, yScale, amplitude,
                        sizeX, sizeY, sizeZ,
                        volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                        volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                        target, SharedConstants.DEBUG_ENABLE_FARLANDS);
                SYNCHRONOUS_CALLS.increment();
                LAST_CALL_NATIVE.set(true);
                success = true;
                COMPLETED.increment();
                return true;
            }

            job = nativeRuntime.submitPerlinStack(layers,
                    sizeX, sizeY, sizeZ,
                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                    target, SharedConstants.DEBUG_ENABLE_FARLANDS);
            if (job == null) {
                ASYNC_QUEUE_REJECTIONS.increment();
                return false;
            }

            long deadline = System.nanoTime() + 30_000_000_000L;
            boolean cancelRequested = false;
            int status;
            while (true) {
                status = job.waitForStatus(10_000_000L);
                if (status == NativeRuntime.JOB_COMPLETE
                        || status == NativeRuntime.JOB_FAILED
                        || status == NativeRuntime.JOB_CANCELLED) {
                    terminal = true;
                    break;
                }
                if (Thread.interrupted()) {
                    interrupted = true;
                    if (!cancelRequested) {
                        job.cancel();
                        cancelRequested = true;
                    }
                }
                if (!cancelRequested && System.nanoTime() >= deadline) {
                    JPEOptimizer.LOGGER.warn("Native Perlin stack job exceeded 30 seconds; cancelling if still queued and waiting for a safe terminal state");
                    job.cancel();
                    cancelRequested = true;
                }
            }
            if (status != NativeRuntime.JOB_COMPLETE) {
                NATIVE_JOB_FAILURES.increment();
                return false;
            }
            job.copyOutputInto(target);
            LAST_CALL_NATIVE.set(true);
            success = true;
            COMPLETED.increment();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            NATIVE_EXCEPTIONS.increment();
            JPEOptimizer.LOGGER.warn("Native Perlin stack backend failed; using vanilla layer dispatch: {}", failure.toString());
            return false;
        } finally {
            if (job != null) {
                try {
                    if (!terminal) {
                        job.cancel();
                        int cleanupStatus;
                        do {
                            cleanupStatus = job.waitForStatus(10_000_000L);
                            if (Thread.interrupted()) interrupted = true;
                        } while (cleanupStatus != NativeRuntime.JOB_COMPLETE
                                && cleanupStatus != NativeRuntime.JOB_FAILED
                                && cleanupStatus != NativeRuntime.JOB_CANCELLED);
                    }
                    job.close();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    JPEOptimizer.LOGGER.error("Unable to release native Perlin stack job safely", cleanupFailure);
                }
            }
            if (!success) FALLBACKS.increment();
            TOTAL_NANOS.add(System.nanoTime() - started);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public static boolean tryAddToVolume(PerlinNoise noise, DensityBuffer buffer, DensityVolume volume,
                                         double xzScale, double yScale, float amplitude) {
        LAST_CALL_NATIVE.set(false);
        if (BYPASS.get() || !enabled()) return false;

        ATTEMPTS.increment();
        SAMPLES.add(volume.size());
        long started = System.nanoTime();
        boolean success = false;
        int sizeX = volume.sizeX();
        int sizeY = volume.sizeY();
        int sizeZ = volume.sizeZ();
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            INVALID_DIMENSIONS.increment();
            FALLBACKS.increment();
            TOTAL_NANOS.add(System.nanoTime() - started);
            return false;
        }
        long expectedElements;
        try {
            expectedElements = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        } catch (ArithmeticException overflow) {
            INVALID_DIMENSIONS.increment();
            FALLBACKS.increment();
            TOTAL_NANOS.add(System.nanoTime() - started);
            return false;
        }
        if (expectedElements != volume.size()) {
            AXIS_VOLUME_MISMATCHES.increment();
            FALLBACKS.increment();
            TOTAL_NANOS.add(System.nanoTime() - started);
            return false;
        }
        if (expectedElements > 1_048_576L) {
            OVERSIZED_VOLUMES.increment();
            FALLBACKS.increment();
            TOTAL_NANOS.add(System.nanoTime() - started);
            return false;
        }

        NativeRuntime nativeRuntime = runtime();
        if (nativeRuntime == null) {
            RUNTIME_UNAVAILABLE.increment();
            FALLBACKS.increment();
            TOTAL_NANOS.add(System.nanoTime() - started);
            return false;
        }

        NativeRuntime.NativeJob job = null;
        boolean interrupted = false;
        boolean terminal = false;
        try {
            GradientNoiseAccessor access = (GradientNoiseAccessor) (Object) noise;
            if (Boolean.getBoolean("jpe.nativePerlinProfile")) {
                profilePerlinYRuns(List.of(new NativeRuntime.PerlinLayer(
                        access.jpe$perms(),
                        access.jpe$offsetX(), access.jpe$offsetY(), access.jpe$offsetZ(),
                        xzScale, yScale, amplitude
                )), volume);
            }
            float[] target = ((DensityBufferAccessor) buffer).jpe$values();
            if (target.length < expectedElements) {
                BUFFER_VOLUME_MISMATCHES.increment();
                return false;
            }

            // The synchronous path stays on Minecraft's existing worldgen worker.
            // The asynchronous path uses the native pool and a leased shared FFM output buffer.
            if (Boolean.getBoolean("jpe.nativePerlinSynchronous")) {
                nativeRuntime.perlinAddVolume(
                        access.jpe$perms(), sizeX, sizeY, sizeZ,
                        volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                        volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                        access.jpe$offsetX(), access.jpe$offsetY(), access.jpe$offsetZ(),
                        xzScale, yScale, amplitude, target,
                        SharedConstants.DEBUG_ENABLE_FARLANDS
                );
                SYNCHRONOUS_CALLS.increment();
                LAST_CALL_NATIVE.set(true);
                success = true;
                COMPLETED.increment();
                return true;
            }

            // The single-layer permutation is copied into Rust-owned memory; output remains leased.
            job = nativeRuntime.submitPerlinVolume(
                    access.jpe$perms(), sizeX, sizeY, sizeZ,
                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                    access.jpe$offsetX(), access.jpe$offsetY(), access.jpe$offsetZ(),
                    xzScale, yScale, amplitude, target,
                    SharedConstants.DEBUG_ENABLE_FARLANDS
            );
            if (job == null) {
                ASYNC_QUEUE_REJECTIONS.increment();
                return false;
            }

            long deadline = System.nanoTime() + 30_000_000_000L;
            boolean cancelRequested = false;
            int status;
            while (true) {
                status = job.waitForStatus(10_000_000L);
                if (status == NativeRuntime.JOB_COMPLETE
                        || status == NativeRuntime.JOB_FAILED
                        || status == NativeRuntime.JOB_CANCELLED) {
                    terminal = true;
                    break;
                }
                if (Thread.interrupted()) {
                    interrupted = true;
                    if (!cancelRequested) {
                        job.cancel();
                        cancelRequested = true;
                    }
                }
                if (!cancelRequested && System.nanoTime() >= deadline) {
                    JPEOptimizer.LOGGER.warn("Native Perlin job exceeded 30 seconds; cancelling if still queued and waiting for a safe terminal state");
                    job.cancel();
                    cancelRequested = true;
                }
            }

            if (status != NativeRuntime.JOB_COMPLETE) {
                NATIVE_JOB_FAILURES.increment();
                return false;
            }
            job.copyOutputInto(target);
            LAST_CALL_NATIVE.set(true);
            success = true;
            COMPLETED.increment();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            NATIVE_EXCEPTIONS.increment();
            JPEOptimizer.LOGGER.warn("Native Perlin backend failed; using Java fallback: {}", failure.toString());
            return false;
        } finally {
            if (job != null) {
                try {
                    if (!terminal) {
                        job.cancel();
                        int cleanupStatus;
                        do {
                            cleanupStatus = job.waitForStatus(10_000_000L);
                            if (Thread.interrupted()) interrupted = true;
                        } while (cleanupStatus != NativeRuntime.JOB_COMPLETE
                                && cleanupStatus != NativeRuntime.JOB_FAILED
                                && cleanupStatus != NativeRuntime.JOB_CANCELLED);
                    }
                    job.close();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    JPEOptimizer.LOGGER.error("Unable to release native Perlin job safely", cleanupFailure);
                }
            }
            if (!success) FALLBACKS.increment();
            TOTAL_NANOS.add(System.nanoTime() - started);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }


    /**
     * Opt-in diagnostic for real Minecraft DensityVolume calls. It mirrors the native
     * Y-coordinate wrapping/floor grouping and reports how much work can enter the
     * current eight-wide AVX2 loop. Disabled by default; profiling itself scans Y.
     */
    private static void profilePerlinYRuns(List<NativeRuntime.PerlinLayer> layers,
                                           DensityVolume volume) {
        long sequence = PROFILE_CALLS.incrementAndGet();
        // Sample every 32nd volume to keep diagnostic overhead bounded on busy worldgen.
        if ((sequence & 31L) != 1L) return;

        int sizeY = volume.sizeY();
        int minY = volume.minBlockY();
        int stepY = volume.stepBlockY();
        long totalRuns = 0;
        long totalSamples = 0;
        long candidateVectors = 0;
        long candidateSamples = 0;
        long eligibleLayers = 0;
        int maxRun = 0;
        int minRun = Integer.MAX_VALUE;
        double minScale = Double.POSITIVE_INFINITY;
        double maxScale = Double.NEGATIVE_INFINITY;
        boolean farLands = SharedConstants.DEBUG_ENABLE_FARLANDS;
        int layerIndex = 0;

        for (NativeRuntime.PerlinLayer layer : layers) {
            double scale = layer.yScale();
            minScale = Math.min(minScale, scale);
            maxScale = Math.max(maxScale, scale);
            boolean eligible = Math.abs((double) stepY * scale) <= (1.0 / 16.0);
            if (eligible) eligibleLayers++;

            long layerRuns = 0;
            long layerSamples = 0;
            long layerCandidateVectors = 0;
            long layerCandidateSamples = 0;
            int layerMaxRun = 0;
            int layerMinRun = Integer.MAX_VALUE;
            int runLength = 0;
            int previousFloor = 0;
            for (int iy = 0; iy < sizeY; iy++) {
                int blockY = minY + iy * stepY;
                double y = (double) blockY * scale;
                if (!farLands && (y < -Math.nextDown(16_777_216.0)
                        || y >= Math.nextDown(16_777_216.0))) {
                    y -= Math.floor(y / 33_554_432.0 + 0.5) * 33_554_432.0;
                }
                y += layer.offsetY();
                double floored = Math.floor(y);
                int floorY = floored >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                        : floored <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) floored;

                if (iy == 0 || floorY == previousFloor) {
                    runLength++;
                } else {
                    layerRuns++;
                    layerSamples += runLength;
                    layerMinRun = Math.min(layerMinRun, runLength);
                    layerMaxRun = Math.max(layerMaxRun, runLength);
                    if (eligible) {
                        long vectors = runLength / 8;
                        layerCandidateVectors += vectors;
                        layerCandidateSamples += vectors * 8;
                    }
                    runLength = 1;
                }
                previousFloor = floorY;
            }
            if (runLength > 0) {
                layerRuns++;
                layerSamples += runLength;
                layerMinRun = Math.min(layerMinRun, runLength);
                layerMaxRun = Math.max(layerMaxRun, runLength);
                if (eligible) {
                    long vectors = runLength / 8;
                    layerCandidateVectors += vectors;
                    layerCandidateSamples += vectors * 8;
                }
            }

            totalRuns += layerRuns;
            totalSamples += layerSamples;
            candidateVectors += layerCandidateVectors;
            candidateSamples += layerCandidateSamples;
            minRun = Math.min(minRun, layerMinRun);
            maxRun = Math.max(maxRun, layerMaxRun);

            JPEOptimizer.LOGGER.info(
                    "JPE_PERLIN_Y_PROFILE_LAYER sample={} layer={} yScale={} eligible={} floorRuns={} avgRun={} minRun={} maxRun={} avx2CandidateVectors={} avx2CandidateSamples={} scalarOrTailSamples={} stepY={} farLands={}",
                    sequence, layerIndex++, scale, eligible, layerRuns,
                    layerRuns == 0 ? 0.0 : (double) layerSamples / layerRuns,
                    layerMinRun == Integer.MAX_VALUE ? 0 : layerMinRun, layerMaxRun,
                    layerCandidateVectors, layerCandidateSamples,
                    layerSamples - layerCandidateSamples, stepY, farLands);
        }

        long scalarTailSamples = totalSamples - candidateSamples;
        JPEOptimizer.LOGGER.info(
                "JPE_PERLIN_Y_PROFILE sample={} origin=({}, {}, {}) volume={}x{}x{} stepY={} layers={} yScaleRange=[{},{}] eligibleLayers={} floorRuns={} avgRun={} minRun={} maxRun={} avx2CandidateVectors={} avx2CandidateSamples={} scalarOrTailSamples={} farLands={}",
                sequence, volume.minBlockX(), minY, volume.minBlockZ(),
                volume.sizeX(), sizeY, volume.sizeZ(), stepY, layers.size(),
                minScale, maxScale, eligibleLayers, totalRuns,
                totalRuns == 0 ? 0.0 : (double) totalSamples / totalRuns,
                minRun == Integer.MAX_VALUE ? 0 : minRun, maxRun,
                candidateVectors, candidateSamples, scalarTailSamples, farLands);
    }

    private static Object[] readNoiseStackLayers(NoiseStack stack) {
        try {
            Field field = noiseStackLayersField;
            if (field == null) {
                synchronized (NativePerlinNoiseBackend.class) {
                    field = noiseStackLayersField;
                    if (field == null) {
                        field = NoiseStack.class.getDeclaredField("layers");
                        if (!field.trySetAccessible()) {
                            throw new IllegalStateException("NoiseStack.layers is not reflectively accessible");
                        }
                        noiseStackLayersField = field;
                    }
                }
            }
            return (Object[]) field.get(stack);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to read NoiseStack.layers", failure);
        }
    }

    private static NativeRuntime runtime() {
        NativeRuntime current = runtime;
        if (current != null) return current;
        synchronized (NativePerlinNoiseBackend.class) {
            if (runtime != null) return runtime;
            if (loadAttempted) return null;
            loadAttempted = true;
            String path = System.getProperty("jpe.nativeLibraryPath");
            if (path == null || path.isBlank()) return null;
            try {
                runtime = NativeRuntime.load(java.nio.file.Path.of(path));
                JPEOptimizer.LOGGER.info("Native Perlin backend loaded from {} with {} workers", path, runtime.workerCount());
                return runtime;
            } catch (Exception | LinkageError failure) {
                JPEOptimizer.LOGGER.warn("Native Perlin backend unavailable; using Java fallback: {}", failure.toString());
                return null;
            }
        }
    }
}
