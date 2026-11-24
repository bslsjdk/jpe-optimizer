package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.ClimateTargetArrayReuse;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opt-in replacement for the one temporary long[7] created per biome lookup.
 * The reusable array is thread-local and is only used during the synchronous
 * RTree traversal. Reentrant lookups fall back to a fresh array.
 */
@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree")
public abstract class ClimateRTreeTargetArrayMixin {
    @Inject(method = "search", at = @At("HEAD"))
    private void jpe$enterSearch(CallbackInfo ci) {
        ClimateTargetArrayReuse.enterSearch();
    }

    @Inject(method = "search", at = @At("RETURN"))
    private void jpe$exitSearch(CallbackInfo ci) {
        ClimateTargetArrayReuse.exitSearch();
    }

    @Redirect(
            method = "search",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/Climate$TargetPoint;toParameterArray()[J"
            )
    )
    private long[] jpe$reuseTargetArray(Climate.TargetPoint target) {
        return ClimateTargetArrayReuse.toParameterArray(target);
    }
}
