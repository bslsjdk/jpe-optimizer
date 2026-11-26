package dev.jpe.optimizer.runtime;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EffectiveNoiseRouterDigestTest {
    @Test
    void canonicalDigestIsStableForEquivalentEncodedRouters() {
        var first = EffectiveNoiseRouterDigest.digestEncoded(
                JsonParser.parseString("{\"b\":[1,2],\"a\":{\"y\":4,\"x\":3}}"));
        var second = EffectiveNoiseRouterDigest.digestEncoded(
                JsonParser.parseString("{\"a\":{\"x\":3,\"y\":4},\"b\":[1,2]}"));

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertArrayEquals(first.orElseThrow(), second.orElseThrow());
    }

    @Test
    void changedEncodedRouterValueChangesDigest() {
        byte[] first = EffectiveNoiseRouterDigest.digestEncoded(
                JsonParser.parseString("{\"final_density\":0.8}")).orElseThrow();
        byte[] second = EffectiveNoiseRouterDigest.digestEncoded(
                JsonParser.parseString("{\"final_density\":0.9}")).orElseThrow();

        assertFalse(java.util.Arrays.equals(first, second));
    }

    @Test
    void missingRouterOrOpsOrEncodedValueFailsClosed() {
        assertEquals(Optional.empty(), EffectiveNoiseRouterDigest.tryCreate(null, JsonOps.INSTANCE));
        assertEquals(Optional.empty(), EffectiveNoiseRouterDigest.tryCreate(null, null));
        assertEquals(Optional.empty(), EffectiveNoiseRouterDigest.digestEncoded(null));
    }
}
