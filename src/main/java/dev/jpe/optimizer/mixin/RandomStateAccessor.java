package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RandomState.class)
public interface RandomStateAccessor {
    @Accessor("densityFunctionCompiler")
    DensityFunctionCompiler jpe$getDensityFunctionCompiler();
}
