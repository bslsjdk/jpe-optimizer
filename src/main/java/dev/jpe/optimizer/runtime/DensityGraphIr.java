package dev.jpe.optimizer.runtime;

import java.util.List;
import java.util.Objects;

/** Compact, topologically ordered density graph transport model. */
public final class DensityGraphIr {
    public static final int VERSION = 10;
    public static final int MAX_NODES = 65_536;
    public static final int NO_INPUT = -1;

    public static final int CONST = 1, X = 2, Y = 3, Z = 4, ADD = 5, MUL = 6, OPAQUE = 7, SUB = 8, INPUT = 9, DIV = 10, CLAMP = 11, MIN = 12, MAX = 13, NEGATE = 14, ABS = 15, SQUARE = 16, CUBE = 17, SQRT = 18, LOG = 19, RECIPROCAL = 20, SIGN = 21, SQUEEZE = 22, LEAKY_RELU = 23, GRADIENT_CLAMPED_X = 24, GRADIENT_CLAMPED_Y = 25, GRADIENT_CLAMPED_Z = 26, NOISE = 27, NOISE_TRANSPOSED = 28, INTERPOLATED = 29, RANGE_CHOICE = 30, LERP = 31, POW = 32, SPLINE = 33, SLICE_X = 34, SLICE_Y = 35, SLICE_Z = 36, ROUND = 37, INTERVAL_SELECT = 38, FIND_TOP_SURFACE = 39, DISTANCE_TO_POINT = 40, BLEND_DENSITY = 41, END_ISLAND = 42;

    /** RoundFunction.Type ordinals, in vanilla declaration order. */
    public static final int ROUND_FLOOR = 0, ROUND_ROUND = 1, ROUND_CEIL = 2, ROUND_TRUNCATE = 3;

    /** CubicSpline node kinds. */
    public static final int SPLINE_CONSTANT = 0, SPLINE_MULTIPOINT = 1;

    public record Node(int opcode, int inputA, int inputB, int reserved, double immediate) {
        public Node(int opcode, int inputA, int inputB, double immediate) {
            this(opcode, inputA, inputB, 0, immediate);
        }
        public static Node constant(double value) { return new Node(CONST, NO_INPUT, NO_INPUT, value); }
        public static Node x() { return new Node(X, NO_INPUT, NO_INPUT, 0.0); }
        public static Node y() { return new Node(Y, NO_INPUT, NO_INPUT, 0.0); }
        public static Node z() { return new Node(Z, NO_INPUT, NO_INPUT, 0.0); }
        public static Node add(int left, int right) { return new Node(ADD, left, right, 0.0); }
        public static Node multiply(int left, int right) { return new Node(MUL, left, right, 0.0); }
        public static Node subtract(int left, int right) { return new Node(SUB, left, right, 0.0); }
        public static Node divide(int numerator, int denominator) { return new Node(DIV, numerator, denominator, 0.0); }
        /** Vanilla MinSampler.sampleVolume: choose right only when right < left; ties and NaN keep left. */
        public static Node min(int left, int right) { return new Node(MIN, left, right, 0.0); }
        /** Vanilla MaxSampler.sampleVolume: choose right only when right > left; ties and NaN keep left. */
        public static Node max(int left, int right) { return new Node(MAX, left, right, 0.0); }
        public static Node negate(int input) { return new Node(NEGATE, input, NO_INPUT, 0.0); }
        public static Node abs(int input) { return new Node(ABS, input, NO_INPUT, 0.0); }
        public static Node square(int input) { return new Node(SQUARE, input, NO_INPUT, 0.0); }
        public static Node cube(int input) { return new Node(CUBE, input, NO_INPUT, 0.0); }
        public static Node sqrt(int input) { return new Node(SQRT, input, NO_INPUT, 0.0); }
        public static Node log(int input) { return new Node(LOG, input, NO_INPUT, 0.0); }
        public static Node reciprocal(int input) { return new Node(RECIPROCAL, input, NO_INPUT, 0.0); }
        public static Node sign(int input) { return new Node(SIGN, input, NO_INPUT, 0.0); }
        public static Node squeeze(int input) { return new Node(SQUEEZE, input, NO_INPUT, 0.0); }
        public static Node leakyRelu(int input, float negativeFactor) {
            return new Node(LEAKY_RELU, input, NO_INPUT, 0, negativeFactor);
        }
        /** Integer-coordinate clamped gradient; raw coordinates occupy the integer ABI fields. */
        public static Node gradientClamped(int axis, int fromCoordinate, int minCoordinate,
                                           int maxCoordinate, float fromValue, float coordinateFactor) {
            int opcode = switch (axis) {
                case 0 -> GRADIENT_CLAMPED_X;
                case 1 -> GRADIENT_CLAMPED_Y;
                case 2 -> GRADIENT_CLAMPED_Z;
                default -> throw new IllegalArgumentException("Gradient axis must be X=0, Y=1, or Z=2");
            };
            long bits = ((long) Float.floatToRawIntBits(fromValue) << 32)
                    | (Float.floatToRawIntBits(coordinateFactor) & 0xffff_ffffL);
            return new Node(opcode, fromCoordinate, minCoordinate, maxCoordinate, Double.longBitsToDouble(bits));
        }
        /** Encodes min/max as two raw float32 words in the immediate's 64-bit payload. */
        public static Node clamp(int input, float min, float max) {
            long bits = ((long) Float.floatToRawIntBits(min) << 32)
                    | (Float.floatToRawIntBits(max) & 0xffff_ffffL);
            return new Node(CLAMP, input, NO_INPUT, Double.longBitsToDouble(bits));
        }
        /** Reads one external float32 volume, indexed by the non-negative input slot. */
        public static Node input(int inputIndex) {
            if (inputIndex < 0 || inputIndex > 255) throw new IllegalArgumentException("Input index must be in [0, 255]");
            return new Node(INPUT, NO_INPUT, NO_INPUT, inputIndex);
        }
        /** Experimental sentinel: native evaluation emits NaN and continues through parents. */
        public static Node opaque() { return new Node(OPAQUE, NO_INPUT, NO_INPUT, Double.NaN); }
        public static Node opaque(int inputA, int inputB) { return new Node(OPAQUE, inputA, inputB, Double.NaN); }

