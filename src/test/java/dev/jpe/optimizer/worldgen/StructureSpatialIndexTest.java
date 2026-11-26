package dev.jpe.optimizer.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class StructureSpatialIndexTest {
    @Test
    void randomizedQueriesMatchVanillaLinearOrder() {
        Random random = new Random(0x2630_5EEDL);
        StructureSpatialIndex.Box[] boxes = new StructureSpatialIndex.Box[768];
        for (int i = 0; i < boxes.length; ++i) {
            boxes[i] = randomBox(random, 8, 160);
        }

        StructureSpatialIndex index = new StructureSpatialIndex(boxes);
        for (int i = 0; i < 4_000; ++i) {
            StructureSpatialIndex.Box query = randomBox(random, 4, 192);
            assertEquals(linearFindFirst(boxes, query), index.findFirst(query),
                    "query " + i + " " + query);
        }
    }

    @Test
    void preservesInclusiveFaceTouchingAndInsertionOrder() {
        StructureSpatialIndex.Box[] boxes = {
                new StructureSpatialIndex.Box(0, 0, 0, 31, 20, 31),
                new StructureSpatialIndex.Box(32, 0, 0, 63, 20, 31),
                new StructureSpatialIndex.Box(32, 0, 0, 40, 20, 8)
        };
        StructureSpatialIndex index = new StructureSpatialIndex(boxes);

        assertEquals(0, index.findFirst(new StructureSpatialIndex.Box(31, 10, 31, 32, 11, 32)));
        assertEquals(1, index.findFirst(new StructureSpatialIndex.Box(63, 20, 31, 64, 21, 32)));
    }

    @Test
    void oversizedPiecesAndBroadQueriesUseCorrectFallbacks() {
        StructureSpatialIndex.Box[] boxes = {
                new StructureSpatialIndex.Box(-20_000, -64, -20_000, 20_000, 320, 20_000),
                new StructureSpatialIndex.Box(10_000, -64, 10_000, 10_031, 320, 10_031),
                new StructureSpatialIndex.Box(-64, -64, -64, 64, 320, 64)
        };
        StructureSpatialIndex index = new StructureSpatialIndex(boxes);

        assertEquals(0, index.findFirst(new StructureSpatialIndex.Box(19_999, 0, 19_999, 20_001, 1, 20_001)));
        assertEquals(0, index.findFirst(new StructureSpatialIndex.Box(-30_000, -64, -30_000, 30_000, 320, 30_000)));
    }

    @Test
    void appendedPiecesBecomeQueryableWithoutRebuilding() {
        StructureSpatialIndex.Box[] initial = {
                new StructureSpatialIndex.Box(0, 0, 0, 8, 8, 8),
                new StructureSpatialIndex.Box(128, 0, 128, 136, 8, 136)
        };
        StructureSpatialIndex index = new StructureSpatialIndex(initial);
        StructureSpatialIndex.Box appended = new StructureSpatialIndex.Box(-40, -10, -40, -32, 10, -32);
        index.add(appended);

        assertEquals(2, index.findFirst(new StructureSpatialIndex.Box(-32, 0, -32, -31, 1, -31)));
    }

    private static StructureSpatialIndex.Box randomBox(Random random, int minimumSpan, int maximumSpan) {
        int minX = random.nextInt(8_193) - 4_096;
        int minY = random.nextInt(1_025) - 512;
        int minZ = random.nextInt(8_193) - 4_096;
        int spanX = minimumSpan + random.nextInt(maximumSpan - minimumSpan + 1);
        int spanY = minimumSpan + random.nextInt(maximumSpan - minimumSpan + 1);
        int spanZ = minimumSpan + random.nextInt(maximumSpan - minimumSpan + 1);
        return new StructureSpatialIndex.Box(minX, minY, minZ,
                minX + spanX, minY + spanY, minZ + spanZ);
    }

    private static int linearFindFirst(StructureSpatialIndex.Box[] boxes, StructureSpatialIndex.Box query) {
        for (int i = 0; i < boxes.length; ++i) {
            if (boxes[i] != null && boxes[i].intersects(query)) {
                return i;
            }
        }
        return -1;
    }
}
