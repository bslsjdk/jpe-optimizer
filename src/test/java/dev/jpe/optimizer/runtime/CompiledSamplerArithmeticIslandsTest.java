package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompiledSamplerArithmeticIslandsTest {
    private static CompiledSamplerGraphIr.Node node(int id, CompiledSamplerGraphIr.Op op,
            int... children) {
        List<Integer> childIds = java.util.Arrays.stream(children).boxed().toList();
        return new CompiledSamplerGraphIr.Node(id, op, "test." + op, childIds, Map.of(), "");
    }

    @Test
    void groupsMaximalAddSubConstantRegionAndExposesOpaqueInputs() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.ADD, 1, 2),
                node(1, CompiledSamplerGraphIr.Op.CONSTANT),
                node(2, CompiledSamplerGraphIr.Op.SUB, 3, 4),
                node(3, CompiledSamplerGraphIr.Op.OPAQUE),
                node(4, CompiledSamplerGraphIr.Op.CONSTANT)
        ), 0, 0);

        CompiledSamplerArithmeticIslands.Plan plan =
                CompiledSamplerArithmeticIslands.plan(graph);

        assertEquals(4, plan.eligibleNodeCount());
        assertEquals(1, plan.boundaryNodeCount());
        assertEquals(1, plan.islands().size());
        assertEquals(List.of(0, 2, 4, 1), plan.islands().get(0).nodeIds());
        assertEquals(List.of(new CompiledSamplerArithmeticIslands.InputEdge(2, "child[0]", 3)),
                plan.islands().get(0).externalInputs());
        assertEquals(List.of(0), plan.islands().get(0).outputNodeIds());
    }

    @Test
    void doesNotMergeAcrossShortCircuitOrCacheBoundaries() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.ADD, 1, 2),
                node(1, CompiledSamplerGraphIr.Op.MUL_ZERO_SHORT_CIRCUIT, 3, 4),
                node(2, CompiledSamplerGraphIr.Op.CONSTANT),
                node(3, CompiledSamplerGraphIr.Op.CONSTANT),
                node(4, CompiledSamplerGraphIr.Op.CACHE_BOUNDARY, 5),
                node(5, CompiledSamplerGraphIr.Op.SUB, 6, 7),
                node(6, CompiledSamplerGraphIr.Op.CONSTANT),
                node(7, CompiledSamplerGraphIr.Op.CONSTANT)
        ), 0, 0);

        CompiledSamplerArithmeticIslands.Plan plan =
                CompiledSamplerArithmeticIslands.plan(graph);

        assertEquals(3, plan.islands().size());
        assertEquals(List.of(0, 2), plan.islands().get(0).nodeIds());
        assertEquals(List.of(3), plan.islands().get(1).nodeIds());
        assertEquals(List.of(5, 7, 6), plan.islands().get(2).nodeIds());
    }

    @Test
    void rejectsDuplicateIdsAndMissingRoot() {
        assertThrows(IllegalArgumentException.class, () ->
                CompiledSamplerArithmeticIslands.plan(new CompiledSamplerGraphIr.Graph(List.of(
                        node(0, CompiledSamplerGraphIr.Op.CONSTANT),
                        node(0, CompiledSamplerGraphIr.Op.ADD)
                ), 0, 0)));
        assertThrows(IllegalArgumentException.class, () ->
                CompiledSamplerArithmeticIslands.plan(new CompiledSamplerGraphIr.Graph(List.of(
                        node(1, CompiledSamplerGraphIr.Op.CONSTANT)
                ), 0, 0)));
    }
}
