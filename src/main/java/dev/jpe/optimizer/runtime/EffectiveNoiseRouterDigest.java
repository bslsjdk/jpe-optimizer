package dev.jpe.optimizer.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.level.levelgen.NoiseRouter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes a canonical digest from the effective NoiseRouter object held by RandomState.
 *
 * The caller supplies DynamicOps so registry-aware RegistryOps can be used when
 * available. If the active registry context cannot encode the router, this
 * returns empty and callers must treat persistent caching as unavailable.
 */
public final class EffectiveNoiseRouterDigest {
    private EffectiveNoiseRouterDigest() {}

    public static Optional<byte[]> tryCreate(NoiseRouter router, DynamicOps<JsonElement> ops) {
        if (router == null || ops == null) return Optional.empty();
        try {
            Optional<JsonElement> encoded = NoiseRouter.CODEC.encodeStart(ops, router).result();
            return encoded.flatMap(EffectiveNoiseRouterDigest::digestEncoded);
        } catch (RuntimeException | NoSuchAlgorithmException failure) {
            return Optional.empty();
        }
    }

    static Optional<byte[]> digestEncoded(JsonElement encoded) {
        if (encoded == null) return Optional.empty();
        try {
            byte[] canonical = canonicalJson(encoded).getBytes(StandardCharsets.UTF_8);
            return Optional.of(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            return Optional.empty();
        }
    }

    private static String canonicalJson(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(object.entrySet());
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            StringBuilder result = new StringBuilder("{");
            for (int i = 0; i < entries.size(); i++) {
                if (i != 0) result.append(',');
                Map.Entry<String, JsonElement> entry = entries.get(i);
                result.append(new JsonPrimitive(entry.getKey())).append(':')
                        .append(canonicalJson(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.size(); i++) {
                if (i != 0) result.append(',');
                result.append(canonicalJson(array.get(i)));
            }
            return result.append(']').toString();
        }
        return element.toString();
    }
}
