package dev.jpe.optimizer.runtime;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.ContextBoundSampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.synth.Noise;

/**
 * Supplies the table-backed nodes that only the live sampler objects can describe.
 *
 * <p>Three families carry state that never reaches the scalar inventory: the
 * Perlin stack of a noise node, the cubic-spline tree of a spline node, and the
 * threshold array of an interval select. This provider reads those objects
 * directly and appends their tables to the program payload.
 *
 * <p>It also decides which wrappers are transparent. A cache wrapper is pure
 * memoisation, so it always collapses to its child. A context wrapper collapses
 * only when the bound {@link SamplerContext} carries no override for its key;
 * otherwise the fallback child is not what actually runs, and the node must stay
 * a hole. A blend wrapper collapses only when the context has no active
 * {@link Blender}, which is the case for every chunk that has no legacy
 * neighbours.
 */
public final class LiveSamplerPayloadProvider implements SamplerGraphProgramAdapter.PayloadProvider {
    private static final String INTERVAL_SELECT =
            "net.minecraft.world.level.levelgen.densityfunction.op.IntervalSelectFunction$Sampler";
    private static final String SINGLE_THRESHOLD =
            "net.minecraft.world.level.levelgen.densityfunction.op.IntervalSelectFunction$SingleThresholdSampler";

    private final MinecraftSamplerInventory.Inventory inventory;
    private final SamplerContext context;
    private final Map<Class<?>, Map<String, Method>> methods = new HashMap<>();

    private LiveSamplerPayloadProvider(MinecraftSamplerInventory.Inventory inventory,
                                       SamplerContext context) {
        this.inventory = inventory;
        this.context = context;
    }

    public static LiveSamplerPayloadProvider of(MinecraftSamplerInventory.Inventory inventory,
                                                SamplerContext context) {
        return new LiveSamplerPayloadProvider(inventory, context);
    }

    /** True when the bound context lets the whole graph run without Java-only nodes. */
    public boolean blenderInactive() {
        if (context == null) return true;
        Blender blender = context.getField(Blender.CONTEXT_KEY);
        return blender == null || blender.isEmpty();
    }

    @Override
    public boolean transparent(CompiledSamplerGraphIr.Node node) {
        Object source = inventory.source(node.id());
        return switch (node.op()) {
            // The inventory already resolved the context to the sampler that runs.
            case CONTEXT_BOUNDARY -> source instanceof ContextBoundSampler;
            case BLEND -> blenderInactive();
            default -> false;
        };
    }

    @Override
    public DensityGraphIr.Node lower(CompiledSamplerGraphIr.Node node, int[] children,
                                     DensityGraphPayloadCapture tables) {
        Object source = inventory.source(node.id());
        return switch (node.op()) {
            case NOISE -> noise(source, tables);
            case NOISE_SHIFTED -> shiftedNoise(source, children, tables);
            case NOISE_TRANSPOSED -> transposedNoise(source, tables);
            case SPLINE -> spline(source, children, tables);
            case INTERVAL_SELECT -> intervalSelect(node, source, children, tables);
            // SimpleDensityFunction.BEARDIFIER falls back to a zero constant, and an
            // empty Beardifier fills the volume with zero, so it lowers to CONST 0.
            // Eliding it would skip the parent's x + 0.0f, which can flip -0.0.
            case OPAQUE -> isEmptyBeardifier(source)
                    ? DensityGraphIr.Node.constant(0.0f) : null;
            default -> null;
        };
    }

