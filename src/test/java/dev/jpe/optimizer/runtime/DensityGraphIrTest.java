package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DensityGraphIrTest {
    @Test
    void acceptsTopologicallyOrderedArithmeticGraph() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.x(),
                DensityGraphIr.Node.y(),
                DensityGraphIr.Node.constant(2.0),
                DensityGraphIr.Node.add(0, 2),
                DensityGraphIr.Node.multiply(3, 1)
        ), 4, 0x1234L);

        assertEquals(5, graph.nodes().size());
        assertEquals(4, graph.rootNode());
        assertEquals(0x1234L, graph.configurationFingerprint());
    }

    @Test
    void acceptsSubtractionOpcode() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.x(),
                DensityGraphIr.Node.constant(3.5),
                DensityGraphIr.Node.subtract(0, 1)
        ), 2, 0x5ABL);

        assertEquals(DensityGraphIr.SUB, graph.nodes().get(2).opcode());
        assertEquals(10, DensityGraphIr.VERSION);
    }

    @Test
    void acceptsFloat32DivisionOpcode() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.constant(8.0), DensityGraphIr.Node.constant(2.0),
                DensityGraphIr.Node.divide(0, 1)
        ), 2, 0xD1L);
        assertEquals(DensityGraphIr.DIV, graph.nodes().get(2).opcode());
        assertEquals(10, DensityGraphIr.VERSION);
    }

    @Test
    void encodesClampBoundsAsExactFloat32Payload() {
        float min = Float.intBitsToFloat(0x80000000);
        float max = Float.intBitsToFloat(0x7fc01234);
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.input(0), DensityGraphIr.Node.clamp(0, min, max)
        ), 1, 0xC1A4L);
        DensityGraphIr.Node clamp = graph.nodes().get(1);
        assertEquals(DensityGraphIr.CLAMP, clamp.opcode());
        assertEquals(0, clamp.inputA());
        assertEquals(DensityGraphIr.NO_INPUT, clamp.inputB());
        long bits = Double.doubleToRawLongBits(clamp.immediate());
        assertEquals(0x800000007fc01234L, bits);
    }

    @Test
    void rejectsClampWithSecondInputOrForwardReference() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                DensityGraphIr.Node.constant(1.0),
                new DensityGraphIr.Node(DensityGraphIr.CLAMP, 0, 0, 0.0)
        ), 1, 0L));
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                new DensityGraphIr.Node(DensityGraphIr.CLAMP, 1, DensityGraphIr.NO_INPUT, 0.0),
                DensityGraphIr.Node.constant(1.0)
        ), 0, 0L));
    }

    @Test
    void acceptsMinecraftVolumeMinMaxOpcodes() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.input(0), DensityGraphIr.Node.input(1),
                DensityGraphIr.Node.min(0, 1), DensityGraphIr.Node.max(0, 1)
        ), 3, 0xA11L);
        assertEquals(DensityGraphIr.MIN, graph.nodes().get(2).opcode());
        assertEquals(DensityGraphIr.MAX, graph.nodes().get(3).opcode());
        assertEquals(10, DensityGraphIr.VERSION);
    }

    @Test
    void acceptsExternalVolumeInputNode() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.input(0),
                DensityGraphIr.Node.constant(2.0),
                DensityGraphIr.Node.add(0, 1)
        ), 2, 0x1A9L);
        assertEquals(DensityGraphIr.INPUT, graph.nodes().get(0).opcode());
        assertEquals(10, DensityGraphIr.VERSION);
    }

    @Test
    void rejectsOutOfRangeExternalInputIndex() {
        assertThrows(IllegalArgumentException.class, () -> DensityGraphIr.Node.input(256));
    }

    @Test
    void acceptsUnaryNativeDensityOpcodes() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.input(0),
                DensityGraphIr.Node.negate(0),
                DensityGraphIr.Node.abs(0),
                DensityGraphIr.Node.square(0),
                DensityGraphIr.Node.cube(0)
        ), 4, 0xA85L);
        assertEquals(DensityGraphIr.NEGATE, graph.nodes().get(1).opcode());
        assertEquals(DensityGraphIr.ABS, graph.nodes().get(2).opcode());
        assertEquals(DensityGraphIr.SQUARE, graph.nodes().get(3).opcode());
        assertEquals(DensityGraphIr.CUBE, graph.nodes().get(4).opcode());
    }

    @Test
    void preservesParameterizedLeakyReluFloat32Factor() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.input(0),
                DensityGraphIr.Node.leakyRelu(0, 0.25F)
        ), 1, 0xA87L);
        assertEquals(DensityGraphIr.LEAKY_RELU, graph.nodes().get(1).opcode());
        assertEquals(0.25, graph.nodes().get(1).immediate());
    }

    @Test
    void rejectsNonFloat32LeakyReluFactor() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                DensityGraphIr.Node.input(0),
                new DensityGraphIr.Node(DensityGraphIr.LEAKY_RELU, 0,
                        DensityGraphIr.NO_INPUT, 0.2500000001)
        ), 1, 0L));
    }

    @Test
    void rejectsUnaryNodesWithSecondInputOrForwardReference() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                DensityGraphIr.Node.constant(1.0),
                new DensityGraphIr.Node(DensityGraphIr.ABS, 0, 0, 0.0)
        ), 1, 0L));
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                new DensityGraphIr.Node(DensityGraphIr.NEGATE, 1, DensityGraphIr.NO_INPUT, 0.0),
                DensityGraphIr.Node.constant(1.0)
        ), 0, 0L));
    }

    @Test
    void rejectsForwardReferences() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                DensityGraphIr.Node.add(1, 0),
                DensityGraphIr.Node.constant(1.0)
        ), 0, 7L));
    }

    @Test
    void rejectsLeafNodesWithInputs() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                new DensityGraphIr.Node(DensityGraphIr.X, 0, DensityGraphIr.NO_INPUT, 0.0)
        ), 0, 7L));
    }

    @Test
    void rejectsOpaqueInputsThatDisagreeWithDiagnosticDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(
                List.of(DensityGraphIr.Node.constant(1.0), DensityGraphIr.Node.opaque(0, -1)),
                List.of(new DensityGraphIr.Diagnostic(1, "ExampleOpaque", "test", List.of(1))),
                1, 0L));
    }

    @Test
    void rejectsDuplicateOpaqueDiagnostics() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(
                List.of(DensityGraphIr.Node.constant(1.0), DensityGraphIr.Node.opaque(0, -1)),
                List.of(
                        new DensityGraphIr.Diagnostic(1, "ExampleOpaque", "first", List.of(0)),
                        new DensityGraphIr.Diagnostic(1, "ExampleOpaque", "second", List.of(0))),
                1, 0L));
    }

    @Test
    void rejectsUnknownOpcodesAndInvalidRoots() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                new DensityGraphIr.Node(99, -1, -1, 0.0)
        ), 0, 7L));
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                DensityGraphIr.Node.constant(1.0)
        ), 1, 7L));
    }
    @Test
    void acceptsClampedGradientRawIntegerPayloadWithoutTreatingItAsGraphEdges() {
        DensityGraphIr.Node gradient = DensityGraphIr.Node.gradientClamped(1,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, 0.25f, 0.5f);
        DensityGraphIr graph = new DensityGraphIr(List.of(gradient), 0, 0L);
        assertEquals(DensityGraphIr.GRADIENT_CLAMPED_Y, graph.nodes().get(0).opcode());
        assertEquals(Integer.MIN_VALUE, graph.nodes().get(0).inputA());
        assertEquals(Integer.MIN_VALUE, graph.nodes().get(0).inputB());
        assertEquals(Integer.MAX_VALUE, graph.nodes().get(0).reserved());
    }

}
