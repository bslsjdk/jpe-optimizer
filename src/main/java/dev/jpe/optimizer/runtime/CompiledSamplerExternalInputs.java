package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/**
 * Resolves an arithmetic island's opaque sampler boundaries to full-volume inputs.
 *
 * Sampling is delegated back to Minecraft's DensitySampler API with the exact
 * Bound context and DensityVolume. This preserves ContextBoundSampler resolution
 * and CachingDensitySampler cache-cell behavior instead of inspecting their state.
 * Instances are scoped to one Bound/volume pair and must not be persisted.
 */
public final class CompiledSamplerExternalInputs {
    private static final long MAX_STAGED_FLOATS = 16_777_216L;
    private final DensitySampler.Bound bound;
    private final DensityVolume volume;
    private final IdentityHashMap<DensitySampler, float[]> sampled = new IdentityHashMap<>();
    private long stagedFloats;

    public CompiledSamplerExternalInputs(DensitySampler.Bound bound, DensityVolume volume) {
        this.bound = Objects.requireNonNull(bound, "bound");
        this.volume = Objects.requireNonNull(volume, "volume");
    }

    /** Returns input-major arrays in the exact order recorded by the lowered island. */
    public float[][] resolve(CompiledSamplerGraphTrace.CapturedGraph captured,
                             CompiledSamplerArithmeticLowering.LoweredIsland island) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(island, "island");
        List<Integer> sourceIds = island.inputSourceNodeIds();
        if (sourceIds.size() > 256) {
            throw new IllegalArgumentException("An arithmetic island may use at most 256 external samplers");
        }
        float[][] result = new float[sourceIds.size()][];
        for (int i = 0; i < sourceIds.size(); ++i) {
            Object source = captured.source(sourceIds.get(i));
            if (!(source instanceof DensitySampler sampler)) {
                throw new IllegalArgumentException("External graph node is not a DensitySampler: "
                        + sourceIds.get(i));
            }
            result[i] = sampleOnce(sampler);
        }
        return result;
    }

    private float[] sampleOnce(DensitySampler sampler) {
        float[] existing = sampled.get(sampler);
        if (existing != null) return existing;
        int size = volume.size();
        if (size < 0 || stagedFloats + size > MAX_STAGED_FLOATS) {
            throw new IllegalArgumentException("External density input staging exceeds the 64 MiB safety budget");
        }
        DensityBuffer buffer = DensityBuffer.createUnpooled(size);
        sampler.sampleVolume(bound.context(), buffer, volume);
        if (buffer.size() != size) {
            throw new IllegalStateException("Minecraft sampler returned an unexpected density buffer size");
        }
        float[] values = ((DensityBufferAccessor) buffer).jpe$values();
        if (values.length < size) {
            throw new IllegalStateException("Minecraft sampler buffer capacity is smaller than its volume");
        }
        sampled.put(sampler, values);
        stagedFloats += size;
        return values;
    }

    public int distinctSamplerCount() {
        return sampled.size();
    }

    public long stagedFloatCount() {
        return stagedFloats;
    }
}
