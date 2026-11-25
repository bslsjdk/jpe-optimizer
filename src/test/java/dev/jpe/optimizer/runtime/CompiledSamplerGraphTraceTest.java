package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompiledSamplerGraphTraceTest {
    public record TestSampler(Object left, Object right, float scale) {}
    public record LeafSampler(float constant) {}
    private record HiddenSampler(Object child) {}
    private static final class CustomSampler {}

    @Test
    void capturesRecordSamplerChildrenAndScalarParameters() {
        LeafSampler shared = new LeafSampler(2.5F);
        TestSampler root = new TestSampler(shared, shared, 3.0F);

        CompiledSamplerGraphTrace.Snapshot snapshot =
                CompiledSamplerGraphTrace.capture(root,
                        value -> value instanceof TestSampler || value instanceof LeafSampler, 16);

        assertEquals(2, snapshot.nodes().size());
        assertEquals(0, snapshot.rootId());
        assertEquals(java.util.List.of(1, 1), snapshot.nodes().get(0).children());
        assertEquals("3.0", snapshot.nodes().get(0).values().get(0).value());
        assertEquals("2.5", snapshot.nodes().get(1).values().get(0).value());
        assertEquals(0, snapshot.droppedNodes());
    }

    @Test
    void lowersKnownCompiledSamplerClassesWithoutLosingBoundaries() {
        CompiledSamplerGraphTrace.Snapshot snapshot = new CompiledSamplerGraphTrace.Snapshot(
                java.util.List.of(
                        new CompiledSamplerGraphTrace.NodeInfo(0,
                                "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MulSampler",
                                java.util.List.of(1, 2), java.util.List.of(), ""),
                        new CompiledSamplerGraphTrace.NodeInfo(1,
                                "net.minecraft.world.level.levelgen.densityfunction.CachingDensitySampler",
                                java.util.List.of(3), java.util.List.of(
                                        new CompiledSamplerGraphTrace.ComponentValue("id", "int", "7")), ""),
                        new CompiledSamplerGraphTrace.NodeInfo(2,
                                "net.minecraft.world.level.levelgen.densityfunction.ContextBoundSampler",
                                java.util.List.of(4), java.util.List.of(), ""),
                        new CompiledSamplerGraphTrace.NodeInfo(3, "custom.NoiseSampler",
                                java.util.List.of(), java.util.List.of(), "not a record")
                ), 0, 0);

        CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(snapshot);

        assertEquals(CompiledSamplerGraphIr.Op.MUL_ZERO_SHORT_CIRCUIT, graph.nodes().get(0).op());
        assertEquals(java.util.List.of(1, 2), graph.nodes().get(0).children());
        assertEquals(CompiledSamplerGraphIr.Op.CACHE_BOUNDARY, graph.nodes().get(1).op());
        assertEquals("7", graph.nodes().get(1).scalarValues().get("id"));
        assertEquals(CompiledSamplerGraphIr.Op.CONTEXT_BOUNDARY, graph.nodes().get(2).op());
        assertEquals(CompiledSamplerGraphIr.Op.OPAQUE, graph.nodes().get(3).op());
        assertEquals(CompiledSamplerGraphIr.Op.CONST_DIV_LEFT,
                CompiledSamplerGraphIr.lower(new CompiledSamplerGraphTrace.Snapshot(
                        java.util.List.of(new CompiledSamplerGraphTrace.NodeInfo(0,
                                "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstDivSampler",
                                java.util.List.of(1), java.util.List.of(
                                        new CompiledSamplerGraphTrace.ComponentValue("left", "float", "0.0")), "")),
                        0, 0)).nodes().get(0).op());
    }

    @Test
    void readsPrivateNestedSamplerRecordAccessors() {
        HiddenSampler root = new HiddenSampler(new LeafSampler(4.0F));
        CompiledSamplerGraphTrace.Snapshot snapshot =
                CompiledSamplerGraphTrace.capture(root,
                        value -> value instanceof HiddenSampler || value instanceof LeafSampler, 8);

        assertEquals(2, snapshot.nodes().size());
        assertEquals(java.util.List.of(1), snapshot.nodes().get(0).children());
        assertTrue(snapshot.nodes().get(0).limitation().isEmpty());
    }

    @Test
    void preservesUnknownNonRecordSamplerAsOpaqueLeaf() {
        CustomSampler root = new CustomSampler();
        CompiledSamplerGraphTrace.Snapshot snapshot =
                CompiledSamplerGraphTrace.capture(root, value -> value instanceof CustomSampler, 4);

        assertEquals(1, snapshot.nodes().size());
        assertTrue(snapshot.nodes().get(0).limitation().contains("not a record"));
    }

    @Test
    void enforcesNodeBudgetWithoutWalkingPastLimit() {
        LeafSampler first = new LeafSampler(1.0F);
        TestSampler root = new TestSampler(first, new LeafSampler(2.0F), 1.0F);

        CompiledSamplerGraphTrace.Snapshot snapshot =
                CompiledSamplerGraphTrace.capture(root,
                        value -> value instanceof TestSampler || value instanceof LeafSampler, 2);

        assertEquals(2, snapshot.nodes().size());
        assertEquals(1, snapshot.droppedNodes());
        assertTrue(snapshot.nodes().get(0).limitation().contains("budget"));
    }
}
