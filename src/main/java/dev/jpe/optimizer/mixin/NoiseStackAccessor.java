package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.synth.NoiseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the ordered immutable layer array without copying it. */
@Mixin(NoiseStack.class)
public interface NoiseStackAccessor {
    @Accessor("layers") Object[] jpe$layers();
}
