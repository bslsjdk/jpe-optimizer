package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import java.util.concurrent.atomic.LongAdder;

/** Native batch adapter for the exact Minecraft 26.3 Blender density operation. */
public final class NativeBlenderDensityBackend {
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder COMPLETED = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final LongAdder PARITY_SAMPLES = new LongAdder();
    private static final LongAdder PARITY_MISMATCHES = new LongAdder();

    private NativeBlenderDensityBackend() {}

    public static boolean tryBlend(SamplerContext context, DensityVolume volume, DensityBuffer buffer) {
        boolean enabled = Boolean.getBoolean("jpe.nativeBlenderEnabled");
        boolean verify = Boolean.getBoolean("jpe.verifyNativeBlenderParity");
        if (!enabled && !verify) return false;
        Blender blender = context.getField(Blender.CONTEXT_KEY);
        if (blender == null || blender.isEmpty()) return false;
        if (((dev.jpe.optimizer.mixin.BlenderAccessor) blender).jpe$heightAndBiomeBlendingData().isEmpty()
                && ((dev.jpe.optimizer.mixin.BlenderAccessor) blender).jpe$densityBlendingData().isEmpty()) {
            return false;
        }
        ATTEMPTS.increment();
        NativeRuntime runtime = NativePerlinNoiseBackend.runtimeForDiagnostics();
        if (runtime == null || !runtime.supportsBlenderDensityVolume()) {
            FALLBACKS.increment();
            return false;
        }
        float[] input = ((DensityBufferAccessor) buffer).jpe$values();
        int length = volume.size();
        if (length <= 0 || length > input.length) {
            FALLBACKS.increment();
            return false;
        }
        float[] nativeOutput = verify ? new float[length] : input;
        try {
            runtime.blendDensityVolume(blender, volume, input, nativeOutput);
        } catch (RuntimeException | LinkageError failure) {
            FALLBACKS.increment();
            JPEOptimizer.LOGGER.warn("Native Blender batch failed; using exact Java Blender: {}", failure.toString());
            return false;
        }

        if (verify) {
            long mismatches = 0;
            int firstMismatch = -1;
            int index = 0;
            for (int z = 0; z < volume.sizeZ(); ++z) {
                int blockZ = volume.blockZ(z);
                for (int x = 0; x < volume.sizeX(); ++x) {
                    int blockX = volume.blockX(x);
                    for (int y = 0; y < volume.sizeY(); ++y) {
                        int blockY = volume.blockY(y);
                        float expected = blender.blendDensity(blockX, blockY, blockZ, input[index]);
                        if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(nativeOutput[index])) {
                            if (firstMismatch < 0) firstMismatch = index;
                            ++mismatches;
                        }
                        ++index;
                    }
                }
            }
            PARITY_SAMPLES.add(length);
            PARITY_MISMATCHES.add(mismatches);
            JPEOptimizer.LOGGER.info(
                    "JPE_NATIVE_BLENDER_PARITY samples={} exact={} mismatches={} firstMismatch={}",
                    length, mismatches == 0, mismatches, firstMismatch);
            if (mismatches != 0) {
                FALLBACKS.increment();
                return false;
            }
        }

        if (!enabled) return false; // parity-only mode never commits Native output
        if (verify) System.arraycopy(nativeOutput, 0, input, 0, length);
        COMPLETED.increment();
        return true;
    }

    public static String metrics() {
        return "attempts=" + ATTEMPTS.sum() + " completed=" + COMPLETED.sum()
                + " fallbacks=" + FALLBACKS.sum() + " paritySamples=" + PARITY_SAMPLES.sum()
                + " parityMismatches=" + PARITY_MISMATCHES.sum();
    }
}
