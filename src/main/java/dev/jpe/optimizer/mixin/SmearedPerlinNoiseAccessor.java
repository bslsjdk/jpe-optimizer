package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the immutable vanilla Y-smearing scale for the exact native port. */
@Mixin(SmearedPerlinNoise.class)
public interface SmearedPerlinNoiseAccessor {
    @Accessor("fudgeYScale") double jpe$fudgeYScale();
}
