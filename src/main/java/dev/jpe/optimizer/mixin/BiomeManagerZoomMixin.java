package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.math.BiomeZoomMath;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces only the arithmetic inside BiomeManager's eight-corner zoom
 * comparison. Coordinate hashing and biome-source lookups remain vanilla.
 */
@Mixin(BiomeManager.class)
public abstract class BiomeManagerZoomMixin {
    /**
     * @author JPE
     * @reason Exact 8 x 1024 lookup table for the squared jitter offsets.
     */
    @Overwrite
    private static double getFiddledDistance(
            final long seed,
            final int xRandom,
            final int yRandom,
            final int zRandom,
            final double distanceX,
            final double distanceY,
            final double distanceZ
    ) {
        return BiomeZoomMath.fiddledDistance(
                seed, xRandom, yRandom, zRandom, distanceX, distanceY, distanceZ
        );
    }
}
