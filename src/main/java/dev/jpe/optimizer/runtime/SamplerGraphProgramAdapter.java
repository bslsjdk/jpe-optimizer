package dev.jpe.optimizer.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts a captured compiled-sampler inventory into one native program.
 *
 * <p>{@link CompiledSamplerArithmeticLowering} handles disjoint arithmetic
 * islands and keeps every other node as an external Java input. This adapter is
 * the whole-graph counterpart: it lowers every node family whose semantics are
 * fully described by the inventory (arithmetic, unary, clamp, clamped gradient)
 * and keeps the rest as explicit OPAQUE holes, which native evaluation turns
 * into NaN instead of guessing.
 *
 * <p>Table-backed families (spline, interval select, distance point, blend,
 * end island) cannot be reconstructed from scalar record components. A caller
 * that holds the live objects supplies them through {@link PayloadProvider};
 * without one those nodes stay OPAQUE.
 */
public final class SamplerGraphProgramAdapter {
    /** Supplies table-backed nodes the inventory alone cannot describe. */
    public interface PayloadProvider {
        /**
         * @param source       inventory node being lowered
         * @param childIndices already resolved native indices, in inventory child order
         * @param tables       collector for spline/interval/distance/blend tables
         * @return a node referencing {@code tables}, or {@code null} to keep it OPAQUE
         */
        DensityGraphIr.Node lower(CompiledSamplerGraphIr.Node source, int[] childIndices,
                                  DensityGraphPayloadCapture tables);

        /**
         * True when the node is a pure wrapper whose value is exactly its first
         * child, so the adapter can alias the child index and emit nothing.
         * Caching is always transparent; context binding only when the bound
         * context does not override the key, which the provider can observe.
         */
        default boolean transparent(CompiledSamplerGraphIr.Node source) { return false; }
    }

    /**
     * @param sourceNodes adapted node index to the sampler node id it came from, or
     *                    {@code -1} for a constant the lowering emitted on its own.
     *                    Diagnostics need it because the adapter renumbers the graph.
     */
    public record Result(DensityGraphIr graph, int nativeNodes, int opaqueNodes,
                         List<String> opaqueClasses, int[] sourceNodes) {
        public Result {
            opaqueClasses = List.copyOf(opaqueClasses);
            sourceNodes = sourceNodes.clone();
        }
        public boolean complete() { return opaqueNodes == 0; }
    }

    private SamplerGraphProgramAdapter() {}

    public static Result adapt(CompiledSamplerGraphIr.Graph graph, DensityGraphCapture.Mode mode,
                               long configurationFingerprint, PayloadProvider provider) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(mode, "mode");
        Map<Integer, CompiledSamplerGraphIr.Node> byId = new HashMap<>();
        for (CompiledSamplerGraphIr.Node node : graph.nodes()) {
            if (byId.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate sampler node id " + node.id());
            }
        }
        if (!byId.containsKey(graph.rootId())) {
            throw new IllegalArgumentException("Sampler graph root is missing");
        }
        for (CompiledSamplerGraphIr.Node node : graph.nodes()) {
            for (int child : node.children()) {
                if (!byId.containsKey(child)) {
                    throw new IllegalArgumentException("Sampler node " + node.id() + " references missing child " + child);
                }
            }
        }

        DensityGraphPayloadCapture tables = new DensityGraphPayloadCapture();
        ArrayList<DensityGraphIr.Node> nodes = new ArrayList<>(graph.nodes().size());
        ArrayList<DensityGraphIr.Diagnostic> diagnostics = new ArrayList<>();
        ArrayList<String> opaqueClasses = new ArrayList<>();
        Map<Integer, Integer> nativeIndex = new HashMap<>(graph.nodes().size());
        Set<Integer> visiting = new HashSet<>();