        // ---- Nodes whose state lives in the program payload table -------------
        /** Ordered noise stack; the slot indexes the program's noise plan table. */
        public static Node noise(int planSlot) {
            if (planSlot < 0 || planSlot > 63) throw new IllegalArgumentException("Noise slot must be in [0, 63]");
            return new Node(NOISE, planSlot, NO_INPUT, 0, 0.0);
        }
        /** ShiftB: sample the transposed (z,x,0) plane and broadcast along Y. */
        public static Node noiseTransposed(int planSlot) {
            if (planSlot < 0 || planSlot > 63) throw new IllegalArgumentException("Noise slot must be in [0, 63]");
            return new Node(NOISE_TRANSPOSED, planSlot, NO_INPUT, 0, 0.0);
        }
        /** Coarse cell grid plus trilinear expansion; the child is evaluated at cell resolution. */
        public static Node interpolated(int input, int cellSizeXz, int cellSizeY) {
            if (cellSizeXz < 1 || cellSizeXz > 64 || cellSizeY < 1 || cellSizeY > 64) {
                throw new IllegalArgumentException("Interpolated cell size must be in [1, 64]");
            }
            return new Node(INTERPOLATED, input, cellSizeXz, cellSizeY, 0.0);
        }
        /** Vanilla half-open interval: min <= value < max selects the in-range branch. */
        public static Node rangeChoice(int input, int whenInRange, int whenOutOfRange,
                                       float minInclusive, float maxExclusive) {
            long bits = ((long) Float.floatToRawIntBits(minInclusive) << 32)
                    | (Float.floatToRawIntBits(maxExclusive) & 0xffff_ffffL);
            return new Node(RANGE_CHOICE, input, whenInRange, whenOutOfRange, Double.longBitsToDouble(bits));
        }
        /** Mth.lerp(alpha, first, second) with vanilla's alpha == 0 short circuit. */
        public static Node lerp(int alpha, int first, int second) {
            return new Node(LERP, alpha, first, second, 0.0);
        }
        /** (float) Math.pow((double) base, (double) exponent). */
        public static Node pow(int base, int exponent) {
            return new Node(POW, base, exponent, 0, 0.0);
        }
        /** CubicSpline root; the tree lives in the program's spline tables. */
        public static Node spline(int splineRoot) {
            if (splineRoot < 0) throw new IllegalArgumentException("Spline root must be non-negative");
            return new Node(SPLINE, splineRoot, NO_INPUT, 0, 0.0);
        }
        /** SliceFunction: pin one axis to a constant block coordinate. */
        public static Node slice(int axis, int input, int coordinate) {
            int opcode = switch (axis) {
                case 0 -> SLICE_X;
                case 1 -> SLICE_Y;
                case 2 -> SLICE_Z;
                default -> throw new IllegalArgumentException("Slice axis must be X=0, Y=1, or Z=2");
            };
            return new Node(opcode, input, NO_INPUT, 0, coordinate);
        }
        /** RoundFunction; pass NO_INPUT as multiple for the default 1.0. */
        public static Node round(int input, int multiple, int type) {
            if (type < ROUND_FLOOR || type > ROUND_TRUNCATE) {
                throw new IllegalArgumentException("Round type must be FLOOR, ROUND, CEIL, or TRUNCATE");
            }
            return new Node(ROUND, input, multiple, type, 0.0);
        }
        /** IntervalSelectFunction; the table carries the input, thresholds, and branches. */
        public static Node intervalSelect(int tableIndex) {
            if (tableIndex < 0) throw new IllegalArgumentException("Interval table index must be non-negative");
            return new Node(INTERVAL_SELECT, tableIndex, NO_INPUT, 0, 0.0);
        }
        /** FindTopSurfaceFunction; the result is one height per column, so sizeY must be 1. */
        public static Node findTopSurface(int density, int upperBound, int lowerBound, int cellHeight) {
            if (cellHeight < 1 || cellHeight > 64) {
                throw new IllegalArgumentException("Top-surface cell height must be in [1, 64]");
            }
            return new Node(FIND_TOP_SURFACE, density, upperBound, cellHeight, lowerBound);
        }
        /** DistanceToPointFunction; the point and metric live in the program table. */
        public static Node distanceToPoint(int pointIndex) {
            if (pointIndex < 0) throw new IllegalArgumentException("Distance point index must be non-negative");
            return new Node(DISTANCE_TO_POINT, pointIndex, NO_INPUT, 0, 0.0);
        }
        /** BlendDensityFunction; the region comes from the program payload. */
        public static Node blendDensity(int input) {
            return new Node(BLEND_DENSITY, input, NO_INPUT, 0, 0.0);
        }
        /** EndIslandFunction; uses the program's simplex permutation. */
        public static Node endIsland() {
            return new Node(END_ISLAND, NO_INPUT, NO_INPUT, 0, 0.0);
        }
    }

