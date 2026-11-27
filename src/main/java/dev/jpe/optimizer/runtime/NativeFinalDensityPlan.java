package dev.jpe.optimizer.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One compiled native plan for a complete density graph.
 *
 * <p>This is the execution half of the {@code doFill} handoff: the graph is
 * compiled once, then every chunk volume is one bulk evaluate call. It is
 * Minecraft-free on purpose, so the compile/evaluate/compare path can be
 * exercised without the game.
 *
 * <p>The caller owns the decision to trust the output. {@link #compare} reports
 * raw bit mismatches against the Java volume; nothing here commits values into
 * a chunk.
 */
public final class NativeFinalDensityPlan implements AutoCloseable {
    /** Volume geometry in Minecraft's {@code min + index * step} convention. */
    public record Geometry(int sizeX, int sizeY, int sizeZ,
                           int minX, int minY, int minZ,
                           int stepX, int stepY, int stepZ) {
        public Geometry {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("Volume dimensions must be positive");
            }
            long samples = (long) sizeX * sizeY * sizeZ;
            if (samples > 1_048_576L) {
                throw new IllegalArgumentException("Volume exceeds the 1,048,576 sample limit");
            }
        }
        public int sampleCount() { return sizeX * sizeY * sizeZ; }
    }

    /** Bit-level comparison result for one volume. */
    public record Parity(int compared, int mismatches, int firstMismatch,
                         float javaValue, float nativeValue) {
        public boolean exact() { return mismatches == 0; }
    }

    private final Arena arena;
    private final NativeDensityProgramBridge bridge;
    private final MethodHandle evaluate;
    private final MethodHandle evaluateMulti;
    private final MethodHandle release;
    private final long handle;
    private final long configurationFingerprint;
    private final int nodeCount;
    private volatile boolean closed;

    private NativeFinalDensityPlan(Arena arena, NativeDensityProgramBridge bridge,
                                   MethodHandle evaluate, MethodHandle evaluateMulti,
                                   MethodHandle release,
                                   long handle, long configurationFingerprint, int nodeCount) {
        this.arena = arena;
        this.bridge = bridge;
        this.evaluate = evaluate;
        this.evaluateMulti = evaluateMulti;
        this.release = release;
        this.handle = handle;
        this.configurationFingerprint = configurationFingerprint;
        this.nodeCount = nodeCount;
    }

    /** Locates the runtime library the same way the test and the mod do. */
    public static Path defaultLibrary() {
        // A packaged jar carries the cdylib under META-INF/natives; the loader
        // also honours -Djpe.nativeLibraryPath and a local target/ build.
        return NativeLibraryLoader.resolve();
    }

    /** Compiles {@code graph} and retains the native plan until {@link #close()}. */
    public static NativeFinalDensityPlan compile(Path library, DensityGraphIr graph) {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(graph, "graph");
        if (graph.experimental()) {
            throw new IllegalArgumentException("A plan for chunk output must not contain OPAQUE nodes");
        }
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
            Linker linker = Linker.nativeLinker();
            NativeDensityProgramBridge bridge = NativeDensityProgramBridge.bind(lookup, linker);
            MethodHandle evaluate = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_evaluate").orElseThrow(() ->
                            new IllegalStateException("Missing density graph evaluate symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MethodHandle evaluateMulti = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_evaluate_multi_with_inputs").orElseThrow(() ->
                            new IllegalStateException("Missing density graph multi-evaluate symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MethodHandle release = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_release").orElseThrow(() ->
                            new IllegalStateException("Missing density graph release symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

            long handle;
            try (Arena scratch = Arena.ofConfined()) {
                byte[] encoded = encodeNodes(graph.nodes());
                MemorySegment nodes = scratch.allocate(encoded.length, 8);
                MemorySegment.copy(encoded, 0, nodes, ValueLayout.JAVA_BYTE, 0, encoded.length);
                MemorySegment plans = encodeNoisePlans(scratch, graph.payload().noisePlans());
                handle = bridge.compile(nodes, graph.nodes().size(), graph.rootNode(),
                        graph.configurationFingerprint(),
                        plans, graph.payload().noisePlans().size(),
                        DensityGraphProgram.from(graph.payload()));
            }
            return new NativeFinalDensityPlan(arena, bridge, evaluate, evaluateMulti, release, handle,
                    graph.configurationFingerprint(), graph.nodes().size());
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Unable to compile the native final-density plan", failure);
        }
    }

    public long configurationFingerprint() { return configurationFingerprint; }
    /** Number of native nodes in the compiled program. */
    public int nodeCount() { return nodeCount; }
    public long handle() { return handle; }

    /** Evaluates the whole volume. The returned array is owned by the caller. */
    public float[] evaluate(Geometry geometry) {
        Objects.requireNonNull(geometry, "geometry");
        if (closed) throw new IllegalStateException("Plan is closed");
        float[] output = new float[geometry.sampleCount()];
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment buffer = scratch.allocate((long) output.length * Float.BYTES, 8);
            int status;
            try {
                status = (int) evaluate.invokeExact(handle,
                        geometry.sizeX(), geometry.sizeY(), geometry.sizeZ(),
                        geometry.minX(), geometry.minY(), geometry.minZ(),
                        geometry.stepX(), geometry.stepY(), geometry.stepZ(),
                        buffer, (long) output.length);
            } catch (Throwable failure) {
                throw new IllegalStateException("Native density evaluation failed", failure);
            }
            if (status != 0) {
                throw new IllegalStateException("Native density evaluation returned status " + status);
            }
            MemorySegment.copy(buffer, ValueLayout.JAVA_FLOAT, 0, output, 0, output.length);
        }
        return output;
    }

    /**
     * Evaluates several node subtrees over one volume in a single native call.
     * Diagnostic only: it is how a mismatch is traced back to a single node.
     */
    public float[][] evaluateNodes(int[] nodeIds, Geometry geometry) {
        Objects.requireNonNull(nodeIds, "nodeIds");
        if (closed) throw new IllegalStateException("Plan is closed");
        if (nodeIds.length == 0) return new float[0][];
        int samples = geometry.sampleCount();
        float[][] result = new float[nodeIds.length][samples];
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment roots = scratch.allocate((long) nodeIds.length * Integer.BYTES, 4);
            for (int i = 0; i < nodeIds.length; ++i) {
                roots.setAtIndex(ValueLayout.JAVA_INT, i, nodeIds[i]);
            }
            MemorySegment buffer = scratch.allocate((long) nodeIds.length * samples * Float.BYTES, 8);
            int status;
            try {
                status = (int) evaluateMulti.invokeExact(handle,
                        geometry.sizeX(), geometry.sizeY(), geometry.sizeZ(),
                        geometry.minX(), geometry.minY(), geometry.minZ(),
                        geometry.stepX(), geometry.stepY(), geometry.stepZ(),
                        MemorySegment.NULL, 0,
                        roots, nodeIds.length,
                        buffer, (long) nodeIds.length * samples);
            } catch (Throwable failure) {
                throw new IllegalStateException("Native node evaluation failed", failure);
            }
            if (status != 0) {
                throw new IllegalStateException("Native node evaluation returned status " + status);
            }
            for (int i = 0; i < nodeIds.length; ++i) {
                // The segment offset is a byte offset, not an element index.
                MemorySegment.copy(buffer, ValueLayout.JAVA_FLOAT,
                        (long) i * samples * Float.BYTES, result[i], 0, samples);
            }
        }
        return result;
    }

    /** Evaluates the volume and compares it with a Java-produced one, bit for bit. */
    public Parity compare(float[] javaVolume, Geometry geometry) {
        return compare(javaVolume, evaluate(geometry));
    }

    /** Compares two already-evaluated volumes bit for bit. */
    public Parity compare(float[] javaVolume, float[] nativeVolume) {
        if (javaVolume.length != nativeVolume.length) {
            throw new IllegalArgumentException("Java volume length does not match the geometry");
        }
        int mismatches = 0;
        int first = -1;
        float javaValue = 0.0f;
        float nativeValue = 0.0f;
        for (int i = 0; i < javaVolume.length; ++i) {
            if (Float.floatToRawIntBits(javaVolume[i]) != Float.floatToRawIntBits(nativeVolume[i])) {
                if (first < 0) {
                    first = i;
                    javaValue = javaVolume[i];
                    nativeValue = nativeVolume[i];
                }
                ++mismatches;
            }
        }
        return new Parity(javaVolume.length, mismatches, first, javaValue, nativeValue);
    }

    // ---- Noise plan encoding -------------------------------------------------
    /** struct JpeNoisePlan: const JpePerlinLayer *layers; u32 layer_count; u32 flags. */
    static final long NOISE_PLAN_BYTES = 16L;
    /** struct JpePerlinLayer: pointer, five doubles, float, double, two u32, double. */
    static final long PERLIN_LAYER_BYTES = 80L;

    /**
     * Writes the plan array and one layer array per plan into {@code arena}.
     * The native compiler copies every permutation, so the arena may be closed
     * as soon as {@code compile} returns.
     */
    static MemorySegment encodeNoisePlans(Arena arena, java.util.List<DensityGraphIr.NoisePlan> plans) {
        if (plans.isEmpty()) return MemorySegment.NULL;
        int totalLayers = 0;
        for (DensityGraphIr.NoisePlan plan : plans) totalLayers += plan.layers().size();
        MemorySegment planArray = arena.allocate(NOISE_PLAN_BYTES * plans.size(), 8);
        MemorySegment layerArray = arena.allocate(PERLIN_LAYER_BYTES * totalLayers, 8);
        MemorySegment permutations = arena.allocate(256L * totalLayers, 8);

        int layerIndex = 0;
        for (int slot = 0; slot < plans.size(); ++slot) {
            DensityGraphIr.NoisePlan plan = plans.get(slot);
            for (DensityGraphIr.NoiseLayer layer : plan.layers()) {
                long base = PERLIN_LAYER_BYTES * layerIndex;
                long permutationOffset = 256L * layerIndex;
                MemorySegment.copy(layer.permutation(), 0, permutations,
                        ValueLayout.JAVA_BYTE, permutationOffset, 256);
                layerArray.set(ValueLayout.ADDRESS, base, permutations.asSlice(permutationOffset, 256));
                layerArray.set(ValueLayout.JAVA_DOUBLE, base + 8L, layer.offsetX());
                layerArray.set(ValueLayout.JAVA_DOUBLE, base + 16L, layer.offsetY());
                layerArray.set(ValueLayout.JAVA_DOUBLE, base + 24L, layer.offsetZ());
                layerArray.set(ValueLayout.JAVA_DOUBLE, base + 32L, layer.xzScale());
                layerArray.set(ValueLayout.JAVA_DOUBLE, base + 40L, layer.yScale());
                layerArray.set(ValueLayout.JAVA_FLOAT, base + 48L, layer.amplitude());
                layerArray.set(ValueLayout.JAVA_DOUBLE, base + 56L, layer.fudgeYScale());
                layerArray.set(ValueLayout.JAVA_INT, base + 64L, layer.smeared() ? 1 : 0);
                layerArray.set(ValueLayout.JAVA_INT, base + 68L, 0);
                layerArray.set(ValueLayout.JAVA_DOUBLE, base + 72L, layer.frequency());
                ++layerIndex;
            }
            long planBase = NOISE_PLAN_BYTES * slot;
            int firstLayer = layerIndex - plan.layers().size();
            planArray.set(ValueLayout.ADDRESS, planBase,
                    layerArray.asSlice(PERLIN_LAYER_BYTES * firstLayer));
            planArray.set(ValueLayout.JAVA_INT, planBase + 8L, plan.layers().size());
            planArray.set(ValueLayout.JAVA_INT, planBase + 12L, 0);
        }
        return planArray;
    }

    /**
     * FNV-1a over every payload byte. The native cache keys on this value plus a
     * full payload compare, so two worlds with different permutations can never
     * share a compiled plan.
     */
    public static long payloadFingerprint(DensityGraphIr.Payload payload) {
        long hash = 1469598103934665603L;
        for (DensityGraphIr.NoisePlan plan : payload.noisePlans()) {
            for (DensityGraphIr.NoiseLayer layer : plan.layers()) {
                for (byte value : layer.permutation()) {
                    hash ^= value & 0xffL;
                    hash *= 1099511628211L;
                }
                hash = mix(hash, Double.doubleToRawLongBits(layer.offsetX()));
                hash = mix(hash, Double.doubleToRawLongBits(layer.offsetY()));
                hash = mix(hash, Double.doubleToRawLongBits(layer.offsetZ()));
                hash = mix(hash, Double.doubleToRawLongBits(layer.xzScale()));
                hash = mix(hash, Double.doubleToRawLongBits(layer.yScale()));
                hash = mix(hash, Float.floatToRawIntBits(layer.amplitude()));
                hash = mix(hash, Double.doubleToRawLongBits(layer.fudgeYScale()));
                hash = mix(hash, layer.smeared() ? 1 : 0);
                hash = mix(hash, Double.doubleToRawLongBits(layer.frequency()));
            }
            hash = mix(hash, 0x9e3779b97f4a7c15L);
        }
        for (DensityGraphIr.SplinePoint point : payload.splinePoints()) {
            hash = mix(hash, Float.floatToRawIntBits(point.location()));
            hash = mix(hash, Float.floatToRawIntBits(point.derivative()));
            hash = mix(hash, point.child());
        }
        for (DensityGraphIr.SplineNode node : payload.splines()) {
            hash = mix(hash, node.kind());
            hash = mix(hash, node.coordinate());
            hash = mix(hash, Float.floatToRawIntBits(node.constant()));
            hash = mix(hash, node.firstPoint());
            hash = mix(hash, node.pointCount());
        }
        for (float threshold : payload.intervalThresholds()) {
            hash = mix(hash, Float.floatToRawIntBits(threshold));
        }
        for (int branch : payload.intervalBranches()) hash = mix(hash, branch);
        for (DensityGraphIr.IntervalSelect select : payload.intervalSelects()) {
            hash = mix(hash, select.inputNode());
            hash = mix(hash, select.firstThreshold());
            hash = mix(hash, select.thresholdCount());
            hash = mix(hash, select.firstBranch());
        }
        for (DensityGraphIr.DistancePoint point : payload.distancePoints()) {
            hash = mix(hash, point.x());
            hash = mix(hash, point.y());
            hash = mix(hash, point.z());
            hash = mix(hash, point.metric());
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        hash *= 1099511628211L;
        return hash;
    }

    static byte[] encodeNodes(java.util.List<DensityGraphIr.Node> nodes) {        java.nio.ByteBuffer buffer = java.nio.ByteBuffer
                .allocate(nodes.size() * 24)
                .order(java.nio.ByteOrder.nativeOrder());
        for (DensityGraphIr.Node node : nodes) {
            buffer.putInt(node.opcode());
            // NO_INPUT is -1, which is already 0xffffffff. Do not collapse every
            // negative value: gradient nodes store raw negative coordinates here.
            buffer.putInt(node.inputA());
            buffer.putInt(node.inputB());
            buffer.putInt(node.opcode() == DensityGraphIr.OPAQUE ? 1 : node.reserved());
            buffer.putDouble(node.immediate());
        }
        return buffer.array();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            release.invokeExact(handle);
        } catch (Throwable ignored) {
            // The arena close below still releases the library; a failed release
            // only leaves the plan to the runtime's own cache eviction.
        } finally {
            bridge.close();
            arena.close();
        }
    }
}
