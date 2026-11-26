package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        assertEquals(java.util.List.of(
                new CompiledSamplerGraphTrace.ChildEdge("left", 1),
                new CompiledSamplerGraphTrace.ChildEdge("right", 1)), snapshot.nodes().get(0).childEdges());
        assertEquals(java.util.List.of(
                new CompiledSamplerGraphTrace.ChildEdge("left", 1),
                new CompiledSamplerGraphTrace.ChildEdge("right", 1)),
                snapshot.nodes().get(0).childEdges());
        assertEquals("3.0", snapshot.nodes().get(0).values().get(0).value());
        assertEquals("2.5", snapshot.nodes().get(1).values().get(0).value());
        assertEquals(0, snapshot.droppedNodes());
        assertEquals(snapshot.nodes().get(0).childEdges(),
                CompiledSamplerGraphIr.lower(snapshot).nodes().get(0).childEdges());
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
    void classifiesMinecraftMinAndMaxSamplersWithTheirRuntimeBounds() {
        CompiledSamplerGraphTrace.Snapshot snapshot = new CompiledSamplerGraphTrace.Snapshot(
                java.util.List.of(
                        new CompiledSamplerGraphTrace.NodeInfo(0,
                                "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MinSampler",
                                java.util.List.of(1, 2), java.util.List.of(
                                        new CompiledSamplerGraphTrace.ComponentValue("rightMinValue", "float", "-0.25")), ""),
                        new CompiledSamplerGraphTrace.NodeInfo(1,
                                "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MaxSampler",
                                java.util.List.of(3, 4), java.util.List.of(
                                        new CompiledSamplerGraphTrace.ComponentValue("rightMaxValue", "float", "0.5")), ""),
                        new CompiledSamplerGraphTrace.NodeInfo(2, "custom.Left", java.util.List.of(), java.util.List.of(), ""),
                        new CompiledSamplerGraphTrace.NodeInfo(3, "custom.Left2", java.util.List.of(), java.util.List.of(), ""),
                        new CompiledSamplerGraphTrace.NodeInfo(4, "custom.Right2", java.util.List.of(), java.util.List.of(), "")
                ), 0, 0);

        CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(snapshot);
        assertEquals(CompiledSamplerGraphIr.Op.MIN_RIGHT_BOUND, graph.nodes().get(0).op());
        assertEquals(CompiledSamplerGraphIr.Op.MAX_RIGHT_BOUND, graph.nodes().get(1).op());
        assertEquals("-0.25", graph.nodes().get(0).scalarValues().get("rightMinValue"));
        assertEquals("0.5", graph.nodes().get(1).scalarValues().get("rightMaxValue"));
    }

    @Test
    void classifiesExactMinecraftUnaryMathSamplerClasses() {
        String prefix = "net.minecraft.world.level.levelgen.densityfunction.op.UnaryFunction$";
        java.util.List<String> classes = java.util.List.of(
                "SqrtSampler", "LogSampler", "ReciprocalSampler", "SignSampler", "SqueezeSampler", "LeakyReLUSampler");
        java.util.List<CompiledSamplerGraphIr.Op> expected = java.util.List.of(
                CompiledSamplerGraphIr.Op.SQRT, CompiledSamplerGraphIr.Op.LOG,
                CompiledSamplerGraphIr.Op.RECIPROCAL, CompiledSamplerGraphIr.Op.SIGN,
                CompiledSamplerGraphIr.Op.SQUEEZE, CompiledSamplerGraphIr.Op.LEAKY_RELU);
        java.util.ArrayList<CompiledSamplerGraphTrace.NodeInfo> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < classes.size(); i++) {
            nodes.add(new CompiledSamplerGraphTrace.NodeInfo(i, prefix + classes.get(i),
                    java.util.List.of(5), java.util.List.of(), ""));
        }
        nodes.add(new CompiledSamplerGraphTrace.NodeInfo(5, "custom.Input",
                java.util.List.of(), java.util.List.of(), "not a record"));
        CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(
                new CompiledSamplerGraphTrace.Snapshot(nodes, 0, 0));
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), graph.nodes().get(i).op());
        }
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
    void detailedCaptureRetainsExactSamplerIdentitiesForEphemeralBinding() {
        LeafSampler shared = new LeafSampler(7.0F);
        TestSampler root = new TestSampler(shared, shared, 1.0F);

        CompiledSamplerGraphTrace.CapturedGraph captured =
                CompiledSamplerGraphTrace.captureDetailed(root,
                        value -> value instanceof TestSampler || value instanceof LeafSampler, 8);

        assertSame(root, captured.source(0));
        assertSame(shared, captured.source(1));
        assertEquals(2, captured.samplerSources().size());
        assertEquals(captured.snapshot().nodes().size(), captured.samplerSources().size());
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
    

    @Test
    void classifiesMinecraftFloatConstantSampler() {
        CompiledSamplerGraphTrace.Snapshot snapshot = new CompiledSamplerGraphTrace.Snapshot(
                java.util.List.of(new CompiledSamplerGraphTrace.NodeInfo(0,
                        "net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction$Sampler",
                        java.util.List.of(), java.util.List.of(
                                new CompiledSamplerGraphTrace.ComponentValue("value", "float", "0.125")), "")),
                0, 0);

        CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(snapshot);

        assertEquals(CompiledSamplerGraphIr.Op.CONSTANT, graph.nodes().get(0).op());
        assertEquals("0.125", graph.nodes().get(0).scalarValues().get("value"));
    }
    @Test
    void classifiesMinecraftClampSamplerAndPreservesFloatBounds() {
        CompiledSamplerGraphTrace.Snapshot snapshot = new CompiledSamplerGraphTrace.Snapshot(
                java.util.List.of(new CompiledSamplerGraphTrace.NodeInfo(0,
                        "net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction$Sampler",
                        java.util.List.of(1), java.util.List.of(
                                new CompiledSamplerGraphTrace.ComponentValue("min", "float", "-0.0"),
                                new CompiledSamplerGraphTrace.ComponentValue("max", "float", "1.25")), ""),
                        new CompiledSamplerGraphTrace.NodeInfo(1, "custom.NoiseSampler",
                                java.util.List.of(), java.util.List.of(), "not a record")),
                0, 0);

        CompiledSamplerGraphIr.Node clamp = CompiledSamplerGraphIr.lower(snapshot).nodes().get(0);

        assertEquals(CompiledSamplerGraphIr.Op.CLAMP, clamp.op());
        assertEquals("-0.0", clamp.scalarValues().get("min"));
        assertEquals("1.25", clamp.scalarValues().get("max"));
    }

    @Test
    void classifiesMinecraftClampedGradientSamplerAndPreservesIntegerParameters() {
        CompiledSamplerGraphTrace.Snapshot snapshot = new CompiledSamplerGraphTrace.Snapshot(
                java.util.List.of(new CompiledSamplerGraphTrace.NodeInfo(0,
                        "net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction$ClampedSampler",
                        java.util.List.of(), java.util.List.of(
                                new CompiledSamplerGraphTrace.ComponentValue("axis", "net.minecraft.core.Direction$Axis", "Y"),
                                new CompiledSamplerGraphTrace.ComponentValue("fromCoordinate", "int", "-64"),
                                new CompiledSamplerGraphTrace.ComponentValue("minCoordinate", "int", "-64"),
                                new CompiledSamplerGraphTrace.ComponentValue("maxCoordinate", "int", "320"),
                                new CompiledSamplerGraphTrace.ComponentValue("fromValue", "float", "-1.0"),
                                new CompiledSamplerGraphTrace.ComponentValue("coordinateFactor", "float", "0.00390625")), "")),
                0, 0);
        CompiledSamplerGraphIr.Node gradient = CompiledSamplerGraphIr.lower(snapshot).nodes().get(0);
        assertEquals(CompiledSamplerGraphIr.Op.GRADIENT_CLAMPED, gradient.op());
        assertEquals("Y", gradient.scalarValues().get("axis"));
        assertEquals("-64", gradient.scalarValues().get("fromCoordinate"));
        assertEquals("320", gradient.scalarValues().get("maxCoordinate"));
    }

}
