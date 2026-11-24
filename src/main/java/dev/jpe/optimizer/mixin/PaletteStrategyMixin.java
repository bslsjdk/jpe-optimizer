package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.PaletteHashExperiment;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Experimental replacement for the tiny 3-bit and 4-bit linear palettes.
 * Disabled by default; uses Minecraft's own HashMap palette implementation.
 */
@Mixin(Strategy.class)
abstract class PaletteStrategyMixin {
    @Shadow @Final @Mutable
    private static Configuration THREE_BITS_LINEAR;

    @Shadow @Final @Mutable
    private static Configuration FOUR_BITS_LINEAR;

    @Shadow @Final
    private static Palette.Factory HASHMAP_PALETTE_FACTORY;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void jpe$enableHashPalettes(CallbackInfo ci) {
        if (!Boolean.getBoolean("jpe.paletteHashEnabled")) return;
        THREE_BITS_LINEAR = new Configuration.Simple(HASHMAP_PALETTE_FACTORY, 3);
        FOUR_BITS_LINEAR = new Configuration.Simple(HASHMAP_PALETTE_FACTORY, 4);
        PaletteHashExperiment.markApplied();
    }
}
