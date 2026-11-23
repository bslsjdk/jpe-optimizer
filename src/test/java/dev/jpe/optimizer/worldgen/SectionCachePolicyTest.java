package dev.jpe.optimizer.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionCachePolicyTest {
    @Test
    void descendingRunsCoverEverySampleAndRefreshOnlyAtSectionBoundaries() {
        for (int minY = -2048; minY <= 1024; minY += 7) {
            for (int height = 1; height <= 1024; height += 13) {
                int y = height - 1;
                int blockY = minY + y;
                int processed = 0;
                int runs = 0;

                while (y >= 0) {
                    int sectionY = Math.floorDiv(blockY, 16);
                    int runLength = SectionCachePolicy.descendingRunLength(y + 1, blockY);
                    assertTrue(runLength > 0);
                    runs++;

                    for (int i = 0; i < runLength; ++i) {
                        assertEquals(sectionY, Math.floorDiv(blockY, 16),
                                "run crossed a section boundary at Y=" + blockY);
                        --y;
                        --blockY;
                        processed++;
                    }

                    if (y >= 0) {
                        assertTrue(sectionY != Math.floorDiv(blockY, 16),
                                "run ended before the next section boundary");
                    }
                }

                int expectedRuns = Math.floorDiv(minY + height - 1, 16) - Math.floorDiv(minY, 16) + 1;
                assertEquals(height, processed);
                assertEquals(expectedRuns, runs);
            }
        }
    }

    @Test
    void firstRunIsCorrectForEveryLocalYAndNegativeWorldY() {
        for (int blockY = -4096; blockY <= 4096; ++blockY) {
            int expected = (blockY & 15) + 1;
            assertEquals(expected, SectionCachePolicy.descendingRunLength(4096, blockY));
            for (int remaining = 1; remaining <= 32; ++remaining) {
                assertEquals(Math.min(remaining, expected),
                        SectionCachePolicy.descendingRunLength(remaining, blockY));
            }
        }
    }
}
