package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.vector.DensityVectorKernel.BinaryOp;
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

/** Vector path for binary arithmetic and min/max; child sampler order is preserved. */
@Mixin(targets = {
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$AddSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$SubSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MulSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$DivSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MinSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MaxSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstAddSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstSubSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstMulSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstDivSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstMinSampler",
        "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstMaxSampler"
})
public abstract class BinaryFunctionMinMaxMixin {
    @Inject(method = "sampleVolume", at = @At("HEAD"), cancellable = true)
    private void jpe$sampleVolume(SamplerContext context, DensityBuffer outputBuffer,
                                  DensityVolume volume, CallbackInfo callback) {
        if (!DensityVectorRuntime.enabled()) return;
        Object sampler = this;
        if (sampler instanceof BinaryFunction.AddSampler op) {
            jpe$applyTwoBuffer(context, outputBuffer, volume, op.left(), op.right(), BinaryOp.ADD);
        } else if (sampler instanceof BinaryFunction.SubSampler op) {
            jpe$applyTwoBuffer(context, outputBuffer, volume, op.left(), op.right(), BinaryOp.SUBTRACT);
        } else if (sampler instanceof BinaryFunction.MulSampler op) {
            jpe$applyTwoBuffer(context, outputBuffer, volume, op.left(), op.right(), BinaryOp.MULTIPLY);
        } else if (sampler instanceof BinaryFunction.DivSampler op) {
            jpe$applyTwoBuffer(context, outputBuffer, volume, op.left(), op.right(), BinaryOp.DIVIDE);
        } else if (sampler instanceof BinaryFunction.MinSampler op) {
            jpe$applyMinMax(context, outputBuffer, volume, op.left(), op.right(), true);
        } else if (sampler instanceof BinaryFunction.MaxSampler op) {
            jpe$applyMinMax(context, outputBuffer, volume, op.left(), op.right(), false);
        } else if (sampler instanceof BinaryFunction.ConstAddSampler op) {
            jpe$applyConst(context, outputBuffer, volume, op.left(), op.right(), BinaryOp.ADD);
        } else if (sampler instanceof BinaryFunction.ConstSubSampler op) {
            op.right().sampleVolume(context, outputBuffer, volume);
            DensityVectorRuntime.binaryConstLeft(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    outputBuffer.size(), op.left(), BinaryOp.SUBTRACT);
        } else if (sampler instanceof BinaryFunction.ConstMulSampler op) {
            jpe$applyConst(context, outputBuffer, volume, op.left(), op.right(), BinaryOp.MULTIPLY);
        } else if (sampler instanceof BinaryFunction.ConstDivSampler op) {
            op.right().sampleVolume(context, outputBuffer, volume);
            DensityVectorRuntime.binaryConstLeft(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    outputBuffer.size(), op.left(), BinaryOp.DIVIDE);
        } else if (sampler instanceof BinaryFunction.ConstMinSampler op) {
            op.left().sampleVolume(context, outputBuffer, volume);
            DensityVectorRuntime.minMaxConst(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    outputBuffer.size(), op.right(), true);
        } else if (sampler instanceof BinaryFunction.ConstMaxSampler op) {
            op.left().sampleVolume(context, outputBuffer, volume);
            DensityVectorRuntime.minMaxConst(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    outputBuffer.size(), op.right(), false);
        } else {
            return;
        }
        callback.cancel();
    }

    @Unique
    private static void jpe$applyTwoBuffer(SamplerContext context, DensityBuffer outputBuffer,
                                            DensityVolume volume, DensitySampler left,
                                            DensitySampler right, BinaryOp operation) {
        left.sampleVolume(context, outputBuffer, volume);
        try (ScopedDensityBuffer rightBuffer = context.acquireBuffer(volume)) {
            right.sampleVolume(context, rightBuffer, volume);
            DensityVectorRuntime.binary(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    ((DensityBufferAccessor) rightBuffer).jpe$values(), outputBuffer.size(), operation);
        }
    }

    @Unique
    private static void jpe$applyMinMax(SamplerContext context, DensityBuffer outputBuffer,
                                        DensityVolume volume, DensitySampler left,
                                        DensitySampler right, boolean minimum) {
        left.sampleVolume(context, outputBuffer, volume);
        try (ScopedDensityBuffer rightBuffer = context.acquireBuffer(volume)) {
            right.sampleVolume(context, rightBuffer, volume);
            DensityVectorRuntime.minMax(((DensityBufferAccessor) outputBuffer).jpe$values(),
                    ((DensityBufferAccessor) rightBuffer).jpe$values(), outputBuffer.size(), minimum);
        }
    }

    @Unique
    private static void jpe$applyConst(SamplerContext context, DensityBuffer outputBuffer,
                                       DensityVolume volume, DensitySampler left,
                                       float right, BinaryOp operation) {
        left.sampleVolume(context, outputBuffer, volume);
        DensityVectorRuntime.binaryConst(((DensityBufferAccessor) outputBuffer).jpe$values(),
                outputBuffer.size(), right, operation);
    }
}
