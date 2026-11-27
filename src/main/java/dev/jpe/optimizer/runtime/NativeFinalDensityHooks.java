package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/**
 * Native handoff at the final-density volume boundary.
 *
 * <p>Java remains authoritative. After the vanilla sampler has filled the
 * buffer, this hook may compile the captured graph once, evaluate the same
 * volume natively, and compare every float bit. Only in {@code commit} mode and
 * only after an exact comparison does it overwrite the buffer.
 *
 * <p>Modes, selected with {@code -Djpe.nativeFinalDensity=}:
 * <ul>
 *   <li>{@code off} (default): no work at all</li>
 *   <li>{@code shadow}: compile and compare, never change the buffer</li>
 *   <li>{@code commit}: compare, then replace the buffer when it matches</li>
 * </ul>
 *
 * <p>A graph with any OPAQUE node is refused: an experimental plan must never
 * reach block placement.
 */
public final class NativeFinalDensityHooks {
    private static final System.Logger LOGGER =
            System.getLogger(NativeFinalDensityHooks.class.getName());
    private static final String MODE =
            System.getProperty("jpe.nativeFinalDensity", "off").trim().toLowerCase(java.util.Locale.ROOT);
    private static final boolean SHADOW = MODE.equals("shadow") || MODE.equals("commit");
    private static final boolean COMMIT = MODE.equals("commit");
    private static final int MAX_TRACKED_SAMPLERS = 8;

    private static final Map<Object, State> STATES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static final class State {
        NativeFinalDensityPlan plan;
        String fingerprint = "";
        boolean attempted;
        long volumes;
        long mismatchedVolumes;
    }

    private NativeFinalDensityHooks() {}

    public static boolean enabled() {
        return SHADOW;
    }

    /** Called once per chunk, after the Java sampler has produced the volume. */
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
            prepare(sampler, state);
        }
        if (state.plan == null) return;

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
        state.volumes++;
        if (!parity.exact()) {
            state.mismatchedVolumes++;
            LOGGER.log(System.Logger.Level.WARNING,
                    "Native final-density mismatch: compared={0} mismatches={1} first={2} java={3} native={4}",
                    parity.compared(), parity.mismatches(), parity.firstMismatch(),
                    parity.javaValue(), parity.nativeValue());
            if (COMMIT) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Commit refused: native final density is not bit-exact for this volume");
            }
            return;
        }
        if (COMMIT) {
            System.arraycopy(nativeValues, 0, javaValues, 0, geometry.sampleCount());
        }
        LOGGER.log(System.Logger.Level.DEBUG,
                "Native final-density parity ok: fingerprint={0} samples={1} volumes={2} committed={3}",
                state.fingerprint, parity.compared(), state.volumes, COMMIT);
    }

    private static void prepare(DensitySampler sampler, State state) {
        Path library = NativeFinalDensityPlan.defaultLibrary();
        if (library == null) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Native final-density requested but the runtime library was not found");
            return;
        }
        try {
            CompiledSamplerGraphTrace.CapturedGraph captured =
                    CompiledSamplerGraphTrace.captureDetailed(sampler, DensitySampler.class::isInstance, 65_536);
            CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(captured.snapshot());
            String fingerprint = CompiledSamplerGraphIr.semanticFingerprint(graph);
            SamplerGraphProgramAdapter.Result adapted = SamplerGraphProgramAdapter.adapt(
                    graph, DensityGraphCapture.Mode.EXPERIMENTAL, 0L, null);
            if (!adapted.complete()) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Native final-density coverage: nodes={0} native={1} opaque={2} classes={3}; staying on Java",
                        adapted.graph().nodes().size(), adapted.nativeNodes(), adapted.opaqueNodes(),
                        adapted.opaqueClasses());
                return;
            }
            state.plan = NativeFinalDensityPlan.compile(library, adapted.graph());
            state.fingerprint = fingerprint;
            LOGGER.log(System.Logger.Level.INFO,
                    "Native final-density plan compiled: fingerprint={0} nodes={1} mode={2}",
                    fingerprint, adapted.graph().nodes().size(), MODE);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Unable to compile the native final-density plan", failure);
        }
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
