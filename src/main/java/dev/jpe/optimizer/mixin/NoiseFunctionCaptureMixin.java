package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.NativeDensityGraphCompiler;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures the exact seed-bound Noise instance created by vanilla's density
 * compiler. The graph compiler may use it only when it is a plain Perlin stack
 * with no domain-warp inputs; every other implementation falls back to vanilla.
 */
@Mixin(NoiseFunction.class)
public abstract class NoiseFunctionCaptureMixin {
    @Redirect(
            method = "compileSampler",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/densityfunction/DensityFunction$CompileContext;createNoiseSampler(Lnet/minecraft/core/Holder;)Lnet/minecraft/world/level/levelgen/synth/Noise;"
            )
    )
    private Noise jpe$captureCompiledNoise(
            DensityFunction.CompileContext context,
            Holder<NormalNoise> parameters
    ) {
        Noise noise = context.createNoiseSampler(parameters);
        NativeDensityGraphCompiler.captureNoise((NoiseFunction) (Object) this, noise);
        return noise;
    }
}
