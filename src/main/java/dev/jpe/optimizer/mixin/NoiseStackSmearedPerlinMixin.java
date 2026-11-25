package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.runtime.NativePerlinNoiseBackend;
import dev.jpe.optimizer.vector.PerlinNoiseVectorizer;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts the exact 26.3 SmearedPerlin stack before its per-layer Java loop. */
@Mixin(targets = "net.minecraft.world.level.levelgen.synth.NoiseStack$SmearedPerlin")
public abstract class NoiseStackSmearedPerlinMixin {
    @Inject(method = "addToVolume", at = @At("HEAD"), cancellable = true)
    private void jpe$addSmearedStackToVolume(DensityBuffer buffer, DensityVolume volume,
                                             double xzScale, double yScale, float amplitude,
                                             CallbackInfo callback) {
        if (PerlinNoiseVectorizer.vanillaBypass()) return;
        if (NativePerlinNoiseBackend.tryAddToStack((NoiseStack) (Object) this,
                buffer, volume, xzScale, yScale, amplitude)) {
            callback.cancel();
        }
    }
}
