package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import java.util.Objects;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** Opt-in exact full-volume parity check for one lowered arithmetic island. */
public final class CompiledSamplerNativeParity {
    public record Report(int outputCount, int samplesPerOutput, long comparedValues,
                         long mismatches, int firstOutputIndex, int firstSampleIndex,
                         int expectedBits, int actualBits) {
        public boolean exact() { return mismatches == 0; }
    }

    private CompiledSamplerNativeParity() {}

    /**
     * Executes the lowered island with Java-sampled external boundaries, then
     * compares every output sample against Minecraft's original Java sampler.
     * This method is diagnostic only; callers must never use its output to place blocks.
     */
    public static Report verify(NativeRuntime.NativeDensityGraph executable,
                                CompiledSamplerGraphTrace.CapturedGraph captured,
                                CompiledSamplerArithmeticLowering.LoweredIsland island,
                                DensitySampler.Bound bound, DensityVolume volume,
                                int sizeX, int sizeY, int sizeZ,
                                int minX, int minY, int minZ,
                                int stepX, int stepY, int stepZ) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(island, "island");
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(volume, "volume");
        int sampleCount = volume.size();
        if (sampleCount <= 0) throw new IllegalArgumentException("Density volume must not be empty");
        long outputFloats = Math.multiplyExact((long) island.outputSourceNodeIds().size(), sampleCount);
        long inputFloats = Math.multiplyExact((long) island.inputSourceNodeIds().size(), sampleCount);
        if (Math.addExact(outputFloats, inputFloats) > 16_777_216L) {
            throw new IllegalArgumentException("Parity staging exceeds the 64 MiB input/output budget");
        }

        CompiledSamplerExternalInputs externalInputs = new CompiledSamplerExternalInputs(bound, volume);
        float[][] actual = new float[island.outputSourceNodeIds().size()][];
        for (int i = 0; i < actual.length; ++i) actual[i] = new float[sampleCount];
        externalInputs.evaluate(executable, captured, island,
                sizeX, sizeY, sizeZ, minX, minY, minZ, stepX, stepY, stepZ, actual);

        long compared = 0;
        long mismatches = 0;
        int firstOutput = -1;
        int firstSample = -1;
        int expectedBits = 0;
        int actualBits = 0;
        for (int output = 0; output < actual.length; ++output) {
            Object source = captured.source(island.outputSourceNodeIds().get(output));
            if (!(source instanceof DensitySampler sampler)) {
                throw new IllegalArgumentException("Island output is not a DensitySampler");
            }
            DensityBuffer expectedBuffer = DensityBuffer.createUnpooled(sampleCount);
            sampler.sampleVolume(bound.context(), expectedBuffer, volume);
            float[] expected = ((DensityBufferAccessor) expectedBuffer).jpe$values();
            for (int sample = 0; sample < sampleCount; ++sample) {
                int expectedValue = Float.floatToRawIntBits(expected[sample]);
                int actualValue = Float.floatToRawIntBits(actual[output][sample]);
                ++compared;
                if (expectedValue != actualValue) {
                    if (mismatches == 0) {
                        firstOutput = output;
                        firstSample = sample;
                        expectedBits = expectedValue;
                        actualBits = actualValue;
                    }
                    ++mismatches;
                }
            }
        }
        return new Report(actual.length, sampleCount, compared, mismatches,
                firstOutput, firstSample, expectedBits, actualBits);
    }
}