    /**
     * A structure beard is real terrain state, not a density expression, so it can
     * only be elided when the sampler carries no affected box: vanilla then fills
     * the volume with zero and never touches it.
     */
    private static boolean isEmptyBeardifier(Object source) {
        if (!(source instanceof net.minecraft.world.level.levelgen.Beardifier beardifier)) return false;
        if (beardifier == net.minecraft.world.level.levelgen.Beardifier.EMPTY) return true;
        try {
            var field = beardifier.getClass().getDeclaredField("affectedBox");
            if (!field.trySetAccessible()) return false;
            return field.get(beardifier) == null;
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    private DensityGraphIr.Node noise(Object source, DensityGraphPayloadCapture tables) {
        if (!(source instanceof NoiseFunction.Sampler sampler)) {
            throw new IllegalArgumentException("Noise node is not a NoiseFunction.Sampler");
        }
        DensityGraphIr.NoisePlan plan = MinecraftNoisePlanCapture.capture(
                sampler.noise(), sampler.xzScale(), sampler.yScale(), 1.0f);
        if (plan == null) {
            throw new IllegalArgumentException("Noise stack cannot be reproduced natively");
        }
        return DensityGraphIr.Node.noise(tables.addNoisePlan(plan));
    }

    /**
     * Shifted noise samples the stack at (x * scale + shiftX, y * scale,
     * z * scale + shiftZ). The plan keeps the un-folded scales and the layer
     * frequency, because vanilla multiplies the shifted coordinate by the
     * frequency and folding would round differently.
     */
    private DensityGraphIr.Node shiftedNoise(Object source, int[] children,
                                             DensityGraphPayloadCapture tables) {
        if (!(source instanceof NoiseFunction.ShiftedXzSampler sampler)) {
            throw new IllegalArgumentException("Shifted noise node is not a ShiftedXzSampler");
        }
        if (children.length != 2) {
            throw new IllegalArgumentException("Shifted noise needs shiftX and shiftZ children");
        }
        DensityGraphIr.NoisePlan plan = MinecraftNoisePlanCapture.capture(
                sampler.noise(), sampler.xzScale(), sampler.yScale(), 1.0f, false);
        if (plan == null) {
            throw new IllegalArgumentException("Shifted noise stack cannot be reproduced natively");
        }
        return DensityGraphIr.Node.noiseShifted(tables.addNoisePlan(plan), children[0], children[1]);
    }

    /** ShiftB: vanilla samples a (z,x,0) plane with 0.25/0.25/4.0 and broadcasts along Y. */
    private DensityGraphIr.Node transposedNoise(Object source, DensityGraphPayloadCapture tables) {
        Noise noise = capturedNoise(source);
        if (noise == null) {
            throw new IllegalArgumentException("ShiftB sampler exposes no noise stack");
        }
        DensityGraphIr.NoisePlan plan = MinecraftNoisePlanCapture.capture(noise, 0.25, 0.25, 4.0f);
        if (plan == null) {
            throw new IllegalArgumentException("ShiftB noise stack cannot be reproduced natively");
        }
        return DensityGraphIr.Node.noiseTransposed(tables.addNoisePlan(plan));
    }

    private static Noise capturedNoise(Object source) {
        for (var field : source.getClass().getDeclaredFields()) {
            if (!Noise.class.isAssignableFrom(field.getType())) continue;
            try {
                if (!field.trySetAccessible()) return null;
                Object value = field.get(source);
                return value instanceof Noise noise ? noise : null;
            } catch (ReflectiveOperationException failure) {
                return null;
            }
        }
        return null;
    }

    private DensityGraphIr.Node spline(Object source, int[] children,
                                       DensityGraphPayloadCapture tables) {
        Object function = MinecraftSplineAccess.function(source);
        CubicSpline<?> tree = MinecraftSplineAccess.tree(function);
        if (tree == null) {
            Float constant = MinecraftSplineAccess.constant(function);
            if (constant == null) {
                throw new IllegalArgumentException("Spline wrapper exposes no tree");
            }
            return DensityGraphIr.Node.constant(constant);
        }
        List<net.minecraft.world.level.levelgen.densityfunction.DensitySampler> coordinates =
                MinecraftSplineAccess.coordinateSamplers(tree);
        if (coordinates.size() != children.length) {
            throw new IllegalArgumentException(
                    "Spline coordinate count does not match the captured children");
        }
        IdentityHashMap<net.minecraft.world.level.levelgen.densityfunction.DensitySampler, Integer> indices =
                new IdentityHashMap<>(coordinates.size() * 2);
        for (int i = 0; i < coordinates.size(); ++i) {
            indices.put(coordinates.get(i), children[i]);
        }
        return DensityGraphIr.Node.spline(MinecraftSplineAccess.flatten(tree, tables, indices));
    }

    private DensityGraphIr.Node intervalSelect(CompiledSamplerGraphIr.Node node, Object source,
                                               int[] children, DensityGraphPayloadCapture tables) {
        if (children.length < 2) {
            throw new IllegalArgumentException("Interval select needs an input and at least one branch");
        }
        float[] thresholds;
        if (node.sourceClass().equals(SINGLE_THRESHOLD)) {
            String raw = node.scalarValues().get("threshold");
            if (raw == null) throw new IllegalArgumentException("Single threshold is missing");
            thresholds = new float[] {Float.parseFloat(raw)};
        } else if (node.sourceClass().equals(INTERVAL_SELECT)) {
            thresholds = (float[]) read(source, "thresholds");
        } else {
            throw new IllegalArgumentException("Unknown interval select implementation "
                    + node.sourceClass());
        }
        // One input plus one branch per interval, so N thresholds need N + 2 children.
        if (children.length != thresholds.length + 2) {
            throw new IllegalArgumentException("Interval select branch count does not match thresholds:"
                    + " children=" + children.length + " thresholds=" + thresholds.length
                    + " source=" + node.sourceClass());
        }
        int[] branches = new int[children.length - 1];
        System.arraycopy(children, 1, branches, 0, branches.length);
        return DensityGraphIr.Node.intervalSelect(
                tables.addIntervalSelect(children[0], thresholds, branches));
    }

    private Object read(Object target, String name) {
        Class<?> type = target.getClass();
        Method method = methods
                .computeIfAbsent(type, key -> new HashMap<>())
                .computeIfAbsent(name, key -> {
                    try {
                        Method found = type.getDeclaredMethod(key);
                        if (!found.trySetAccessible()) {
                            throw new IllegalStateException("Cannot access " + type.getName() + "." + key);
                        }
                        return found;
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Missing " + type.getName() + "." + key, failure);
                    }
                });
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to read " + type.getName() + "." + name, failure);
        }
    }
}
