package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.DensityBufferMetrics;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records whether the vanilla pool found a reusable buffer or allocated a new one. */
@Mixin(DensityBufferPool.class)
public abstract class DensityBufferPoolMetricsMixin {
    @Inject(method = "tryTakeBest", at = @At("RETURN"))
    private void jpe$recordPoolLookup(int minCapacity, int maxCapacity,
                                      CallbackInfoReturnable<ScopedDensityBuffer> callback) {
        ScopedDensityBuffer reused = callback.getReturnValue();
        DensityBufferMetrics.recordPoolLookup(minCapacity, reused == null ? -1 : reused.capacity());
    }
}
