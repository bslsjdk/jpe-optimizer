package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.vector.DensityVectorRuntime;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vectorizes only the final range-selection pass. Branch samplers are still
 * evaluated in vanilla order and use the same context-scoped pooled buffers.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction$Sampler")
public abstract class RangeChoiceSamplerMixin {
    @Shadow @Final private DensitySampler input;
    @Shadow @Final private float minInclusive;
    @Shadow @Final private float maxExclusive;
    @Shadow @Final private DensitySampler whenInRange;
    @Shadow @Final private DensitySampler whenOutOfRange;

    @Inject(method = "sampleVolume", at = @At("HEAD"), cancellable = true)
    private void jpe$sampleVolume(SamplerContext context, DensityBuffer outputBuffer,
                                  DensityVolume volume, CallbackInfo callback) {
        if (!DensityVectorRuntime.enabled()) return;

        this.whenInRange.sampleVolume(context, outputBuffer, volume);
        try (ScopedDensityBuffer inputBuffer = context.acquireBuffer(volume)) {
            this.input.sampleVolume(context, inputBuffer, volume);
            try (ScopedDensityBuffer outOfRangeBuffer = context.acquireBuffer(volume)) {
                this.whenOutOfRange.sampleVolume(context, outOfRangeBuffer, volume);
                DensityVectorRuntime.rangeChoice(
                        ((DensityBufferAccessor) outputBuffer).jpe$values(),
                        ((DensityBufferAccessor) inputBuffer).jpe$values(),
                        ((DensityBufferAccessor) outOfRangeBuffer).jpe$values(),
                        outputBuffer.size(),
                        this.minInclusive,
                        this.maxExclusive
                );
            }
        }
        callback.cancel();
    }
}
