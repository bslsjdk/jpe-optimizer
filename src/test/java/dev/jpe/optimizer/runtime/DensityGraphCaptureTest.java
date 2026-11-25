package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DensityGraphCaptureTest {
    private static final class DensityNode {}

    @Test
    void lowersExplicitSupportedOperationsInTopologicalOrder() {
        DensityGraphCapture capture = new DensityGraphCapture();
        DensityNode x = new DensityNode();
        DensityNode two = new DensityNode();
        DensityNode sum = new DensityNode();
        DensityNode y = new DensityNode();
        DensityNode root = new DensityNode();

        capture.coordinate(x, 0);
        capture.constant(two, 2.0);
        capture.add(sum, x, two);
        capture.coordinate(y, 1);
        capture.multiply(root, sum, y);

        DensityGraphIr graph = capture.finish(root, 123L);
        assertEquals(5, graph.nodes().size());
        assertEquals(4, graph.rootNode());
        assertEquals(DensityGraphIr.X, graph.nodes().get(0).opcode());
        assertEquals(DensityGraphIr.CONST, graph.nodes().get(1).opcode());
        assertEquals(DensityGraphIr.ADD, graph.nodes().get(2).opcode());
        assertEquals(DensityGraphIr.Y, graph.nodes().get(3).opcode());
        assertEquals(DensityGraphIr.MUL, graph.nodes().get(4).opcode());
        assertEquals(123L, graph.configurationFingerprint());
    }

    @Test
    void refusesUnknownOrUnsupportedReachableNodes() {
        DensityGraphCapture capture = new DensityGraphCapture();
        DensityNode known = new DensityNode();
        DensityNode unknown = new DensityNode();
        DensityNode root = new DensityNode();
        capture.constant(known, 1.0);
        capture.unsupported(unknown, "random state is not represented in IR v1");
        capture.add(root, known, unknown);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> capture.finish(root, 0L));
        assertTrue(failure.getMessage().contains("random state"));
    }

    @Test
    void excludesUnreachableCapturedNodesAndPreservesSharedChildren() {
        DensityGraphCapture capture = new DensityGraphCapture();
        DensityNode shared = new DensityNode();
        DensityNode left = new DensityNode();
        DensityNode right = new DensityNode();
        DensityNode root = new DensityNode();
        DensityNode unused = new DensityNode();

        capture.constant(shared, 3.0);
        capture.add(left, shared, shared);
        capture.multiply(right, shared, shared);
        capture.add(root, left, right);
        capture.constant(unused, 999.0);

        DensityGraphIr graph = capture.finish(root, 0L);
        assertEquals(4, graph.nodes().size());
        assertEquals(0, graph.nodes().get(1).inputA());
        assertEquals(0, graph.nodes().get(1).inputB());
        assertEquals(0, graph.nodes().get(2).inputA());
        assertEquals(0, graph.nodes().get(2).inputB());
    }
}
