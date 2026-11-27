package dev.jpe.optimizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the immutable vertical smear interval used by Minecraft's SmearedPerlinNoise. */
@Mixin(targets = "net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise")
public interface SmearedPerlinNoiseAccessor {
    @Accessor("fudgeYScale") double jpe$fudgeYScale();
}
