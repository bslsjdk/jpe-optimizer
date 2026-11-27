package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

/**
 * Native handoff at the final-density volume boundary.
 *
 * <p>Modes, selected with {@code -Djpe.nativeFinalDensity=}:
 * <ul>
 *   <li>{@code off} (default): no work at all</li>
 *   <li>{@code shadow}: compile and compare against the Java volume, never commit</li>
 *   <li>{@code commit}: warm up by comparing, then let native fill the volume and
 *       skip the Java sampler entirely for every later chunk</li>
 * </ul>
 *
 * <p>The warm-up exists because one chunk is not evidence: the first
 * {@code -Djpe.nativeFinalDensityWarmup} chunks (default 8) run both paths and are
 * compared bit for bit. Only after that many exact volumes does the plan become
 * authoritative. A single mismatch disables the plan permanently and logs the
 * first differing sample, so a bad lowering can never reach block placement.
 *
 * <p>Chunks whose bound context carries an active {@code Blender} always stay on
 * Java: the blend region is per-chunk state that the compiled plan does not
 * carry, and the plan is built on the assumption that blending is the identity.
 */
public final class NativeFinalDensityHooks {
    private static final System.Logger LOGGER =
            System.getLogger(NativeFinalDensityHooks.class.getName());
    private static final String MODE =
            System.getProperty("jpe.nativeFinalDensity", "off").trim().toLowerCase(java.util.Locale.ROOT);
    private static final boolean SHADOW = MODE.equals("shadow") || MODE.equals("commit");
    private static final boolean COMMIT = MODE.equals("commit");
    private static final int WARMUP = Math.max(0,
            Integer.getInteger("jpe.nativeFinalDensityWarmup", 8));
    private static final int MAX_TRACKED_SAMPLERS = 8;

    private static final Map<Object, State> STATES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static final class State {
        NativeFinalDensityPlan plan;
        DensityGraphIr graph;
        MinecraftSamplerInventory.Inventory inventory;
        int[] sourceNodes = new int[0];
        String fingerprint = "";
        boolean attempted;
        boolean nativeOnly;
        long verifiedVolumes;
        long mismatchedVolumes;
        long skippedBlended;
        long nativeVolumes;
    }

    private NativeFinalDensityHooks() {}

    public static boolean enabled() {
        return SHADOW;
    }

    /** True once this sampler's plan is trusted to replace the Java volume outright. */
    public static boolean nativeAuthoritative(DensitySampler.Bound bound) {
        if (!COMMIT) return false;
        State state = STATES.get(bound.sampler());
        return state != null && state.nativeOnly && state.plan != null;
    }

    /**
     * Called before the Java sampler runs. Returns a buffer filled natively when
     * the plan is trusted for this chunk, or {@code null} to let vanilla fill it.
     * The caller owns the returned buffer and must close it.
     */
    public static ScopedDensityBuffer tryNativeVolume(DensitySampler.Bound bound, DensityVolume volume) {
        if (!COMMIT) return null;
        DensitySampler sampler = bound.sampler();
        State state;
        synchronized (STATES) {
            state = STATES.get(sampler);
        }
        if (state == null) {
            // First chunk for this sampler: prepare while Java still fills the buffer.
            state = stateFor(sampler);
            if (state == null) return null;
            state.attempted = true;
            prepare(bound, state);
            return null;
        }
        if (!state.nativeOnly || state.plan == null) return null;
        if (!blendIsIdentity(bound)) {
            state.skippedBlended++;
            return null;
        }
        NativeFinalDensityPlan.Geometry geometry = geometry(volume);
        if (geometry == null) return null;
        float[] values;
        try {
            values = state.plan.evaluate(geometry);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Native final-density evaluation failed; reverting to Java", failure);
            state.plan = null;
            return null;
        }
        ScopedDensityBuffer buffer = bound.context().acquireBuffer(volume);
        float[] target = ((DensityBufferAccessor) buffer).jpe$values();
        if (target.length < values.length) {
            buffer.close();
            return null;
        }
        System.arraycopy(values, 0, target, 0, values.length);
        state.nativeVolumes++;
        return buffer;
    }