    // ---- Program payload tables ----------------------------------------------
    public record SplineNode(int kind, int coordinate, float constant, int firstPoint, int pointCount) {
        public SplineNode {
            if (kind != SPLINE_CONSTANT && kind != SPLINE_MULTIPOINT) {
                throw new IllegalArgumentException("Spline kind must be CONSTANT or MULTIPOINT");
            }
        }
        public static SplineNode constant(float value) {
            return new SplineNode(SPLINE_CONSTANT, 0, value, 0, 0);
        }
        public static SplineNode multipoint(int coordinate, int firstPoint, int pointCount) {
            return new SplineNode(SPLINE_MULTIPOINT, coordinate, 0.0f, firstPoint, pointCount);
        }
    }

    public record SplinePoint(float location, float derivative, int child) {}

    public record IntervalSelect(int inputNode, int firstThreshold, int thresholdCount, int firstBranch) {}

    /** metric: 0 euclidean, 1 squared, 2 manhattan, 3 chebyshev. */
    public record DistancePoint(int x, int y, int z, int metric) {
        public DistancePoint {
            if (metric < 0 || metric > 3) throw new IllegalArgumentException("Distance metric must be in [0, 3]");
        }
    }

    /**
     * Pre-resolved blend region. Vanilla resolves its blending maps per voxel through
     * ChunkPos lookups and boundary fallbacks; the caller flattens both maps into
     * dense cell grids so every probe becomes one index.
     */
    public record BlendRegion(boolean active, int baseCellX, int baseCellY, int baseCellZ,
                              int cellsX, int cellsY, int cellsZ,
                              float[] fixedDensity, float[] density, byte[] densityMask) {
        public BlendRegion {
            if (active) {
                if (cellsX <= 0 || cellsY <= 0 || cellsZ <= 0) {
                    throw new IllegalArgumentException("Blend region must have positive dimensions");
                }
                long cells = (long) cellsX * cellsY * cellsZ;
                if (fixedDensity.length != cells || density.length != cells || densityMask.length != cells) {
                    throw new IllegalArgumentException("Blend region arrays must match cellsX * cellsY * cellsZ");
                }
            }
        }
        public static BlendRegion inactive() {
            return new BlendRegion(false, 0, 0, 0, 0, 0, 0, new float[0], new float[0], new byte[0]);
        }
    }

