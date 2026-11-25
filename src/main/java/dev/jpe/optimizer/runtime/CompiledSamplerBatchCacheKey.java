package dev.jpe.optimizer.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Canonical cache-key builder for compiled-sampler arithmetic batches.
 *
 * The world seed remains in the key until seed-derived sampler constants are
 * fully externalized into per-world runtime bindings. This is conservative:
 * it may reduce cache sharing between worlds, but cannot silently reuse a
 * plan whose captured constants belong to another seed.
 */
public final class CompiledSamplerBatchCacheKey {
    private static final String LOWERING_VERSION = "compiled-sampler-batch-v1";

    private CompiledSamplerBatchCacheKey() {}

    public static String create(DensityGraphCache.Identity identity,
                                CompiledSamplerGraphIr.Graph graph,
                                List<CompiledSamplerArithmeticIslands.Island> islands,
                                long worldSeed) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(islands, "islands");
        if (islands.isEmpty()) {
            throw new IllegalArgumentException("At least one arithmetic island is required");
        }

        StringBuilder canonical = new StringBuilder(256)
                .append(identity.stableKey()).append(':')
                .append(LOWERING_VERSION).append(':')
                .append(Long.toUnsignedString(worldSeed)).append(':')
                .append(CompiledSamplerGraphIr.semanticFingerprint(graph));
        HashSet<Integer> members = new HashSet<>();
        canonical.append(":islands=").append(islands.size());
        for (CompiledSamplerArithmeticIslands.Island island : islands) {
            Objects.requireNonNull(island, "island");
            if (island.nodeIds().isEmpty() || island.outputNodeIds().isEmpty()) {
                throw new IllegalArgumentException("Cached arithmetic islands must have nodes and outputs");
            }
            canonical.append("|nodes=").append(island.nodeIds().size()).append(':');
            for (int node : island.nodeIds()) {
                if (!members.add(node)) {
                    throw new IllegalArgumentException("Arithmetic islands overlap at node " + node);
                }
                canonical.append(node).append(',');
            }
            canonical.append("|outputs=").append(island.outputNodeIds().size()).append(':');
            for (int output : island.outputNodeIds()) {
                if (!island.nodeIds().contains(output)) {
                    throw new IllegalArgumentException("Island output is not a member: " + output);
                }
                canonical.append(output).append(',');
            }
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(Character.forDigit((item >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(item & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
