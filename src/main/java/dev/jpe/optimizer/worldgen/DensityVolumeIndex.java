package dev.jpe.optimizer.worldgen;

/**
 * Computes the first flat-buffer index for a descending Y traversal.
 * DensityVolume stores Y contiguously, so subsequent indices are decremented.
 */
public final class DensityVolumeIndex {
    private DensityVolumeIndex() {}

    public static int descendingStartIndex(int sizeX, int sizeY, int x, int z) {
        return (x + z * sizeX) * sizeY + sizeY - 1;
    }
}