    public record Payload(List<SplineNode> splines, List<SplinePoint> splinePoints,
                          List<IntervalSelect> intervalSelects, float[] intervalThresholds,
                          int[] intervalBranches, List<DistancePoint> distancePoints,
                          BlendRegion blend, byte[] endIslandPermutation) {
        public Payload {
            splines = List.copyOf(splines);
            splinePoints = List.copyOf(splinePoints);
            intervalSelects = List.copyOf(intervalSelects);
            distancePoints = List.copyOf(distancePoints);
            if (blend == null) blend = BlendRegion.inactive();
        }
        public static Payload empty() {
            return new Payload(List.of(), List.of(), List.of(), new float[0], new int[0],
                    List.of(), BlendRegion.inactive(), null);
        }
    }

    /** Sidecar diagnostic. It is deliberately not part of the hot native node record. */
    public record Diagnostic(int nodeIndex, String sourceClass, String reason, List<Integer> childIndices) {
        public Diagnostic(int nodeIndex, String sourceClass, String reason) {
            this(nodeIndex, sourceClass, reason, List.of());
        }
        public Diagnostic {
            if (nodeIndex < 0) throw new IllegalArgumentException("Negative diagnostic node index");
            Objects.requireNonNull(sourceClass, "sourceClass");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(childIndices, "childIndices");
            childIndices = List.copyOf(childIndices);
            if (childIndices.size() > MAX_NODES) {
                throw new IllegalArgumentException("Opaque dependency list exceeds the IR limit");
            }
            for (int child : childIndices) {
                if (child < 0 || child >= nodeIndex) {
                    throw new IllegalArgumentException("Opaque dependency must reference an earlier node");
                }
            }
        }
    }

    private final List<Node> nodes;
    private final List<Diagnostic> diagnostics;
    private final int rootNode;
    private final long configurationFingerprint;
    private final Payload payload;

    public DensityGraphIr(List<Node> nodes, int rootNode, long configurationFingerprint) {
        this(nodes, List.of(), rootNode, configurationFingerprint, Payload.empty());
    }

    public DensityGraphIr(List<Node> nodes, List<Diagnostic> diagnostics,
                          int rootNode, long configurationFingerprint) {
        this(nodes, diagnostics, rootNode, configurationFingerprint, Payload.empty());
    }

