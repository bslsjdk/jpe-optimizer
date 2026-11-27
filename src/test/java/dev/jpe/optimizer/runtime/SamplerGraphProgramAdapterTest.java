package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SamplerGraphProgramAdapterTest {
    private static CompiledSamplerGraphIr.Node node(int id, CompiledSamplerGraphIr.Op op,
                                                    List<Integer> children, Map<String, String> values) {
        return new CompiledSamplerGraphIr.Node(id, op, "test." + op, children, List.of(), values, "");
    }

    private static CompiledSamplerGraphIr.Graph graph(CompiledSamplerGraphIr.Node... nodes) {
        return new CompiledSamplerGraphIr.Graph(List.of(nodes), 0, 0);
    }

    @Test
    void lowersWholeGraphInsteadOfOnlyArithmeticIslands() {
        CompiledSamplerGraphIr.Graph source = graph(
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(1, 2), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.CONSTANT, List.of(), Map.of("value", "2.5")),
                node(2, CompiledSamplerGraphIr.Op.SQUARE, List.of(3), Map.of()),
                node(3, CompiledSamplerGraphIr.Op.CLAMP, List.of(4), Map.of("min", "-1", "max", "1")),
                node(4, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()));

        SamplerGraphProgramAdapter.Result result = SamplerGraphProgramAdapter.adapt(
                source, DensityGraphCapture.Mode.EXPERIMENTAL, 0x42L, null);

        DensityGraphIr ir = result.graph();
        assertEquals(5, ir.nodes().size());
        assertEquals(4, ir.rootNode());
        assertEquals(DensityGraphIr.CONST, ir.nodes().get(0).opcode());
        assertEquals(DensityGraphIr.OPAQUE, ir.nodes().get(1).opcode());
        assertEquals(DensityGraphIr.CLAMP, ir.nodes().get(2).opcode());
        assertEquals(DensityGraphIr.SQUARE, ir.nodes().get(3).opcode());
        assertEquals(DensityGraphIr.ADD, ir.nodes().get(4).opcode());
        assertEquals(4, result.nativeNodes());
        assertEquals(1, result.opaqueNodes());
        assertFalse(result.complete());
        assertEquals(1, ir.diagnostics().size());
        assertEquals(0x42L, ir.configurationFingerprint());
    }

    @Test
    void materializesConstantSpecializedOperandsBeforeTheConsumer() {
        CompiledSamplerGraphIr.Graph source = graph(
                node(0, CompiledSamplerGraphIr.Op.CONST_SUB_LEFT, List.of(1), Map.of("left", "10")),
                node(1, CompiledSamplerGraphIr.Op.CONST_MUL_RIGHT, List.of(2), Map.of("right", "0.5")),
                node(2, CompiledSamplerGraphIr.Op.CONSTANT, List.of(), Map.of("value", "3")));

        SamplerGraphProgramAdapter.Result result = SamplerGraphProgramAdapter.adapt(
                source, DensityGraphCapture.Mode.STRICT, 0L, null);

        DensityGraphIr ir = result.graph();
        assertTrue(result.complete());
        assertEquals(5, ir.nodes().size());
        assertEquals(DensityGraphIr.CONST, ir.nodes().get(0).opcode());
        assertEquals(3.0, ir.nodes().get(0).immediate());
        assertEquals(0.5, ir.nodes().get(1).immediate());
        assertEquals(DensityGraphIr.MUL, ir.nodes().get(2).opcode());
        assertEquals(0, ir.nodes().get(2).inputA());
        assertEquals(1, ir.nodes().get(2).inputB());
        assertEquals(10.0, ir.nodes().get(3).immediate());
        assertEquals(DensityGraphIr.SUB, ir.nodes().get(4).opcode());
        assertEquals(3, ir.nodes().get(4).inputA());
        assertEquals(2, ir.nodes().get(4).inputB());
        assertEquals(4, ir.rootNode());
    }

    @Test
    void strictModeRejectsBoundariesAndExperimentalModeKeepsThemAsNaN() {
        CompiledSamplerGraphIr.Graph source = graph(
                node(0, CompiledSamplerGraphIr.Op.ADD, List.of(1, 2), Map.of()),
                node(1, CompiledSamplerGraphIr.Op.CACHE_BOUNDARY, List.of(), Map.of()),
                node(2, CompiledSamplerGraphIr.Op.CONSTANT, List.of(), Map.of("value", "1")));

        assertThrows(IllegalArgumentException.class, () -> SamplerGraphProgramAdapter.adapt(
                source, DensityGraphCapture.Mode.STRICT, 0L, null));

        SamplerGraphProgramAdapter.Result relaxed = SamplerGraphProgramAdapter.adapt(
                source, DensityGraphCapture.Mode.EXPERIMENTAL, 0L, null);
        assertEquals(DensityGraphIr.OPAQUE, relaxed.graph().nodes().get(0).opcode());
        assertEquals(DensityGraphIr.CONST, relaxed.graph().nodes().get(1).opcode());
        assertEquals(DensityGraphIr.ADD, relaxed.graph().nodes().get(2).opcode());
        assertEquals(2, relaxed.graph().rootNode());
    }

    @Test
    void payloadProviderSuppliesSplineTablesForNodesTheInventoryCannotDescribe() {
        CompiledSamplerGraphIr.Graph source = graph(
                node(0, CompiledSamplerGraphIr.Op.CONST_ADD_RIGHT, List.of(1), Map.of("right", "1")),
                node(1, CompiledSamplerGraphIr.Op.OPAQUE, List.of(), Map.of()));

        SamplerGraphProgramAdapter.PayloadProvider provider = (node, children, tables) ->
                node.op() == CompiledSamplerGraphIr.Op.OPAQUE
                        ? DensityGraphIr.Node.spline(tables.addConstantSpline(5.0f))
                        : null;
        SamplerGraphProgramAdapter.Result result = SamplerGraphProgramAdapter.adapt(
                source, DensityGraphCapture.Mode.STRICT, 0L, provider);

        assertTrue(result.complete());
        assertEquals(1, result.graph().payload().splines().size());
        assertEquals(DensityGraphIr.SPLINE, result.graph().nodes().get(0).opcode());
        assertEquals(0, result.graph().nodes().get(0).inputA());
        assertEquals(DensityGraphIr.ADD, result.graph().nodes().get(2).opcode());
    }
}
