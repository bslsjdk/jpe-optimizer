package dev.jpe.optimizer.worldgen;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import dev.jpe.optimizer.mixin.DensityFunctionCompilerAccessor;
import dev.jpe.optimizer.mixin.RandomStateAccessor;
import dev.jpe.optimizer.mixin.NoiseChunkAccessor;
import dev.jpe.optimizer.runtime.NativePerlinNoiseBackend;
import dev.jpe.optimizer.runtime.NativeRuntime;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.synth.Noise;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;

/**
 * Compiles the subset of the actual 26.3 final-density tree that has a verified
 * native implementation. Any unknown/context-dependent node rejects the whole
 * graph, leaving the existing vanilla sampler untouched.
 */
public final class NativeDensityGraphCompiler {
    private static final long CONFIGURATION_FINGERPRINT = 0x4A50452603000001L;
    private static final Object LOCK = new Object();

    // The compiler context is owned by RandomState and remains stable across chunks.
    // Weak keys prevent a closed world's captured samplers from being retained.
    private static final Map<DensityFunction.CompileContext, Map<NoiseFunction, Noise>> CAPTURED_NOISE =
            new WeakHashMap<>();

    private static final ReferenceQueue<RandomState> RANDOM_STATE_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference<RandomState>, StateGraphs> GRAPHS = new HashMap<>();

    private record GraphHandle(NativeRuntime runtime, long handle) {}

