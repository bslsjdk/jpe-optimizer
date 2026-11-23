package dev.jpe.optimizer.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SectionCachePolicyTest {
    @Test
    void descendingScansRefreshExactlyOncePerSectionAcrossNegativeAndPositiveY() {
        for (int minY = -2048; minY <= 1024; minY += 7) {
            for (int height = 1; height <= 1024; height += 13) {
                int topY = minY + height - 1;
                boolean initialized = false;
                int selectedSectionY = Integer.MIN_VALUE;
                int refreshes = 0;

                for (int y = topY; y >= minY; y--) {
                    if (SectionCachePolicy.shouldRefresh(initialized, y)) {
                        initialized = true;
                        selectedSectionY = Math.floorDiv(y, 16);
                        refreshes++;
                    }
                    assertEquals(Math.floorDiv(y, 16), selectedSectionY,
                            "cached section mismatch at Y=" + y + " range=" + minY + ".." + topY);
                }

                int expectedRefreshes = Math.floorDiv(topY, 16) - Math.floorDiv(minY, 16) + 1;
                assertEquals(expectedRefreshes, refreshes,
                        "wrong section lookup count for range=" + minY + ".." + topY);
            }
        }
    }

    @Test
    void firstLookupIsRequiredEvenWhenStartingAtLocalYNotFifteen() {
        for (int y = -2048; y <= 2048; y++) {
            assertEquals(true, SectionCachePolicy.shouldRefresh(false, y));
            assertEquals((y & 15) == 15, SectionCachePolicy.shouldRefresh(true, y));
        }
    }
}
