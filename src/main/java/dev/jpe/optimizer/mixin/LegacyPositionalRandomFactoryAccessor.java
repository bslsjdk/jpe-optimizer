package dev.jpe.optimizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.LegacyRandomSource$LegacyPositionalRandomFactory")
public interface LegacyPositionalRandomFactoryAccessor {
    @Accessor("seed")
    long jpe$seed();
}
