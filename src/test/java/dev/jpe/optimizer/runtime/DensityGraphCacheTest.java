package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DensityGraphCacheTest {
    @TempDir Path temporaryDirectory;

    private static DensityGraphIr sample() {
        return new DensityGraphIr(
                java.util.List.of(
                        DensityGraphIr.Node.x(),
                        DensityGraphIr.Node.constant(2.5),
                        DensityGraphIr.Node.add(0, 1)),
                2, 0x12648430L);
    }

    @Test
    void secondStartupLoadsIrWithoutCallingJavaCaptureBuilder() throws IOException {
        var first = DensityGraphCache.loadOrBuild(temporaryDirectory, "mc26.3|neoforge|test-pack|overworld", DensityGraphCacheTest::sample);
        assertFalse(first.cacheHit());
        assertTrue(Files.isRegularFile(first.cacheFile()));

        AtomicBoolean captureCalled = new AtomicBoolean();
        var second = DensityGraphCache.loadOrBuild(temporaryDirectory, "mc26.3|neoforge|test-pack|overworld", () -> {
            captureCalled.set(true);
            throw new AssertionError("cache hit must not traverse Java DensityFunction objects");
        });

        assertTrue(second.cacheHit());
        assertFalse(captureCalled.get());
        assertEquals(first.graph().nodes(), second.graph().nodes());
        assertEquals(first.graph().rootNode(), second.graph().rootNode());
        assertEquals(first.graph().configurationFingerprint(), second.graph().configurationFingerprint());
    }

    @Test
    void differentCacheKeysDoNotReuseAnotherWorldgenGraph() throws IOException {
        var first = DensityGraphCache.loadOrBuild(temporaryDirectory, "pack-A|overworld", DensityGraphCacheTest::sample);
        var second = DensityGraphCache.loadOrBuild(temporaryDirectory, "pack-B|overworld", DensityGraphCacheTest::sample);
        assertNotEquals(first.cacheFile(), second.cacheFile());
        assertFalse(second.cacheHit());
    }

    @Test
    void checksumFailureIsTreatedAsCacheMissAndRebuilt() throws IOException {
        var first = DensityGraphCache.loadOrBuild(temporaryDirectory, "integrity-key", DensityGraphCacheTest::sample);
        byte[] damaged = Files.readAllBytes(first.cacheFile());
        damaged[damaged.length / 2] ^= 0x40;
        Files.write(first.cacheFile(), damaged);

        var rebuilt = DensityGraphCache.loadOrBuild(temporaryDirectory, "integrity-key", DensityGraphCacheTest::sample);
        assertFalse(rebuilt.cacheHit());
        assertEquals(sample().nodes(), rebuilt.graph().nodes());
    }

    @Test
    void persistsExperimentalDiagnosticsAndOpaqueDependencies() throws IOException {
        DensityGraphIr experimental = new DensityGraphIr(
                java.util.List.of(DensityGraphIr.Node.constant(7.0),
                        DensityGraphIr.Node.opaque(0, DensityGraphIr.NO_INPUT)),
                java.util.List.of(new DensityGraphIr.Diagnostic(1, "example.UnknownDensity", "missing random context",
                        java.util.List.of(0))),
                1, 77L);
        var first = DensityGraphCache.loadOrBuild(temporaryDirectory, "experimental-key", () -> experimental);
        var second = DensityGraphCache.loadOrBuild(temporaryDirectory, "experimental-key",
                () -> fail("experimental graph should have been loaded from cache"));
        assertTrue(second.cacheHit());
        assertTrue(second.graph().experimental());
        assertEquals(experimental.nodes(), second.graph().nodes());
        assertEquals(experimental.diagnostics(), second.graph().diagnostics());
    }
    @Test
    void canonicalIdentityChangesWhenDimensionOrModSetChanges() {
        DensityGraphCache.Identity base = new DensityGraphCache.Identity(
                "26.3", "NeoForge", "jpe-native-1", "mods-hash-A",
                "minecraft:overworld", 123L, 456L);
        DensityGraphCache.Identity otherDimension = new DensityGraphCache.Identity(
                "26.3", "NeoForge", "jpe-native-1", "mods-hash-A",
                "minecraft:the_nether", 123L, 456L);
        DensityGraphCache.Identity otherMods = new DensityGraphCache.Identity(
                "26.3", "NeoForge", "jpe-native-1", "mods-hash-B",
                "minecraft:overworld", 123L, 456L);
        assertNotEquals(base.stableKey(), otherDimension.stableKey());
        assertNotEquals(base.stableKey(), otherMods.stableKey());
    }

    @Test
    void persistsAllOpaqueDependencyIndices() throws IOException {
        DensityGraphIr graph = new DensityGraphIr(
                java.util.List.of(DensityGraphIr.Node.constant(1), DensityGraphIr.Node.constant(2),
                        DensityGraphIr.Node.constant(3), DensityGraphIr.Node.opaque(0, 1)),
                java.util.List.of(new DensityGraphIr.Diagnostic(3, "example.Nary", "unknown fan-in",
                        java.util.List.of(0, 1, 2))),
                3, 91L);
        var first = DensityGraphCache.loadOrBuild(temporaryDirectory, "nary-opaque", () -> graph);
        var second = DensityGraphCache.loadOrBuild(temporaryDirectory, "nary-opaque",
                () -> fail("cache should preserve opaque dependency metadata"));
        assertTrue(second.cacheHit());
        assertEquals(java.util.List.of(0, 1, 2), second.graph().diagnostics().get(0).childIndices());
    }


}
