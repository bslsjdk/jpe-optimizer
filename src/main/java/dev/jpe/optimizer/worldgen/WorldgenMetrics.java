package dev.jpe.optimizer.worldgen;

import dev.jpe.optimizer.JPEOptimizer;
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
    private static final LongAdder DENSITY_SAMPLE_NANOS = new LongAdder();
    private static final LongAdder BLOCK_FILL_NANOS = new LongAdder();
    private static final LongAdder SAMPLES = new LongAdder();

    private WorldgenMetrics() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void record(long densitySampleNanos, long blockFillNanos, int sampleCount) {
        if (!ENABLED) {
            return;
        }

        DENSITY_SAMPLE_NANOS.add(densitySampleNanos);
        BLOCK_FILL_NANOS.add(blockFillNanos);
        SAMPLES.add(sampleCount);

        long calls = DO_FILL_CALLS.incrementAndGet();
        if (calls % LOG_INTERVAL == 0L) {
            double averageDensityMs = DENSITY_SAMPLE_NANOS.sum() / (double) calls / 1_000_000.0;
            double averageBlockFillMs = BLOCK_FILL_NANOS.sum() / (double) calls / 1_000_000.0;
            long averageSamples = SAMPLES.sum() / calls;

            JPEOptimizer.LOGGER.info(
                    "JPE worldgen metrics: doFillCalls={}, avgSamplesPerCall={}, avgDensitySampleMs={}, avgBlockFillMs={}",
                    calls,
                    averageSamples,
                    String.format(java.util.Locale.ROOT, "%.3f", averageDensityMs),
                    String.format(java.util.Locale.ROOT, "%.3f", averageBlockFillMs)
            );
        }
    }
}
