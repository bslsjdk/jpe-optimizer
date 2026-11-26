package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RandomState.class)
public interface RandomStateAccessor {
    @Accessor("densityFunctionCompiler")
    DensityFunctionCompiler jpe$getDensityFunctionCompiler();

    /** Stable world-seed input used to separate persistent per-world density graph entries. */
    @Accessor("seed")
    long jpe$getSeed();

    /** Effective router captured by this RandomState after worldgen settings are resolved. */
    @Accessor("router")
    NoiseRouter jpe$getRouter();
}
