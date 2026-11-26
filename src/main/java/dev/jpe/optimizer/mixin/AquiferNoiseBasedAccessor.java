package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses the flag that vanilla clears before returning for positive density. */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public interface AquiferNoiseBasedAccessor {
    @Accessor("shouldScheduleFluidUpdate")
    void jpe$setShouldScheduleFluidUpdate(boolean value);

    @Accessor("aquiferLocationCache")
    long[] jpe$getAquiferLocationCache();

    @Accessor("minGridX")
    int jpe$getMinGridX();

    @Accessor("minGridY")
    int jpe$getMinGridY();

    @Accessor("minGridZ")
    int jpe$getMinGridZ();

    @Accessor("gridSizeX")
    int jpe$getGridSizeX();

    @Accessor("gridSizeZ")
    int jpe$getGridSizeZ();

    @Accessor("positionalRandomFactory")
    net.minecraft.world.level.levelgen.PositionalRandomFactory jpe$getPositionalRandomFactory();
}
