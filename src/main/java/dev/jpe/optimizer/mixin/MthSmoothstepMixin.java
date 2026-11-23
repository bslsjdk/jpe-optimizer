package dev.jpe.optimizer.mixin;

import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces the hot Mth.smoothstep(float) with a lookup-table implementation.
 * Called billions of times during worldgen (PerlinNoise alpha, blend).
 * <1e-5 abs error (FastMathTest), visually identical terrain.
 */
@Mixin(Mth.class)
public abstract class MthSmoothstepMixin {
    /**
     * @author dreamxi
     * @reason lookup-table smoothstep, faster than the polynomial for hot worldgen calls
     */
    @Overwrite
    public static float smoothstep(float x) {
        return dev.jpe.optimizer.math.FastMath.smoothstep(x);
    }

    /**
     * @author dreamxi
     * @reason lookup-table smoothstepDerivative, called 3x per PerlinNoise gradient sample
     */
    @Overwrite
    public static float smoothstepDerivative(float x) {
        return dev.jpe.optimizer.math.FastMath.smoothstepDerivative(x);
    }
}