package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NoiseChunk.class)
public interface NoiseChunkAccessor {
    @Accessor("bufferPool")
    DensityBufferPool jpe$getBufferPool();

    @Accessor("randomState")
    net.minecraft.world.level.levelgen.RandomState jpe$getRandomState();
}
