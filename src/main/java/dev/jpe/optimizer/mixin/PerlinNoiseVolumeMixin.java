package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.vector.DensityVectorRuntime;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import dev.jpe.optimizer.vector.PerlinNoiseVectorizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vectorizes contiguous Y interpolation lanes during Perlin volume sampling. */
@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseVolumeMixin {
    @Inject(method = "addToVolume", at = @At("HEAD"), cancellable = true)
    private void jpe$addToVolume(DensityBuffer buffer, DensityVolume volume,
                                 double xzScale, double yScale, float amplitude, CallbackInfo callback) {
        if (!DensityVectorRuntime.enabled() || PerlinNoiseVectorizer.vanillaBypass()) return;
        if (PerlinNoiseVectorizer.addToVolume((PerlinNoise) (Object) this,
                buffer, volume, xzScale, yScale, amplitude)) callback.cancel();
    }
}
