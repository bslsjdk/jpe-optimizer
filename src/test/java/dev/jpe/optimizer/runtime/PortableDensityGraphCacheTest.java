package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PortableDensityGraphCacheTest {
    @TempDir Path temporaryDirectory;

    private static byte[] fingerprint(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private static PortableDensityGraphCache.Graph sampleGraph() {
        byte[] permutation = new byte[256];
        for (int i = 0; i < permutation.length; i++) permutation[i] = (byte) (i * 31);
        var layer = new PortableDensityGraphCache.PerlinLayer(
                permutation, 1.25, -2.5, 3.75, 0.125, 0.25, 0.5F);
        var stack = new PortableDensityGraphCache.PerlinStack(List.of(layer));
        return new PortableDensityGraphCache.Graph(List.of(
                NativeRuntime.DensityNode.x(),
                new NativeRuntime.DensityNode(7, -1, -1, Float.floatToRawIntBits(1.0F),
                        0.0, 0.75, 0.5),
                NativeRuntime.DensityNode.add(0, 1)
        ), 2, List.of(stack));
    }

    @Test
    void roundTripsPortableNodesAndPerlinDescriptorsWithoutNativeHandles() throws Exception {
        Path cache = temporaryDirectory.resolve("world.dgraph");
        byte[] fingerprint = fingerprint(0x4A);

        PortableDensityGraphCache.write(cache, fingerprint, 0x1020304050607080L, sampleGraph());

        var loaded = PortableDensityGraphCache.read(cache, fingerprint, 0x1020304050607080L);
        assertTrue(loaded.isPresent());
        var graph = loaded.orElseThrow();
        assertEquals(2, graph.rootNode());
        assertEquals(3, graph.nodes().size());
        assertEquals(0.0, graph.nodes().get(1).immediate());
        assertEquals(1, graph.perlinStacks().size());

        var layer = graph.perlinStacks().getFirst().layers().getFirst();
        assertArrayEquals(sampleGraph().perlinStacks().getFirst().layers().getFirst().permutation(),
                layer.permutation());
        assertEquals(1.25, layer.offsetX());
        assertEquals(-2.5, layer.offsetY());
        assertEquals(3.75, layer.offsetZ());
        assertEquals(0.125, layer.xzScale());
        assertEquals(0.25, layer.yScale());
        assertEquals(0.5F, layer.amplitude());
    }

    @Test
    void fingerprintAndSemanticVersionMismatchAreCacheMisses() throws Exception {
        Path cache = temporaryDirectory.resolve("world.dgraph");
        PortableDensityGraphCache.write(cache, fingerprint(1), 17L, sampleGraph());

        assertTrue(PortableDensityGraphCache.read(cache, fingerprint(2), 17L).isEmpty());
        assertTrue(PortableDensityGraphCache.read(cache, fingerprint(1), 18L).isEmpty());
    }

    @Test
    void checksumRejectsCorruptedCache() throws Exception {
        Path cache = temporaryDirectory.resolve("world.dgraph");
        PortableDensityGraphCache.write(cache, fingerprint(1), 17L, sampleGraph());
        byte[] bytes = Files.readAllBytes(cache);
        bytes[bytes.length / 2] ^= 0x40;
        Files.write(cache, bytes);

        assertEquals(Optional.empty(),
                PortableDensityGraphCache.read(cache, fingerprint(1), 17L));
    }

    @Test
    void malformedButChecksummedDescriptorsBecomeCacheMisses() throws Exception {
        Path cache = temporaryDirectory.resolve("malformed.dgraph");
        byte[] expectedFingerprint = fingerprint(7);
        PortableDensityGraphCache.write(cache, expectedFingerprint, 29L, sampleGraph());

        byte[] bytes = Files.readAllBytes(cache);
        // Header: magic(8), format(4), semantic(8), world fingerprint(32),
        // root(4), node count(4), then stack count(4).
        java.nio.ByteBuffer.wrap(bytes).putInt(60, 0);
        byte[] body = java.util.Arrays.copyOf(bytes, bytes.length - 32);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
        System.arraycopy(digest, 0, bytes, body.length, digest.length);
        Files.write(cache, bytes);

        assertTrue(PortableDensityGraphCache.read(cache, expectedFingerprint, 29L).isEmpty());
    }

    @Test
    void rejectsPerlinNodesThatContainRawHandlesInsteadOfPortableSlots() {
        assertThrows(IllegalArgumentException.class, () ->
                new PortableDensityGraphCache.Graph(List.of(
                        NativeRuntime.DensityNode.perlinStack(123456L, 1.0, 1.0, 1.0F)
                ), 0, List.of()));
    }

    @Test
    void roundTripsShiftBPerlinNodeAsPortableStackReference() throws Exception {
        Path cache = temporaryDirectory.resolve("shift-b.dgraph");
        byte[] worldFingerprint = fingerprint(0x5B);
        var stack = sampleGraph().perlinStacks().getFirst();
        var shiftB = new NativeRuntime.DensityNode(
                33, -1, -1, Float.floatToRawIntBits(4.0F), 0.0, 0.25, 0.25);
        var graph = new PortableDensityGraphCache.Graph(List.of(shiftB), 0, List.of(stack));

        PortableDensityGraphCache.write(cache, worldFingerprint, 0x33445566L, graph);

        var loaded = PortableDensityGraphCache.read(cache, worldFingerprint, 0x33445566L).orElseThrow();
        assertEquals(33, loaded.nodes().getFirst().opcode());
        assertEquals(0.0, loaded.nodes().getFirst().immediate());
        assertEquals(0.25, loaded.nodes().getFirst().auxiliary());
        assertEquals(0.25, loaded.nodes().getFirst().tertiary());
        assertEquals(1, loaded.perlinStacks().size());
    }

    @Test
    void rejectsShiftBNodesWithoutPortableStackDescriptors() {
        var shiftB = new NativeRuntime.DensityNode(
                33, -1, -1, Float.floatToRawIntBits(4.0F), 0.0, 0.25, 0.25);
        assertThrows(IllegalArgumentException.class, () ->
                new PortableDensityGraphCache.Graph(List.of(shiftB), 0, List.of()));
    }

    @Test
    void roundTripsFindTopSurfaceNodeWithoutProcessLocalHandles() throws Exception {
        Path cache = temporaryDirectory.resolve("find-top-surface.dgraph");
        byte[] worldFingerprint = fingerprint(0x6C);
        var density = NativeRuntime.DensityNode.constant(0.25);
        var upperBound = NativeRuntime.DensityNode.constant(320.0);
        var findTop = NativeRuntime.DensityNode.findTopSurface(0, 1, -64, 8);
        var graph = new PortableDensityGraphCache.Graph(
                List.of(density, upperBound, findTop), 2, List.of());

        PortableDensityGraphCache.write(cache, worldFingerprint, 0x77665544L, graph);

        var loaded = PortableDensityGraphCache.read(cache, worldFingerprint, 0x77665544L).orElseThrow();
        assertEquals(2, loaded.rootNode());
        assertEquals(34, loaded.nodes().get(2).opcode());
        assertEquals(-64, loaded.nodes().get(2).reserved());
        assertEquals(8.0, loaded.nodes().get(2).immediate());
        assertTrue(loaded.perlinStacks().isEmpty());
    }

    @Test
    void rejectsInvalidPortablePerlinSlots() {
        assertThrows(IllegalArgumentException.class, () ->
                new PortableDensityGraphCache.Graph(List.of(
                        new NativeRuntime.DensityNode(31, 0, 0, 0, 1.5, 1.0, 1.0)
                ), 0, List.of(sampleGraph().perlinStacks().getFirst())));
    }

    @Test
    void defensiveCopiesPreventPermutationMutationAfterConstruction() {
        byte[] permutation = new byte[256];
        permutation[0] = 12;
        var layer = new PortableDensityGraphCache.PerlinLayer(
                permutation, 0, 0, 0, 1, 1, 1);
        permutation[0] = 99;
        assertEquals(12, layer.permutation()[0]);
        byte[] returned = layer.permutation();
        returned[0] = 88;
        assertEquals(12, layer.permutation()[0]);
    }
}
