package dev.jpe.optimizer.runtime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

/**
 * Reads the cubic-spline tree that {@code SplineFunction.Sampler} keeps private.
 *
 * <p>Vanilla compiles a spline into a {@code BoundedFloatFunction} wrapper: either
 * an anonymous constant, or an anonymous class that closes over one
 * {@code CubicSpline.Multipoint}. Both the wrapper and the tree types are
 * package-private, so this class reaches them through cached reflective
 * accessors instead of pattern matching.
 *
 * <p>The wrapper also hides the coordinate samplers, which are real nodes in the
 * density graph. {@link #coordinateSamplers} returns them in a stable
 * depth-first order so the inventory and the lowering pass agree on child
 * indices without sharing mutable state.
 */
public final class MinecraftSplineAccess {
    private static Method locations;
    private static Method values;
    private static Method derivatives;
    private static Method coordinate;
    private static Method constantValue;
    private static Method samplerField;
    private static boolean unavailable;

    private MinecraftSplineAccess() {}

    /** True when the reflective surface matched the expected 26.3 shape. */
    public static boolean available() {
        try {
            ensureAccessors();
            return !unavailable;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static synchronized void ensureAccessors() {
        if (unavailable || locations != null) return;
        try {
            Class<?> multipoint = Class.forName("net.minecraft.util.CubicSpline$Multipoint");
            Class<?> constant = Class.forName("net.minecraft.util.CubicSpline$Constant");
            Class<?> samplerCoordinate = Class.forName(
                    "net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction$SamplerCoordinate");
            locations = accessible(multipoint.getDeclaredMethod("locations"));
            values = accessible(multipoint.getDeclaredMethod("values"));
            derivatives = accessible(multipoint.getDeclaredMethod("derivatives"));
            coordinate = accessible(multipoint.getDeclaredMethod("coordinate"));
            constantValue = accessible(constant.getDeclaredMethod("value"));
            samplerField = accessible(samplerCoordinate.getDeclaredMethod("sampler"));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unavailable = true;
        }
    }

    private static Method accessible(Method method) {
        if (!method.trySetAccessible()) {
            throw new IllegalStateException("Cannot access " + method);
        }
        return method;
    }

    /** The {@code BoundedFloatFunction} a compiled spline sampler wraps. */
    public static Object function(Object splineSampler) {
        try {
            var field = splineSampler.getClass().getDeclaredField("sampler");
            if (!field.trySetAccessible()) return null;
            return field.get(splineSampler);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * The spline tree behind a compiled wrapper, or {@code null} when the wrapper
     * is a constant. The tree is read from the wrapper's captured field rather
     * than from a public accessor, because vanilla keeps no other handle on it.
     */
    public static CubicSpline<?> tree(Object function) {
        if (function == null) return null;
        ensureAccessors();
        if (unavailable) return null;
        for (var field : function.getClass().getDeclaredFields()) {
            if (!CubicSpline.class.isAssignableFrom(field.getType())) continue;
            try {
                if (!field.trySetAccessible()) return null;
                Object value = field.get(function);
                return value instanceof CubicSpline<?> spline ? spline : null;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        }
        return null;
    }

    /** The constant a compiled wrapper returns when it wraps no tree. */
    public static Float constant(Object function) {
        if (function == null) return null;
        for (var field : function.getClass().getDeclaredFields()) {
            if (field.getType() != float.class) continue;
            try {
                if (!field.trySetAccessible()) return null;
                return field.getFloat(function);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        }
        return null;
    }

    public static boolean isConstant(CubicSpline<?> spline) {
        return spline != null && !isMultipoint(spline);
    }

    private static boolean isMultipoint(CubicSpline<?> spline) {
        return spline.getClass().getName().endsWith("CubicSpline$Multipoint");
    }

    public static float[] locations(CubicSpline<?> multipoint) {
        return (float[]) invoke(locations, multipoint);
    }

    public static float[] derivatives(CubicSpline<?> multipoint) {
        return (float[]) invoke(derivatives, multipoint);
    }

    @SuppressWarnings("unchecked")
    public static List<CubicSpline<?>> children(CubicSpline<?> multipoint) {
        return (List<CubicSpline<?>>) invoke(values, multipoint);
    }

    public static float constantOf(CubicSpline<?> constant) {
        return (float) invoke(constantValue, constant);
    }

    /** The density sampler that feeds a multipoint's coordinate. */
    public static DensitySampler coordinateSampler(CubicSpline<?> multipoint) {
        Object coordinateObject = invoke(coordinate, multipoint);
        if (coordinateObject == null) return null;
        Object sampler = invoke(samplerField, coordinateObject);
        return sampler instanceof DensitySampler density ? density : null;
    }

    /**
     * Distinct coordinate samplers in depth-first order. A spline tree normally
     * has exactly one, but the terrain splines of a custom dimension may mix.
     */
    public static List<DensitySampler> coordinateSamplers(CubicSpline<?> root) {
        ArrayList<DensitySampler> result = new ArrayList<>(1);
        IdentityHashMap<DensitySampler, Boolean> seen = new IdentityHashMap<>();
        collect(root, result, seen);
        return result;
    }

    private static void collect(CubicSpline<?> node, List<DensitySampler> result,
                                Map<DensitySampler, Boolean> seen) {
        if (node == null || !isMultipoint(node)) return;
        DensitySampler sampler = coordinateSampler(node);
        if (sampler != null && seen.put(sampler, Boolean.TRUE) == null) {
            result.add(sampler);
        }
        for (CubicSpline<?> child : children(node)) collect(child, result, seen);
    }

    /**
     * Flattens the tree into the program's spline tables and returns the root slot.
     * Children are emitted after their parent slot is reserved, so a shared subtree
     * cannot recurse forever.
     */
    public static int flatten(CubicSpline<?> root, DensityGraphPayloadCapture tables,
                              Map<DensitySampler, Integer> coordinateIndices) {
        return flatten(root, tables, coordinateIndices, new IdentityHashMap<>());
    }

    private static int flatten(CubicSpline<?> node, DensityGraphPayloadCapture tables,
                               Map<DensitySampler, Integer> coordinateIndices,
                               Map<CubicSpline<?>, Integer> emitted) {
        Integer existing = emitted.get(node);
        if (existing != null) return existing;
        if (!isMultipoint(node)) {
            int slot = tables.addConstantSpline(constantOf(node));
            emitted.put(node, slot);
            return slot;
        }

        int slot = tables.reserveSpline();
        emitted.put(node, slot);

        DensitySampler coordinateSampler = coordinateSampler(node);
        Integer coordinateNode = coordinateSampler == null ? null : coordinateIndices.get(coordinateSampler);
        if (coordinateNode == null) {
            throw new IllegalArgumentException("Spline coordinate sampler is not part of the density graph");
        }
        float[] locations = locations(node);
        float[] derivatives = derivatives(node);
        List<CubicSpline<?>> children = children(node);
        if (locations.length != children.size() || locations.length != derivatives.length) {
            throw new IllegalArgumentException("Spline point arrays disagree in length");
        }
        int[] childSlots = new int[children.size()];
        for (int i = 0; i < childSlots.length; ++i) {
            childSlots[i] = flatten(children.get(i), tables, coordinateIndices, emitted);
        }
        tables.defineMultipointSpline(slot, coordinateNode, locations, derivatives, childSlots);
        return slot;
    }

    private static Object invoke(Method method, Object target) {
        ensureAccessors();
        if (unavailable || method == null) {
            throw new IllegalStateException("CubicSpline accessors are unavailable");
        }
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to read a cubic spline", failure);
        }
    }
}
