package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlendingData.class)
public interface BlendingDataAccessor {
    @Accessor("densities")
    float[][] jpe$densities();

    @Accessor("areaWithOldGeneration")
    LevelHeightAccessor jpe$areaWithOldGeneration();
}
