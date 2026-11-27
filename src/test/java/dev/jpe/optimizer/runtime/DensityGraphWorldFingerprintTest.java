package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DensityGraphWorldFingerprintTest {
    private static final byte[] SETTINGS_DIGEST = new byte[32];

    private static byte[] create(List<DensityGraphWorldFingerprint.ModVersion> mods) {
        return DensityGraphWorldFingerprint.create(
                123456789L, "26.3", "neoforge", "26.3.0.115",
                "minecraft:overworld", NativeRuntime.EXPECTED_ABI_VERSION,
                0x4A50452603000005L, SETTINGS_DIGEST, mods);
    }

    @Test
    void modOrderingDoesNotChangeFingerprint() {
        var first = create(List.of(
                new DensityGraphWorldFingerprint.ModVersion("example_b", "2.0", "b".repeat(64)),
                new DensityGraphWorldFingerprint.ModVersion("example_a", "1.0", "a".repeat(64))));
        var second = create(List.of(
                new DensityGraphWorldFingerprint.ModVersion("example_a", "1.0", "a".repeat(64)),
                new DensityGraphWorldFingerprint.ModVersion("example_b", "2.0", "b".repeat(64))));

        assertArrayEquals(first, second);
    }

    @Test
    void seedDimensionSettingsAndModChangesInvalidateFingerprint() {
        byte[] baseline = create(List.of(new DensityGraphWorldFingerprint.ModVersion("example", "1.0", "1".repeat(64))));
        byte[] changedSeed = DensityGraphWorldFingerprint.create(
                123456790L, "26.3", "neoforge", "26.3.0.115", "minecraft:overworld",
                NativeRuntime.EXPECTED_ABI_VERSION, 0x4A50452603000005L, SETTINGS_DIGEST,
                List.of(new DensityGraphWorldFingerprint.ModVersion("example", "1.0", "1".repeat(64))));
        byte[] changedDimension = DensityGraphWorldFingerprint.create(
                123456789L, "26.3", "neoforge", "26.3.0.115", "minecraft:the_nether",
                NativeRuntime.EXPECTED_ABI_VERSION, 0x4A50452603000005L, SETTINGS_DIGEST,
                List.of(new DensityGraphWorldFingerprint.ModVersion("example", "1.0", "1".repeat(64))));
        byte[] changedSettings = SETTINGS_DIGEST.clone();
        changedSettings[0] = 1;
        byte[] changedMod = create(List.of(new DensityGraphWorldFingerprint.ModVersion("example", "1.1", "1".repeat(64))));

        assertFalse(java.util.Arrays.equals(baseline, changedSeed));
        assertFalse(java.util.Arrays.equals(baseline, changedDimension));
        assertFalse(java.util.Arrays.equals(baseline, DensityGraphWorldFingerprint.create(
                123456789L, "26.3", "neoforge", "26.3.0.115", "minecraft:overworld",
                NativeRuntime.EXPECTED_ABI_VERSION, 0x4A50452603000005L, changedSettings,
                List.of(new DensityGraphWorldFingerprint.ModVersion("example", "1.0", "1".repeat(64))))));
        assertFalse(java.util.Arrays.equals(baseline, changedMod));
    }

    @Test
    void changedModArtifactBytesInvalidateFingerprintEvenWhenVersionIsUnchanged() {
        byte[] original = create(List.of(
                new DensityGraphWorldFingerprint.ModVersion("example", "1.0", "1".repeat(64))));
        byte[] replacedArtifact = create(List.of(
                new DensityGraphWorldFingerprint.ModVersion("example", "1.0", "2".repeat(64))));
        assertFalse(java.util.Arrays.equals(original, replacedArtifact));
    }

    @Test
    void gameLoaderAbiAndCompilerChangesInvalidateFingerprint() {
        var mods = List.of(new DensityGraphWorldFingerprint.ModVersion("example", "1.0", "1".repeat(64)));
        byte[] baseline = create(mods);

        byte[] changedMinecraft = DensityGraphWorldFingerprint.create(
                123456789L, "26.4", "neoforge", "26.3.0.115", "minecraft:overworld",
                NativeRuntime.EXPECTED_ABI_VERSION, 0x4A50452603000005L, SETTINGS_DIGEST, mods);
        byte[] changedLoaderId = DensityGraphWorldFingerprint.create(
                123456789L, "26.3", "other-loader", "26.3.0.115", "minecraft:overworld",
                NativeRuntime.EXPECTED_ABI_VERSION, 0x4A50452603000005L, SETTINGS_DIGEST, mods);
        byte[] changedLoaderVersion = DensityGraphWorldFingerprint.create(
                123456789L, "26.3", "neoforge", "26.3.0.116", "minecraft:overworld",
                NativeRuntime.EXPECTED_ABI_VERSION, 0x4A50452603000005L, SETTINGS_DIGEST, mods);
        byte[] changedAbi = DensityGraphWorldFingerprint.create(
                123456789L, "26.3", "neoforge", "26.3.0.115", "minecraft:overworld",
                NativeRuntime.EXPECTED_ABI_VERSION + 1, 0x4A50452603000005L, SETTINGS_DIGEST, mods);
        byte[] changedCompiler = DensityGraphWorldFingerprint.create(
                123456789L, "26.3", "neoforge", "26.3.0.115", "minecraft:overworld",
                NativeRuntime.EXPECTED_ABI_VERSION, 0x4A50452603000006L, SETTINGS_DIGEST, mods);

        assertFalse(java.util.Arrays.equals(baseline, changedMinecraft));
        assertFalse(java.util.Arrays.equals(baseline, changedLoaderId));
        assertFalse(java.util.Arrays.equals(baseline, changedLoaderVersion));
        assertFalse(java.util.Arrays.equals(baseline, changedAbi));
        assertFalse(java.util.Arrays.equals(baseline, changedCompiler));
    }

    @Test
    void refusesMissingEffectiveWorldgenDigest() {
        assertThrows(IllegalArgumentException.class, () ->
                DensityGraphWorldFingerprint.create(
                        1L, "26.3", "neoforge", "26.3", "minecraft:overworld",
                        NativeRuntime.EXPECTED_ABI_VERSION, 1L, null, List.of()));
    }

    @Test
    void refusesDuplicateModIds() {
        assertThrows(IllegalArgumentException.class, () -> create(List.of(
                new DensityGraphWorldFingerprint.ModVersion("example", "1.0", "1".repeat(64)),
                new DensityGraphWorldFingerprint.ModVersion("example", "2.0"))));
    }
}
