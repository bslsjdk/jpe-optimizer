package dev.jpe.optimizer.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DensityVolumeIndexTest {
    @Test
    void descendingLinearIndicesMatchVanillaForAllCoordinates() {
        for (int sizeX = 1; sizeX <= 19; sizeX += 3) {
            for (int sizeY = 1; sizeY <= 97; sizeY += 8) {
                for (int sizeZ = 1; sizeZ <= 17; sizeZ += 4) {
                    for (int z = 0; z < sizeZ; ++z) {
                        for (int x = 0; x < sizeX; ++x) {
                            int index = DensityVolumeIndex.descendingStartIndex(sizeX, sizeY, x, z);
                            for (int y = sizeY - 1; y >= 0; --y, --index) {
                                int vanillaIndex = y + (x + z * sizeX) * sizeY;
                                assertEquals(
                                        vanillaIndex,
                                        index,
                                        "index mismatch for volume=" + sizeX + "x" + sizeY + "x" + sizeZ
                                                + " at x=" + x + ", y=" + y + ", z=" + z
                                );
                            }
                        }
                    }
                }
            }
        }
    }
}
