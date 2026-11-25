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
    void rejectsUnknownOpcodesAndInvalidRoots() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                new DensityGraphIr.Node(99, -1, -1, 0.0)
        ), 0, 7L));
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr(List.of(
                DensityGraphIr.Node.constant(1.0)
        ), 1, 7L));
    }
}
