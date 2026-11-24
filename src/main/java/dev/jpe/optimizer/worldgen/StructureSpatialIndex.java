package dev.jpe.optimizer.worldgen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * XZ broadphase for structure-piece collision checks.
 *
 * <p>The index only narrows the candidate set. Every candidate is still tested
 * against all three axes using inclusive bounds, and the smallest insertion
 * index wins to preserve vanilla's first-match semantics.</p>
 */
public final class StructureSpatialIndex {
    public static final int MIN_INDEXED_PIECES = 24;

    private static final boolean METRICS_ENABLED = Boolean.getBoolean("jpe.structureIndexMetrics");
    private static final LongAdder METRIC_QUERIES = new LongAdder();
    private static final LongAdder METRIC_BUILDS = new LongAdder();
    private static final LongAdder METRIC_INDEXED_PIECES = new LongAdder();
    private static final LongAdder METRIC_CANDIDATE_CHECKS = new LongAdder();
    private static final LongAdder METRIC_LINEAR_FALLBACKS = new LongAdder();
    private static final LongAdder METRIC_VANILLA_LINEAR_CHECKS = new LongAdder();

    public record Metrics(long queries, long builds, long indexedPieces,
                          long candidateChecks, long linearFallbacks,
                          long vanillaLinearChecks) {}

    public static Metrics metricsSnapshot() {
        return new Metrics(METRIC_QUERIES.sum(), METRIC_BUILDS.sum(),
                METRIC_INDEXED_PIECES.sum(), METRIC_CANDIDATE_CHECKS.sum(),
                METRIC_LINEAR_FALLBACKS.sum(), METRIC_VANILLA_LINEAR_CHECKS.sum());
    }

    /** Records the work the vanilla linear scan would perform when the index is disabled or below threshold. */
    public static void recordVanillaLinearQuery(int pieceCount) {
        if (METRICS_ENABLED) {
            METRIC_QUERIES.increment();
            METRIC_VANILLA_LINEAR_CHECKS.add(Math.max(0, pieceCount));
        }
    }

    private static final int CELL_SHIFT = 5;
    private static final int MAX_CELLS_PER_BOX = 256;
    private static final int MAX_QUERY_CELLS = 256;

    private final Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();
    private final IntArrayList oversizedPieces = new IntArrayList();

    private Box[] boxes;
    private int[] seen;
    private int size;
    private int queryStamp;

    public StructureSpatialIndex(Box[] initialBoxes) {
        this.boxes = Arrays.copyOf(initialBoxes, Math.max(16, initialBoxes.length));
        this.seen = new int[this.boxes.length];
        this.size = initialBoxes.length;
        if (METRICS_ENABLED) {
            METRIC_BUILDS.increment();
            METRIC_INDEXED_PIECES.add(initialBoxes.length);
        }
        for (int i = 0; i < initialBoxes.length; ++i) {
            indexBox(initialBoxes[i], i);
        }
    }

    /** Appends a piece after the builder's backing list has appended it. */
    public void add(Box box) {
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
    public int findFirst(Box query) {
        if (METRICS_ENABLED) {
            METRIC_QUERIES.increment();
            METRIC_VANILLA_LINEAR_CHECKS.add(size);
        }
        if (query == null || size == 0) {
            return -1;
        }

        int minCellX = Math.floorDiv(query.minX, 1 << CELL_SHIFT);
        int maxCellX = Math.floorDiv(query.maxX, 1 << CELL_SHIFT);
        int minCellZ = Math.floorDiv(query.minZ, 1 << CELL_SHIFT);
        int maxCellZ = Math.floorDiv(query.maxZ, 1 << CELL_SHIFT);
        long cellCount = ((long) maxCellX - minCellX + 1L)
                * ((long) maxCellZ - minCellZ + 1L);
        if (cellCount > MAX_QUERY_CELLS) {
            if (METRICS_ENABLED) {
                METRIC_LINEAR_FALLBACKS.increment();
            }
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
                    Box candidateBox = boxes[candidate];
                    if (candidateBox != null && METRICS_ENABLED) {
                        METRIC_CANDIDATE_CHECKS.increment();
                    }
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
            Box candidateBox = boxes[candidate];
            if (candidateBox != null && METRICS_ENABLED) {
                METRIC_CANDIDATE_CHECKS.increment();
            }
            if (candidateBox != null && candidateBox.intersects(query)) {
                best = candidate;
                if (best == 0) {
                    return 0;
                }
            }
        }

        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private int linearFindFirst(Box query) {
        for (int i = 0; i < size; ++i) {
            Box candidate = boxes[i];
            if (candidate != null) {
                if (METRICS_ENABLED) {
                    METRIC_CANDIDATE_CHECKS.increment();
                }
            }
            if (candidate != null && candidate.intersects(query)) {
                return i;
            }
        }
        return -1;
    }

    private void indexBox(Box box, int index) {
        if (box == null) {
            return;
        }

        int minCellX = Math.floorDiv(box.minX, 1 << CELL_SHIFT);
        int maxCellX = Math.floorDiv(box.maxX, 1 << CELL_SHIFT);
        int minCellZ = Math.floorDiv(box.minZ, 1 << CELL_SHIFT);
        int maxCellZ = Math.floorDiv(box.maxZ, 1 << CELL_SHIFT);
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

    /** Primitive, immutable copy of a Minecraft bounding box for indexing. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean intersects(Box other) {
            return this.minX <= other.maxX && this.maxX >= other.minX
                    && this.minY <= other.maxY && this.maxY >= other.minY
                    && this.minZ <= other.maxZ && this.maxZ >= other.minZ;
        }
    }
}
