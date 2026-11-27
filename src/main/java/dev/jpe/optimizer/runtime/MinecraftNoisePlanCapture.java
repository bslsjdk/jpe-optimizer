package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.mixin.GradientNoiseAccessor;
import dev.jpe.optimizer.mixin.NoiseStackLayerAccessor;
import dev.jpe.optimizer.mixin.SmearedPerlinNoiseAccessor;
import java.util.ArrayList;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

/**
 * Turns a live vanilla {@code Noise} into the native program's noise table.
 *
 * <p>The expansion mirrors {@code NoiseStack.addToVolume} exactly: each layer's
 * frequency multiplies the outer {@code NoiseFunction} scale, and the outer
 * amplitude multiplies the layer amplitude. Vanilla performs
 * {@code (amplitude * layerAmplitude) * perlin}, so the multiplication is folded
 * the same way here; regrouping would change the float bits.
 *
 * <p>The permutation bytes are read from the already-created {@code PerlinNoise}
 * instances, which is what makes the native stack reproduce the world seed
 * without replaying the random factory.
 */
public final class MinecraftNoisePlanCapture {
    private MinecraftNoisePlanCapture() {}

    /** Returns {@code null} for noise implementations the native stack cannot reproduce. */
    public static DensityGraphIr.NoisePlan capture(Noise noise, double xzScale, double yScale,
                                                   float amplitude) {
        return capture(noise, xzScale, yScale, amplitude, true);
    }

    /**
     * @param foldScales {@code true} for the volume path, where vanilla passes the
     *                   already-multiplied scale into each layer; {@code false} for
     *                   the scalar path, where the shifted coordinate is multiplied
     *                   by the layer frequency after the shift is added.
     */
    public static DensityGraphIr.NoisePlan capture(Noise noise, double xzScale, double yScale,
                                                   float amplitude, boolean foldScales) {
        if (!(noise instanceof NoiseStack stack)) return null;
        Object[] layers = NativePerlinNoiseBackend.layersOf(stack);
        if (layers.length == 0 || layers.length > 64) return null;

        ArrayList<DensityGraphIr.NoiseLayer> captured = new ArrayList<>(layers.length);
        for (Object sourceLayer : layers) {
            if (!(sourceLayer instanceof NoiseStackLayerAccessor layer)) return null;
            Noise layerNoise = layer.jpe$noise();
            if (layerNoise == null) return null;
            boolean smeared = layerNoise.getClass() == SmearedPerlinNoise.class;
            if (!smeared && layerNoise.getClass() != PerlinNoise.class) return null;
            if (!(layerNoise instanceof GradientNoiseAccessor gradient)) return null;
            byte[] permutation = gradient.jpe$perms();
            if (permutation == null || permutation.length != 256) return null;

            double frequency = layer.jpe$frequency();
            captured.add(new DensityGraphIr.NoiseLayer(
                    permutation.clone(),
                    gradient.jpe$offsetX(),
                    gradient.jpe$offsetY(),
                    gradient.jpe$offsetZ(),
                    foldScales ? xzScale * frequency : xzScale,
                    foldScales ? yScale * frequency : yScale,
                    amplitude * layer.jpe$amplitude(),
                    smeared ? ((SmearedPerlinNoiseAccessor) layerNoise).jpe$fudgeYScale() : 0.0,
                    smeared,
                    foldScales ? 0.0 : frequency));
        }
        return new DensityGraphIr.NoisePlan(captured);
    }
}
