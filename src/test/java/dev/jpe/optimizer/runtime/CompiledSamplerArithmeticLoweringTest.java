package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompiledSamplerArithmeticLoweringTest {
    private static CompiledSamplerGraphIr.Node node(int id, CompiledSamplerGraphIr.Op op,
            List<Integer> children, Map<String, String> values) {
        return new CompiledSamplerGraphIr.Node(id, op, "test." + op,
                children, List.of(), values, "");
    }

    @Test
    void lowersRootFirstTraceIntoTopologicalNativeGraphWithExternalInput() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(1, 2), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.CONSTANT, List.of(), Map.of("value", "4.5")),
                node(2, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of())
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Island island =
                CompiledSamplerArithmeticIslands.plan(graph).islands().get(0);

        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, island, 0x1234);

        assertEquals(List.of(2), lowered.inputSourceNodeIds());
        assertEquals(2, lowered.graph().rootNode());
        assertEquals(DensityGraphIr.INPUT, lowered.graph().nodes().get(0).opcode());
        assertEquals(DensityGraphIr.CONST, lowered.graph().nodes().get(1).opcode());
        assertEquals(DensityGraphIr.ADD, lowered.graph().nodes().get(2).opcode());
        assertEquals(1, lowered.graph().nodes().get(2).inputA());
        assertEquals(0, lowered.graph().nodes().get(2).inputB());
        assertEquals(0x1234, lowered.graph().configurationFingerprint());
    }

    @Test
    void deduplicatesOneSharedExternalSamplerAcrossConsumers() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(1, 2), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.ADD, List.of(3, 4), Map.of()),
                node(2, CompiledSamplerGraphIr.Op.SUB, List.of(3, 5), Map.of()),
                node(3, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()),
                node(4, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()),
                node(5, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of())
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Island island =
                CompiledSamplerArithmeticIslands.plan(graph).islands().get(0);

        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, island, 0);

        assertEquals(List.of(3, 4, 5), lowered.inputSourceNodeIds());
        assertEquals(3, lowered.graph().nodes().stream()
                .filter(node -> node.opcode() == DensityGraphIr.INPUT).count());
        assertEquals(6, lowered.graph().nodes().size());
        assertEquals(5, lowered.graph().rootNode());
    }

    @Test
    void batchesIndependentIslandsIntoOneMultiOutputGraphAndDeduplicatesSharedInputs() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(5, CompiledSamplerGraphIr.Op.OPAQUE, List.of(0, 1), Map.of()),
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(2, 3), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.SUB, List.of(2, 4), Map.of()),
                node(2, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()),
                node(3, CompiledSamplerGraphIr.Op.CONSTANT, List.of(), Map.of("value", "3.0")),
                node(4, CompiledSamplerGraphIr.Op.CONSTANT, List.of(), Map.of("value", "4.0"))
        ), 5, 0);
        CompiledSamplerArithmeticIslands.Plan plan = CompiledSamplerArithmeticIslands.plan(graph);
        assertEquals(2, plan.islands().size());
        CompiledSamplerArithmeticLowering.LoweredIsland batch =
                CompiledSamplerArithmeticLowering.lowerAll(graph, plan.islands(), 0xD1);
        assertEquals(List.of(2), batch.inputSourceNodeIds());
        assertEquals(List.of(0, 1), batch.outputSourceNodeIds());
        assertEquals(2, batch.nativeOutputNodeIds().size());
        assertEquals(1, batch.graph().nodes().stream()
                .filter(node -> node.opcode() == DensityGraphIr.INPUT).count());
        assertEquals(2, batch.graph().nodes().stream()
                .filter(node -> node.opcode() == DensityGraphIr.CONST).count());
        assertEquals(1, batch.graph().nodes().stream()
                .filter(node -> node.opcode() == DensityGraphIr.ADD).count());
        assertEquals(1, batch.graph().nodes().stream()
                .filter(node -> node.opcode() == DensityGraphIr.SUB).count());
        assertEquals(DensityGraphIr.ADD,
                batch.graph().nodes().get(batch.nativeOutputNodeIds().get(0)).opcode());
        assertEquals(DensityGraphIr.SUB,
                batch.graph().nodes().get(batch.nativeOutputNodeIds().get(1)).opcode());
        for (int i = 0; i < batch.graph().nodes().size(); i++) {
            DensityGraphIr.Node node = batch.graph().nodes().get(i);
            if (node.opcode() == DensityGraphIr.ADD || node.opcode() == DensityGraphIr.SUB) {
                assertTrue(node.inputA() >= 0 && node.inputA() < i);
                assertTrue(node.inputB() >= 0 && node.inputB() < i);
            }
        }
    }

    @Test
    void lowersExactUnarySamplerOperationsToNativeNodes() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(1, 2), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.NEGATE, List.of(3), Map.of()),
                node(2, CompiledSamplerGraphIr.Op.CUBE, List.of(4), Map.of()),
                node(3, CompiledSamplerGraphIr.Op.ABS, List.of(5), Map.of()),
                node(4, CompiledSamplerGraphIr.Op.SQUARE, List.of(6), Map.of()),
                node(5, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()),
                node(6, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of())
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Plan plan = CompiledSamplerArithmeticIslands.plan(graph);
        assertEquals(1, plan.islands().size());
        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, plan.islands().get(0), 0xA85);
        assertEquals(List.of(5, 6), lowered.inputSourceNodeIds());
        assertEquals(DensityGraphIr.ABS, lowered.graph().nodes().get(2).opcode());
        assertEquals(DensityGraphIr.SQUARE, lowered.graph().nodes().get(3).opcode());
        assertEquals(DensityGraphIr.NEGATE, lowered.graph().nodes().get(4).opcode());
        assertEquals(DensityGraphIr.CUBE, lowered.graph().nodes().get(5).opcode());
        assertEquals(DensityGraphIr.ADD, lowered.graph().nodes().get(6).opcode());
    }

    @Test
    void lowersConstantLeftDivisionWithoutReversingOperands() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.CONST_DIV_LEFT, List.of(1), Map.of("left", "12.0")),
                node(1, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of())
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Island island =
                CompiledSamplerArithmeticIslands.plan(graph).islands().get(0);
        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, island, 0xD1);
        assertEquals(List.of(1), lowered.inputSourceNodeIds());
        assertEquals(DensityGraphIr.INPUT, lowered.graph().nodes().get(0).opcode());
        assertEquals(DensityGraphIr.CONST, lowered.graph().nodes().get(1).opcode());
        assertEquals(DensityGraphIr.DIV, lowered.graph().nodes().get(2).opcode());
        assertEquals(1, lowered.graph().nodes().get(2).inputA());
        assertEquals(0, lowered.graph().nodes().get(2).inputB());
    }

    @Test
    void lowersMinecraftClampWithFloat32Bounds() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.CLAMP, List.of(1),
                        Map.of("min", "-0.0", "max", "0.75")),
                node(1, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of())
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Island island =
                CompiledSamplerArithmeticIslands.plan(graph).islands().get(0);
        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, island, 0xC1A4);
        DensityGraphIr.Node clamp = lowered.graph().nodes().get(lowered.graph().rootNode());
        assertEquals(DensityGraphIr.CLAMP, clamp.opcode());
        assertEquals(0, clamp.inputA());
        assertEquals(DensityGraphIr.NO_INPUT, clamp.inputB());
        long bits = Double.doubleToRawLongBits(clamp.immediate());
        assertEquals(0x800000003f400000L, bits);
    }

    @Test
    void lowersMinecraftMinMaxVolumeSamplersWithoutDiscardingOperandOrder() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.MIN_RIGHT_BOUND, List.of(1, 2),
                        Map.of("rightMinValue", "-100.0")),
                node(1, CompiledSamplerGraphIr.Op.MAX_RIGHT_BOUND, List.of(3, 4),
                        Map.of("rightMaxValue", "100.0")),
                node(2, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()),
                node(3, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()),
                node(4, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of())
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Plan plan = CompiledSamplerArithmeticIslands.plan(graph);
        assertEquals(1, plan.islands().size());
        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, plan.islands().get(0), 0xA11);
        assertEquals(DensityGraphIr.MAX, lowered.graph().nodes().get(3).opcode());
        assertEquals(DensityGraphIr.MIN, lowered.graph().nodes().get(4).opcode());
        // External slots are sorted by source trace order: 2 -> 0, 3 -> 1, 4 -> 2.
        // MAX is emitted first (node 3); MIN then consumes MAX and input slot 0.
        assertEquals(1, lowered.graph().nodes().get(3).inputA());
        assertEquals(2, lowered.graph().nodes().get(3).inputB());
        assertEquals(3, lowered.graph().nodes().get(4).inputA());
        assertEquals(0, lowered.graph().nodes().get(4).inputB());
    }

    @Test
    void lowersConstantSpecializedMinMaxUsingVanillaRightBoundSemantics() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(1, 2), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.CONST_MIN_RIGHT, List.of(3), Map.of("right", "-12.5")),
                node(2, CompiledSamplerGraphIr.Op.CONST_MAX_RIGHT, List.of(4), Map.of("right", "7.25")),
                node(3, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()),
                node(4, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of())
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Plan plan = CompiledSamplerArithmeticIslands.plan(graph);
        assertEquals(1, plan.islands().size());
        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, plan.islands().get(0), 0xC057);
        assertEquals(List.of(3, 4), lowered.inputSourceNodeIds());
        assertEquals(DensityGraphIr.CONST, lowered.graph().nodes().get(2).opcode());
        assertEquals(-12.5f, (float) lowered.graph().nodes().get(2).immediate());
        assertEquals(DensityGraphIr.MIN, lowered.graph().nodes().get(3).opcode());
        assertEquals(0, lowered.graph().nodes().get(3).inputA());
        assertEquals(2, lowered.graph().nodes().get(3).inputB());
        assertEquals(DensityGraphIr.CONST, lowered.graph().nodes().get(4).opcode());
        assertEquals(7.25f, (float) lowered.graph().nodes().get(4).immediate());
        assertEquals(DensityGraphIr.MAX, lowered.graph().nodes().get(5).opcode());
        assertEquals(1, lowered.graph().nodes().get(5).inputA());
        assertEquals(4, lowered.graph().nodes().get(5).inputB());
        assertEquals(DensityGraphIr.ADD, lowered.graph().nodes().get(6).opcode());
    }

    @Test
    void lowersAllOutputsOfOneSharedArithmeticIsland() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(1, 2), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.CONSTANT, List.of(), Map.of("value", "1.0")),
                node(2, CompiledSamplerGraphIr.Op.CONSTANT, List.of(), Map.of("value", "2.0"))
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Island multiOutput =
                new CompiledSamplerArithmeticIslands.Island(0, List.of(0, 1, 2),
                        List.of(), List.of(0, 1, 2));

        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, multiOutput, 0);

        assertEquals(List.of(0, 1, 2), lowered.outputSourceNodeIds());
        assertEquals(List.of(2, 0, 1), lowered.nativeOutputNodeIds());
        assertEquals(2, lowered.graph().rootNode());
        assertEquals(3, lowered.graph().nodes().size());
    }


    @Test
    void lowersConstantSpecializedAddSubtractAndMultiplyInExactOperandOrder() {
        CompiledSamplerGraphIr.Graph graph = new CompiledSamplerGraphIr.Graph(List.of(
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(1, 2), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.CONST_ADD_RIGHT, List.of(3),
                        Map.of("right", "2.5")),
                node(2, CompiledSamplerGraphIr.Op.CONST_SUB_LEFT, List.of(4),
                        Map.of("left", "9.0")),
                node(3, CompiledSamplerGraphIr.Op.CONST_MUL_RIGHT, List.of(5),
                        Map.of("right", "-4.0")),
                node(4, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()),
                node(5, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of())
        ), 0, 0);
        CompiledSamplerArithmeticIslands.Plan plan = CompiledSamplerArithmeticIslands.plan(graph);
        assertEquals(1, plan.islands().size());
        CompiledSamplerArithmeticIslands.Island island = plan.islands().get(0);
        assertEquals(List.of(4, 5), island.externalInputs().stream()
                .map(CompiledSamplerArithmeticIslands.InputEdge::sourceNode).distinct().toList());
        assertEquals(List.of(0), island.outputNodeIds());

        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                CompiledSamplerArithmeticLowering.lower(graph, island, 0x55);

        assertEquals(List.of(4, 5), lowered.inputSourceNodeIds());
        assertEquals(List.of(0), lowered.outputSourceNodeIds());
        assertEquals(9, lowered.graph().nodes().size());
        assertEquals(DensityGraphIr.INPUT, lowered.graph().nodes().get(0).opcode());
        assertEquals(DensityGraphIr.INPUT, lowered.graph().nodes().get(1).opcode());
        assertEquals(DensityGraphIr.CONST, lowered.graph().nodes().get(2).opcode());
        assertEquals(DensityGraphIr.SUB, lowered.graph().nodes().get(3).opcode());
        assertEquals(2, lowered.graph().nodes().get(3).inputA());
        assertEquals(0, lowered.graph().nodes().get(3).inputB());
        assertEquals(DensityGraphIr.CONST, lowered.graph().nodes().get(4).opcode());
        assertEquals(DensityGraphIr.MUL, lowered.graph().nodes().get(5).opcode());
        assertEquals(1, lowered.graph().nodes().get(5).inputA());
        assertEquals(4, lowered.graph().nodes().get(5).inputB());
        assertEquals(DensityGraphIr.CONST, lowered.graph().nodes().get(6).opcode());
        assertEquals(DensityGraphIr.ADD, lowered.graph().nodes().get(7).opcode());
        assertEquals(5, lowered.graph().nodes().get(7).inputA());
        assertEquals(6, lowered.graph().nodes().get(7).inputB());
        assertEquals(DensityGraphIr.ADD, lowered.graph().nodes().get(8).opcode());
        assertEquals(7, lowered.graph().nodes().get(8).inputA());
        assertEquals(3, lowered.graph().nodes().get(8).inputB());
    }
}
