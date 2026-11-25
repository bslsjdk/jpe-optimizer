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
        assertEquals(7, DensityGraphIr.VERSION);
    }

    @Test
    void acceptsFloat32DivisionOpcode() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.constant(8.0), DensityGraphIr.Node.constant(2.0),
                DensityGraphIr.Node.divide(0, 1)
        ), 2, 0xD1L);
        assertEquals(DensityGraphIr.DIV, graph.nodes().get(2).opcode());
        assertEquals(7, DensityGraphIr.VERSION);
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
    void acceptsExternalVolumeInputNode() {
        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.input(0),
                DensityGraphIr.Node.constant(2.0),
                DensityGraphIr.Node.add(0, 1)
        ), 2, 0x1A9L);
        assertEquals(DensityGraphIr.INPUT, graph.nodes().get(0).opcode());
        assertEquals(7, DensityGraphIr.VERSION);
    }

    @Test
    void rejectsOutOfRangeExternalInputIndex() {
        assertThrows(IllegalArgumentException.class, () -> DensityGraphIr.Node.input(256));
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
}
