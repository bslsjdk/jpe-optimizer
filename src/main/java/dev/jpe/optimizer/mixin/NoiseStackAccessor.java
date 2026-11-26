package dev.jpe.optimizer.mixin;

import net.minecraft.world.level.levelgen.synth.NoiseStack;

import java.lang.reflect.Field;

/**
 * Cached bridge for the protected NoiseStack layer array.
 *
 * Mixin @Accessor requires the exact JVM field descriptor. Returning Object[]
 * is not compatible with NoiseStack.Layer[]; reflection avoids forging an
 * incorrect descriptor while paying lookup cost only once.
 */
public final class NoiseStackAccessor {
    private static final Field LAYERS = findLayersField();

    private NoiseStackAccessor() {}

    private static Field findLayersField() {
        try {
            Field field = NoiseStack.class.getDeclaredField("layers");
            if (!field.trySetAccessible()) {
                throw new IllegalStateException("NoiseStack.layers is not accessible");
            }
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static Object[] layers(NoiseStack stack) {
        try {
            return (Object[]) LAYERS.get(stack);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read NoiseStack.layers", failure);
        }
    }
}
