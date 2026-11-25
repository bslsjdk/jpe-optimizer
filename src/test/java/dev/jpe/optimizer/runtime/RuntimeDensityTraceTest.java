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
        trace.start();
        try (RuntimeDensityTrace.Scope ignored = trace.enter(root)) {
            trace.branch(root, 11, true);
            trace.branch(root, 11, false);
        }
        RuntimeDensityTrace.Snapshot bothOutcomes = trace.stop();
        assertEquals(2, bothOutcomes.guards().size());
    }

    @Test
    void staleScopeCannotCorruptANewerCaptureOnSameWorker() {
        RuntimeDensityTrace trace = new RuntimeDensityTrace();
        Node oldNode = new Node();
        Node newNode = new Node();
        trace.start();
        RuntimeDensityTrace.Scope stale = trace.enter(oldNode);
        trace.stop();

        trace.start();
        try (RuntimeDensityTrace.Scope current = trace.enter(newNode)) {
            stale.close();
            trace.branch(newNode, 19, true);
        }
        RuntimeDensityTrace.Snapshot snapshot = trace.stop();
        assertEquals(1, snapshot.nodes().size());
        assertEquals(newNode.getClass().getName(), snapshot.nodes().get(0).implementation());
        assertEquals(1, snapshot.guards().size());
    }


    @Test
    void wrongThreadCloseDoesNotConsumeScopeOrCorruptOwnerStack() throws InterruptedException {
        RuntimeDensityTrace trace = new RuntimeDensityTrace();
        Node root = new Node();
        Child child = new Child();
        Node afterRoot = new Node();
        trace.start();

        RuntimeDensityTrace.Scope rootScope = trace.enter(root);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try {
                rootScope.close();
            } catch (Throwable expected) {
                failure.set(expected);
            }
        });
        wrongThread.start();
        wrongThread.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        try (RuntimeDensityTrace.Scope ignored = trace.enter(child)) {
            // The original root must still be the active parent.
        }
        rootScope.close();
        try (RuntimeDensityTrace.Scope ignored = trace.enter(afterRoot)) {
            // Closing root on its owner thread must have emptied the stack.
        }

        RuntimeDensityTrace.Snapshot snapshot = trace.stop();
        assertEquals(1, snapshot.edges().size());
        assertEquals(root.getClass().getName(), snapshot.nodes().get(0).implementation());
        assertEquals(child.getClass().getName(), snapshot.nodes().get(1).implementation());
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
