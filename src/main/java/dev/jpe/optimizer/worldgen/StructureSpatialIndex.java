package dev.jpe.optimizer.worldgen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * XZ broadphase for structure-piece collision checks.
 *
 * <p>The index only narrows the candidate set. Every candidate is still tested
 * with Minecraft's original 3D {@link BoundingBox#intersects(BoundingBox)}
 * predicate, and the smallest insertion index wins to preserve vanilla's
 * first-match semantics.</p>
 */
public final class StructureSpatialIndex {
    public static final int MIN_INDEXED_PIECES = 24;

    private static final int CELL_SHIFT = 5;
    private static final int MAX_CELLS_PER_BOX = 256;
    private static final int MAX_QUERY_CELLS = 256;

    private final Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();
    private final IntArrayList oversizedPieces = new IntArrayList();

    private BoundingBox[] boxes;
    private int[] seen;
    private int size;
    private int queryStamp;

    public StructureSpatialIndex(BoundingBox[] initialBoxes) {
        this.boxes = Arrays.copyOf(initialBoxes, Math.max(16, initialBoxes.length));
        this.seen = new int[this.boxes.length];
        this.size = initialBoxes.length;
        for (int i = 0; i < initialBoxes.length; ++i) {
            indexBox(initialBoxes[i], i);
        }
    }

    /** Appends a piece after the builder's backing list has appended it. */
    public void add(BoundingBox box) {
        ensureCapacity(size + 1);
        boxes[size] = box;
        indexBox(box, size);
        ++size;
    }

    /**
     * Returns the first intersecting insertion index, or -1 when none match.
     * Very broad queries use a linear scan because traversing thousands of
     * empty grid cells would cost more than testing the current piece list.
     */
    public int findFirst(BoundingBox query) {
        if (query == null || size == 0) {
            return -1;
        }

        int minCellX = Math.floorDiv(query.minX(), 1 << CELL_SHIFT);
        int maxCellX = Math.floorDiv(query.maxX(), 1 << CELL_SHIFT);
        int minCellZ = Math.floorDiv(query.minZ(), 1 << CELL_SHIFT);
        int maxCellZ = Math.floorDiv(query.maxZ(), 1 << CELL_SHIFT);
        long cellCount = ((long) maxCellX - minCellX + 1L)
                * ((long) maxCellZ - minCellZ + 1L);
        if (cellCount > MAX_QUERY_CELLS) {
            return linearFindFirst(query);
        }

        int stamp = nextQueryStamp();
        int best = Integer.MAX_VALUE;

        for (int cellX = minCellX; cellX <= maxCellX; ++cellX) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; ++cellZ) {
                IntArrayList bucket = cells.get(key(cellX, cellZ));
                if (bucket == null) {
                    continue;
                }
                for (int i = 0; i < bucket.size(); ++i) {
                    int candidate = bucket.getInt(i);
                    if (candidate >= best || seen[candidate] == stamp) {
                        continue;
                    }
                    seen[candidate] = stamp;
                    BoundingBox candidateBox = boxes[candidate];
                    if (candidateBox != null && candidateBox.intersects(query)) {
                        best = candidate;
                        if (best == 0) {
                            return 0;
                        }
                    }
                }
            }
        }

        for (int i = 0; i < oversizedPieces.size(); ++i) {
            int candidate = oversizedPieces.getInt(i);
            if (candidate >= best || seen[candidate] == stamp) {
                continue;
            }
            seen[candidate] = stamp;
            BoundingBox candidateBox = boxes[candidate];
            if (candidateBox != null && candidateBox.intersects(query)) {
                best = candidate;
                if (best == 0) {
                    return 0;
                }
            }
        }

        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private int linearFindFirst(BoundingBox query) {
        for (int i = 0; i < size; ++i) {
            BoundingBox candidate = boxes[i];
            if (candidate != null && candidate.intersects(query)) {
                return i;
            }
        }
        return -1;
    }

    private void indexBox(BoundingBox box, int index) {
        if (box == null) {
            return;
        }

        int minCellX = Math.floorDiv(box.minX(), 1 << CELL_SHIFT);
        int maxCellX = Math.floorDiv(box.maxX(), 1 << CELL_SHIFT);
        int minCellZ = Math.floorDiv(box.minZ(), 1 << CELL_SHIFT);
        int maxCellZ = Math.floorDiv(box.maxZ(), 1 << CELL_SHIFT);
        long cellCount = ((long) maxCellX - minCellX + 1L)
                * ((long) maxCellZ - minCellZ + 1L);
        if (cellCount > MAX_CELLS_PER_BOX) {
            oversizedPieces.add(index);
            return;
        }

        for (int cellX = minCellX; cellX <= maxCellX; ++cellX) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; ++cellZ) {
                long key = key(cellX, cellZ);
                IntArrayList bucket = cells.get(key);
                if (bucket == null) {
                    bucket = new IntArrayList(4);
                    cells.put(key, bucket);
                }
                bucket.add(index);
            }
        }
    }

    private int nextQueryStamp() {
        if (queryStamp == Integer.MAX_VALUE) {
            Arrays.fill(seen, 0);
            queryStamp = 1;
        } else {
            ++queryStamp;
            if (queryStamp == 0) {
                queryStamp = 1;
            }
        }
        return queryStamp;
    }

    private void ensureCapacity(int required) {
        if (required <= boxes.length) {
            return;
        }
        int capacity = Math.max(required, boxes.length << 1);
        boxes = Arrays.copyOf(boxes, capacity);
        seen = Arrays.copyOf(seen, capacity);
    }

    private static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xffffffffL);
    }
}
