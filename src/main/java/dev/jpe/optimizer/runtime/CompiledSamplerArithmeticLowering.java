package dev.jpe.optimizer.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Lowers a pure compiled-sampler island to native density IR. */
public final class CompiledSamplerArithmeticLowering {
    public record LoweredIsland(int islandId, DensityGraphIr graph,
                                List<Integer> inputSourceNodeIds, List<Integer> outputSourceNodeIds,
                                Map<Integer, Integer> nativeNodeBySourceNode) {
        public LoweredIsland {
            Objects.requireNonNull(graph, "graph");
            inputSourceNodeIds = List.copyOf(inputSourceNodeIds);
            outputSourceNodeIds = List.copyOf(outputSourceNodeIds);
            if (outputSourceNodeIds.isEmpty()) throw new IllegalArgumentException("An arithmetic island needs at least one output");
            nativeNodeBySourceNode = Map.copyOf(nativeNodeBySourceNode);
        }
        /** Compatibility accessor for callers that only consume the first output. */
        public int outputSourceNodeId() { return outputSourceNodeIds.get(0); }
        public List<Integer> nativeOutputNodeIds() {
            return outputSourceNodeIds.stream().map(id -> {
                Integer index = nativeNodeBySourceNode.get(id);
                if (index == null) throw new IllegalStateException("Missing lowered output node " + id);
                return index;
            }).toList();
        }
        /** Runs every island output in one native batch, sharing all intermediate calculations. */
        public void evaluate(NativeRuntime.NativeDensityGraph executable,
                             int sizeX, int sizeY, int sizeZ,
                             int minX, int minY, int minZ,
                             int stepX, int stepY, int stepZ,
                             float[][] externalInputVolumes, float[][] outputVolumes) {
            Objects.requireNonNull(executable, "executable");
            Objects.requireNonNull(outputVolumes, "outputVolumes");
            if (outputVolumes.length != outputSourceNodeIds.size()) {
                throw new IllegalArgumentException("Output volume count must match the lowered island outputs");
            }
            List<Integer> roots = nativeOutputNodeIds();
            int[] nativeRoots = roots.stream().mapToInt(Integer::intValue).toArray();
            executable.evaluateMultiWithInputs(sizeX, sizeY, sizeZ,
                    minX, minY, minZ, stepX, stepY, stepZ,
                    externalInputVolumes, nativeRoots, outputVolumes);
        }
    }

    private CompiledSamplerArithmeticLowering() {}

    /**
     * Combines disjoint arithmetic islands into one graph with multiple roots.
     * Opaque/cache/context nodes remain external inputs; batching never merges
     * semantic boundaries.
     */
    public static LoweredIsland lowerAll(CompiledSamplerGraphIr.Graph source,
                                         List<CompiledSamplerArithmeticIslands.Island> islands,
                                         long configurationFingerprint) {
        Objects.requireNonNull(islands, "islands");
        if (islands.isEmpty()) throw new IllegalArgumentException("At least one arithmetic island is required");
        LinkedHashSet<Integer> members = new LinkedHashSet<>();
        ArrayList<Integer> outputs = new ArrayList<>();
        for (CompiledSamplerArithmeticIslands.Island island : islands) {
            Objects.requireNonNull(island, "island");
            for (int id : island.nodeIds()) {
                if (!members.add(id)) throw new IllegalArgumentException("Arithmetic islands overlap at node " + id);
            }
            outputs.addAll(island.outputNodeIds());
        }
        CompiledSamplerArithmeticIslands.Island combined = new CompiledSamplerArithmeticIslands.Island(
                -1, new ArrayList<>(members), List.of(), outputs);
        return lower(source, combined, configurationFingerprint);
    }

    public static LoweredIsland lower(CompiledSamplerGraphIr.Graph source,
                                      CompiledSamplerArithmeticIslands.Island island,
                                      long configurationFingerprint) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(island, "island");
        Map<Integer, CompiledSamplerGraphIr.Node> byId = new HashMap<>();
        Map<Integer, Integer> sourceOrder = new HashMap<>();
        for (int i = 0; i < source.nodes().size(); i++) {
            CompiledSamplerGraphIr.Node node = source.nodes().get(i);
            if (byId.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate source sampler id: " + node.id());
            }
            sourceOrder.put(node.id(), i);
        }

        Set<Integer> members = new HashSet<>(island.nodeIds());
        if (members.isEmpty()) throw new IllegalArgumentException("Cannot lower an empty arithmetic island");
        for (int id : members) {
            CompiledSamplerGraphIr.Node node = byId.get(id);
            if (node == null || !eligible(node.op())) {
                throw new IllegalArgumentException("Island contains missing or unsupported node " + id);
            }
        }
        if (island.outputNodeIds().isEmpty()) {
            throw new IllegalArgumentException("Arithmetic island has no outputs");
        }
        for (int outputSource : island.outputNodeIds()) {
            if (!members.contains(outputSource)) throw new IllegalArgumentException("Island output is not a member");
        }
        int outputSource = island.outputNodeIds().get(0);

