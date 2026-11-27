package dev.jpe.optimizer.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Builds a strict cross-launch cache key from the resolved worldgen environment.
 *
 * The effectiveWorldgenDigest must be computed from the actual resolved settings
 * and datapack/registry inputs. This class intentionally refuses to invent that
 * digest from a settings identifier alone.
 */
public final class DensityGraphWorldFingerprint {
    private static final long MAGIC = 0x4A50455747464E31L; // "JPEWGFN1"
    private static final int FORMAT_VERSION = 2;

    private DensityGraphWorldFingerprint() {}

    public record ModVersion(String id, String version, String artifactSha256) {
        public ModVersion {
            requireText(id, "mod id");
            requireText(version, "mod version");
            if (artifactSha256 == null || artifactSha256.length() != 64
                    || !artifactSha256.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                throw new IllegalArgumentException("Mod artifact digest must be a 64-character SHA-256 hex value");
            }
            artifactSha256 = artifactSha256.toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static byte[] create(
            long worldSeed,
            String minecraftVersion,
            String loaderId,
            String loaderVersion,
            String dimensionId,
            int nativeAbiVersion,
            long semanticFingerprint,
            byte[] effectiveWorldgenDigest,
            Collection<ModVersion> mods
    ) {
        requireText(minecraftVersion, "Minecraft version");
        requireText(loaderId, "loader id");
        requireText(loaderVersion, "loader version");
        requireText(dimensionId, "dimension id");
        if (nativeAbiVersion <= 0) throw new IllegalArgumentException("Native ABI version must be positive");
        if (effectiveWorldgenDigest == null || effectiveWorldgenDigest.length != 32) {
            throw new IllegalArgumentException("Effective worldgen digest must be a 32-byte SHA-256 value");
        }
        Objects.requireNonNull(mods, "mods");

        List<ModVersion> orderedMods = new ArrayList<>(mods);
        orderedMods.sort(Comparator.comparing(ModVersion::id).thenComparing(ModVersion::version));
        HashSet<String> seenIds = new HashSet<>();
        for (ModVersion mod : orderedMods) {
            Objects.requireNonNull(mod, "mod");
            if (!seenIds.add(mod.id())) {
                throw new IllegalArgumentException("Duplicate mod id in fingerprint input: " + mod.id());
            }
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeLong(worldSeed);
                writeText(output, minecraftVersion);
                writeText(output, loaderId);
                writeText(output, loaderVersion);
                writeText(output, dimensionId);
                output.writeInt(nativeAbiVersion);
                output.writeLong(semanticFingerprint);
                output.write(effectiveWorldgenDigest);
                output.writeInt(orderedMods.size());
                for (ModVersion mod : orderedMods) {
                    writeText(output, mod.id());
                    writeText(output, mod.version());
                    writeText(output, mod.artifactSha256());
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
    }
}
