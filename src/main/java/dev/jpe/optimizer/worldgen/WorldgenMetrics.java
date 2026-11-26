package dev.jpe.optimizer.worldgen;

import dev.jpe.optimizer.JPEOptimizer;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in, low-frequency timing counters for the two expensive phases of
 * NoiseBasedChunkGenerator.doFill. Disabled by default.
 */
public final class WorldgenMetrics {
    private static final boolean ENABLED = Boolean.getBoolean("jpe.worldgenMetrics");
    private static final long LOG_INTERVAL = 64L;

    private static final AtomicLong DO_FILL_CALLS = new AtomicLong();
    private static final AtomicInteger ACTIVE_DO_FILL = new AtomicInteger();
    private static final AtomicInteger MAX_CONCURRENT_DO_FILL = new AtomicInteger();
    private static final com.sun.management.ThreadMXBean ALLOCATION_BEAN = allocationBean();
    private static final LongAdder DENSITY_SAMPLE_NANOS = new LongAdder();
    private static final LongAdder DENSITY_ALLOCATED_BYTES = new LongAdder();
    private static final LongAdder BLOCK_FILL_ALLOCATED_BYTES = new LongAdder();
    private static final LongAdder BLOCK_FILL_NANOS = new LongAdder();
    private static final LongAdder SAMPLES = new LongAdder();
    private static final LongAdder SAMPLED_AQUIFER_NANOS = new LongAdder();
    private static final LongAdder SAMPLED_PLACEMENT_NANOS = new LongAdder();
    private static final LongAdder TIMING_SAMPLE_COUNT = new LongAdder();
    private static final LongAdder AQUIFER_CALLS_EXECUTED = new LongAdder();
    private static final LongAdder AQUIFER_CALLS_SKIPPED = new LongAdder();

    public record Snapshot(long calls, long densitySampleNanos, long blockFillNanos, long samples,
                           long sampledAquiferNanos, long sampledPlacementNanos, long timingSamples,
                           int maxConcurrentDoFill, long densityAllocatedBytes,
                           long blockFillAllocatedBytes) {}

    public static Snapshot snapshot() {
        return new Snapshot(DO_FILL_CALLS.get(), DENSITY_SAMPLE_NANOS.sum(),
                BLOCK_FILL_NANOS.sum(), SAMPLES.sum(), SAMPLED_AQUIFER_NANOS.sum(),
                SAMPLED_PLACEMENT_NANOS.sum(), TIMING_SAMPLE_COUNT.sum(), MAX_CONCURRENT_DO_FILL.get(),
                DENSITY_ALLOCATED_BYTES.sum(), BLOCK_FILL_ALLOCATED_BYTES.sum());
    }

    private WorldgenMetrics() {}

    private static com.sun.management.ThreadMXBean allocationBean() {
        if (!ENABLED) return null;
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean sunBean)
                || !sunBean.isThreadAllocatedMemorySupported()) return null;
        try {
            if (!sunBean.isThreadAllocatedMemoryEnabled()) sunBean.setThreadAllocatedMemoryEnabled(true);
            return sunBean.isThreadAllocatedMemoryEnabled() ? sunBean : null;
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return null;
        }
    }

    public static boolean allocationSupported() {
        return ENABLED && ALLOCATION_BEAN != null;
    }

    /** Returns the current worker's cumulative Java allocation counter, or -1 when unsupported. */
    public static long currentThreadAllocatedBytes() {
        return ENABLED && ALLOCATION_BEAN != null
                ? ALLOCATION_BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1L;
    }

    public static long allocatedDelta(long before, long after) {
        return before >= 0L && after >= before ? after - before : -1L;
    }

    public static void beginDoFill() {
        int active = ACTIVE_DO_FILL.incrementAndGet();
        MAX_CONCURRENT_DO_FILL.accumulateAndGet(active, Math::max);
    }

    public static void endDoFill() {
        ACTIVE_DO_FILL.decrementAndGet();
    }

    public static void resetConcurrentPeak() {
        MAX_CONCURRENT_DO_FILL.set(ACTIVE_DO_FILL.get());
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void record(long densitySampleNanos, long blockFillNanos, int sampleCount,
                              long sampledAquiferNanos, long sampledPlacementNanos, long timingSampleCount,
                              long aquiferCallsExecuted, long aquiferCallsSkipped,
                              long densityAllocatedBytes, long blockFillAllocatedBytes) {
        if (!ENABLED) {
            return;
        }

        DENSITY_SAMPLE_NANOS.add(densitySampleNanos);
        BLOCK_FILL_NANOS.add(blockFillNanos);
        SAMPLES.add(sampleCount);
        if (densityAllocatedBytes >= 0L) DENSITY_ALLOCATED_BYTES.add(densityAllocatedBytes);
        if (blockFillAllocatedBytes >= 0L) BLOCK_FILL_ALLOCATED_BYTES.add(blockFillAllocatedBytes);
        SAMPLED_AQUIFER_NANOS.add(sampledAquiferNanos);
        SAMPLED_PLACEMENT_NANOS.add(sampledPlacementNanos);
        TIMING_SAMPLE_COUNT.add(timingSampleCount);
        AQUIFER_CALLS_EXECUTED.add(aquiferCallsExecuted);
        AQUIFER_CALLS_SKIPPED.add(aquiferCallsSkipped);

        long calls = DO_FILL_CALLS.incrementAndGet();
        if (calls % LOG_INTERVAL == 0L) {
            double averageDensityMs = DENSITY_SAMPLE_NANOS.sum() / (double) calls / 1_000_000.0;
            double averageBlockFillMs = BLOCK_FILL_NANOS.sum() / (double) calls / 1_000_000.0;
            long averageSamples = SAMPLES.sum() / calls;

            JPEOptimizer.LOGGER.info(
                    "JPE worldgen metrics: doFillCalls={}, avgSamplesPerCall={}, avgDensitySampleMs={}, avgBlockFillMs={}, sampledAquiferNs={}, sampledPlacementNs={}, timingSamples={}, aquiferCallsExecuted={}, aquiferCallsSkipped={}",
                    calls,
                    averageSamples,
                    String.format(java.util.Locale.ROOT, "%.3f", averageDensityMs),
                    String.format(java.util.Locale.ROOT, "%.3f", averageBlockFillMs),
                    SAMPLED_AQUIFER_NANOS.sum(), SAMPLED_PLACEMENT_NANOS.sum(), TIMING_SAMPLE_COUNT.sum(),
                    AQUIFER_CALLS_EXECUTED.sum(), AQUIFER_CALLS_SKIPPED.sum()
            );
        }
    }
}
