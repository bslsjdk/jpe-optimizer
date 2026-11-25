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
                java.util.List.of(new DensityGraphIr.Diagnostic(1, "example.UnknownDensity", "missing random context")),
                1, 77L);
        var first = DensityGraphCache.loadOrBuild(temporaryDirectory, "experimental-key", () -> experimental);
        var second = DensityGraphCache.loadOrBuild(temporaryDirectory, "experimental-key",
                () -> fail("experimental graph should have been loaded from cache"));
        assertTrue(second.cacheHit());
        assertTrue(second.graph().experimental());
        assertEquals(experimental.nodes(), second.graph().nodes());
        assertEquals(experimental.diagnostics(), second.graph().diagnostics());
    }
}
