package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
