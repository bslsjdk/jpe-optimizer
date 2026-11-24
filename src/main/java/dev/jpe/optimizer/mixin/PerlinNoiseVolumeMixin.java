package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.runtime.NativePerlinNoiseBackend;
import dev.jpe.optimizer.vector.DensityVectorRuntime;
import dev.jpe.optimizer.vector.PerlinNoiseVectorizer;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dispatches Perlin volume work to the opt-in native backend, then the Java SIMD path. */
@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseVolumeMixin {
    @Inject(method = "addToVolume", at = @At("HEAD"), cancellable = true)
    private void jpe$addToVolume(DensityBuffer buffer, DensityVolume volume,
                                 double xzScale, double yScale, float amplitude, CallbackInfo callback) {
        if (PerlinNoiseVectorizer.vanillaBypass()) return;

        if (!NativePerlinNoiseBackend.bypassed()
                && NativePerlinNoiseBackend.tryAddToVolume((PerlinNoise) (Object) this,
                buffer, volume, xzScale, yScale, amplitude)) {
            callback.cancel();
            return;
        }

        if (!DensityVectorRuntime.enabled() || !PerlinNoiseVectorizer.enabled()) return;
        if (PerlinNoiseVectorizer.addToVolume((PerlinNoise) (Object) this,
                buffer, volume, xzScale, yScale, amplitude)) callback.cancel();
    }
}
