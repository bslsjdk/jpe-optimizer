package dev.jpe.optimizer.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.densityfunction.ContextBoundSampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Captures the compiled sampler DAG with the child edges a whole-graph lowering
 * needs, including the two families whose children are not record components.
 *
 * <p>{@link CompiledSamplerGraphTrace} intentionally walks record components only,
 * which is enough for diagnostics but loses two things the native compiler needs:
 * {@code IntervalSelectFunction$Sampler} keeps its branches in a
 * {@code DensitySampler[]}, and {@code SplineFunction$Sampler} is not a record at
 * all, so its coordinate samplers never appear. Both would leave the graph with
 * holes that no lowering can fill.
 *
 * <p>The output uses the trace's own record types so the existing classification
 * and fingerprinting keep working unchanged.
 */
public final class MinecraftSamplerInventory {
    /** Node id → live sampler, valid only for the volume being compiled. */
    public record Inventory(CompiledSamplerGraphTrace.Snapshot snapshot, List<Object> sources) {
        public Inventory {
            Objects.requireNonNull(snapshot, "snapshot");
            sources = List.copyOf(sources);
            if (sources.size() != snapshot.nodes().size()) {
                throw new IllegalArgumentException("Source count must match graph nodes");
            }
        }
        public Object source(int id) {
            if (id < 0 || id >= sources.size()) {
                throw new IllegalArgumentException("Unknown sampler node id " + id);
            }
            return sources.get(id);
        }
    }

    private record Edge(String name, Object child) {}
    private record Value(String name, String type, String value) {}
    private record Description(List<Edge> edges, List<Value> values, String limitation) {}

    private static final String INTERVAL_SELECT =
            "net.minecraft.world.level.levelgen.densityfunction.op.IntervalSelectFunction$Sampler";
    private static final String SPLINE =
            "net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction$Sampler";
    private static final String CONTEXT_BOUND =
            "net.minecraft.world.level.levelgen.densityfunction.ContextBoundSampler";
    private static final String SHIFT_B_PREFIX =
            "net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction$ShiftB$";

    private MinecraftSamplerInventory() {}

    public static Inventory capture(Object root, int maxNodes) {
        return capture(root, maxNodes, null);
    }

    /**
     * @param context when present, a context-bound node is resolved to the sampler
     *                that will actually run, so the plan matches this chunk
     */
    public static Inventory capture(Object root, int maxNodes, SamplerContext context) {
        Objects.requireNonNull(root, "root");
        if (maxNodes < 1) throw new IllegalArgumentException("maxNodes must be positive");

        IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        ArrayList<Object> sources = new ArrayList<>(Math.min(maxNodes, 256));
        ArrayList<Description> descriptions = new ArrayList<>(Math.min(maxNodes, 256));
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        ids.put(root, 0);
        sources.add(root);
        descriptions.add(describe(root, context));
        pending.add(0);

        long dropped = 0;
        while (!pending.isEmpty()) {
            int id = pending.poll();
            Description description = descriptions.get(id);
            for (Edge edge : description.edges()) {
                Object child = edge.child();
                if (child == null || ids.containsKey(child)) continue;
                if (sources.size() >= maxNodes) {
                    dropped++;
                    continue;
                }
                ids.put(child, sources.size());
                sources.add(child);
                descriptions.add(describe(child, context));
                pending.add(sources.size() - 1);
            }
        }

        ArrayList<CompiledSamplerGraphTrace.NodeInfo> nodes = new ArrayList<>(sources.size());
        for (int id = 0; id < sources.size(); ++id) {
            Description description = descriptions.get(id);
            ArrayList<Integer> children = new ArrayList<>(description.edges().size());
            ArrayList<CompiledSamplerGraphTrace.ChildEdge> edges =
                    new ArrayList<>(description.edges().size());
            for (Edge edge : description.edges()) {
                Integer childId = ids.get(edge.child());
                if (childId == null) continue;
                children.add(childId);
                edges.add(new CompiledSamplerGraphTrace.ChildEdge(edge.name(), childId));
            }
            ArrayList<CompiledSamplerGraphTrace.ComponentValue> values =
                    new ArrayList<>(description.values().size());
            for (Value value : description.values()) {
                values.add(new CompiledSamplerGraphTrace.ComponentValue(
                        value.name(), value.type(), value.value()));
            }
            nodes.add(new CompiledSamplerGraphTrace.NodeInfo(id,
                    sources.get(id).getClass().getName(), children, edges, values,
                    description.limitation()));
        }
        return new Inventory(
                new CompiledSamplerGraphTrace.Snapshot(nodes, 0, dropped), sources);
    }

    private static Description describe(Object sampler, SamplerContext context) {
        String type = sampler.getClass().getName();
        if (INTERVAL_SELECT.equals(type)) {
            return describeIntervalSelect(sampler);
        }
        if (SPLINE.equals(type)) {
            return describeSpline(sampler);
        }
        if (CONTEXT_BOUND.equals(type)) {
            return describeContextBound(sampler, context);
        }
        if (type.startsWith(SHIFT_B_PREFIX)) {
            // Anonymous ShiftB sampler: no children, one captured noise stack.
            return new Description(List.of(), List.of(new Value("shiftB", "boolean", "true")), "");
        }
        return describeRecord(sampler);
    }

