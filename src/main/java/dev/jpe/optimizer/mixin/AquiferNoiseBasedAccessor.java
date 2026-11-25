package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses the flag that vanilla clears before returning for positive density. */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public interface AquiferNoiseBasedAccessor {
    @Accessor("shouldScheduleFluidUpdate")
    void jpe$setShouldScheduleFluidUpdate(boolean value);
}
