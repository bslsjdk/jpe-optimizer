package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.synth.NoiseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads NoiseStack.layers without placing mod classes in Minecraft's package.
 * Object is intentional: the protected nested Layer type cannot be named from
 * this package, while the JVM can safely return the Layer[] reference as Object.
 */
@Mixin(NoiseStack.class)
public interface NoiseStackLayersAccessor {
    @Accessor("layers")
    Object jpe$getLayers();
}
