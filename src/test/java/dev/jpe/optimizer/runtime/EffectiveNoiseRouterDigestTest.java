package dev.jpe.optimizer.runtime;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EffectiveNoiseRouterDigestTest {
    private static NoiseRouter router(float finalDensity) {
        return new NoiseRouter(
                DensityFunctions.constant(0.1F),
                DensityFunctions.constant(0.2F),
                DensityFunctions.constant(0.3F),
                DensityFunctions.constant(0.4F),
                DensityFunctions.constant(0.5F),
                DensityFunctions.constant(0.6F),
                DensityFunctions.constant(0.7F),
                DensityFunctions.constant(finalDensity));
    }

    @Test
    void canonicalDigestIsStableForEquivalentResolvedRouters() {
        Optional<byte[]> first = EffectiveNoiseRouterDigest.tryCreate(router(0.8F), JsonOps.INSTANCE);
        Optional<byte[]> second = EffectiveNoiseRouterDigest.tryCreate(router(0.8F), JsonOps.INSTANCE);

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertArrayEquals(first.orElseThrow(), second.orElseThrow());
    }

    @Test
    void changedFinalDensityChangesEffectiveRouterDigest() {
        byte[] first = EffectiveNoiseRouterDigest.tryCreate(router(0.8F), JsonOps.INSTANCE).orElseThrow();
        byte[] second = EffectiveNoiseRouterDigest.tryCreate(router(0.9F), JsonOps.INSTANCE).orElseThrow();

        assertFalse(java.util.Arrays.equals(first, second));
    }

    @Test
    void missingRouterOrOpsFailsClosed() {
        assertEquals(Optional.empty(), EffectiveNoiseRouterDigest.tryCreate(null, JsonOps.INSTANCE));
        assertEquals(Optional.empty(), EffectiveNoiseRouterDigest.tryCreate(router(0.8F), null));
    }
}