    private static final class StateGraphs {
        private final IdentityHashMap<DensityFunction, GraphHandle> handles = new IdentityHashMap<>();
        private final Set<DensityFunction> disabled =
                Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static final class IdentityWeakReference<T> extends WeakReference<T> {
        private final int identityHash;

        private IdentityWeakReference(T value, ReferenceQueue<T> queue) {
            super(value, queue);
            identityHash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference<?> reference)) return false;
            Object left = get();
            return left != null && left == reference.get();
        }
    }

    private NativeDensityGraphCompiler() {}

    /** Called from NoiseFunction.compileSampler after vanilla creates its seed-bound Noise. */
    public static void captureNoise(
            NoiseFunction function, DensityFunction.CompileContext context, Noise noise
    ) {
        synchronized (LOCK) {
            CAPTURED_NOISE.computeIfAbsent(context, ignored -> new HashMap<>()).put(function, noise);
        }
    }

    /**
     * Returns a pooled density buffer filled by one native DAG evaluation, or
     * null when the graph contains an unsupported node or native execution fails.
     */
    public static ScopedDensityBuffer trySampleVolume(
            DensityFunction root, NoiseChunk noiseChunk, DensityVolume volume
    ) {
        NativeRuntime runtime = NativePerlinNoiseBackend.runtimeForDensityGraph();
        if (runtime == null) return null;

        NoiseChunkAccessor noiseChunkAccess = (NoiseChunkAccessor) (Object) noiseChunk;
        RandomState randomState = noiseChunkAccess.jpe$getRandomState();
        DensityFunction.CompileContext context = ((DensityFunctionCompilerAccessor)
                (Object) ((RandomStateAccessor) (Object) randomState).jpe$getDensityFunctionCompiler())
                .jpe$getContext();
        // BlendDensityFunction is a pass-through only for a genuinely empty Blender.
        // Non-empty legacy chunks must use vanilla's context-bound blend sampler.
        if (!noiseChunkAccess.jpe$getBlender().isEmpty()) return null;
        long handle = graphHandle(root, randomState, context, true, runtime);
        if (handle == 0L) return null;

        DensityBufferPool pool = noiseChunkAccess.jpe$getBufferPool();
        ScopedDensityBuffer output = pool.acquire(volume.size());
        try {
            runtime.evaluateDensityGraphF32(
                    handle,
                    volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                    ((DensityBufferAccessor) output).jpe$values()
            );
            return output;
        } catch (RuntimeException | LinkageError failure) {
            output.close();
            disableGraph(root, randomState, runtime);
            return null;
        }
    }

    private static long graphHandle(
            DensityFunction root,
            RandomState randomState,
            DensityFunction.CompileContext context,
            boolean blenderEmpty,
            NativeRuntime runtime
    ) {
        synchronized (LOCK) {
            drainQueues();
            IdentityWeakReference<RandomState> lookup = new IdentityWeakReference<>(randomState, null);
            StateGraphs stateGraphs = GRAPHS.get(lookup);
            if (stateGraphs == null) {
                stateGraphs = new StateGraphs();
                GRAPHS.put(new IdentityWeakReference<>(randomState, RANDOM_STATE_QUEUE), stateGraphs);
            }
            if (stateGraphs.disabled.contains(root)) return 0L;

            GraphHandle existing = stateGraphs.handles.get(root);
            if (existing != null) {
                if (existing.runtime() == runtime) return existing.handle();
                stateGraphs.handles.remove(root);
                existing.runtime().releaseDensityGraph(existing.handle());
            }

            Builder builder = new Builder(context, blenderEmpty);
            int rootIndex;
            try {
                rootIndex = builder.lower(root);
            } catch (RuntimeException unsupported) {
                stateGraphs.disabled.add(root);
                return 0L;
            }

            if (rootIndex < 0 || builder.nodes.isEmpty()) {
                stateGraphs.disabled.add(root);
                return 0L;
            }

            try {
                long handle = runtime.compileDensityGraph(builder.nodes, rootIndex, CONFIGURATION_FINGERPRINT);
                if (handle == 0L) {
                    stateGraphs.disabled.add(root);
                    return 0L;
                }
                stateGraphs.handles.put(root, new GraphHandle(runtime, handle));
                return handle;
            } catch (RuntimeException rejected) {
                stateGraphs.disabled.add(root);
                return 0L;
            }
        }
    }

    private static void disableGraph(DensityFunction root, RandomState randomState, NativeRuntime runtime) {
        synchronized (LOCK) {
            StateGraphs stateGraphs = GRAPHS.get(new IdentityWeakReference<>(randomState, null));
            if (stateGraphs == null) return;
            GraphHandle existing = stateGraphs.handles.remove(root);
            if (existing != null) existing.runtime().releaseDensityGraph(existing.handle());
            stateGraphs.disabled.add(root);
        }
    }

    @SuppressWarnings("unchecked")
    private static void drainQueues() {
        IdentityWeakReference<RandomState> expired;
        while ((expired = (IdentityWeakReference<RandomState>) RANDOM_STATE_QUEUE.poll()) != null) {
            StateGraphs stateGraphs = GRAPHS.remove(expired);
            if (stateGraphs != null) {
                for (GraphHandle graph : stateGraphs.handles.values()) {
                    graph.runtime().releaseDensityGraph(graph.handle());
                }
                stateGraphs.handles.clear();
                stateGraphs.disabled.clear();
            }
        }
    }

    private static Object component(Object value, String name) {
        if (value == null || !value.getClass().isRecord()) {
            throw new UnsupportedOperationException("Expected record component " + name);
        }
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            if (!component.getName().equals(name)) continue;
            try {
                return component.getAccessor().invoke(value);
            } catch (ReflectiveOperationException failure) {
                throw new UnsupportedOperationException("Unable to read density component " + name, failure);
            }
        }
        throw new UnsupportedOperationException("Missing density component " + name);
    }

    private static final class Builder {
        private final DensityFunction.CompileContext context;
        private final boolean blenderEmpty;
        private final List<NativeRuntime.DensityNode> nodes = new ArrayList<>();
        private final IdentityHashMap<DensityFunction, Integer> indices = new IdentityHashMap<>();
        private final IdentityHashMap<DensityFunction, Boolean> active = new IdentityHashMap<>();

        private Builder(DensityFunction.CompileContext context, boolean blenderEmpty) {
            this.context = context;
            this.blenderEmpty = blenderEmpty;
        }

        private int append(NativeRuntime.DensityNode node) {
            if (nodes.size() >= 65_536) throw new UnsupportedOperationException("Density graph exceeds native node limit");
            int index = nodes.size();
            nodes.add(node);
            return index;
        }

        private int lower(DensityFunction function) {
            if (function == null) throw new UnsupportedOperationException("Null density function");
            Integer existing = indices.get(function);
            if (existing != null) return existing;
            if (active.put(function, Boolean.TRUE) != null) {
                throw new UnsupportedOperationException("Cyclic density graph");
            }

            try {
                String kind = function.getClass().getSimpleName();
                int result;
                switch (kind) {
                    case "HolderHolder" -> {
                        Holder<DensityFunction> holder = (Holder<DensityFunction>) component(function, "function");
                        result = lower(holder.value());
                    }
                    case "CacheFunction" -> result = lower((DensityFunction) component(function, "input"));
                    case "BlendDensityFunction" -> {
                        if (!blenderEmpty) {
                            throw new UnsupportedOperationException("Legacy chunk blending requires a native blender node");
                        }
                        result = lower((DensityFunction) component(function, "input"));
                    }
                    case "ConstantFunction" -> {
                        Number value = (Number) component(function, "value");
                        result = append(NativeRuntime.DensityNode.constant(value.floatValue()));
                    }
                    case "BinaryFunction" -> {
                        String operation = component(function, "type").toString();
                        int left = lower((DensityFunction) component(function, "left"));
                        int right = lower((DensityFunction) component(function, "right"));
                        NativeRuntime.DensityNode node = switch (operation) {
                            case "ADD" -> NativeRuntime.DensityNode.add(left, right);
                            case "SUB" -> NativeRuntime.DensityNode.subtract(left, right);
                            case "MUL" -> NativeRuntime.DensityNode.multiply(left, right);
                            case "DIV" -> NativeRuntime.DensityNode.divide(left, right);
                            case "MIN" -> NativeRuntime.DensityNode.min(left, right);
                            case "MAX" -> NativeRuntime.DensityNode.max(left, right);
                            default -> throw new UnsupportedOperationException("Unsupported binary density op " + operation);
                        };
                        result = append(node);
                    }
                    case "UnaryFunction" -> {
                        String operation = component(function, "type").toString();
                        int input = lower((DensityFunction) component(function, "input"));
                        NativeRuntime.DensityNode node = switch (operation) {
                            case "ABS" -> NativeRuntime.DensityNode.abs(input);
                            case "NEGATE" -> NativeRuntime.DensityNode.negate(input);
                            case "SQUARE" -> NativeRuntime.DensityNode.square(input);
                            case "CUBE" -> NativeRuntime.DensityNode.cube(input);
                            case "SQRT" -> NativeRuntime.DensityNode.sqrt(input);
                            case "HALF_NEGATIVE" -> NativeRuntime.DensityNode.halfNegative(input);
                            case "QUARTER_NEGATIVE" -> NativeRuntime.DensityNode.quarterNegative(input);
                            case "RECIPROCAL" -> NativeRuntime.DensityNode.reciprocal(input);
                            case "SIGN" -> NativeRuntime.DensityNode.sign(input);
                            case "SQUEEZE" -> NativeRuntime.DensityNode.squeeze(input);
                            default -> throw new UnsupportedOperationException("Unsupported unary density op " + operation);
                        };
                        result = append(node);
                    }
                    case "LerpFunction" -> {
                        int alpha = lower((DensityFunction) component(function, "alpha"));
                        int first = lower((DensityFunction) component(function, "first"));
                        int second = lower((DensityFunction) component(function, "second"));
                        result = append(NativeRuntime.DensityNode.lerp(alpha, first, second));
                    }
                    case "GradientFunction" -> {
                        String axisName = component(function, "axis").toString();
                        String tilingName = component(function, "tiling").toString();
                        if (!tilingName.equals("CLAMP_TO_EDGE")) {
                            throw new UnsupportedOperationException("Unsupported gradient tiling " + tilingName);
                        }
                        int axis = switch (axisName) {
                            case "X" -> 0;
                            case "Y" -> 1;
                            case "Z" -> 2;
                            default -> throw new UnsupportedOperationException("Unknown gradient axis " + axisName);
                        };
                        int fromCoordinate = ((Number) component(function, "fromCoordinate")).intValue();
                        int toCoordinate = ((Number) component(function, "toCoordinate")).intValue();
                        float fromValue = ((Number) component(function, "fromValue")).floatValue();
                        float toValue = ((Number) component(function, "toValue")).floatValue();
                        result = append(NativeRuntime.DensityNode.gradient(
                                axis, fromCoordinate, toCoordinate, fromValue, toValue));
                    }
                    case "PowFunction" -> {
                        int base = lower((DensityFunction) component(function, "base"));
                        int exponent = lower((DensityFunction) component(function, "exponent"));
                        result = append(NativeRuntime.DensityNode.pow(base, exponent));
                    }
                    case "ClampFunction" -> {
                        int input = lower((DensityFunction) component(function, "input"));
                        float minimum = ((Number) component(function, "min")).floatValue();
                        float maximum = ((Number) component(function, "max")).floatValue();
                        result = append(NativeRuntime.DensityNode.clamp(input, minimum, maximum));
                    }
                    case "NoiseFunction" -> {
                        if (!DensityFunctions.zero().equals(component(function, "shiftX"))
                                || !DensityFunctions.zero().equals(component(function, "shiftY"))
                                || !DensityFunctions.zero().equals(component(function, "shiftZ"))) {
                            throw new UnsupportedOperationException("Domain-warped noise is not yet lowered");
                        }
                        Noise noise;
                        synchronized (LOCK) {
                            Map<NoiseFunction, Noise> captured = CAPTURED_NOISE.get(context);
                            noise = captured == null ? null : captured.get((NoiseFunction) function);
                        }
                        long preparedHandle = NativePerlinNoiseBackend.preparedStackHandleForDensityGraph(noise);
                        if (preparedHandle == 0L) {
                            throw new UnsupportedOperationException("Noise sampler is not a supported Perlin stack");
                        }
                        double xzScale = ((Number) component(function, "xzScale")).doubleValue();
                        double yScale = ((Number) component(function, "yScale")).doubleValue();
                        result = append(NativeRuntime.DensityNode.perlinStack(
                                preparedHandle, xzScale, yScale, 1.0F));
                    }
                    default -> throw new UnsupportedOperationException("Unsupported density node " + kind);
                }
                indices.put(function, result);
                return result;
            } finally {
                active.remove(function);
            }
        }
    }
}
