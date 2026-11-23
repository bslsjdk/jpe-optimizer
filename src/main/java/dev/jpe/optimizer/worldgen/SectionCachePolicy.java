package dev.jpe.optimizer.worldgen;

/** Branch-light section-boundary policy for descending, contiguous block-Y scans. */
public final class SectionCachePolicy {
    private SectionCachePolicy() {}

    /**
     * Returns true for the first section access and at each next section's
     * highest local Y (15), when scanning downwards one block at a time.
     */
    public static boolean shouldRefresh(boolean initialized, int blockY) {
        return !initialized || (blockY & 15) == 15;
    }
}
