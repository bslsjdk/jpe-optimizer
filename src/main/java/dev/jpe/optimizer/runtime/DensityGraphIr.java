package dev.jpe.optimizer.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Version-1 pointer-free density graph description.
 *
 * Nodes are topologically ordered. This is a transport model only: a Minecraft
 * graph may be sent native-side only after every reachable operation has a
 * verified semantic lowering.
 */
public final class DensityGraphIr {
    public static final int VERSION = 1;
    public static final int MAX_NODES = 65_536;
    public static final int NO_INPUT = -1;

    public static final int CONST = 1;
    public static final int X = 2;
    public static final int Y = 3;
    public static final int Z = 4;
    public static final int ADD = 5;
    public static final int MUL = 6;

    public record Node(int opcode, int inputA, int inputB, double immediate) {
        public static Node constant(double value) {
            return new Node(CONST, NO_INPUT, NO_INPUT, value);
        }

        public static Node x() { return new Node(X, NO_INPUT, NO_INPUT, 0.0); }
        public static Node y() { return new Node(Y, NO_INPUT, NO_INPUT, 0.0); }
        public static Node z() { return new Node(Z, NO_INPUT, NO_INPUT, 0.0); }
        public static Node add(int left, int right) { return new Node(ADD, left, right, 0.0); }
        public static Node multiply(int left, int right) { return new Node(MUL, left, right, 0.0); }
    }

    private final List<Node> nodes;
    private final int rootNode;
    private final long configurationFingerprint;

    public DensityGraphIr(List<Node> nodes, int rootNode, long configurationFingerprint) {
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("Density graph node count must be between 1 and " + MAX_NODES);
        }
        if (rootNode < 0 || rootNode >= nodes.size()) {
            throw new IllegalArgumentException("Density graph root is outside the node array");
        }
        this.nodes = List.copyOf(nodes);
        this.rootNode = rootNode;
        this.configurationFingerprint = configurationFingerprint;
        validateTopologicalOrder(this.nodes);
    }

    private static void validateTopologicalOrder(List<Node> nodes) {
        for (int i = 0; i < nodes.size(); ++i) {
            Node node = Objects.requireNonNull(nodes.get(i), "node");
            switch (node.opcode()) {
                case CONST, X, Y, Z -> {
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
    public int rootNode() { return rootNode; }
    public long configurationFingerprint() { return configurationFingerprint; }
}