    public DensityGraphIr(List<Node> nodes, List<Diagnostic> diagnostics,
                          int rootNode, long configurationFingerprint, Payload payload) {
        Objects.requireNonNull(nodes, "nodes");
        this.payload = Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("Density graph node count must be between 1 and " + MAX_NODES);
        }
        if (rootNode < 0 || rootNode >= nodes.size()) {
            throw new IllegalArgumentException("Density graph root is outside the node array");
        }
        this.nodes = List.copyOf(nodes);
        this.diagnostics = List.copyOf(diagnostics);
        this.rootNode = rootNode;
        this.configurationFingerprint = configurationFingerprint;
        validateTopologicalOrder(this.nodes, this.payload);
        boolean[] diagnosed = new boolean[this.nodes.size()];
        for (Diagnostic diagnostic : this.diagnostics) {
            int nodeIndex = diagnostic.nodeIndex();
            if (nodeIndex >= this.nodes.size() || this.nodes.get(nodeIndex).opcode() != OPAQUE) {
                throw new IllegalArgumentException("Diagnostic must reference an OPAQUE node");
            }
            if (diagnosed[nodeIndex]) {
                throw new IllegalArgumentException("OPAQUE node must not have duplicate diagnostics");
            }
            diagnosed[nodeIndex] = true;
            Node opaque = this.nodes.get(nodeIndex);
            List<Integer> children = diagnostic.childIndices();
            int expectedA = children.isEmpty() ? NO_INPUT : children.get(0);
            int expectedB = children.size() < 2 ? NO_INPUT : children.get(1);
            if (opaque.inputA() != expectedA || opaque.inputB() != expectedB) {
                throw new IllegalArgumentException(
                        "OPAQUE node inputs must match the first two diagnostic dependencies");
            }
        }
    }

    private static void validateTopologicalOrder(List<Node> nodes, Payload payload) {
        for (int i = 0; i < nodes.size(); ++i) {
            Node node = Objects.requireNonNull(nodes.get(i), "node");
            switch (node.opcode()) {
                case CONST, X, Y, Z, INPUT -> {
                    if (node.inputA() != NO_INPUT || node.inputB() != NO_INPUT
                            || node.opcode() == INPUT && (!Double.isFinite(node.immediate())
                            || node.immediate() < 0 || node.immediate() > 255
                            || node.immediate() != Math.rint(node.immediate()))) {
                        throw new IllegalArgumentException("Leaf node " + i + " must not have inputs");
                    }
                }
                case GRADIENT_CLAMPED_X, GRADIENT_CLAMPED_Y, GRADIENT_CLAMPED_Z -> {
                    if (Integer.compare(node.inputB(), node.reserved()) > 0) {
                        throw new IllegalArgumentException("Gradient clamp minimum exceeds maximum at node " + i);
                    }
                }
                case OPAQUE -> {
                    if (node.inputA() == NO_INPUT && node.inputB() != NO_INPUT
                            || node.inputA() != NO_INPUT && (node.inputA() < 0 || node.inputA() >= i)
                            || node.inputB() != NO_INPUT && (node.inputB() < 0 || node.inputB() >= i)) {
                        throw new IllegalArgumentException("Opaque node " + i + " has invalid child references");
                    }
                }
                case NEGATE, ABS, SQUARE, CUBE, SQRT, LOG, RECIPROCAL, SIGN, SQUEEZE -> {
                    if (node.inputA() < 0 || node.inputA() >= i || node.inputB() != NO_INPUT) {
                        throw new IllegalArgumentException("Unary node " + i + " must reference one earlier node");
                    }
                }
                case LEAKY_RELU -> {
                    if (node.inputA() < 0 || node.inputA() >= i || node.inputB() != NO_INPUT
                            || !Double.isFinite(node.immediate())
                            || node.immediate() != (double) (float) node.immediate()) {
                        throw new IllegalArgumentException("LeakyReLU node " + i + " requires one input and a finite float32 factor");
                    }
                }
                case CLAMP -> {
                    if (node.inputA() < 0 || node.inputA() >= i || node.inputB() != NO_INPUT) {
                        throw new IllegalArgumentException("Clamp node " + i + " must reference one earlier input");
                    }
                }
                case ADD, MUL, SUB, DIV, MIN, MAX -> {
                    if (node.inputA() < 0 || node.inputA() >= i
                            || node.inputB() < 0 || node.inputB() >= i) {
                        throw new IllegalArgumentException("Binary node " + i
                                + " must reference two earlier nodes");
                    }
                }
                case NOISE, NOISE_TRANSPOSED -> {
                    if (node.inputA() < 0 || node.inputA() > 63 || node.inputB() != NO_INPUT
                            || node.reserved() != 0 || node.immediate() != 0.0) {
                        throw new IllegalArgumentException("Noise node " + i + " requires a plan slot and no inputs");
                    }
                }
                case INTERPOLATED -> {
                    if (node.inputA() < 0 || node.inputA() >= i
                            || node.inputB() < 1 || node.inputB() > 64
                            || node.reserved() < 1 || node.reserved() > 64
                            || node.immediate() != 0.0) {
                        throw new IllegalArgumentException("Interpolated node " + i
                                + " requires one earlier input and cell sizes in [1, 64]");
                    }
                }
                case RANGE_CHOICE -> {
                    if (node.inputA() < 0 || node.inputA() >= i
                            || node.inputB() < 0 || node.inputB() >= i
                            || node.reserved() < 0 || node.reserved() >= i
                            || !Double.isFinite(node.immediate())) {
                        throw new IllegalArgumentException("RangeChoice node " + i
                                + " requires three earlier inputs and finite bounds");
                    }
                }
                case LERP -> {
                    if (node.inputA() < 0 || node.inputA() >= i
                            || node.inputB() < 0 || node.inputB() >= i
                            || node.reserved() < 0 || node.reserved() >= i) {
                        throw new IllegalArgumentException("Lerp node " + i + " requires three earlier inputs");
                    }
                }
                case POW -> {
                    if (node.inputA() < 0 || node.inputA() >= i
                            || node.inputB() < 0 || node.inputB() >= i || node.reserved() != 0) {
                        throw new IllegalArgumentException("Pow node " + i + " requires two earlier inputs");
                    }
                }
                case SPLINE -> {
                    if (node.inputB() != NO_INPUT || node.reserved() != 0 || node.immediate() != 0.0) {
                        throw new IllegalArgumentException("Spline node " + i + " takes no operands");
                    }
                    if (node.inputA() < 0 || node.inputA() >= payload.splines().size()) {
                        throw new IllegalArgumentException("Spline node " + i + " references a missing root");
                    }
                    SplineNode root = payload.splines().get(node.inputA());
                    if (root.kind() == SPLINE_MULTIPOINT && (root.coordinate() < 0 || root.coordinate() >= i)) {
                        throw new IllegalArgumentException("Spline root of node " + i
                                + " must read an earlier node");
                    }
                }
                case SLICE_X, SLICE_Y, SLICE_Z -> {
                    if (node.inputA() < 0 || node.inputA() >= i || node.inputB() != NO_INPUT
                            || node.reserved() != 0) {
                        throw new IllegalArgumentException("Slice node " + i + " requires one earlier input");
                    }
                    if (!Double.isFinite(node.immediate()) || node.immediate() != Math.rint(node.immediate())
                            || node.immediate() < Integer.MIN_VALUE || node.immediate() > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("Slice node " + i + " needs an integral coordinate");
                    }
                }
                case ROUND -> {
                    if (node.inputA() < 0 || node.inputA() >= i
                            || node.reserved() < ROUND_FLOOR || node.reserved() > ROUND_TRUNCATE
                            || node.immediate() != 0.0) {
                        throw new IllegalArgumentException("Round node " + i + " has an invalid type or input");
                    }
                    if (node.inputB() != NO_INPUT && (node.inputB() < 0 || node.inputB() >= i)) {
                        throw new IllegalArgumentException("Round node " + i + " has an invalid multiple");
                    }
                }
                case INTERVAL_SELECT -> {
                    if (node.inputB() != NO_INPUT || node.reserved() != 0 || node.immediate() != 0.0) {
                        throw new IllegalArgumentException("IntervalSelect node " + i + " takes no operands");
                    }
                    if (node.inputA() < 0 || node.inputA() >= payload.intervalSelects().size()) {
                        throw new IllegalArgumentException("IntervalSelect node " + i + " references a missing table");
                    }
                    IntervalSelect table = payload.intervalSelects().get(node.inputA());
                    if (table.inputNode() < 0 || table.inputNode() >= i) {
                        throw new IllegalArgumentException("IntervalSelect table of node " + i
                                + " must read an earlier node");
                    }
                    if ((long) table.firstThreshold() + table.thresholdCount()
                            > payload.intervalThresholds().length) {
                        throw new IllegalArgumentException("IntervalSelect table of node " + i
                                + " exceeds the threshold table");
                    }
                    if ((long) table.firstBranch() + table.thresholdCount() + 1
                            > payload.intervalBranches().length) {
                        throw new IllegalArgumentException("IntervalSelect table of node " + i
                                + " exceeds the branch table");
                    }
                    for (int k = 0; k <= table.thresholdCount(); ++k) {
                        int branch = payload.intervalBranches()[table.firstBranch() + k];
                        if (branch < 0 || branch >= i) {
                            throw new IllegalArgumentException("IntervalSelect branch of node " + i
                                    + " must reference an earlier node");
                        }
                    }
                    for (int k = 1; k < table.thresholdCount(); ++k) {
                        int previous = table.firstThreshold() + k - 1;
                        int current = table.firstThreshold() + k;
                        if (!(payload.intervalThresholds()[previous] < payload.intervalThresholds()[current])) {
                            throw new IllegalArgumentException("IntervalSelect thresholds of node " + i
                                    + " must be strictly ascending");
                        }
                    }
                }
                case FIND_TOP_SURFACE -> {
                    if (node.inputA() < 0 || node.inputA() >= i
                            || node.inputB() < 0 || node.inputB() >= i) {
                        throw new IllegalArgumentException("FindTopSurface node " + i
                                + " requires two earlier inputs");
                    }
                    if (node.reserved() < 1 || node.reserved() > 64) {
                        throw new IllegalArgumentException("FindTopSurface cell height must be in [1, 64]");
                    }
                    if (!Double.isFinite(node.immediate()) || node.immediate() != Math.rint(node.immediate())
                            || node.immediate() < Integer.MIN_VALUE || node.immediate() > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("FindTopSurface node " + i
                                + " needs an integral lower bound");
                    }
                }
                case DISTANCE_TO_POINT -> {
                    if (node.inputB() != NO_INPUT || node.reserved() != 0 || node.immediate() != 0.0) {
                        throw new IllegalArgumentException("DistanceToPoint node " + i + " takes no operands");
                    }
                    if (node.inputA() < 0 || node.inputA() >= payload.distancePoints().size()) {
                        throw new IllegalArgumentException("DistanceToPoint node " + i
                                + " references a missing point");
                    }
                }
                case BLEND_DENSITY -> {
                    if (node.inputA() < 0 || node.inputA() >= i || node.inputB() != NO_INPUT
                            || node.reserved() != 0 || node.immediate() != 0.0) {
                        throw new IllegalArgumentException("BlendDensity node " + i
                                + " requires one earlier input");
                    }
                }
                case END_ISLAND -> {
                    if (node.inputA() != NO_INPUT || node.inputB() != NO_INPUT
                            || node.reserved() != 0 || node.immediate() != 0.0) {
                        throw new IllegalArgumentException("EndIsland node " + i + " takes no operands");
                    }
                    if (payload.endIslandPermutation() == null
                            || payload.endIslandPermutation().length != 256) {
                        throw new IllegalArgumentException("EndIsland node " + i
                                + " needs a 256-entry simplex permutation");
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported density IR opcode: " + node.opcode());
            }
        }
        validateSplineTables(payload);
    }

    private static void validateSplineTables(Payload payload) {
        List<SplineNode> splines = payload.splines();
        for (int index = 0; index < splines.size(); ++index) {
            SplineNode node = splines.get(index);
            if (node.kind() == SPLINE_CONSTANT) continue;
            if (node.pointCount() < 1) {
                throw new IllegalArgumentException("Spline " + index + " has no points");
            }
            if ((long) node.firstPoint() + node.pointCount() > payload.splinePoints().size()) {
                throw new IllegalArgumentException("Spline " + index + " exceeds the point table");
            }
            for (int k = 0; k < node.pointCount(); ++k) {
                SplinePoint point = payload.splinePoints().get(node.firstPoint() + k);
                if (point.child() < 0 || point.child() >= splines.size()) {
                    throw new IllegalArgumentException("Spline " + index + " has an invalid child");
                }
                if (k > 0) {
                    float previous = payload.splinePoints().get(node.firstPoint() + k - 1).location();
                    if (!(previous < point.location())) {
                        throw new IllegalArgumentException("Spline " + index
                                + " locations must be strictly ascending");
                    }
                }
            }
        }
    }

    public List<Node> nodes() { return nodes; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public int rootNode() { return rootNode; }
    public long configurationFingerprint() { return configurationFingerprint; }
    public Payload payload() { return payload; }
    public boolean experimental() { return nodes.stream().anyMatch(node -> node.opcode() == OPAQUE); }
}
