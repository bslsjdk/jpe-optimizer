package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.synth.GradientNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the immutable permutation table and coordinate offsets used by PerlinNoise. */
@Mixin(GradientNoise.class)
public interface GradientNoiseAccessor {
    @Accessor("perms") byte[] jpe$perms();
    @Accessor("offsetX") double jpe$offsetX();
    @Accessor("offsetY") double jpe$offsetY();
    @Accessor("offsetZ") double jpe$offsetZ();
}
