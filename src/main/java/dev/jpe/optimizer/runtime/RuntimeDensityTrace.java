package dev.jpe.optimizer.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, opt-in runtime trace collector for DensityFunction instrumentation.
 *
 * This records observed call relationships and branch outcomes. It deliberately
 * does not claim that a call trace alone is a serializable density graph:
 * implementations with hidden state, caches, random inputs, or side effects
 * must provide explicit semantic adapters before they can be lowered to native.
 */
public final class RuntimeDensityTrace {
    public static final int DEFAULT_MAX_EVENTS = 1_000_000;

    public record NodeInfo(int id, String implementation) {}
    public record Edge(int callerId, int calleeId) {}
    public record BranchGuard(int nodeId, int guardId, boolean outcome) {}
    public record Snapshot(List<NodeInfo> nodes, List<Edge> edges,
                           List<BranchGuard> guards, long droppedEvents) {
        public Snapshot {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            guards = List.copyOf(guards);
        }
    }

    private final int maxEvents;
    private final AtomicBoolean recording = new AtomicBoolean();
    private final ThreadLocal<ArrayDeque<Integer>> callStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
    private final ArrayList<NodeInfo> nodes = new ArrayList<>();
    private final ArrayList<Edge> edges = new ArrayList<>();
    private final ArrayList<BranchGuard> guards = new ArrayList<>();
    private final Set<Long> edgeKeys = new HashSet<>();
    private final Set<Long> guardKeys = new HashSet<>();
    private long droppedEvents;

    public RuntimeDensityTrace() {
        this(DEFAULT_MAX_EVENTS);
    }

    public RuntimeDensityTrace(int maxEvents) {
        if (maxEvents < 1) throw new IllegalArgumentException("maxEvents must be positive");
        this.maxEvents = maxEvents;
    }

    /** Starts a fresh capture. Existing snapshots are not retained. */
    public synchronized void start() {
        ids.clear();
        nodes.clear();
        edges.clear();
        guards.clear();
        droppedEvents = 0;
        edgeKeys.clear();
        guardKeys.clear();
        callStack.remove();
        recording.set(true);
    }

    /** Stops capture and returns an immutable snapshot. */
    public synchronized Snapshot stop() {
        recording.set(false);
        callStack.remove();
        return new Snapshot(nodes, edges, guards, droppedEvents);
    }

    /**
     * Enters an observed DensityFunction implementation. Call close() in a
     * finally/try-with-resources block so nested call relationships stay valid.
     */
    public Scope enter(Object densityFunction) {
        Objects.requireNonNull(densityFunction, "densityFunction");
        if (!recording.get()) return Scope.NOOP;
        int id = nodeId(densityFunction);
        ArrayDeque<Integer> stack = callStack.get();
        if (!stack.isEmpty()) addEdge(stack.peek(), id);
        stack.push(id);
        return new Scope(this, id, Thread.currentThread());
    }

    /** Records a runtime branch outcome; it is a guard, not a constant fold. */
    public void branch(Object densityFunction, int guardId, boolean outcome) {
        Objects.requireNonNull(densityFunction, "densityFunction");
        if (!recording.get()) return;
        int id = nodeId(densityFunction);
        synchronized (this) {
            long key = (((long) id) << 32) ^ (guardId & 0xffffffffL) ^ (outcome ? 0x8000000000000000L : 0L);
            if (!guardKeys.add(key)) return;
            if (eventCount() >= maxEvents) {
                droppedEvents++;
                return;
            }
            guards.add(new BranchGuard(id, guardId, outcome));
        }
    }

    private synchronized int nodeId(Object value) {
        Integer existing = ids.get(value);
        if (existing != null) return existing;
        if (eventCount() >= maxEvents) {
            droppedEvents++;
            return -1;
        }
        int id = nodes.size();
        ids.put(value, id);
        nodes.add(new NodeInfo(id, value.getClass().getName()));
        return id;
    }

    private synchronized void addEdge(int caller, int callee) {
        if (caller < 0 || callee < 0) return;
        long key = (((long) caller) << 32) | (callee & 0xffffffffL);
        if (!edgeKeys.add(key)) return;
        if (eventCount() >= maxEvents) {
            droppedEvents++;
            return;
        }
        edges.add(new Edge(caller, callee));
    }

    private int eventCount() {
        return nodes.size() + edges.size() + guards.size();
    }

    private void leave(int id, Thread owner) {
        if (!recording.get()) return;
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Density trace scope closed on a different thread");
        }
        ArrayDeque<Integer> stack = callStack.get();
        if (stack.isEmpty() || stack.pop() != id) {
            stack.clear();
            throw new IllegalStateException("Density trace scopes closed out of order");
        }
        if (stack.isEmpty()) callStack.remove();
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope NOOP = new Scope(null, -1, null);
        private RuntimeDensityTrace owner;
        private final int id;
        private final Thread thread;

        private Scope(RuntimeDensityTrace owner, int id, Thread thread) {
            this.owner = owner;
            this.id = id;
            this.thread = thread;
        }

        @Override
        public void close() {
            RuntimeDensityTrace current = owner;
            if (current == null) return;
            owner = null;
            current.leave(id, thread);
        }
    }
}
