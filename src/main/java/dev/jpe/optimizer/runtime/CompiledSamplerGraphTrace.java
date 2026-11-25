package dev.jpe.optimizer.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Bounded diagnostic inventory of Minecraft's already-compiled sampler graph.
 * It inspects record components only and never walks arbitrary object fields,
 * contexts, registries, noise objects, or world state.
 */
public final class CompiledSamplerGraphTrace {
    public record ComponentValue(String name, String type, String value) {}
    public record ChildEdge(String componentName, int childId) {
        public ChildEdge {
            Objects.requireNonNull(componentName, "componentName");
            if (childId < 0) throw new IllegalArgumentException("childId must be non-negative");
        }
    }
    public record NodeInfo(int id, String implementation, List<Integer> children,
                           List<ChildEdge> childEdges, List<ComponentValue> values, String limitation) {
        public NodeInfo {
            children = List.copyOf(children);
            childEdges = List.copyOf(childEdges);
            values = List.copyOf(values);
        }
        /** Compatibility constructor for synthetic fixtures created before named edges were added. */
        public NodeInfo(int id, String implementation, List<Integer> children,
                        List<ComponentValue> values, String limitation) {
            this(id, implementation, children, List.of(), values, limitation);
        }
    }
    public record Snapshot(List<NodeInfo> nodes, int rootId, long droppedNodes) {
        public Snapshot { nodes = List.copyOf(nodes); }
    }
    /** Ephemeral capture retaining live sampler identities only for one volume execution. */
    public record CapturedGraph(Snapshot snapshot, List<Object> samplerSources) {
        public CapturedGraph {
            Objects.requireNonNull(snapshot, "snapshot");
            samplerSources = List.copyOf(samplerSources);
            if (samplerSources.size() != snapshot.nodes().size()) {
                throw new IllegalArgumentException("Sampler source count must match graph nodes");
            }
        }
        public Object source(int nodeId) {
            if (nodeId < 0 || nodeId >= samplerSources.size()) {
                throw new IllegalArgumentException("Unknown sampler node id " + nodeId);
            }
            return samplerSources.get(nodeId);
        }
    }

    private static final class NodeBuilder {
        final int id;
        final Object source;
        final ArrayList<Integer> children = new ArrayList<>();
        final ArrayList<ChildEdge> childEdges = new ArrayList<>();
        final ArrayList<ComponentValue> values = new ArrayList<>();
        String limitation = "";
        RecordComponent[] components;
        int nextComponent;
        NodeBuilder(int id, Object source) {
            this.id = id;
            this.source = source;
        }
    }

    private CompiledSamplerGraphTrace() {}

    /** Captures a record-based sampler DAG without invoking sampler evaluation. */
    public static Snapshot capture(Object root, Predicate<Object> isSampler, int maxNodes) {
        return captureDetailed(root, isSampler, maxNodes).snapshot();
    }

    /**
     * Captures the graph plus its live source identities. The returned object is
     * intentionally ephemeral: callers must not put it in static or persistent caches.
     */
    public static CapturedGraph captureDetailed(Object root, Predicate<Object> isSampler, int maxNodes) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(isSampler, "isSampler");
        if (maxNodes < 1) throw new IllegalArgumentException("maxNodes must be positive");
        if (!isSampler.test(root)) throw new IllegalArgumentException("Root is not a sampler");

        IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        ArrayList<NodeBuilder> builders = new ArrayList<>(Math.min(maxNodes, 256));
        ArrayDeque<NodeBuilder> stack = new ArrayDeque<>();
        NodeBuilder rootNode = new NodeBuilder(0, root);
        ids.put(root, 0);
        builders.add(rootNode);
        stack.push(rootNode);
        long dropped = 0;

        while (!stack.isEmpty()) {
            NodeBuilder current = stack.peek();
            if (current.components == null) {
                Class<?> type = current.source.getClass();
                if (!type.isRecord()) {
                    current.limitation = "Sampler implementation is not a record; children were not inspected";
                    stack.pop();
                    continue;
                }
                current.components = type.getRecordComponents();
                if (current.components == null) {
                    current.limitation = "Record component metadata is unavailable";
                    stack.pop();
                    continue;
                }
            }

            if (current.nextComponent >= current.components.length) {
                stack.pop();
                continue;
            }

            RecordComponent component = current.components[current.nextComponent++];
            final Object value;
            try {
                var accessor = component.getAccessor();
                if (!accessor.canAccess(current.source) && !accessor.trySetAccessible()) {
                    current.limitation = "Record accessor is not accessible: " + component.getName();
                    continue;
                }
                value = accessor.invoke(current.source);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException failure) {
                current.limitation = "Unable to read record component " + component.getName()
                        + ": " + failure.getClass().getSimpleName();
                continue;
            }

            if (value != null && isSampler.test(value)) {
                Integer childId = ids.get(value);
                if (childId == null) {
                    if (builders.size() >= maxNodes) {
                        dropped++;
                        current.limitation = "Sampler node budget exhausted";
                        continue;
                    }
                    childId = builders.size();
                    ids.put(value, childId);
                    NodeBuilder child = new NodeBuilder(childId, value);
                    builders.add(child);
                    current.children.add(childId);
                    current.childEdges.add(new ChildEdge(component.getName(), childId));
                    stack.push(child);
                } else {
                    current.children.add(childId);
                    current.childEdges.add(new ChildEdge(component.getName(), childId));
                }
            } else if (isSafeScalar(value)) {
                String rendered = renderScalar(value);
                if (rendered.length() > 256) rendered = rendered.substring(0, 256);
                current.values.add(new ComponentValue(component.getName(),
                        component.getType().getName(), rendered));
            } else if (value != null) {
                current.values.add(new ComponentValue(component.getName(),
                        component.getType().getName(), "<non-sampler state omitted>"));
            }
        }

        ArrayList<NodeInfo> result = new ArrayList<>(builders.size());
        for (NodeBuilder builder : builders) {
            result.add(new NodeInfo(builder.id, builder.source.getClass().getName(),
                    builder.children, builder.childEdges, builder.values, builder.limitation));
        }
        Snapshot snapshot = new Snapshot(result, 0, dropped);
        ArrayList<Object> sources = new ArrayList<>(builders.size());
        for (NodeBuilder builder : builders) sources.add(builder.source);
        return new CapturedGraph(snapshot, sources);
    }

    private static boolean isSafeScalar(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>) return true;
        Class<?> type = value.getClass();
        return type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class;
    }

    private static String renderScalar(Object value) {
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        return String.valueOf(value);
    }
}
