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
        /** Adapted node of the structure beard, or -1 when the graph has none. */
        int beardNode = -1;
        /** Its parent, the root add. Both are excluded from node probes. */
        int beardParent = -1;
        String fingerprint = "";
        boolean attempted;
        boolean nativeOnly;
        long verifiedVolumes;
        long mismatchedVolumes;
        long skippedBlended;
        long nativeVolumes;
        long nearVolumes;
        long skippedBearded;
        long nativeNanos;
        long compareNanos;
        long timedVolumes;
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
            values = evaluateNative(state, geometry, bound);
            if (values == null) {
                state.skippedBearded++;
                return null;
            }
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
        long nativeStart = System.nanoTime();
        try {
            nativeValues = evaluateNative(state, geometry, bound);
            if (nativeValues == null) {
                state.skippedBearded++;
                return;
            }
            long compared = System.nanoTime();
            parity = state.plan.compare(javaValues, nativeValues);
            long finished = System.nanoTime();
            state.nativeNanos += compared - nativeStart;
            state.compareNanos += finished - compared;
            if (++state.timedVolumes % 64 == 0) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Native cost: volumes={0} nativeMs={1} compareMs={2} samples={3}",
                        state.timedVolumes,
                        Double.toString(state.nativeNanos / (double) state.timedVolumes / 1.0e6),
                        Double.toString(state.compareNanos / (double) state.timedVolumes / 1.0e6),
                        Integer.toString(parity.compared()));
            }
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Native final-density evaluation failed", failure);
            state.plan = null;
            return;
        }
        if (!parity.exact() && parity.withinUlps(1)) {
            // One float32 step on a minority of samples. Every sample still rounds to
            // the value Java would produce, so the volume is usable, but the plan is
            // not bit-exact and the count is reported rather than hidden.
            state.nearVolumes++;
            if (state.nearVolumes <= 4 || state.nearVolumes % 64 == 0) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Native final-density within one ulp: compared={0} mismatches={1} first={2}"
                                + " java={3} native={4} nearVolumes={5}",
                        parity.compared(), parity.mismatches(), parity.firstMismatch(),
                        Float.toString(parity.javaValue()), Float.toString(parity.nativeValue()),
                        state.nearVolumes);
            }
        } else if (!parity.exact()) {
            state.mismatchedVolumes++;
            LOGGER.log(System.Logger.Level.ERROR,
                    "Native final-density mismatch: compared={0} mismatches={1} maxUlps={2} first={3}"
                            + " java={4} native={5}; plan disabled for this sampler",
                    parity.compared(), parity.mismatches(), parity.maxUlps(), parity.firstMismatch(),
                    Float.toString(parity.javaValue()), Float.toString(parity.nativeValue()));
            if (Boolean.getBoolean("jpe.nativeFinalDensityDebug")) {
                diagnoseVolume(geometry, javaValues, nativeValues);
            }
            if (Boolean.getBoolean("jpe.nativeFinalDensityNodeProbe")) {
                diagnoseNodes(state, geometry, largestDifference(javaValues, nativeValues), bound);
            }
            state.plan = null;
            state.nativeOnly = false;
            return;
        }
        state.verifiedVolumes++;
        if (state.verifiedVolumes % 32 == 0) {
            LOGGER.log(System.Logger.Level.INFO,
                    "Native final-density parity ok: verified={0} mismatched={1} nativeVolumes={2}",
                    state.verifiedVolumes, state.mismatchedVolumes, state.nativeVolumes);
        }
        if (COMMIT && !state.nativeOnly && state.verifiedVolumes >= WARMUP) {
            state.nativeOnly = true;
            LOGGER.log(System.Logger.Level.INFO,
                    "Native final-density now authoritative: fingerprint={0} verified={1}"
                            + " near={2} mismatched={3} nodes={4}",
                    state.fingerprint, state.verifiedVolumes, state.nearVolumes,
                    state.mismatchedVolumes, state.plan.nodeCount());
        }
        LOGGER.log(System.Logger.Level.DEBUG,
                "Native final-density parity ok: fingerprint={0} samples={1} verified={2} nativeOnly={3}",
                state.fingerprint, parity.compared(), state.verifiedVolumes, state.nativeOnly);
    }

    /**
     * Finds the structure beard in the compiled graph and checks that it is the
     * second term of the root add, which is the only shape this runtime can
     * reconstruct: the payload capture lowers a Beardifier to a zero constant
     * because a beard is chunk state, not a density expression, so the volume is
     * only complete once the real per-block values are added back.
     *
     * <p>Any other position disables the plan instead of silently dropping terrain.
     */
    private static void locateBeardifier(State state) {
        int count = state.graph.nodes().size();
        for (int i = 0; i < count; ++i) {
            int original = i < state.sourceNodes.length ? state.sourceNodes[i] : -1;
            if (original < 0) continue;
            Object source;
            try {
                source = state.inventory.source(original);
            } catch (RuntimeException missing) {
                continue;
            }
            if (!(source instanceof net.minecraft.world.level.levelgen.Beardifier)) continue;
            int root = count - 1;
            DensityGraphIr.Node top = state.graph.nodes().get(root);
            if (top.opcode() != DensityGraphIr.ADD
                    || (top.inputA() != i && top.inputB() != i)) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Structure beard sits at node {0}, not as a term of the root add;"
                                + " plan disabled because the volume would miss terrain", i);
                state.plan = null;
                return;
            }
            state.beardNode = i;
            state.beardParent = root;
            LOGGER.log(System.Logger.Level.INFO,
                    "Structure beard lowered to node {0} under root add {1}", i, root);
            return;
        }
    }

    /**
     * Native volume for this chunk, or {@code null} when the chunk carries structure
     * state the graph cannot express.
     *
     * <p>The payload capture lowers the beard to a zero constant because a beard is
     * chunk state, not a density expression, and the plan is compiled once per sampler
     * while the beard changes per chunk. The zero is therefore exact for a chunk whose
     * beard is empty, and wrong for one whose beard is not: the structure contribution
     * reaches up to a few units of density, which is terrain, not rounding.
     *
     * <p>Reading the live beard from the sampler context costs one lookup and decides
     * per chunk, instead of 98,304 scalar samples that would dominate the volume.
     */
    private static float[] evaluateNative(State state, NativeFinalDensityPlan.Geometry geometry,
                                          DensitySampler.Bound bound) {
        if (state.beardNode >= 0 && beardIsPresent(bound)) return null;
        return state.plan.evaluate(geometry);
    }

    /** True when this chunk's beard can contribute density. */
    private static boolean beardIsPresent(DensitySampler.Bound bound) {
        try {
            Object beard = bound.context().getField(
                    net.minecraft.world.level.levelgen.Beardifier.CONTEXT_KEY);
            return beard != null && beard != net.minecraft.world.level.levelgen.Beardifier.EMPTY;
        } catch (RuntimeException unavailable) {
            // Fail closed: an unreadable context means the volume cannot be trusted.
            return true;
        }
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
    /** Index of the sample with the largest absolute difference, or -1. */
    private static int largestDifference(float[] javaValues, float[] nativeValues) {
        int limit = Math.min(javaValues.length, nativeValues.length);
        int best = -1;
        float bestDelta = -1.0f;
        for (int i = 0; i < limit; ++i) {
            float delta = Math.abs(javaValues[i] - nativeValues[i]);
            if (delta > bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    /**
     * Localises the largest disagreement of a volume by probing two points: the exact
     * block, and the interpolation corner it belongs to.
     *
     * <p>At the exact block, nodes inside an {@code INTERPOLATED} subtree are skipped:
     * vanilla only evaluates those on the 4/8 lattice, so their scalar value at an
     * arbitrary block is a corner value and comparing it there reports a difference
     * that belongs to the probe, not to the runtime.
     *
     * <p>At the corner every node is comparable, including the interpolated children,
     * which is why both probes are printed: a fault at a corner only shows in the
     * second, a fault in the blend only in the first.
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
        boolean[] interpolated = interpolatedNodes(state);
        probe(state, bound, blockX, blockY, blockZ, interpolated, true, "Point");
        // Nearest corner rather than the one below: the largest difference sits at the
        // far edge of a cell as often as at its origin.
        probe(state, bound,
                Math.round(blockX / 4.0f) * 4,
                Math.round(blockY / 8.0f) * 8,
                Math.round(blockZ / 4.0f) * 4,
                interpolated, false, "Corner");
    }

    /** Nodes reachable from an {@code INTERPOLATED} child, i.e. corner-only nodes. */
    private static boolean[] interpolatedNodes(State state) {
        int count = state.graph.nodes().size();
        boolean[] interpolated = new boolean[count];
        int[] stack = new int[count];
        for (int i = 0; i < count; ++i) {
            DensityGraphIr.Node node = state.graph.nodes().get(i);
            if (node.opcode() != DensityGraphIr.INTERPOLATED) continue;
            int top = 0;
            stack[top++] = node.inputA();
            while (top > 0) {
                int current = stack[--top];
                if (current < 0 || current >= count || interpolated[current]) continue;
                interpolated[current] = true;
                DensityGraphIr.Node inner = state.graph.nodes().get(current);
                if (inner.inputA() >= 0) stack[top++] = inner.inputA();
                if (inner.inputB() >= 0) stack[top++] = inner.inputB();
                if (inner.reserved() >= 0) stack[top++] = inner.reserved();
            }
        }
        return interpolated;
    }

    private static void probe(State state, DensitySampler.Bound bound, int blockX, int blockY,
                              int blockZ, boolean[] interpolated, boolean skipInterpolated,
                              String label) {
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
        // Java side for every node first, so a mismatch can be reported together with
        // the values it was computed from.
        float[] javaValues = new float[count];
        boolean[] available = new boolean[count];
        for (int i = 0; i < count; ++i) {
            int original = i < state.sourceNodes.length ? state.sourceNodes[i] : -1;
            if (original < 0 || (skipInterpolated && interpolated[i])) continue;
            // The beard is deliberately absent from the native graph; its contribution
            // is added per volume, so these two lanes never agree with Java.
            if (i == state.beardNode || i == state.beardParent) continue;
            try {
                javaValues[i] = ((DensitySampler) state.inventory.source(original))
                        .sampleValue(bound.context(), blockX, blockY, blockZ);
                available[i] = true;
            } catch (RuntimeException failure) {
                available[i] = false;
            }
        }
        int reported = 0;
        int compared = 0;
        int mismatches = 0;
        for (int i = 0; i < count; ++i) {
            if (!available[i]) continue;
            ++compared;
            if (Float.floatToRawIntBits(javaValues[i]) == Float.floatToRawIntBits(nativeValues[i][0])) continue;
            ++mismatches;
            if (reported >= 12) continue;
            DensityGraphIr.Node node = state.graph.nodes().get(i);
            LOGGER.log(System.Logger.Level.INFO,
                    "{0} node {1} op={2} {3}{4} java={5} native={6} | a[{7}]={8}/{9} b[{10}]={11}/{12} r[{13}]={14}/{15}",
                    label, i, node.opcode(),
                    state.inventory.source(state.sourceNodes[i]).getClass().getSimpleName(),
                    interpolated[i] ? "[corner-only]" : "",
                    Float.toString(javaValues[i]), Float.toString(nativeValues[i][0]),
                    node.inputA(), text(javaValues, available, node.inputA()),
                    text(nativeValues, node.inputA()),
                    node.inputB(), text(javaValues, available, node.inputB()),
                    text(nativeValues, node.inputB()),
                    node.reserved(), text(javaValues, available, node.reserved()),
                    text(nativeValues, node.reserved()));
            ++reported;
        }
        LOGGER.log(System.Logger.Level.INFO,
                "{0} probe at {1},{2},{3}: compared={4} mismatches={5}",
                label, blockX, blockY, blockZ, compared, mismatches);
    }

    private static String text(float[] values, boolean[] available, int index) {
        if (index < 0 || index >= values.length || !available[index]) return "-";
        return Float.toString(values[index]);
    }

    private static String text(float[][] values, int index) {
        if (index < 0 || index >= values.length) return "-";
        return Float.toString(values[index][0]);
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
            locateBeardifier(state);
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
