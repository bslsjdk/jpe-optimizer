package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final LinkedHashMap<String, MutableSamplingStats> samplingStats = new LinkedHashMap<>();
    private long stagedFloats;

    public record SamplingStats(int calls, long samples, long elapsedNanos) {}
    private static final class MutableSamplingStats {
        int calls;
        long samples;
        long elapsedNanos;
        void add(int count, long nanos) { calls++; samples += count; elapsedNanos += nanos; }
    }

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
        long started = System.nanoTime();
        sampler.sampleVolume(bound.context(), buffer, volume);
        long elapsed = System.nanoTime() - started;
        samplingStats.computeIfAbsent(sampler.getClass().getName(), ignored -> new MutableSamplingStats())
                .add(size, elapsed);
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

    /** Resolves the external samplers and executes all island roots in one native call. */
    public void evaluate(NativeRuntime.NativeDensityGraph executable,
                         CompiledSamplerGraphTrace.CapturedGraph captured,
                         CompiledSamplerArithmeticLowering.LoweredIsland island,
                         int sizeX, int sizeY, int sizeZ,
                         int minX, int minY, int minZ,
                         int stepX, int stepY, int stepZ,
                         float[][] outputVolumes) {
        float[][] inputs = resolve(captured, island);
        island.evaluate(executable, sizeX, sizeY, sizeZ,
                minX, minY, minZ, stepX, stepY, stepZ, inputs, outputVolumes);
    }

    public int distinctSamplerCount() {
        return sampled.size();
    }

    public long stagedFloatCount() { return stagedFloats; }

    /** Per-implementation timing breakdown for this diagnostic volume only. */
    public Map<String, SamplingStats> samplingStats() {
        LinkedHashMap<String, SamplingStats> result = new LinkedHashMap<>();
        samplingStats.forEach((name, stats) -> result.put(name,
                new SamplingStats(stats.calls, stats.samples, stats.elapsedNanos)));
        return Map.copyOf(result);
    }
}
