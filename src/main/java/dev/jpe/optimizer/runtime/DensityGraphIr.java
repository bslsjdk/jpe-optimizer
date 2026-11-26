package dev.jpe.optimizer.runtime;

import java.util.List;
import java.util.Objects;

/** Compact, topologically ordered density graph transport model. */
public final class DensityGraphIr {
    public static final int VERSION = 8;
    public static final int MAX_NODES = 65_536;
    public static final int NO_INPUT = -1;

    public static final int CONST = 1, X = 2, Y = 3, Z = 4, ADD = 5, MUL = 6, OPAQUE = 7, SUB = 8, INPUT = 9, DIV = 10, CLAMP = 11, MIN = 12, MAX = 13;

    public record Node(int opcode, int inputA, int inputB, double immediate) {
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

    public DensityGraphIr(List<Node> nodes, int rootNode, long configurationFingerprint) {
        this(nodes, List.of(), rootNode, configurationFingerprint);
    }

    public DensityGraphIr(List<Node> nodes, List<Diagnostic> diagnostics,
                          int rootNode, long configurationFingerprint) {
        Objects.requireNonNull(nodes, "nodes");
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
        validateTopologicalOrder(this.nodes);
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

    private static void validateTopologicalOrder(List<Node> nodes) {
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
                case OPAQUE -> {
                    if (node.inputA() == NO_INPUT && node.inputB() != NO_INPUT
                            || node.inputA() != NO_INPUT && (node.inputA() < 0 || node.inputA() >= i)
                            || node.inputB() != NO_INPUT && (node.inputB() < 0 || node.inputB() >= i)) {
                        throw new IllegalArgumentException("Opaque node " + i + " has invalid child references");
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
                default -> throw new IllegalArgumentException("Unsupported density IR opcode: " + node.opcode());
            }
        }
    }

    public List<Node> nodes() { return nodes; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public int rootNode() { return rootNode; }
    public long configurationFingerprint() { return configurationFingerprint; }
    public boolean experimental() { return nodes.stream().anyMatch(node -> node.opcode() == OPAQUE); }
}