        // Iterative post-order: the inventory is root-first and may be deeper than the Java stack.
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {graph.rootId(), 0});
        visiting.add(graph.rootId());
        while (!stack.isEmpty()) {
            int[] frame = stack.peek();
            CompiledSamplerGraphIr.Node node = byId.get(frame[0]);
            if (frame[1] < node.children().size()) {
                int child = node.children().get(frame[1]++);
                if (nativeIndex.containsKey(child)) continue;
                if (!visiting.add(child)) {
                    throw new IllegalArgumentException("Sampler graph contains a cycle at node " + child);
                }
                stack.push(new int[] {child, 0});
                continue;
            }
            stack.pop();
            visiting.remove(node.id());
            int[] childIndices = new int[node.children().size()];
            for (int i = 0; i < childIndices.length; ++i) {
                childIndices[i] = nativeIndex.get(node.children().get(i));
            }
            if (childIndices.length == 1 && isTransparent(node, provider)) {
                // Pure wrapper: alias the child instead of paying a native node.
                nativeIndex.put(node.id(), childIndices[0]);
                continue;
            }
            DensityGraphIr.Node lowered;
            int before = nodes.size();
            String failureReason = null;
            try {
                lowered = lowerNode(node, childIndices, tables, provider, nodes);
            } catch (RuntimeException unsupported) {
                // Drop any constant the failed lowering already appended, otherwise the
                // topological array would keep an unreferenced node.
                nodes.subList(before, nodes.size()).clear();
                if (mode == DensityGraphCapture.Mode.STRICT) {
                    throw new IllegalArgumentException("Sampler node " + node.id() + " ("
                            + node.sourceClass() + ") cannot be lowered: " + unsupported.getMessage(), unsupported);
                }
                failureReason = unsupported.getClass().getSimpleName()
                        + (unsupported.getMessage() == null ? "" : ": " + unsupported.getMessage());
                lowered = null;
            }
            int index = nodes.size();
            if (lowered == null) {
                int first = childIndices.length > 0 ? childIndices[0] : DensityGraphIr.NO_INPUT;
                int second = childIndices.length > 1 ? childIndices[1] : DensityGraphIr.NO_INPUT;
                lowered = DensityGraphIr.Node.opaque(first, second);
                diagnostics.add(new DensityGraphIr.Diagnostic(index, node.sourceClass(),
                        failureReason == null
                                ? "No exact native lowering for " + node.op()
                                : node.op() + " failed: " + failureReason,
                        toList(childIndices)));
                opaqueClasses.add(node.sourceClass());
            }
            nodes.add(lowered);
            nativeIndex.put(node.id(), index);
        }

        DensityGraphIr ir = new DensityGraphIr(nodes, diagnostics, nativeIndex.get(graph.rootId()),
                configurationFingerprint, tables.build());
        int[] sourceNodes = new int[nodes.size()];
        java.util.Arrays.fill(sourceNodes, -1);
        for (var entry : nativeIndex.entrySet()) sourceNodes[entry.getValue()] = entry.getKey();
        return new Result(ir, nodes.size() - diagnostics.size(), diagnostics.size(), opaqueClasses,
                sourceNodes);
    }

    /** Cache nodes are always value-preserving; other wrappers must prove it. */
    private static boolean isTransparent(CompiledSamplerGraphIr.Node node, PayloadProvider provider) {
        if (node.op() == CompiledSamplerGraphIr.Op.CACHE_BOUNDARY) return true;
        return provider != null && provider.transparent(node);
    }

    private static List<Integer> toList(int[] values) {
        ArrayList<Integer> list = new ArrayList<>(values.length);
        for (int value : values) list.add(value);
        return list;
    }

    private static DensityGraphIr.Node lowerNode(CompiledSamplerGraphIr.Node node, int[] children,
                                                 DensityGraphPayloadCapture tables,
                                                 PayloadProvider provider,
                                                 List<DensityGraphIr.Node> emitted) {
        DensityGraphIr.Node supplied = provider == null ? null : provider.lower(node, children, tables);
        if (supplied != null) return supplied;

        return switch (node.op()) {
            case CONSTANT -> DensityGraphIr.Node.constant(readFloat(node, "value"));
            case ADD -> binary(node, children, DensityGraphIr.ADD);
            case SUB -> binary(node, children, DensityGraphIr.SUB);
            case MIN_RIGHT_BOUND -> binary(node, children, DensityGraphIr.MIN);
            case MAX_RIGHT_BOUND -> binary(node, children, DensityGraphIr.MAX);
            case CONST_ADD_RIGHT -> DensityGraphIr.Node.add(only(children), constant(node, "right", emitted));
            case CONST_SUB_LEFT -> DensityGraphIr.Node.subtract(constant(node, "left", emitted), only(children));
            case CONST_MUL_RIGHT -> DensityGraphIr.Node.multiply(only(children), constant(node, "right", emitted));
            case CONST_DIV_LEFT -> DensityGraphIr.Node.divide(constant(node, "left", emitted), only(children));
            case CONST_MIN_RIGHT -> DensityGraphIr.Node.min(only(children), constant(node, "right", emitted));
            case CONST_MAX_RIGHT -> DensityGraphIr.Node.max(only(children), constant(node, "right", emitted));
            case CLAMP -> DensityGraphIr.Node.clamp(only(children),
                    readFloat(node, "min"), readFloat(node, "max"));
            case NEGATE -> DensityGraphIr.Node.negate(only(children));
            case ABS -> DensityGraphIr.Node.abs(only(children));
            case SQUARE -> DensityGraphIr.Node.square(only(children));
            case CUBE -> DensityGraphIr.Node.cube(only(children));
            case SQRT -> DensityGraphIr.Node.sqrt(only(children));
            case LOG -> DensityGraphIr.Node.log(only(children));
            case RECIPROCAL -> DensityGraphIr.Node.reciprocal(only(children));
            case SIGN -> DensityGraphIr.Node.sign(only(children));
            case SQUEEZE -> DensityGraphIr.Node.squeeze(only(children));
            case LEAKY_RELU -> DensityGraphIr.Node.leakyRelu(only(children), readFloat(node, "negativeFactor"));
            case GRADIENT_CLAMPED -> DensityGraphIr.Node.gradientClamped(
                    axis(node), readInt(node, "fromCoordinate"), readInt(node, "minCoordinate"),
                    readInt(node, "maxCoordinate"), readFloat(node, "fromValue"),
                    readFloat(node, "coordinateFactor"));
            // The zero shortcut only exists in sampleValue. A whole-volume fill runs
            // the plain multiply/divide above, and that is the only path a plan feeds.
            case MUL_ZERO_SHORT_CIRCUIT -> binary(node, children, DensityGraphIr.MUL);
            case DIV_ZERO_SHORT_CIRCUIT -> binary(node, children, DensityGraphIr.DIV);
            case SLICE_X, SLICE_Y, SLICE_Z -> DensityGraphIr.Node.slice(
                    node.op() == CompiledSamplerGraphIr.Op.SLICE_X ? 0
                            : node.op() == CompiledSamplerGraphIr.Op.SLICE_Y ? 1 : 2,
                    only(children),
                    readInt(node, node.op() == CompiledSamplerGraphIr.Op.SLICE_X ? "x"
                            : node.op() == CompiledSamplerGraphIr.Op.SLICE_Y ? "y" : "z"));
            case INTERPOLATED -> DensityGraphIr.Node.interpolated(only(children),
                    readInt(node, "cellSizeXz"), readInt(node, "cellSizeY"));
            case RANGE_CHOICE -> DensityGraphIr.Node.rangeChoice(children[0], children[1], children[2],
                    readFloat(node, "minInclusive"), readFloat(node, "maxExclusive"));
            case LERP -> DensityGraphIr.Node.lerp(children[0], children[1], children[2]);
            case LERP_CONST_FIRST -> DensityGraphIr.Node.lerp(children[0],
                    constant(node, "first", emitted), children[1]);
            case LERP_CONST_SECOND -> DensityGraphIr.Node.lerp(children[0], children[1],
                    constant(node, "second", emitted));
            // These need the live object behind the node; the payload provider owns them.
            case NOISE, NOISE_SHIFTED, NOISE_TRANSPOSED, SPLINE, INTERVAL_SELECT ->
                    throw new IllegalArgumentException(
                    node.op() + " requires a payload provider that can read the live sampler");
            case BLEND -> throw new IllegalArgumentException(
                    "blend region is active; the native plan carries no blender");
            case CACHE_BOUNDARY -> throw new IllegalArgumentException("cache lifetime is a Java boundary");
            case CONTEXT_BOUNDARY -> throw new IllegalArgumentException("context binding is a Java boundary");
            case OPAQUE -> throw new IllegalArgumentException("inventory already marked it opaque");
        };
    }

    private static DensityGraphIr.Node binary(CompiledSamplerGraphIr.Node node, int[] children, int opcode) {
        if (children.length != 2) {
            throw new IllegalArgumentException("Binary sampler needs two children");
        }
        return new DensityGraphIr.Node(opcode, children[0], children[1], 0.0);
    }

    private static int only(int[] children) {
        if (children.length != 1) throw new IllegalArgumentException("Unary sampler needs one child");
        return children[0];
    }

    /**
     * Appends a float32 constant node and returns its native index. Constants are
     * materialized in evaluation order, so the index is always earlier than the
     * consuming node.
     */
    private static int constant(CompiledSamplerGraphIr.Node node, String component,
                                List<DensityGraphIr.Node> emitted) {
        emitted.add(DensityGraphIr.Node.constant(readFloat(node, component)));
        return emitted.size() - 1;
    }

    private static int axis(CompiledSamplerGraphIr.Node node) {
        return switch (node.scalarValues().getOrDefault("axis", "")) {
            case "X" -> 0;
            case "Y" -> 1;
            case "Z" -> 2;
            default -> throw new IllegalArgumentException("invalid gradient axis");
        };
    }

    private static float readFloat(CompiledSamplerGraphIr.Node node, String component) {
        String raw = node.scalarValues().get(component);
        if (raw == null) throw new IllegalArgumentException("missing float component " + component);
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid float32 " + component, failure);
        }
    }

    private static int readInt(CompiledSamplerGraphIr.Node node, String component) {
        String raw = node.scalarValues().get(component);
        if (raw == null) throw new IllegalArgumentException("missing int component " + component);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid int32 " + component, failure);
        }
    }
}
