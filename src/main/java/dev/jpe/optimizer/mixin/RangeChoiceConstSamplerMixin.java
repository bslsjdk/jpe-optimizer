package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.vector.DensityVectorRuntime;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vector path for constant-output range selection, preserving vanilla boundaries. */
@Mixin(targets = "net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction$ConstSampler")
public abstract class RangeChoiceConstSamplerMixin {
    @Shadow @Final private DensitySampler input;
    @Shadow @Final private float minInclusive;
    @Shadow @Final private float maxExclusive;
    @Shadow @Final private float whenInRange;
    @Shadow @Final private float whenOutOfRange;

    @Inject(method = "sampleVolume", at = @At("HEAD"), cancellable = true)
    private void jpe$sampleVolume(SamplerContext context, DensityBuffer outputBuffer,
                                  DensityVolume volume, CallbackInfo callback) {
        if (!DensityVectorRuntime.enabled()) return;
        this.input.sampleVolume(context, outputBuffer, volume);
        DensityVectorRuntime.rangeChoiceConst(((DensityBufferAccessor) outputBuffer).jpe$values(),
                outputBuffer.size(), this.minInclusive, this.maxExclusive, this.whenInRange, this.whenOutOfRange);
        callback.cancel();
    }
}