    /**
     * Resolves the sampler the context actually selects. The fallback is only used
     * when the context has no override, so capturing the fallback unconditionally
     * would describe a graph that never runs.
     */
    private static Description describeContextBound(Object sampler, SamplerContext context) {
        ContextBoundSampler bound = (ContextBoundSampler) sampler;
        DensitySampler resolved = bound.fallbackSampler();
        boolean overridden = false;
        if (context != null) {
            DensitySampler override = context.getField(bound.key());
            if (override != null) {
                resolved = override;
                overridden = true;
            }
        }
        ArrayList<Value> values = new ArrayList<>(1);
        values.add(new Value("contextOverride", "boolean", Boolean.toString(overridden)));
        return new Description(List.of(new Edge("resolved", resolved)), values, "");
    }

    /** IntervalSelect keeps its branches in an array component, not as children. */
    private static Description describeIntervalSelect(Object sampler) {
        ArrayList<Edge> edges = new ArrayList<>();
        ArrayList<Value> values = new ArrayList<>();
        String limitation = "";
        try {
            Object input = component(sampler, "input");
            if (input instanceof DensitySampler) edges.add(new Edge("input", input));
            Object thresholds = component(sampler, "thresholds");
            if (thresholds instanceof float[] array) {
                values.add(new Value("thresholdCount", "int", Integer.toString(array.length)));
                for (int i = 0; i < array.length; ++i) {
                    values.add(new Value("threshold" + i, "float", Float.toString(array[i])));
                }
            }
            Object branches = component(sampler, "samplers");
            if (branches instanceof Object[] array) {
                for (int i = 0; i < array.length; ++i) {
                    if (array[i] instanceof DensitySampler) {
                        edges.add(new Edge("branch" + i, array[i]));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            limitation = "Unable to read interval select components: " + failure.getClass().getSimpleName();
        }
        return new Description(edges, values, limitation);
    }

    /** SplineFunction.Sampler is not a record; its coordinate samplers are the children. */
    private static Description describeSpline(Object sampler) {
        ArrayList<Edge> edges = new ArrayList<>();
        ArrayList<Value> values = new ArrayList<>();
        Object function = MinecraftSplineAccess.function(sampler);
        CubicSpline<?> tree = MinecraftSplineAccess.tree(function);
        if (tree == null) {
            Float constant = MinecraftSplineAccess.constant(function);
            if (constant != null) {
                values.add(new Value("constant", "float", Float.toString(constant)));
            }
            return new Description(edges, values, constant == null
                    ? "Spline wrapper exposes neither a tree nor a constant" : "");
        }
        List<DensitySampler> coordinates = MinecraftSplineAccess.coordinateSamplers(tree);
        for (int i = 0; i < coordinates.size(); ++i) {
            edges.add(new Edge("coordinate" + i, coordinates.get(i)));
        }
        values.add(new Value("coordinateCount", "int", Integer.toString(coordinates.size())));
        return new Description(edges, values, "");
    }

    private static Description describeRecord(Object sampler) {
        ArrayList<Edge> edges = new ArrayList<>();
        ArrayList<Value> values = new ArrayList<>();
        String limitation = "";
        Class<?> type = sampler.getClass();
        if (!type.isRecord()) {
            return new Description(edges, values,
                    "Sampler implementation is not a record; children were not inspected");
        }
        for (RecordComponent component : type.getRecordComponents()) {
            Object value;
            try {
                var accessor = component.getAccessor();
                if (!accessor.canAccess(sampler) && !accessor.trySetAccessible()) {
                    limitation = "Record accessor is not accessible: " + component.getName();
                    continue;
                }
                value = accessor.invoke(sampler);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException failure) {
                limitation = "Unable to read record component " + component.getName()
                        + ": " + failure.getClass().getSimpleName();
                continue;
            }
            if (value instanceof DensitySampler) {
                edges.add(new Edge(component.getName(), value));
            } else if (isSafeScalar(value)) {
                String rendered = value instanceof Enum<?> e ? e.name() : String.valueOf(value);
                if (rendered.length() > 256) rendered = rendered.substring(0, 256);
                values.add(new Value(component.getName(), component.getType().getName(), rendered));
            } else if (value != null) {
                values.add(new Value(component.getName(), component.getType().getName(),
                        "<non-sampler state omitted>"));
            }
        }
        return new Description(edges, values, limitation);
    }

    private static Object component(Object sampler, String name) throws ReflectiveOperationException {
        var accessor = sampler.getClass().getDeclaredMethod(name);
        if (!accessor.trySetAccessible()) {
            throw new IllegalAccessException("Cannot access " + name);
        }
        return accessor.invoke(sampler);
    }

    private static boolean isSafeScalar(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>) return true;
        Class<?> type = value.getClass();
        return type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class;
    }
}
