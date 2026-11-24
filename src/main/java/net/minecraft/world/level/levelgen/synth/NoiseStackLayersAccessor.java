package net.minecraft.world.level.levelgen.synth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exact-descriptor accessor for the protected NoiseStack.Layer[] field.
 * This bridge lives in NoiseStack's package so the protected nested record type
 * can be named without reflection or an Object[] descriptor mismatch.
 */
@Mixin(NoiseStack.class)
public interface NoiseStackLayersAccessor {
    @Accessor("layers")
    NoiseStack.Layer[] jpe$getLayers();
}