    /** Called after the Java sampler ran, in shadow and warm-up modes. */
    public static void afterJavaSample(DensitySampler.Bound bound, DensityVolume volume, DensityBuffer buffer) {
        if (!SHADOW) return;
        DensitySampler sampler = bound.sampler();
        State state;
        synchronized (STATES) {
            state = STATES.get(sampler);
            if (state == null) {
                if (STATES.size() >= MAX_TRACKED_SAMPLERS) return;
                state = new State();
                STATES.put(sampler, state);
            }
        }
        if (!state.attempted) {
            state.attempted = true;
            prepare(bound, state);
        }
        if (state.plan == null) return;
        if (!blendIsIdentity(bound)) {
            state.skippedBlended++;
            return;
        }

        NativeFinalDensityPlan.Geometry geometry = geometry(volume);
        if (geometry == null) return;
        float[] javaValues = ((DensityBufferAccessor) buffer).jpe$values();
        if (javaValues.length < geometry.sampleCount()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Final-density buffer is shorter than the volume; native handoff skipped");
            return;
        }

        NativeFinalDensityPlan.Parity parity;
        float[] nativeValues;
        try {
            nativeValues = state.plan.evaluate(geometry);
            parity = state.plan.compare(javaValues, nativeValues);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Native final-density evaluation failed", failure);
            state.plan = null;
            return;
        }
        if (!parity.exact()) {
            state.mismatchedVolumes++;
            LOGGER.log(System.Logger.Level.ERROR,
                    "Native final-density mismatch: compared={0} mismatches={1} first={2} java={3} native={4};"
                            + " plan disabled for this sampler",
                    parity.compared(), parity.mismatches(), parity.firstMismatch(),
                    Float.toString(parity.javaValue()), Float.toString(parity.nativeValue()));
            if (Boolean.getBoolean("jpe.nativeFinalDensityDebug")) {
                diagnoseVolume(geometry, javaValues, nativeValues);
            }
            if (Boolean.getBoolean("jpe.nativeFinalDensityNodeProbe")) {
                diagnoseNodes(state, geometry, parity.firstMismatch(), bound);
            }
            state.plan = null;
            state.nativeOnly = false;
            return;
        }
        state.verifiedVolumes++;
        if (COMMIT && !state.nativeOnly && state.verifiedVolumes >= WARMUP) {
            state.nativeOnly = true;
            LOGGER.log(System.Logger.Level.INFO,
                    "Native final-density now authoritative: fingerprint={0} verified={1} nodes={2}",
                    state.fingerprint, state.verifiedVolumes, state.plan.nodeCount());
        }
        LOGGER.log(System.Logger.Level.DEBUG,
                "Native final-density parity ok: fingerprint={0} samples={1} verified={2} nativeOnly={3}",
                state.fingerprint, parity.compared(), state.verifiedVolumes, state.nativeOnly);
    }

