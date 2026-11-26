package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.DensityBufferMetrics;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts unpooled density-buffer creation without changing allocation behavior. */
@Mixin(DensityBuffer.class)
public abstract class DensityBufferMetricsMixin {
    @Inject(method = "createUnpooled", at = @At("RETURN"))
    private static void jpe$recordUnpooled(int size, CallbackInfoReturnable<DensityBuffer> callback) {
        DensityBufferMetrics.recordUnpooled(size);
    }
}
