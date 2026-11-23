package dev.jpe.optimizer.worldgen;

import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/**
 * Computes the first flat-buffer index for a descending Y traversal.
 * DensityVolume stores Y contiguously, so subsequent indices are decremented.
 */
public final class DensityVolumeIndex {
    private DensityVolumeIndex() {}

    public static int descendingStartIndex(DensityVolume volume, int x, int z) {
        return (x + z * volume.sizeX()) * volume.sizeY() + volume.sizeY() - 1;
    }
}
