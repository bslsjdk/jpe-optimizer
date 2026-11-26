package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.synth.Noise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessors for the protected NoiseStack.Layer record without linking its protected type. */
@Mixin(targets = "net.minecraft.world.level.levelgen.synth.NoiseStack$Layer")
public interface NoiseStackLayerAccessor {
    @Accessor("noise") Noise jpe$noise();
    @Accessor("frequency") double jpe$frequency();
    @Accessor("amplitude") float jpe$amplitude();
}
