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

/**
 * Lowers one pure compiled-sampler arithmetic island to the native density IR.
 * External sampler nodes become deduplicated BUFFER_INPUT slots. This stage
 * does not evaluate those samplers or acquire their buffers.
 */
public final class CompiledSamplerArithmeticLowering {
    public record LoweredIsland(
            int islandId,
            DensityGraphIr graph,
            List<Integer> inputSourceNodeIds,
            int outputSourceNodeId,
            Map<Integer, Integer> nativeNodeBySourceNode) {
        public LoweredIsland {
            Objects.requireNonNull(graph, "graph");
            inputSourceNodeIds = List.copyOf(inputSourceNodeIds);
            nativeNodeBySourceNode = Map.copyOf(nativeNodeBySourceNode);
        }
    }

    private CompiledSamplerArithmeticLowering() {}

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
        if (island.outputNodeIds().size() != 1) {
            throw new IllegalArgumentException(
                    "Native single-root evaluator requires exactly one island output; found "
                            + island.outputNodeIds().size());
        }
        int outputSource = island.outputNodeIds().get(0);
        if (!members.contains(outputSource)) {
            throw new IllegalArgumentException("Island output is not a member");
        }

        LinkedHashSet<Integer> externalSources = new LinkedHashSet<>();
        for (int id : island.nodeIds()) {
            CompiledSamplerGraphIr.Node node = byId.get(id);
            for (int child : node.children()) {
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

        // Stable Kahn ordering guarantees every internal child precedes its consumer,
        // even though the diagnostic trace assigns IDs in root-first traversal order.
        HashMap<Integer, Integer> indegree = new HashMap<>();
        HashMap<Integer, List<Integer>> parents = new HashMap<>();
        for (int id : members) {
            CompiledSamplerGraphIr.Node node = byId.get(id);
            int dependencies = 0;
            for (int child : node.children()) {
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
        return new LoweredIsland(island.id(), graph, orderedInputs, outputSource, nativeIndex);
    }

    private static int resolveChild(int child, Set<Integer> members,
                                    Map<Integer, Integer> nativeIndex,
                                    Map<Integer, Integer> inputSlot) {
        Integer index = members.contains(child) ? nativeIndex.get(child) : null;
        if (index == null && !members.contains(child)) {
            Integer slot = inputSlot.get(child);
            if (slot != null) {
                // Input nodes were emitted first and their native index equals their slot.
                return slot;
            }
        }
        if (index == null) throw new IllegalArgumentException("Unresolved sampler dependency " + child);
        return index;
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
                || op == CompiledSamplerGraphIr.Op.SUB;
    }
}
