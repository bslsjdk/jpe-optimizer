package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.vector.DensityVectorRuntime;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vector path for min/max selection; child samplers and their order are unchanged. */
@Mixin(targets = {
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MinSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MaxSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstMinSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstMaxSampler"
})
public abstract class BinaryFunctionMinMaxMixin {
    @Inject(method = "sampleVolume", at = @At("HEAD"), cancellable = true)
    private void jpe$sampleVolume(SamplerContext context, DensityBuffer outputBuffer,
                                  DensityVolume volume, CallbackInfo callback) {
        if (!DensityVectorRuntime.enabled()) return;
        Object sampler = this;
        if (sampler instanceof BinaryFunction.MinSampler min) {
            jpe$applyTwoBuffer(context, outputBuffer, volume, min.left(), min.right(), true);
        } else if (sampler instanceof BinaryFunction.MaxSampler max) {
            jpe$applyTwoBuffer(context, outputBuffer, volume, max.left(), max.right(), false);
        } else if (sampler instanceof BinaryFunction.ConstMinSampler min) {
            min.left().sampleVolume(context, outputBuffer, volume);
            DensityVectorRuntime.minMaxConst(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    outputBuffer.size(), min.right(), true);
        } else if (sampler instanceof BinaryFunction.ConstMaxSampler max) {
            max.left().sampleVolume(context, outputBuffer, volume);
            DensityVectorRuntime.minMaxConst(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    outputBuffer.size(), max.right(), false);
        } else {
            return;
        }
        callback.cancel();
    }

    @Unique
    private static void jpe$applyTwoBuffer(SamplerContext context, DensityBuffer outputBuffer,
                                            DensityVolume volume, DensitySampler left,
                                            DensitySampler right, boolean minimum) {
        left.sampleVolume(context, outputBuffer, volume);
        try (ScopedDensityBuffer rightBuffer = context.acquireBuffer(volume)) {
            right.sampleVolume(context, rightBuffer, volume);
            DensityVectorRuntime.minMax(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    ((DensityBufferAccessor) rightBuffer).jpe$values(), outputBuffer.size(), minimum);
        }
    }
}
