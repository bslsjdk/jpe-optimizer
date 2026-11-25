package dev.jpe.optimizer.runtime;

import java.util.List;
import java.util.Objects;

/** Compact, topologically ordered density graph transport model. */
public final class DensityGraphIr {
    public static final int VERSION = 1;
    public static final int MAX_NODES = 65_536;
    public static final int NO_INPUT = -1;

    public static final int CONST = 1, X = 2, Y = 3, Z = 4, ADD = 5, MUL = 6, OPAQUE = 7;

    public record Node(int opcode, int inputA, int inputB, double immediate) {
        public static Node constant(double value) { return new Node(CONST, NO_INPUT, NO_INPUT, value); }
        public static Node x() { return new Node(X, NO_INPUT, NO_INPUT, 0.0); }
        public static Node y() { return new Node(Y, NO_INPUT, NO_INPUT, 0.0); }
        public static Node z() { return new Node(Z, NO_INPUT, NO_INPUT, 0.0); }
        public static Node add(int left, int right) { return new Node(ADD, left, right, 0.0); }
        public static Node multiply(int left, int right) { return new Node(MUL, left, right, 0.0); }
        /** Experimental sentinel: native evaluation emits NaN and continues through parents. */
        public static Node opaque() { return new Node(OPAQUE, NO_INPUT, NO_INPUT, Double.NaN); }
    }

    /** Sidecar diagnostic. It is deliberately not part of the hot native node record. */
    public record Diagnostic(int nodeIndex, String sourceClass, String reason) {
        public Diagnostic {
            if (nodeIndex < 0) throw new IllegalArgumentException("Negative diagnostic node index");
            Objects.requireNonNull(sourceClass, "sourceClass");
            Objects.requireNonNull(reason, "reason");
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
        for (Diagnostic diagnostic : this.diagnostics) {
            if (diagnostic.nodeIndex() >= this.nodes.size()
                    || this.nodes.get(diagnostic.nodeIndex()).opcode() != OPAQUE) {
                throw new IllegalArgumentException("Diagnostic must reference an OPAQUE node");
            }
        }
    }

    private static void validateTopologicalOrder(List<Node> nodes) {
        for (int i = 0; i < nodes.size(); ++i) {
            Node node = Objects.requireNonNull(nodes.get(i), "node");
            switch (node.opcode()) {
                case CONST, X, Y, Z, OPAQUE -> {
                    if (node.inputA() != NO_INPUT || node.inputB() != NO_INPUT) {
                        throw new IllegalArgumentException("Leaf node " + i + " must not have inputs");
                    }
                }
                case ADD, MUL -> {
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
    public boolean experimental() { return !diagnostics.isEmpty(); }
}
