package dev.jpe.optimizer.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Byte-exact encoding of the native {@code JpeGraphProgram} payload tables.
 *
 * <p>This class owns every struct size and field offset in one place so the FFM
 * bridge never has to restate the C layout. Everything here is plain
 * {@link ByteBuffer} work, which means the packing can be unit tested without a
 * native library, and the bridge only has to copy bytes and patch pointers.
 *
 * <p>All integers and floats use {@link ByteOrder#nativeOrder()}: the header is
 * consumed by a library compiled for the running host, so host order is the only
 * correct choice.
 */
public final class DensityGraphProgram {
    // ---- Mirrored C layout (native/include/jpe_native_api.h) -----------------
    /** struct JpeSplineNode: u32 kind, u32 coordinate, float constant, u32 firstPoint, u32 pointCount. */
    public static final int SPLINE_NODE_BYTES = 20;
    public static final int SPLINE_NODE_KIND = 0;
    public static final int SPLINE_NODE_COORDINATE = 4;
    public static final int SPLINE_NODE_CONSTANT = 8;
    public static final int SPLINE_NODE_FIRST_POINT = 12;
    public static final int SPLINE_NODE_POINT_COUNT = 16;

    /** struct JpeSplinePoint: float location, float derivative, u32 child. */
    public static final int SPLINE_POINT_BYTES = 12;
    public static final int SPLINE_POINT_LOCATION = 0;
    public static final int SPLINE_POINT_DERIVATIVE = 4;
    public static final int SPLINE_POINT_CHILD = 8;

    /** struct JpeIntervalSelect: four u32 fields. */
    public static final int INTERVAL_SELECT_BYTES = 16;
    public static final int INTERVAL_SELECT_INPUT_NODE = 0;
    public static final int INTERVAL_SELECT_FIRST_THRESHOLD = 4;
    public static final int INTERVAL_SELECT_THRESHOLD_COUNT = 8;
    public static final int INTERVAL_SELECT_FIRST_BRANCH = 12;

    /** struct JpeDistancePoint: i32 x, i32 y, i32 z, u32 metric. */
    public static final int DISTANCE_POINT_BYTES = 16;
    public static final int DISTANCE_POINT_X = 0;
    public static final int DISTANCE_POINT_Y = 4;
    public static final int DISTANCE_POINT_Z = 8;
    public static final int DISTANCE_POINT_METRIC = 12;

    /**
     * struct JpeBlendInput: seven u32/i32 fields, four bytes of tail padding so the
     * first pointer lands on an 8-byte boundary, then three pointers.
     */
    public static final int BLEND_INPUT_BYTES = 56;
    public static final int BLEND_INPUT_ACTIVE = 0;
    public static final int BLEND_INPUT_BASE_CELL_X = 4;
    public static final int BLEND_INPUT_BASE_CELL_Y = 8;
    public static final int BLEND_INPUT_BASE_CELL_Z = 12;
    public static final int BLEND_INPUT_CELLS_X = 16;
    public static final int BLEND_INPUT_CELLS_Y = 20;
    public static final int BLEND_INPUT_CELLS_Z = 24;
    public static final int BLEND_INPUT_FIXED_DENSITY = 32;
    public static final int BLEND_INPUT_DENSITY = 40;
    public static final int BLEND_INPUT_DENSITY_MASK = 48;

    /** struct JpeGraphProgram: seven (pointer, u32, pad) pairs followed by two pointers. */
    public static final int GRAPH_PROGRAM_BYTES = 7 * 16 + 2 * 8;
    public static final int GRAPH_PROGRAM_NOISE_PLANS = 0;
    public static final int GRAPH_PROGRAM_NOISE_PLAN_COUNT = 8;
    public static final int GRAPH_PROGRAM_SPLINES = 16;
    public static final int GRAPH_PROGRAM_SPLINE_COUNT = 24;
    public static final int GRAPH_PROGRAM_SPLINE_POINTS = 32;
    public static final int GRAPH_PROGRAM_SPLINE_POINT_COUNT = 40;
    public static final int GRAPH_PROGRAM_INTERVAL_SELECTS = 48;
    public static final int GRAPH_PROGRAM_INTERVAL_SELECT_COUNT = 56;
    public static final int GRAPH_PROGRAM_INTERVAL_THRESHOLDS = 64;
    public static final int GRAPH_PROGRAM_INTERVAL_THRESHOLD_COUNT = 72;
    public static final int GRAPH_PROGRAM_INTERVAL_BRANCHES = 80;
    public static final int GRAPH_PROGRAM_INTERVAL_BRANCH_COUNT = 88;
    public static final int GRAPH_PROGRAM_DISTANCE_POINTS = 96;
    public static final int GRAPH_PROGRAM_DISTANCE_POINT_COUNT = 104;
    public static final int GRAPH_PROGRAM_BLEND = 112;
    public static final int GRAPH_PROGRAM_END_ISLAND = 120;

    private static final ByteOrder ORDER = ByteOrder.nativeOrder();

    private final byte[] splines;
    private final byte[] splinePoints;
    private final byte[] intervalSelects;
    private final byte[] intervalThresholds;
    private final byte[] intervalBranches;
    private final byte[] distancePoints;
    private final byte[] endIslandPermutation;
    private final DensityGraphIr.BlendRegion blend;

    private DensityGraphProgram(byte[] splines, byte[] splinePoints, byte[] intervalSelects,
                                byte[] intervalThresholds, byte[] intervalBranches,
                                byte[] distancePoints, byte[] endIslandPermutation,
                                DensityGraphIr.BlendRegion blend) {
        this.splines = splines;
        this.splinePoints = splinePoints;
        this.intervalSelects = intervalSelects;
        this.intervalThresholds = intervalThresholds;
        this.intervalBranches = intervalBranches;
        this.distancePoints = distancePoints;
        this.endIslandPermutation = endIslandPermutation;
        this.blend = blend;
    }

    public static DensityGraphProgram empty() {
        return from(DensityGraphIr.Payload.empty());
    }

    /** Encodes every payload table. An empty table becomes a zero-length array. */
    public static DensityGraphProgram from(DensityGraphIr.Payload payload) {
        Objects.requireNonNull(payload, "payload");
        ByteBuffer splines = allocate(payload.splines().size(), SPLINE_NODE_BYTES);
        for (DensityGraphIr.SplineNode node : payload.splines()) {
            splines.putInt(node.kind());
            splines.putInt(node.coordinate());
            splines.putFloat(node.constant());
            splines.putInt(node.firstPoint());
            splines.putInt(node.pointCount());
        }
        ByteBuffer points = allocate(payload.splinePoints().size(), SPLINE_POINT_BYTES);
        for (DensityGraphIr.SplinePoint point : payload.splinePoints()) {
            points.putFloat(point.location());
            points.putFloat(point.derivative());
            points.putInt(point.child());
        }
        ByteBuffer selects = allocate(payload.intervalSelects().size(), INTERVAL_SELECT_BYTES);
        for (DensityGraphIr.IntervalSelect select : payload.intervalSelects()) {
            selects.putInt(select.inputNode());
            selects.putInt(select.firstThreshold());
            selects.putInt(select.thresholdCount());
            selects.putInt(select.firstBranch());
        }
        ByteBuffer thresholds = allocate(payload.intervalThresholds().length, Float.BYTES);
        for (float value : payload.intervalThresholds()) thresholds.putFloat(value);
        ByteBuffer branches = allocate(payload.intervalBranches().length, Integer.BYTES);
        for (int value : payload.intervalBranches()) branches.putInt(value);
        ByteBuffer distances = allocate(payload.distancePoints().size(), DISTANCE_POINT_BYTES);
        for (DensityGraphIr.DistancePoint point : payload.distancePoints()) {
            distances.putInt(point.x());
            distances.putInt(point.y());
            distances.putInt(point.z());
            distances.putInt(point.metric());
        }
        byte[] permutation = payload.endIslandPermutation() == null
                ? new byte[0] : payload.endIslandPermutation().clone();
        return new DensityGraphProgram(
                bytes(splines), bytes(points), bytes(selects), bytes(thresholds),
                bytes(branches), bytes(distances), permutation, payload.blend());
    }

    private static ByteBuffer allocate(int count, int elementBytes) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(count, elementBytes)).order(ORDER);
        return buffer;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        return buffer.array();
    }

    public byte[] splines() { return splines.clone(); }
    public byte[] splinePoints() { return splinePoints.clone(); }
    public byte[] intervalSelects() { return intervalSelects.clone(); }
    public byte[] intervalThresholds() { return intervalThresholds.clone(); }
    public byte[] intervalBranches() { return intervalBranches.clone(); }
    public byte[] distancePoints() { return distancePoints.clone(); }
    public byte[] endIslandPermutation() { return endIslandPermutation.clone(); }
    public DensityGraphIr.BlendRegion blend() { return blend; }

    public int splineCount() { return splines.length / SPLINE_NODE_BYTES; }
    public int splinePointCount() { return splinePoints.length / SPLINE_POINT_BYTES; }
    public int intervalSelectCount() { return intervalSelects.length / INTERVAL_SELECT_BYTES; }
    public int intervalThresholdCount() { return intervalThresholds.length / Float.BYTES; }
    public int intervalBranchCount() { return intervalBranches.length / Integer.BYTES; }
    public int distancePointCount() { return distancePoints.length / DISTANCE_POINT_BYTES; }
    public boolean hasEndIsland() { return endIslandPermutation.length == 256; }

    /** Encodes the blend descriptor. The three pointer fields stay zero for the caller to patch. */
    public byte[] blendInputBytes() {
        if (!blend.active()) return new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(BLEND_INPUT_BYTES).order(ORDER);
        buffer.putInt(BLEND_INPUT_ACTIVE, 1);
        buffer.putInt(BLEND_INPUT_BASE_CELL_X, blend.baseCellX());
        buffer.putInt(BLEND_INPUT_BASE_CELL_Y, blend.baseCellY());
        buffer.putInt(BLEND_INPUT_BASE_CELL_Z, blend.baseCellZ());
        buffer.putInt(BLEND_INPUT_CELLS_X, blend.cellsX());
        buffer.putInt(BLEND_INPUT_CELLS_Y, blend.cellsY());
        buffer.putInt(BLEND_INPUT_CELLS_Z, blend.cellsZ());
        return buffer.array();
    }

    /** Little detail that bites FFM callers: the struct has to be zeroed, not just filled. */
    public static byte[] emptyProgramBytes() {
        return new byte[GRAPH_PROGRAM_BYTES];
    }

    public static int floatBytes(float[] values) {
        return Math.multiplyExact(values.length, Float.BYTES);
    }

    /** Encodes a float grid in native order, which is what the blend region expects. */
    public static byte[] encodeFloats(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(floatBytes(values)).order(ORDER);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }
}
