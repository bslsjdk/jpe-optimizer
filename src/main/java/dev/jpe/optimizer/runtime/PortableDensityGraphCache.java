package dev.jpe.optimizer.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Portable, process-independent representation of a compiled density DAG.
 *
 * Perlin nodes store a descriptor-table slot in {@code immediate}, never a
 * native pointer/handle. Handles are recreated only after loading the file.
 * This class deliberately does not decide whether a world configuration is
 * cache-compatible; callers must provide a complete 32-byte world fingerprint.
 */
public final class PortableDensityGraphCache {
    private static final long MAGIC = 0x4A50454447524150L; // "JPEDGRAP"
    private static final int FORMAT_VERSION = 2;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_NODES = 65_536;
    private static final int MAX_STACKS = 65_536;
    private static final int MAX_LAYERS = 64;
    private static final int MAX_TOTAL_LAYERS = 65_536;

    private PortableDensityGraphCache() {}

    public record PerlinLayer(byte[] permutation, double offsetX, double offsetY, double offsetZ,
                              double xzScale, double yScale, float amplitude) {
        public PerlinLayer {
            if (permutation == null || permutation.length != 256) {
                throw new IllegalArgumentException("Perlin permutation must contain exactly 256 bytes");
            }
            permutation = permutation.clone();
            if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY) || !Double.isFinite(offsetZ)
                    || !Double.isFinite(xzScale) || !Double.isFinite(yScale) || !Float.isFinite(amplitude)) {
                throw new IllegalArgumentException("Perlin layer contains non-finite parameters");
            }
        }
        @Override public byte[] permutation() { return permutation.clone(); }

        NativeRuntime.PerlinLayer toRuntimeLayer() {
            return new NativeRuntime.PerlinLayer(permutation, offsetX, offsetY, offsetZ,
                    xzScale, yScale, amplitude);
        }
    }

    public record PerlinStack(List<PerlinLayer> layers) {
        public PerlinStack {
            layers = List.copyOf(layers);
            if (layers.isEmpty() || layers.size() > MAX_LAYERS) {
                throw new IllegalArgumentException("Perlin stack must contain 1..64 layers");
            }
        }
        List<NativeRuntime.PerlinLayer> toRuntimeLayers() {
            return layers.stream().map(PerlinLayer::toRuntimeLayer).toList();
        }
    }

    public record Graph(List<NativeRuntime.DensityNode> nodes, int rootNode,
                        List<PerlinStack> perlinStacks) {
        public Graph {
            nodes = List.copyOf(nodes);
            perlinStacks = List.copyOf(perlinStacks);
            int totalLayers = 0;
            for (PerlinStack stack : perlinStacks) {
                if (stack == null) throw new IllegalArgumentException("Null portable Perlin stack");
                totalLayers = Math.addExact(totalLayers, stack.layers().size());
                if (totalLayers > MAX_TOTAL_LAYERS) {
                    throw new IllegalArgumentException("Portable graph contains too many Perlin layers");
                }
            }
            validateGraph(nodes, rootNode, perlinStacks.size());
        }

        /** Replaces portable Perlin slot IDs with newly prepared process-local handles. */
        public List<NativeRuntime.DensityNode> materialize(NativeRuntime runtime) {
            long[] handles = new long[perlinStacks.size()];
            try {
                for (int i = 0; i < handles.length; i++) {
                    handles[i] = runtime.preparePerlinStack(perlinStacks.get(i).toRuntimeLayers());
                    if (handles[i] == 0L) {
                        throw new IllegalStateException("Native runtime rejected portable Perlin stack " + i);
                    }
                }
                List<NativeRuntime.DensityNode> result = new ArrayList<>(nodes.size());
                for (NativeRuntime.DensityNode node : nodes) {
                    if (node.opcode() == 7 || node.opcode() == 31 || node.opcode() == 33) {
                        int slot = slot(node.immediate(), handles.length);
                        result.add(new NativeRuntime.DensityNode(node.opcode(), node.inputA(), node.inputB(),
                                node.reserved(), handles[slot], node.auxiliary(), node.tertiary()));
                    } else {
                        result.add(node);
                    }
                }
                return List.copyOf(result);
            } catch (RuntimeException | Error failure) {
                for (long handle : handles) {
                    if (handle != 0L) runtime.releasePreparedPerlinStack(handle);
                }
                throw failure;
            }
        }
    }

    public static void write(Path destination, byte[] worldFingerprint,
                             long semanticFingerprint, Graph graph) throws IOException {
        requireFingerprint(worldFingerprint);
        if (destination == null || graph == null) throw new NullPointerException();
        validateGraph(graph.nodes(), graph.rootNode(), graph.perlinStacks().size());

        byte[] body = encodeBody(worldFingerprint, semanticFingerprint, graph);
        byte[] digest = sha256(body);
        ByteArrayOutputStream complete = new ByteArrayOutputStream(body.length + DIGEST_BYTES);
        complete.write(body);
        complete.write(digest);

        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        String prefix = absolute.getFileName().toString();
        if (prefix.length() < 3) prefix = (prefix + "___").substring(0, 3);
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, complete.toByteArray());
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    /** Invalid, stale, truncated, or incompatible cache entries are treated as misses. */
    public static Optional<Graph> read(Path source, byte[] expectedWorldFingerprint,
                                       long expectedSemanticFingerprint) throws IOException {
        requireFingerprint(expectedWorldFingerprint);
        if (source == null || !Files.isRegularFile(source)) return Optional.empty();
        long fileSize = Files.size(source);
        if (fileSize < 8 + 4 + 8 + 32 + 4 + 4 + 4 + DIGEST_BYTES
                || fileSize > MAX_FILE_BYTES) return Optional.empty();
        byte[] complete = Files.readAllBytes(source);
        if (complete.length < 8 + 4 + 8 + 32 + 4 + 4 + 4 + DIGEST_BYTES
                || complete.length > MAX_FILE_BYTES) return Optional.empty();
        int bodyLength = complete.length - DIGEST_BYTES;
        byte[] body = Arrays.copyOf(complete, bodyLength);
        byte[] actualDigest = Arrays.copyOfRange(complete, bodyLength, complete.length);
        if (!MessageDigest.isEqual(actualDigest, sha256(body))) return Optional.empty();

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readLong() != MAGIC || input.readInt() != FORMAT_VERSION) return Optional.empty();
            if (input.readLong() != expectedSemanticFingerprint) return Optional.empty();
            byte[] fingerprint = input.readNBytes(32);
            if (!MessageDigest.isEqual(fingerprint, expectedWorldFingerprint)) return Optional.empty();
            int root = input.readInt();
            int nodeCount = input.readInt();
            int stackCount = input.readInt();
            if (nodeCount <= 0 || nodeCount > MAX_NODES || stackCount < 0 || stackCount > MAX_STACKS) {
                return Optional.empty();
            }

            List<NativeRuntime.DensityNode> nodes = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                nodes.add(new NativeRuntime.DensityNode(input.readInt(), input.readInt(), input.readInt(),
                        input.readInt(), input.readDouble(), input.readDouble(), input.readDouble()));
            }

            List<PerlinStack> stacks = new ArrayList<>(stackCount);
            int totalLayers = 0;
            for (int i = 0; i < stackCount; i++) {
                int layerCount = input.readInt();
                if (layerCount <= 0 || layerCount > MAX_LAYERS) return Optional.empty();
                totalLayers = Math.addExact(totalLayers, layerCount);
                if (totalLayers > MAX_TOTAL_LAYERS) return Optional.empty();
                List<PerlinLayer> layers = new ArrayList<>(layerCount);
                for (int layer = 0; layer < layerCount; layer++) {
                    byte[] permutation = input.readNBytes(256);
                    if (permutation.length != 256) return Optional.empty();
                    layers.add(new PerlinLayer(permutation, input.readDouble(), input.readDouble(),
                            input.readDouble(), input.readDouble(), input.readDouble(), input.readFloat()));
                }
                stacks.add(new PerlinStack(layers));
            }
            if (input.available() != 0) return Optional.empty();
            return Optional.of(new Graph(nodes, root, stacks));
        } catch (RuntimeException | IOException malformed) {
            // A cache file is an optimization artifact, never trusted worldgen input.
            // Any malformed descriptor or concurrent file replacement becomes a cache miss.
            return Optional.empty();
        }
    }

    private static byte[] encodeBody(byte[] fingerprint, long semanticFingerprint, Graph graph)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeLong(semanticFingerprint);
            output.write(fingerprint);
            output.writeInt(graph.rootNode());
            output.writeInt(graph.nodes().size());
            output.writeInt(graph.perlinStacks().size());
            for (NativeRuntime.DensityNode node : graph.nodes()) {
                output.writeInt(node.opcode());
                output.writeInt(node.inputA());
                output.writeInt(node.inputB());
                output.writeInt(node.reserved());
                output.writeDouble(node.immediate());
                output.writeDouble(node.auxiliary());
                output.writeDouble(node.tertiary());
            }
            for (PerlinStack stack : graph.perlinStacks()) {
                output.writeInt(stack.layers().size());
                for (PerlinLayer layer : stack.layers()) {
                    output.write(layer.permutation);
                    output.writeDouble(layer.offsetX());
                    output.writeDouble(layer.offsetY());
                    output.writeDouble(layer.offsetZ());
                    output.writeDouble(layer.xzScale());
                    output.writeDouble(layer.yScale());
                    output.writeFloat(layer.amplitude());
                }
            }
        }
        if (bytes.size() + DIGEST_BYTES > MAX_FILE_BYTES) {
            throw new IOException("Portable density graph exceeds cache size limit");
        }
        return bytes.toByteArray();
    }

    private static void validateGraph(List<NativeRuntime.DensityNode> nodes, int root, int stackCount) {
        if (nodes == null || nodes.isEmpty() || nodes.size() > MAX_NODES
                || root < 0 || root >= nodes.size() || stackCount < 0 || stackCount > MAX_STACKS) {
            throw new IllegalArgumentException("Invalid portable density graph dimensions");
        }
        for (NativeRuntime.DensityNode node : nodes) {
            if (node == null) throw new IllegalArgumentException("Null density node");
            if (node.opcode() == 7 || node.opcode() == 31 || node.opcode() == 33) slot(node.immediate(), stackCount);
        }
    }

    private static int slot(double encodedSlot, int stackCount) {
        if (!Double.isFinite(encodedSlot) || encodedSlot != Math.rint(encodedSlot)
                || encodedSlot < 0 || encodedSlot >= stackCount) {
            throw new IllegalArgumentException("Perlin node does not contain a valid portable stack slot");
        }
        return (int) encodedSlot;
    }

    private static void requireFingerprint(byte[] fingerprint) {
        if (fingerprint == null || fingerprint.length != 32) {
            throw new IllegalArgumentException("World fingerprint must be a 32-byte SHA-256 value");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
