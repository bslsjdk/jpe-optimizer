package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.MaterialDensityBufferPoolScope;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Redirects context-owned buffers whose lifetime is bounded by surface evaluation. */
@Mixin(targets = "net.minecraft.world.level.levelgen.material.MaterialRuleContext")
public abstract class MaterialRuleContextBufferMixin {
    private static final String CREATE_UNPOOLED =
            "Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;createUnpooled(I)Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;";

    @Redirect(method = "getDensitiesInChunk", at = @At(value = "INVOKE", target = CREATE_UNPOOLED))
    private DensityBuffer jpe$acquirePrefillBuffer(int size) {
        return MaterialDensityBufferPoolScope.acquire(size);
    }

    @Redirect(method = "getMinSurfaceLevel", at = @At(value = "INVOKE", target = CREATE_UNPOOLED))
    private DensityBuffer jpe$acquirePreliminarySurfaceBuffer(int size) {
        return MaterialDensityBufferPoolScope.acquire(size);
    }
}
