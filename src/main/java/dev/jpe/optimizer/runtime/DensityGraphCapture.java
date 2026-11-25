package dev.jpe.optimizer.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Explicit semantic adapter used to lower verified Minecraft density nodes to
 * the small native IR. This is intentionally separate from call tracing:
 * observing a method call never implicitly makes that method native-safe.
 */
public final class DensityGraphCapture {
    private sealed interface Captured permits Leaf, Binary, Unsupported {}
    private record Leaf(DensityGraphIr.Node node) implements Captured {}
    private record Binary(int opcode, Object left, Object right) implements Captured {}
    private record Unsupported(String reason) implements Captured {}

    private final IdentityHashMap<Object, Captured> captured = new IdentityHashMap<>();

    public void constant(Object source, double value) {
        put(source, new Leaf(DensityGraphIr.Node.constant(value)));
    }

    public void coordinate(Object source, int axis) {
        DensityGraphIr.Node node = switch (axis) {
            case 0 -> DensityGraphIr.Node.x();
            case 1 -> DensityGraphIr.Node.y();
            case 2 -> DensityGraphIr.Node.z();
            default -> throw new IllegalArgumentException("Coordinate axis must be 0 (X), 1 (Y), or 2 (Z)");
        };
        put(source, new Leaf(node));
    }

    public void add(Object source, Object left, Object right) {
        binary(source, DensityGraphIr.ADD, left, right);
    }

    public void multiply(Object source, Object left, Object right) {
        binary(source, DensityGraphIr.MUL, left, right);
    }

    /** Marks a node as unsupported; its reachable parents cannot be compiled. */
    public void unsupported(Object source, String reason) {
        put(source, new Unsupported(Objects.requireNonNull(reason, "reason")));
    }

    private void binary(Object source, int opcode, Object left, Object right) {
        if (opcode != DensityGraphIr.ADD && opcode != DensityGraphIr.MUL) {
            throw new IllegalArgumentException("Unsupported binary opcode: " + opcode);
        }
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!captured.containsKey(left) || !captured.containsKey(right)) {
            throw new IllegalStateException("Capture child nodes before their parent");
        }
        put(source, new Binary(opcode, left, right));
    }

    private void put(Object source, Captured value) {
        Objects.requireNonNull(source, "source");
        if (captured.putIfAbsent(source, value) != null) {
            throw new IllegalStateException("Density node was captured more than once");
        }
    }

    /**
     * Builds a topologically ordered graph containing only nodes reachable
     * from root. Unsupported reachable nodes fail closed instead of silently
     * substituting a guessed operation.
     */
    public DensityGraphIr finish(Object root, long configurationFingerprint) {
        Objects.requireNonNull(root, "root");
        ArrayList<DensityGraphIr.Node> nodes = new ArrayList<>();
        IdentityHashMap<Object, Integer> indices = new IdentityHashMap<>();
        int rootIndex = append(root, nodes, indices);
        return new DensityGraphIr(nodes, rootIndex, configurationFingerprint);
    }

    private int append(Object source, List<DensityGraphIr.Node> nodes,
                       IdentityHashMap<Object, Integer> indices) {
        Integer existing = indices.get(source);
        if (existing != null) return existing;
        Captured value = captured.get(source);
        if (value == null) {
            throw new IllegalStateException("Missing semantic adapter for " + source.getClass().getName());
        }
        if (value instanceof Unsupported unsupported) {
            throw new IllegalStateException("Native lowering rejected "
                    + source.getClass().getName() + ": " + unsupported.reason());
        }

        DensityGraphIr.Node node;
        if (value instanceof Leaf leaf) {
            node = leaf.node();
        } else {
            Binary binary = (Binary) value;
            int left = append(binary.left(), nodes, indices);
            int right = append(binary.right(), nodes, indices);
            node = binary.opcode() == DensityGraphIr.ADD
                    ? DensityGraphIr.Node.add(left, right)
                    : DensityGraphIr.Node.multiply(left, right);
        }
        int index = nodes.size();
        nodes.add(node);
        indices.put(source, index);
        return index;
    }
}