    /**
     * Characterises a mismatching volume instead of dumping 98k floats: how large the
     * differences are, and where they sit. The position histogram is the part that
     * actually names the fault — a mismatch confined to {@code x%4==3} is interpolation
     * cell handling, one confined to a handful of Y values is a slice/rounding fault,
     * and a uniform sprinkle of sub-ulp differences is arithmetic order.
     */
    private static void diagnoseVolume(NativeFinalDensityPlan.Geometry geometry,
                                       float[] javaValues, float[] nativeValues) {
        int sizeX = geometry.sizeX();
        int sizeY = geometry.sizeY();
        int sizeZ = geometry.sizeZ();
        int mismatches = 0;
        int nanJava = 0;
        int nanNative = 0;
        int exactMagnitude = 0;
        int tiny = 0;
        int small = 0;
        int large = 0;
        float maxDelta = 0.0f;
        int maxIndex = -1;
        int[] perY = new int[sizeY];
        int[] perCellX = new int[8];
        int[] perCellZ = new int[8];
        StringBuilder first = new StringBuilder();
        int shown = 0;
        for (int index = 0; index < javaValues.length; ++index) {
            float java = javaValues[index];
            float nativeValue = nativeValues[index];
            if (Float.floatToRawIntBits(java) == Float.floatToRawIntBits(nativeValue)) continue;
            ++mismatches;
            boolean javaNan = Float.isNaN(java);
            boolean nativeNan = Float.isNaN(nativeValue);
            if (javaNan) ++nanJava;
            if (nativeNan) ++nanNative;
            float delta = javaNan || nativeNan ? Float.NaN : Math.abs(java - nativeValue);
            if (!Float.isNaN(delta)) {
                if (delta == 0.0f) ++exactMagnitude;
                else if (delta < 1.0e-6f) ++tiny;
                else if (delta < 1.0e-3f) ++small;
                else ++large;
                if (delta > maxDelta) {
                    maxDelta = delta;
                    maxIndex = index;
                }
            }
            int plane = index / sizeY;
            int y = index % sizeY;
            int x = plane % sizeX;
            int z = plane / sizeX;
            ++perY[y];
            ++perCellX[x & 7];
            ++perCellZ[z & 7];
            if (shown < 8) {
                first.append('[').append(geometry.minX() + x * geometry.stepX()).append(',')
                        .append(geometry.minY() + y * geometry.stepY()).append(',')
                        .append(geometry.minZ() + z * geometry.stepZ()).append("] java=")
                        .append(Float.toString(java)).append(" native=")
                        .append(Float.toString(nativeValue)).append(' ');
                ++shown;
            }
        }
        LOGGER.log(System.Logger.Level.INFO,
                "Mismatch shape: volume={0}x{1}x{2} step=({3},{4},{5}) mismatches={6}"
                        + " magnitude[exactZero={7} <1e-6={8} <1e-3={9} large={10}]"
                        + " maxDelta={11} maxAt={12} nan[java={13} native={14}]",
                sizeX, sizeY, sizeZ, geometry.stepX(), geometry.stepY(), geometry.stepZ(),
                mismatches, exactMagnitude, tiny, small, large,
                Float.toString(maxDelta), maxIndex < 0 ? "-" : describe(geometry, maxIndex),
                nanJava, nanNative);
        LOGGER.log(System.Logger.Level.INFO, "First mismatches: {0}", first.toString().trim());
        LOGGER.log(System.Logger.Level.INFO, "Mismatches per Y (blockY=count): {0}",
                histogram(perY, geometry.minY(), geometry.stepY()));
        LOGGER.log(System.Logger.Level.INFO, "Mismatches per x%8: {0}  per z%8: {1}",
                java.util.Arrays.toString(perCellX), java.util.Arrays.toString(perCellZ));
        if (maxIndex >= 0) {
            LOGGER.log(System.Logger.Level.INFO, "Largest delta at {0}", describe(geometry, maxIndex));
        }
    }

    private static String describe(NativeFinalDensityPlan.Geometry geometry, int index) {
        int sizeY = geometry.sizeY();
        int plane = index / sizeY;
        int y = index % sizeY;
        int x = plane % geometry.sizeX();
        int z = plane / geometry.sizeX();
        return "(" + (geometry.minX() + x * geometry.stepX()) + ","
                + (geometry.minY() + y * geometry.stepY()) + ","
                + (geometry.minZ() + z * geometry.stepZ()) + ")";
    }

