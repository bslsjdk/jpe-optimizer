package dev.jpe.optimizer.worldgen;

/** Section-run sizing for descending scans through a unit-stride Y volume. */
public final class SectionCachePolicy {
    private SectionCachePolicy() {}

    /**
     * Returns the number of remaining samples that stay in the current
     * vertical chunk section when blockY decreases by exactly one per sample.
     *
     * @param remainingSamples number of samples left, including the current one
     * @param blockY world-space Y coordinate of the current sample
     */
    public static int descendingRunLength(int remainingSamples, int blockY) {
        return Math.min(remainingSamples, (blockY & 15) + 1);
    }
}