        LinkedHashSet<Integer> externalSources = new LinkedHashSet<>();
        for (int id : island.nodeIds()) {
            for (int child : byId.get(id).children()) {
                if (!members.contains(child)) {
                    if (!byId.containsKey(child)) {
                        throw new IllegalArgumentException("Missing external sampler node " + child);
                    }
                    externalSources.add(child);
                }
            }
        }
        ArrayList<Integer> orderedInputs = new ArrayList<>(externalSources);
        orderedInputs.sort(Comparator.comparingInt(id -> sourceOrder.getOrDefault(id, Integer.MAX_VALUE)));
        if (orderedInputs.size() > 256) {
            throw new IllegalArgumentException("Arithmetic island exceeds 256 distinct external input volumes");
        }

        ArrayList<DensityGraphIr.Node> lowered = new ArrayList<>();
        HashMap<Integer, Integer> nativeIndex = new HashMap<>();
        HashMap<Integer, Integer> inputSlot = new HashMap<>();
        for (int slot = 0; slot < orderedInputs.size(); slot++) {
            int sourceId = orderedInputs.get(slot);
            inputSlot.put(sourceId, slot);
            nativeIndex.put(sourceId, lowered.size());
            lowered.add(DensityGraphIr.Node.input(slot));
        }

        // The trace is root-first. Kahn ordering restores child-before-parent IR order.
        HashMap<Integer, Integer> indegree = new HashMap<>();
        HashMap<Integer, List<Integer>> parents = new HashMap<>();
        for (int id : members) {
            int dependencies = 0;
            for (int child : byId.get(id).children()) {
                if (members.contains(child)) {
                    dependencies++;
                    parents.computeIfAbsent(child, ignored -> new ArrayList<>()).add(id);
                }
            }
            indegree.put(id, dependencies);
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>(
                Comparator.comparingInt(id -> sourceOrder.getOrDefault(id, Integer.MAX_VALUE)));
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });

        int emitted = 0;
        while (!ready.isEmpty()) {
            int sourceId = ready.remove();
            CompiledSamplerGraphIr.Node node = byId.get(sourceId);
            switch (node.op()) {
                case CONSTANT -> lowered.add(DensityGraphIr.Node.constant(readFloatConstant(node)));
                case ADD -> lowered.add(DensityGraphIr.Node.add(
                        resolveChild(node.children().get(0), members, nativeIndex, inputSlot),
                        resolveChild(node.children().get(1), members, nativeIndex, inputSlot)));
                case SUB -> lowered.add(DensityGraphIr.Node.subtract(
                        resolveChild(node.children().get(0), members, nativeIndex, inputSlot),
                        resolveChild(node.children().get(1), members, nativeIndex, inputSlot)));
                case MIN_RIGHT_BOUND -> lowered.add(DensityGraphIr.Node.min(
                        resolveChild(node.children().get(0), members, nativeIndex, inputSlot),
                        resolveChild(node.children().get(1), members, nativeIndex, inputSlot)));
                case MAX_RIGHT_BOUND -> lowered.add(DensityGraphIr.Node.max(
                        resolveChild(node.children().get(0), members, nativeIndex, inputSlot),
                        resolveChild(node.children().get(1), members, nativeIndex, inputSlot)));
                case CONST_ADD_RIGHT -> {
                    int input = resolveOnlyChild(node, members, nativeIndex, inputSlot);
                    int constant = appendConstant(lowered, node, "right");
                    lowered.add(DensityGraphIr.Node.add(input, constant));
                }
                case CONST_SUB_LEFT -> {
                    int input = resolveOnlyChild(node, members, nativeIndex, inputSlot);
                    int constant = appendConstant(lowered, node, "left");
                    lowered.add(DensityGraphIr.Node.subtract(constant, input));
                }
                case CONST_MUL_RIGHT -> {
                    int input = resolveOnlyChild(node, members, nativeIndex, inputSlot);
                    int constant = appendConstant(lowered, node, "right");
                    lowered.add(DensityGraphIr.Node.multiply(input, constant));
                }
                case CONST_DIV_LEFT -> {
                    int input = resolveOnlyChild(node, members, nativeIndex, inputSlot);
                    int constant = appendConstant(lowered, node, "left");
                    lowered.add(DensityGraphIr.Node.divide(constant, input));
                }
                case CONST_MIN_RIGHT -> {
                    int input = resolveOnlyChild(node, members, nativeIndex, inputSlot);
                    int constant = appendConstant(lowered, node, "right");
                    lowered.add(DensityGraphIr.Node.min(input, constant));
                }
                case CONST_MAX_RIGHT -> {
                    int input = resolveOnlyChild(node, members, nativeIndex, inputSlot);
                    int constant = appendConstant(lowered, node, "right");
                    lowered.add(DensityGraphIr.Node.max(input, constant));
                }
                case NEGATE -> lowered.add(DensityGraphIr.Node.negate(
                        resolveOnlyChild(node, members, nativeIndex, inputSlot)));
                case ABS -> lowered.add(DensityGraphIr.Node.abs(
                        resolveOnlyChild(node, members, nativeIndex, inputSlot)));
                case SQUARE -> lowered.add(DensityGraphIr.Node.square(
                        resolveOnlyChild(node, members, nativeIndex, inputSlot)));
                case CUBE -> lowered.add(DensityGraphIr.Node.cube(
                        resolveOnlyChild(node, members, nativeIndex, inputSlot)));
                case CLAMP -> {
                    int input = resolveOnlyChild(node, members, nativeIndex, inputSlot);
                    float min = readFloatComponent(node, "min");
                    float max = readFloatComponent(node, "max");
                    lowered.add(DensityGraphIr.Node.clamp(input, min, max));
                }
                default -> throw new IllegalArgumentException("Unsupported sampler operation " + node.op());
            }
            nativeIndex.put(sourceId, lowered.size() - 1);
            emitted++;
            for (int parent : parents.getOrDefault(sourceId, List.of())) {
                int remaining = indegree.compute(parent, (ignored, value) -> value - 1);
                if (remaining == 0) ready.add(parent);
            }
        }
        if (emitted != members.size()) {
            throw new IllegalArgumentException("Arithmetic island contains a cycle or unresolved dependency");
        }

        Integer root = nativeIndex.get(outputSource);
        if (root == null) throw new IllegalArgumentException("Unable to resolve island output");
        DensityGraphIr graph = new DensityGraphIr(lowered, root, configurationFingerprint);
        return new LoweredIsland(island.id(), graph, orderedInputs, island.outputNodeIds(), nativeIndex);
    }

    private static int resolveChild(int child, Set<Integer> members,
                                    Map<Integer, Integer> nativeIndex,
                                    Map<Integer, Integer> inputSlot) {
        if (members.contains(child)) {
            Integer index = nativeIndex.get(child);
            if (index == null) throw new IllegalArgumentException("Unresolved internal sampler dependency " + child);
            return index;
        }
        Integer slot = inputSlot.get(child);
        if (slot == null) throw new IllegalArgumentException("Unresolved external sampler dependency " + child);
        // Input nodes occupy the first N native indices in the same order as their slots.
        return slot;
    }

    private static int resolveOnlyChild(CompiledSamplerGraphIr.Node node, Set<Integer> members,
                                        Map<Integer, Integer> nativeIndex,
                                        Map<Integer, Integer> inputSlot) {
        if (node.children().size() != 1) {
            throw new IllegalArgumentException("Constant-specialized sampler must have exactly one sampler child");
        }
        return resolveChild(node.children().get(0), members, nativeIndex, inputSlot);
    }

    private static int appendConstant(List<DensityGraphIr.Node> lowered,
                                      CompiledSamplerGraphIr.Node node, String component) {
        String raw = node.scalarValues().get(component);
        if (raw == null) {
            throw new IllegalArgumentException("Constant-specialized sampler " + node.id()
                    + " has no " + component + " float component");
        }
        final float value;
        try {
            value = Float.parseFloat(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid float32 constant on sampler " + node.id(), failure);
        }
        lowered.add(DensityGraphIr.Node.constant(value));
        return lowered.size() - 1;
    }

    private static float readFloatComponent(CompiledSamplerGraphIr.Node node, String component) {
        String raw = node.scalarValues().get(component);
        if (raw == null) {
            throw new IllegalArgumentException("Sampler " + node.id() + " has no " + component + " float component");
        }
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid float32 " + component + " on sampler " + node.id(), failure);
        }
    }

    private static double readFloatConstant(CompiledSamplerGraphIr.Node node) {
        String raw = node.scalarValues().get("value");
        if (raw == null) {
            throw new IllegalArgumentException("Constant sampler " + node.id() + " has no value component");
        }
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid float32 constant on sampler " + node.id(), failure);
        }
    }

    private static boolean eligible(CompiledSamplerGraphIr.Op op) {
        return op == CompiledSamplerGraphIr.Op.CONSTANT
                || op == CompiledSamplerGraphIr.Op.ADD
                || op == CompiledSamplerGraphIr.Op.SUB
                || op == CompiledSamplerGraphIr.Op.CONST_ADD_RIGHT
                || op == CompiledSamplerGraphIr.Op.CONST_SUB_LEFT
                || op == CompiledSamplerGraphIr.Op.CONST_MUL_RIGHT
                || op == CompiledSamplerGraphIr.Op.CONST_DIV_LEFT
                || op == CompiledSamplerGraphIr.Op.CONST_MIN_RIGHT
                || op == CompiledSamplerGraphIr.Op.CONST_MAX_RIGHT
                || op == CompiledSamplerGraphIr.Op.CLAMP
                || op == CompiledSamplerGraphIr.Op.NEGATE
                || op == CompiledSamplerGraphIr.Op.ABS
                || op == CompiledSamplerGraphIr.Op.SQUARE
                || op == CompiledSamplerGraphIr.Op.CUBE
                || op == CompiledSamplerGraphIr.Op.MIN_RIGHT_BOUND
                || op == CompiledSamplerGraphIr.Op.MAX_RIGHT_BOUND;
    }
}
