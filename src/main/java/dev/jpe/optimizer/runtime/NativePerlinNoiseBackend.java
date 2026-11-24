package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import dev.jpe.optimizer.mixin.GradientNoiseAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in bridge that runs Perlin volume work on the Rust worker pool / C++ backend. */
public final class NativePerlinNoiseBackend {
    private static final ThreadLocal<Boolean> LAST_CALL_NATIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static volatile NativeRuntime runtime;
    private static volatile boolean loadAttempted;
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder COMPLETED = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final LongAdder SAMPLES = new LongAdder();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final ThreadLocal<CoordinateScratch> COORDINATES =
            ThreadLocal.withInitial(CoordinateScratch::new);

    private static final class CoordinateScratch {
        private int[] x = new int[0];
        private int[] y = new int[0];
        private int[] z = new int[0];

        private void prepare(int sizeX, int sizeY, int sizeZ) {
            if (x.length != sizeX) x = new int[sizeX];
            if (y.length != sizeY) y = new int[sizeY];
            if (z.length != sizeZ) z = new int[sizeZ];
        }
    }

    public record Metrics(long attempts, long completed, long fallbacks, long samples, long totalNanos) {}

    public static Metrics metricsSnapshot() {
        return new Metrics(ATTEMPTS.sum(), COMPLETED.sum(), FALLBACKS.sum(), SAMPLES.sum(), TOTAL_NANOS.sum());
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

    public static boolean tryAddToVolume(PerlinNoise noise, DensityBuffer buffer, DensityVolume volume,
                                         double xzScale, double yScale, float amplitude) {
        LAST_CALL_NATIVE.set(false);
        if (BYPASS.get() || !enabled()) return false;

        ATTEMPTS.increment();
        SAMPLES.add(volume.size());
        long started = System.nanoTime();
        boolean success = false;
        NativeRuntime nativeRuntime = runtime();
        if (nativeRuntime == null) {
            FALLBACKS.increment();
            TOTAL_NANOS.add(System.nanoTime() - started);
            return false;
        }

        NativeRuntime.NativeJob job = null;
        boolean interrupted = false;
        boolean terminal = false;
        try {
            GradientNoiseAccessor access = (GradientNoiseAccessor) (Object) noise;
            CoordinateScratch coordinates = COORDINATES.get();
            coordinates.prepare(volume.sizeX(), volume.sizeY(), volume.sizeZ());
            int[] blockX = coordinates.x;
            int[] blockY = coordinates.y;
            int[] blockZ = coordinates.z;
            for (int i = 0; i < blockX.length; ++i) blockX[i] = volume.blockX(i);
            for (int i = 0; i < blockY.length; ++i) blockY[i] = volume.blockY(i);
            for (int i = 0; i < blockZ.length; ++i) blockZ[i] = volume.blockZ(i);

            float[] target = ((DensityBufferAccessor) buffer).jpe$values();
            if (target.length != volume.size()) return false;
            // submitPerlinVolume copies the input into FFM memory and then Rust-owned memory
            // before returning. Passing the live array is safe: workers never retain it.
            // Output is copied back only after the native job reaches JOB_COMPLETE.
            job = nativeRuntime.submitPerlinVolume(
                    access.jpe$perms(), blockX, blockY, blockZ,
                    access.jpe$offsetX(), access.jpe$offsetY(), access.jpe$offsetZ(),
                    xzScale, yScale, amplitude, target,
                    SharedConstants.DEBUG_ENABLE_FARLANDS
            );
            if (job == null) return false;

            long deadline = System.nanoTime() + 30_000_000_000L;
            boolean cancelRequested = false;
            int status;
            while (true) {
                status = job.status();
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
                    JPEOptimizer.LOGGER.warn("Native Perlin job timed out; cancelling and falling back to Java");
                    job.cancel();
                    cancelRequested = true;
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    if (!cancelRequested) {
                        job.cancel();
                        cancelRequested = true;
                    }
                }
            }

            if (status != NativeRuntime.JOB_COMPLETE || interrupted) return false;
            job.copyOutputInto(target);
            LAST_CALL_NATIVE.set(true);
            success = true;
            COMPLETED.increment();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            JPEOptimizer.LOGGER.warn("Native Perlin backend failed; using Java fallback: {}", failure.toString());
            return false;
        } finally {
            if (job != null) {
                try {
                    if (!terminal) {
                        job.cancel();
                        while (!job.isDone()) {
                            try {
                                Thread.sleep(1L);
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
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
                JPEOptimizer.LOGGER.info("Native Perlin backend loaded from {}", path);
                return runtime;
            } catch (Exception | LinkageError failure) {
                JPEOptimizer.LOGGER.warn("Native Perlin backend unavailable; using Java fallback: {}", failure.toString());
                return null;
            }
        }
    }
}
