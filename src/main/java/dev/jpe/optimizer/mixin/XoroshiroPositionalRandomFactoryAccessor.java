package dev.jpe.optimizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.XoroshiroRandomSource$XoroshiroPositionalRandomFactory")
public interface XoroshiroPositionalRandomFactoryAccessor {
    @Accessor("seedLo")
    long jpe$seedLo();

    @Accessor("seedHi")
    long jpe$seedHi();
}
