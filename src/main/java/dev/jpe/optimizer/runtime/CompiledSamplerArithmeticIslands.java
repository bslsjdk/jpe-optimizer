package dev.jpe.optimizer.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finds maximal pure ADD/SUB/CONSTANT regions in the diagnostic compiled-sampler IR.
 *
 * This is a planning pass only. It does not execute samplers, emit native code,
 * or claim that a region is safe until its external inputs and float semantics
 * have been validated by a later compiler stage.
 */
public final class CompiledSamplerArithmeticIslands {
    public record InputEdge(int consumerNode, String componentName, int sourceNode) {}
    public record Island(int id, List<Integer> nodeIds, List<InputEdge> externalInputs,
                         List<Integer> outputNodeIds) {
        public Island {
            nodeIds = List.copyOf(nodeIds);
            externalInputs = List.copyOf(externalInputs);
            outputNodeIds = List.copyOf(outputNodeIds);
        }
    }
    public record Plan(List<Island> islands, int eligibleNodeCount, int boundaryNodeCount) {
        public Plan { islands = List.copyOf(islands); }
    }

    private CompiledSamplerArithmeticIslands() {}

    public static Plan plan(CompiledSamplerGraphIr.Graph graph) {
        Objects.requireNonNull(graph, "graph");
        Map<Integer, CompiledSamplerGraphIr.Node> byId = new HashMap<>();
        Map<Integer, List<Integer>> parents = new HashMap<>();
        for (CompiledSamplerGraphIr.Node node : graph.nodes()) {
            if (byId.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate sampler node id: " + node.id());
            }
            for (int child : node.children()) {
                parents.computeIfAbsent(child, ignored -> new ArrayList<>()).add(node.id());
            }
        }
        if (!byId.containsKey(graph.rootId())) {
            throw new IllegalArgumentException("Sampler graph root is missing");
        }

        Set<Integer> eligible = new HashSet<>();
        for (CompiledSamplerGraphIr.Node node : graph.nodes()) {
            if (isEligible(node.op())) eligible.add(node.id());
        }

        ArrayList<Island> islands = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        for (CompiledSamplerGraphIr.Node candidate : graph.nodes()) {
            int id = candidate.id();
            if (!eligible.contains(id) || visited.contains(id)) continue;
            boolean hasEligibleParent = parents.getOrDefault(id, List.of()).stream()
                    .anyMatch(eligible::contains);
            if (hasEligibleParent) continue;

            LinkedHashSet<Integer> members = new LinkedHashSet<>();
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.push(id);
            while (!pending.isEmpty()) {
                int current = pending.pop();
                if (!eligible.contains(current) || !members.add(current)) continue;
                visited.add(current);
                CompiledSamplerGraphIr.Node node = byId.get(current);
                for (int child : node.children()) {
                    if (eligible.contains(child)) pending.push(child);
                }
            }

            ArrayList<InputEdge> inputs = new ArrayList<>();
            ArrayList<Integer> outputs = new ArrayList<>();
            for (int member : members) {
                CompiledSamplerGraphIr.Node node = byId.get(member);
                List<CompiledSamplerGraphTrace.ChildEdge> namedEdges = node.childEdges();
                if (!namedEdges.isEmpty()) {
                    for (CompiledSamplerGraphTrace.ChildEdge edge : namedEdges) {
                        if (!members.contains(edge.childId())) {
                            inputs.add(new InputEdge(member, edge.componentName(), edge.childId()));
                        }
                    }
                } else {
                    for (int index = 0; index < node.children().size(); index++) {
                        int child = node.children().get(index);
                        if (!members.contains(child)) {
                            inputs.add(new InputEdge(member, "child[" + index + "]", child));
                        }
                    }
                }
                boolean hasOutsideParent = parents.getOrDefault(member, List.of()).stream()
                        .anyMatch(parent -> !members.contains(parent));
                if (member == graph.rootId() || hasOutsideParent) outputs.add(member);
            }
            islands.add(new Island(islands.size(), new ArrayList<>(members), inputs, outputs));
        }

        return new Plan(islands, eligible.size(), byId.size() - eligible.size());
    }

    private static boolean isEligible(CompiledSamplerGraphIr.Op op) {
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
                || op == CompiledSamplerGraphIr.Op.GRADIENT_CLAMPED
                || op == CompiledSamplerGraphIr.Op.NEGATE
                || op == CompiledSamplerGraphIr.Op.ABS
                || op == CompiledSamplerGraphIr.Op.SQUARE
                || op == CompiledSamplerGraphIr.Op.CUBE
                || op == CompiledSamplerGraphIr.Op.SQRT
                || op == CompiledSamplerGraphIr.Op.RECIPROCAL
                || op == CompiledSamplerGraphIr.Op.SIGN
                || op == CompiledSamplerGraphIr.Op.SQUEEZE
                || op == CompiledSamplerGraphIr.Op.LEAKY_RELU
                || op == CompiledSamplerGraphIr.Op.MIN_RIGHT_BOUND
                || op == CompiledSamplerGraphIr.Op.MAX_RIGHT_BOUND;
    }
}
