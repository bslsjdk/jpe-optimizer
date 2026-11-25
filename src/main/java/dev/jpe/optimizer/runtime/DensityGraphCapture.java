package dev.jpe.optimizer.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Converts captured runtime objects to compact IR. STRICT rejects unknown semantics;
 * EXPERIMENTAL preserves unknown nodes as explicit native NaN sentinels.
 */
public final class DensityGraphCapture {
    public enum Mode { STRICT, EXPERIMENTAL }
    public record CaptureStats(long conversionNanos, int sourceObjects, int emittedNodes,
                               int opaqueNodes, int unreachableObjects) {}

    private sealed interface Captured permits Leaf, Binary, Unsupported {}
    private record Leaf(DensityGraphIr.Node node) implements Captured {}
    private record Binary(int opcode, Object left, Object right) implements Captured {}
    private record Unsupported(String reason, List<Object> children) implements Captured {}
    private static final class Frame {
        final Object source;
        boolean expanded;
        Frame(Object source) { this.source = source; }
    }

    private final Mode mode;
    private final IdentityHashMap<Object, Captured> captured = new IdentityHashMap<>();
    private volatile CaptureStats lastStats = new CaptureStats(0, 0, 0, 0, 0);

    public DensityGraphCapture() { this(Mode.STRICT); }
    public DensityGraphCapture(Mode mode) { this.mode = Objects.requireNonNull(mode, "mode"); }

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

    public void add(Object source, Object left, Object right) { binary(source, DensityGraphIr.ADD, left, right); }
    public void multiply(Object source, Object left, Object right) { binary(source, DensityGraphIr.MUL, left, right); }

    public void unsupported(Object source, String reason) {
        unsupported(source, reason, new Object[0]);
    }

    /** Captures up to two child dependencies even when the operation itself is unknown. */
    public void unsupported(Object source, String reason, Object... children) {
        Objects.requireNonNull(children, "children");
        if (children.length > 2) throw new IllegalArgumentException("Opaque IR currently supports at most two child edges");
        ArrayList<Object> copied = new ArrayList<>(children.length);
        for (Object child : children) {
            Objects.requireNonNull(child, "opaque child");
            if (!captured.containsKey(child)) throw new IllegalStateException("Capture opaque child nodes before their parent");
            copied.add(child);
        }
        put(source, new Unsupported(Objects.requireNonNull(reason, "reason"), List.copyOf(copied)));
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

    public CaptureStats lastStats() { return lastStats; }

    public DensityGraphIr finish(Object root, long configurationFingerprint) {
        Objects.requireNonNull(root, "root");
        long started = System.nanoTime();
        // Pre-size from the captured identity map and use iterative post-order traversal.
        // This avoids repeated array growth and Java stack overflow on deep density chains.
        int capacity = Math.min(Math.max(captured.size(), 1), DensityGraphIr.MAX_NODES);
        ArrayList<DensityGraphIr.Node> nodes = new ArrayList<>(capacity);
        ArrayList<DensityGraphIr.Diagnostic> diagnostics = new ArrayList<>();
        IdentityHashMap<Object, Integer> indices = new IdentityHashMap<>(capacity);
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root));

        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            Object source = frame.source;
            if (indices.containsKey(source)) {
                stack.pop();
                continue;
            }
            Captured value = captured.get(source);
            if (value == null) {
                if (mode == Mode.STRICT) {
                    throw new IllegalStateException("Missing semantic adapter for " + source.getClass().getName());
                }
                value = new Unsupported("No semantic adapter was registered for this runtime object");
            }
            if (value instanceof Unsupported unsupported && mode == Mode.STRICT) {
                throw new IllegalStateException("Native lowering rejected "
                        + source.getClass().getName() + ": " + unsupported.reason());
            }

            if ((value instanceof Binary || value instanceof Unsupported) && !frame.expanded) {
                frame.expanded = true;
                if (value instanceof Binary binary) {
                    if (!indices.containsKey(binary.right())) stack.push(new Frame(binary.right()));
                    if (!indices.containsKey(binary.left())) stack.push(new Frame(binary.left()));
                } else {
                    List<Object> children = ((Unsupported) value).children();
                    for (int child = children.size() - 1; child >= 0; --child) {
                        Object dependency = children.get(child);
                        if (!indices.containsKey(dependency)) stack.push(new Frame(dependency));
                    }
                }
                continue;
            }

            DensityGraphIr.Node node;
            if (value instanceof Leaf leaf) {
                node = leaf.node();
            } else if (value instanceof Binary binary) {
                Integer left = indices.get(binary.left());
                Integer right = indices.get(binary.right());
                if (left == null || right == null) {
                    throw new IllegalStateException("Density graph contains a cyclic or unresolved dependency");
                }
                node = binary.opcode() == DensityGraphIr.ADD
                        ? DensityGraphIr.Node.add(left, right)
                        : DensityGraphIr.Node.multiply(left, right);
            } else {
                List<Object> children = ((Unsupported) value).children();
                int first = children.isEmpty() ? DensityGraphIr.NO_INPUT : indices.get(children.get(0));
                int second = children.size() < 2 ? DensityGraphIr.NO_INPUT : indices.get(children.get(1));
                node = DensityGraphIr.Node.opaque(first, second);
            }
            int index = nodes.size();
            if (index >= DensityGraphIr.MAX_NODES) {
                throw new IllegalStateException("Density graph exceeds the IR node limit");
            }
            nodes.add(node);
            indices.put(source, index);
            if (value instanceof Unsupported unsupported) {
                diagnostics.add(new DensityGraphIr.Diagnostic(
                        index, source.getClass().getName(), unsupported.reason()));
            }
            stack.pop();
        }

        Integer rootIndex = indices.get(root);
        DensityGraphIr graph = new DensityGraphIr(nodes, diagnostics, rootIndex, configurationFingerprint);
        lastStats = new CaptureStats(System.nanoTime() - started, captured.size(), nodes.size(),
                diagnostics.size(), Math.max(0, captured.size() - nodes.size()));
        return graph;
    }
}