    /** Prints only the non-zero Y buckets, so a fault at one height is obvious. */
    private static String histogram(int[] buckets, int min, int step) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < buckets.length; ++i) {
            if (buckets[i] == 0) continue;
            if (text.length() > 0) text.append(", ");
            text.append(min + i * step).append('=').append(buckets[i]);
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    /**
     * Per-node scalar probe, off unless {@code -Djpe.nativeFinalDensityNodeProbe=true}.
     *
     * <p>Read this with care: a node inside or above an {@code InterpolatedFunction}
     * legitimately differs, because the volume path evaluates that subtree on the cell
     * lattice and interpolates, while {@code sampleValue} returns the raw child value at
     * the block. Only nodes outside every interpolation boundary can be compared this way.
     */
    private static void diagnoseNodes(State state, NativeFinalDensityPlan.Geometry geometry,
                                      int index, DensitySampler.Bound bound) {
        if (index < 0) return;
        int sizeY = geometry.sizeY();
        int plane = index / sizeY;
        int y = index % sizeY;
        int x = plane % geometry.sizeX();
        int z = plane / geometry.sizeX();
        int blockX = geometry.minX() + x * geometry.stepX();
        int blockY = geometry.minY() + y * geometry.stepY();
        int blockZ = geometry.minZ() + z * geometry.stepZ();
        // Snap to the interpolation lattice: there every InterpolatedFunction is the
        // identity on its child, so one scalar sample per node is a valid reference for
        // the whole graph instead of only for leaves.
        blockX = Math.floorDiv(blockX, 4) * 4;
        blockY = Math.floorDiv(blockY, 8) * 8;
        blockZ = Math.floorDiv(blockZ, 4) * 4;
        NativeFinalDensityPlan.Geometry single = new NativeFinalDensityPlan.Geometry(
                1, 1, 1, blockX, blockY, blockZ, 1, 1, 1);
        int count = state.graph.nodes().size();
        int[] ids = new int[count];
        for (int i = 0; i < count; ++i) ids[i] = i;
        float[][] nativeValues;
        try {
            nativeValues = state.plan.evaluateNodes(ids, single);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Per-node native probe failed", failure);
            return;
        }
        int reported = 0;
        int compared = 0;
        int mismatches = 0;
        for (int i = 0; i < count; ++i) {
            int original = i < state.sourceNodes.length ? state.sourceNodes[i] : -1;
            if (original < 0) continue;
            ++compared;
            float java;
            try {
                java = ((DensitySampler) state.inventory.source(original))
                        .sampleValue(bound.context(), blockX, blockY, blockZ);
            } catch (RuntimeException failure) {
                continue;
            }
            if (Float.floatToRawIntBits(java) == Float.floatToRawIntBits(nativeValues[i][0])) continue;
            ++mismatches;
            if (reported >= 16) continue;
            DensityGraphIr.Node node = state.graph.nodes().get(i);
            LOGGER.log(System.Logger.Level.INFO,
                    "node {0} op={1} {2} java={3} native={4}",
                    i, node.opcode(),
                    state.inventory.source(original).getClass().getSimpleName(),
                    Float.toString(java), Float.toString(nativeValues[i][0]));
            ++reported;
        }
        LOGGER.log(System.Logger.Level.INFO,
                "Lattice probe at {0},{1},{2}: compared={3} mismatches={4}",
                blockX, blockY, blockZ, compared, mismatches);
    }

    private static State stateFor(Object sampler) {
        synchronized (STATES) {
            State state = STATES.get(sampler);
            if (state != null) return state;
            if (STATES.size() >= MAX_TRACKED_SAMPLERS) return null;
            state = new State();
            STATES.put(sampler, state);
            return state;
        }
    }

    /** The plan elides BlendDensityFunction, so a real blender invalidates it. */
    private static boolean blendIsIdentity(DensitySampler.Bound bound) {
        var blender = bound.context().getField(
                net.minecraft.world.level.levelgen.blending.Blender.CONTEXT_KEY);
        return blender == null || blender.isEmpty();
    }

    private static void prepare(DensitySampler.Bound bound, State state) {
        Path library = NativeFinalDensityPlan.defaultLibrary();
        if (library == null) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Native final-density requested but the runtime library was not found");
            return;
        }
        try {
            MinecraftSamplerInventory.Inventory inventory =
                    MinecraftSamplerInventory.capture(bound.sampler(), 65_536, bound.context());
            CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(inventory.snapshot());
            String semantic = CompiledSamplerGraphIr.semanticFingerprint(graph);
            LiveSamplerPayloadProvider provider =
                    LiveSamplerPayloadProvider.of(inventory, bound.context());
            SamplerGraphProgramAdapter.Result adapted = SamplerGraphProgramAdapter.adapt(
                    graph, DensityGraphCapture.Mode.EXPERIMENTAL, 0L, provider);
            if (!adapted.complete()) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Native final-density coverage: nodes={0} native={1} opaque={2} classes={3} reasons={4};"
                                + " staying on Java",
                        adapted.graph().nodes().size(), adapted.nativeNodes(), adapted.opaqueNodes(),
                        adapted.opaqueClasses(), reasons(adapted.graph()));
                return;
            }
            DensityGraphIr adaptedGraph = adapted.graph();
            long fingerprint = NativeFinalDensityPlan.payloadFingerprint(adaptedGraph.payload())
                    ^ ((long) semantic.hashCode() << 32);
            DensityGraphIr keyed = new DensityGraphIr(adaptedGraph.nodes(), adaptedGraph.diagnostics(),
                    adaptedGraph.rootNode(), fingerprint, adaptedGraph.payload());
            state.plan = NativeFinalDensityPlan.compile(library, keyed);
            state.graph = keyed;
            state.inventory = inventory;
            state.sourceNodes = adapted.sourceNodes();
            state.fingerprint = semantic;
            LOGGER.log(System.Logger.Level.INFO,
                    "Native final-density plan compiled: fingerprint={0} nodes={1} noisePlans={2} mode={3}",
                    semantic, keyed.nodes().size(), keyed.payload().noisePlans().size(), MODE);
            if (Boolean.getBoolean("jpe.nativeFinalDensityDumpGraph")) {
                dumpGraph(keyed, inventory, adapted.sourceNodes());
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Unable to compile the native final-density plan", failure);
        }
    }

    /**
     * Writes the lowered graph next to the server so a mismatch report can be read
     * against real node ids. Opt-in: {@code -Djpe.nativeFinalDensityDumpGraph=true}.
     */
    private static void dumpGraph(DensityGraphIr graph, MinecraftSamplerInventory.Inventory inventory,
                                  int[] sourceNodes) {
        StringBuilder text = new StringBuilder(16 * 1024);
        text.append("# nodes=").append(graph.nodes().size())
                .append(" root=").append(graph.rootNode())
                .append(" noisePlans=").append(graph.payload().noisePlans().size())
                .append('\n');
        for (int i = 0; i < graph.nodes().size(); ++i) {
            DensityGraphIr.Node node = graph.nodes().get(i);
            String source = "const";
            try {
                int original = i < sourceNodes.length ? sourceNodes[i] : -1;
                if (original >= 0) {
                    source = original + ":" + inventory.source(original).getClass().getSimpleName();
                }
            } catch (RuntimeException missing) {
                source = "?";
            }
            text.append(i)
                    .append(" op=").append(node.opcode())
                    .append(" a=").append(node.inputA())
                    .append(" b=").append(node.inputB())
                    .append(" r=").append(node.reserved())
                    .append(" imm=").append(node.immediate())
                    .append(" class=").append(source)
                    .append('\n');
        }
        try {
            java.nio.file.Path target = Path.of("jpe-graph.txt");
            java.nio.file.Files.writeString(target, text.toString());
            LOGGER.log(System.Logger.Level.INFO, "Wrote {0} nodes to {1}",
                    graph.nodes().size(), target.toAbsolutePath());
        } catch (java.io.IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Unable to write the graph dump", failure);
        }
    }

    /** Distinct lowering failures, so the next gap is visible without a rebuild. */
    private static String reasons(DensityGraphIr graph) {
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
        for (DensityGraphIr.Diagnostic diagnostic : graph.diagnostics()) {
            distinct.add(diagnostic.reason());
            if (distinct.size() >= 6) break;
        }
        return distinct.toString();
    }

    private static NativeFinalDensityPlan.Geometry geometry(DensityVolume volume) {
        int sizeX = volume.sizeX();
        int sizeY = volume.sizeY();
        int sizeZ = volume.sizeZ();
        if ((long) sizeX * sizeY * sizeZ > 1_048_576L) return null;
        int stepX = sizeX > 1 ? volume.blockX(1) - volume.blockX(0) : 1;
        int stepY = sizeY > 1 ? volume.blockY(1) - volume.blockY(0) : 1;
        int stepZ = sizeZ > 1 ? volume.blockZ(1) - volume.blockZ(0) : 1;
        if (stepX <= 0 || stepY <= 0 || stepZ <= 0) return null;
        return new NativeFinalDensityPlan.Geometry(sizeX, sizeY, sizeZ,
                volume.blockX(0), volume.blockY(0), volume.blockZ(0), stepX, stepY, stepZ);
    }
}
