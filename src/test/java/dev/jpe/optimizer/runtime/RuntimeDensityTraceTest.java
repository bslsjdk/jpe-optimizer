package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeDensityTraceTest {
    private static final class Node {}
    private static final class Child {}

    @Test
    void capturesNestedCallsAndBranchGuards() {
        RuntimeDensityTrace trace = new RuntimeDensityTrace();
        Node root = new Node();
        Child child = new Child();

        trace.start();
        try (RuntimeDensityTrace.Scope ignored = trace.enter(root)) {
            try (RuntimeDensityTrace.Scope ignoredChild = trace.enter(child)) {
                trace.branch(child, 7, true);
            }
        }
        RuntimeDensityTrace.Snapshot snapshot = trace.stop();

        assertEquals(2, snapshot.nodes().size());
        assertEquals(1, snapshot.edges().size());
        assertEquals(snapshot.nodes().get(0).id(), snapshot.edges().get(0).callerId());
        assertEquals(snapshot.nodes().get(1).id(), snapshot.edges().get(0).calleeId());
        assertEquals(1, snapshot.guards().size());
        assertTrue(snapshot.guards().get(0).outcome());
    }

    @Test
    void repeatedEdgesAndGuardsAreDeduplicated() {
        RuntimeDensityTrace trace = new RuntimeDensityTrace();
        Node root = new Node();
        Child child = new Child();
        trace.start();
        for (int i = 0; i < 1000; ++i) {
            try (RuntimeDensityTrace.Scope ignored = trace.enter(root)) {
                try (RuntimeDensityTrace.Scope ignoredChild = trace.enter(child)) {
                    trace.branch(child, 11, true);
                }
            }
        }
        RuntimeDensityTrace.Snapshot snapshot = trace.stop();
        assertEquals(1, snapshot.edges().size());
        assertEquals(1, snapshot.guards().size());
    }

    @Test
    void disabledRecorderDoesNotCaptureEvents() {
        RuntimeDensityTrace trace = new RuntimeDensityTrace();
        try (RuntimeDensityTrace.Scope ignored = trace.enter(new Node())) {
            trace.branch(new Node(), 3, false);
        }
        assertTrue(trace.stop().nodes().isEmpty());
    }

    @Test
    void boundsCaptureAndReportsDroppedEvents() {
        RuntimeDensityTrace trace = new RuntimeDensityTrace(2);
        trace.start();
        trace.enter(new Node()).close();
        trace.enter(new Child()).close();
        trace.branch(new Node(), 3, true);
        RuntimeDensityTrace.Snapshot snapshot = trace.stop();

        assertTrue(snapshot.nodes().size() <= 2);
        assertTrue(snapshot.droppedEvents() > 0);
    }
}
