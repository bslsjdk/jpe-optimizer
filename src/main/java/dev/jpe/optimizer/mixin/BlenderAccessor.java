package dev.jpe.optimizer.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Blender.class)
public interface BlenderAccessor {
    @Accessor("heightAndBiomeBlendingData")
    Long2ObjectOpenHashMap<BlendingData> jpe$heightAndBiomeBlendingData();

    @Accessor("densityBlendingData")
    Long2ObjectOpenHashMap<BlendingData> jpe$densityBlendingData();
}
