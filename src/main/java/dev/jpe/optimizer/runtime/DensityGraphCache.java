package dev.jpe.optimizer.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Persistent cache for already-lowered density IR. A cache hit avoids touching
 * Minecraft's Java DensityFunction object graph on subsequent launches.
 *
 * The cache key must include Minecraft/loader/mod versions, dimension and
 * world-generation settings, relevant registry/config fingerprints, and any
 * other input that changes graph topology or semantics.
 */
public final class DensityGraphCache {
    private static final int MAGIC = 0x4A504544; // JPED
    private static final int FORMAT_VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final long MAX_CACHE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_DIAGNOSTIC_TEXT = 8192;

    public record LoadResult(DensityGraphIr graph, boolean cacheHit, Path cacheFile) {}

    private DensityGraphCache() {}

    public static Path nativeCachePath(Path directory, String cacheKey) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(cacheKey, "cacheKey");
        return directory.resolve(hex(sha256(cacheKey.getBytes(StandardCharsets.UTF_8))) + ".jpenative");
    }

    public static LoadResult loadOrBuild(Path directory, String cacheKey,
                                         Supplier<DensityGraphIr> builder) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(cacheKey, "cacheKey");
        Objects.requireNonNull(builder, "builder");
        if (cacheKey.isBlank()) throw new IllegalArgumentException("Density graph cache key must not be blank");
        Files.createDirectories(directory);
        byte[] keyDigest = sha256(cacheKey.getBytes(StandardCharsets.UTF_8));
        Path file = directory.resolve(hex(keyDigest) + ".jpedensity");
        Optional<DensityGraphIr> cached = read(file, keyDigest);
        if (cached.isPresent()) return new LoadResult(cached.get(), true, file);

        DensityGraphIr built = Objects.requireNonNull(builder.get(), "Density graph builder returned null");
        writeAtomically(file, keyDigest, built);
        return new LoadResult(built, false, file);
    }

    public static Optional<DensityGraphIr> read(Path file, String cacheKey) throws IOException {
        Objects.requireNonNull(cacheKey, "cacheKey");
        return read(file, sha256(cacheKey.getBytes(StandardCharsets.UTF_8)));
    }

    private static Optional<DensityGraphIr> read(Path file, byte[] expectedKeyDigest) throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        long size = Files.size(file);
        if (size < 4L + 4L + DIGEST_BYTES + DIGEST_BYTES || size > MAX_CACHE_BYTES) {
            return Optional.empty();
        }
        byte[] bytes = Files.readAllBytes(file);
        int payloadLength = bytes.length - DIGEST_BYTES;
        byte[] expectedChecksum = sha256(java.util.Arrays.copyOf(bytes, payloadLength));
        byte[] actualChecksum = java.util.Arrays.copyOfRange(bytes, payloadLength, bytes.length);
        if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) return Optional.empty();

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadLength))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) return Optional.empty();
            byte[] storedKey = in.readNBytes(DIGEST_BYTES);
            if (!MessageDigest.isEqual(storedKey, expectedKeyDigest)) return Optional.empty();
            long fingerprint = in.readLong();
            int root = in.readInt();
            int nodeCount = in.readInt();
            int diagnosticCount = in.readInt();
            if (nodeCount < 1 || nodeCount > DensityGraphIr.MAX_NODES
                    || diagnosticCount < 0 || diagnosticCount > nodeCount) return Optional.empty();

            ArrayList<DensityGraphIr.Node> nodes = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; ++i) {
                nodes.add(new DensityGraphIr.Node(in.readInt(), in.readInt(), in.readInt(), in.readDouble()));
            }
            ArrayList<DensityGraphIr.Diagnostic> diagnostics = new ArrayList<>(diagnosticCount);
            for (int i = 0; i < diagnosticCount; ++i) {
                int nodeIndex = in.readInt();
                String sourceClass = in.readUTF();
                String reason = in.readUTF();
                if (sourceClass.length() > MAX_DIAGNOSTIC_TEXT || reason.length() > MAX_DIAGNOSTIC_TEXT) {
                    return Optional.empty();
                }
                diagnostics.add(new DensityGraphIr.Diagnostic(nodeIndex, sourceClass, reason));
            }
            if (in.available() != 0) return Optional.empty();
            return Optional.of(new DensityGraphIr(nodes, diagnostics, root, fingerprint));
        } catch (IOException | IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
    }

    private static void writeAtomically(Path target, byte[] keyDigest, DensityGraphIr graph) throws IOException {
        byte[] payload = encode(keyDigest, graph);
        byte[] checksum = sha256(payload);
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(payload.length + checksum.length);
                buffer.put(payload).put(checksum).flip();
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] encode(byte[] keyDigest, DensityGraphIr graph) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.write(keyDigest);
            out.writeLong(graph.configurationFingerprint());
            out.writeInt(graph.rootNode());
            out.writeInt(graph.nodes().size());
            out.writeInt(graph.diagnostics().size());
            for (DensityGraphIr.Node node : graph.nodes()) {
                out.writeInt(node.opcode());
                out.writeInt(node.inputA());
                out.writeInt(node.inputB());
                out.writeDouble(node.immediate());
            }
            for (DensityGraphIr.Diagnostic diagnostic : graph.diagnostics()) {
                if (diagnostic.sourceClass().length() > MAX_DIAGNOSTIC_TEXT
                        || diagnostic.reason().length() > MAX_DIAGNOSTIC_TEXT) {
                    throw new IOException("Density graph diagnostic exceeds cache text limit");
                }
                out.writeInt(diagnostic.nodeIndex());
                out.writeUTF(diagnostic.sourceClass());
                out.writeUTF(diagnostic.reason());
            }
        }
        byte[] result = bytes.toByteArray();
        if (result.length + DIGEST_BYTES > MAX_CACHE_BYTES) {
            throw new IOException("Density graph cache entry exceeds size limit");
        }
        return result;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 0xF, 16))
                .append(Character.forDigit(value & 0xF, 16));
        return result.toString();
    }
}
