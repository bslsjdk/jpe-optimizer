package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses vanilla Aquifer cache metadata and the fluid-update side-effect flag. */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public interface AquiferNoiseBasedAccessor {
    @Accessor("shouldScheduleFluidUpdate")
    void jpe$setShouldScheduleFluidUpdate(boolean value);

    @Accessor("aquiferLocationCache")
    long[] jpe$getAquiferLocationCache();

    @Accessor("aquiferCache")
    Aquifer.FluidStatus[] jpe$getAquiferCache();

    @Invoker("computeFluid")
    Aquifer.FluidStatus jpe$computeFluid(int x, int y, int z);

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

    @Accessor("skipSamplingAboveY")
    int jpe$getSkipSamplingAboveY();

    @Accessor("positionalRandomFactory")
    PositionalRandomFactory jpe$getPositionalRandomFactory();

    @Accessor("skipSamplingAboveY")
    int jpe$getSkipSamplingAboveY();
}
