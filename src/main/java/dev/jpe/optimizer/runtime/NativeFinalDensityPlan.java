package dev.jpe.optimizer.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One compiled native plan for a complete density graph.
 *
 * <p>This is the execution half of the {@code doFill} handoff: the graph is
 * compiled once, then every chunk volume is one bulk evaluate call. It is
 * Minecraft-free on purpose, so the compile/evaluate/compare path can be
 * exercised without the game.
 *
 * <p>The caller owns the decision to trust the output. {@link #compare} reports
 * raw bit mismatches against the Java volume; nothing here commits values into
 * a chunk.
 */
public final class NativeFinalDensityPlan implements AutoCloseable {
    /** Volume geometry in Minecraft's {@code min + index * step} convention. */
    public record Geometry(int sizeX, int sizeY, int sizeZ,
                           int minX, int minY, int minZ,
                           int stepX, int stepY, int stepZ) {
        public Geometry {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("Volume dimensions must be positive");
            }
            long samples = (long) sizeX * sizeY * sizeZ;
            if (samples > 1_048_576L) {
                throw new IllegalArgumentException("Volume exceeds the 1,048,576 sample limit");
            }
        }
        public int sampleCount() { return sizeX * sizeY * sizeZ; }
    }

    /** Bit-level comparison result for one volume. */
    public record Parity(int compared, int mismatches, int firstMismatch,
                         float javaValue, float nativeValue) {
        public boolean exact() { return mismatches == 0; }
    }

    private final Arena arena;
    private final NativeDensityProgramBridge bridge;
    private final MethodHandle evaluate;
    private final MethodHandle release;
    private final long handle;
    private final long configurationFingerprint;
    private volatile boolean closed;

    private NativeFinalDensityPlan(Arena arena, NativeDensityProgramBridge bridge,
                                   MethodHandle evaluate, MethodHandle release,
                                   long handle, long configurationFingerprint) {
        this.arena = arena;
        this.bridge = bridge;
        this.evaluate = evaluate;
        this.release = release;
        this.handle = handle;
        this.configurationFingerprint = configurationFingerprint;
    }

    /** Locates the runtime library the same way the test and the mod do. */
    public static Path defaultLibrary() {
        // A packaged jar carries the cdylib under META-INF/natives; the loader
        // also honours -Djpe.nativeLibraryPath and a local target/ build.
        return NativeLibraryLoader.resolve();
    }

    /** Compiles {@code graph} and retains the native plan until {@link #close()}. */
    public static NativeFinalDensityPlan compile(Path library, DensityGraphIr graph) {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(graph, "graph");
        if (graph.experimental()) {
            throw new IllegalArgumentException("A plan for chunk output must not contain OPAQUE nodes");
        }
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
            Linker linker = Linker.nativeLinker();
            NativeDensityProgramBridge bridge = NativeDensityProgramBridge.bind(lookup, linker);
            MethodHandle evaluate = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_evaluate").orElseThrow(() ->
                            new IllegalStateException("Missing density graph evaluate symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MethodHandle release = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_release").orElseThrow(() ->
                            new IllegalStateException("Missing density graph release symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

            long handle;
            try (Arena scratch = Arena.ofConfined()) {
                byte[] encoded = encodeNodes(graph.nodes());
                MemorySegment nodes = scratch.allocate(encoded.length, 8);
                MemorySegment.copy(encoded, 0, nodes, ValueLayout.JAVA_BYTE, 0, encoded.length);
                handle = bridge.compile(nodes, graph.nodes().size(), graph.rootNode(),
                        graph.configurationFingerprint(), MemorySegment.NULL, 0,
                        DensityGraphProgram.from(graph.payload()));
            }
            return new NativeFinalDensityPlan(arena, bridge, evaluate, release, handle,
                    graph.configurationFingerprint());
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Unable to compile the native final-density plan", failure);
        }
    }

    public long configurationFingerprint() { return configurationFingerprint; }
    public long handle() { return handle; }

    /** Evaluates the whole volume. The returned array is owned by the caller. */
    public float[] evaluate(Geometry geometry) {
        Objects.requireNonNull(geometry, "geometry");
        if (closed) throw new IllegalStateException("Plan is closed");
        float[] output = new float[geometry.sampleCount()];
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment buffer = scratch.allocate((long) output.length * Float.BYTES, 8);
            int status;
            try {
                status = (int) evaluate.invokeExact(handle,
                        geometry.sizeX(), geometry.sizeY(), geometry.sizeZ(),
                        geometry.minX(), geometry.minY(), geometry.minZ(),
                        geometry.stepX(), geometry.stepY(), geometry.stepZ(),
                        buffer, (long) output.length);
            } catch (Throwable failure) {
                throw new IllegalStateException("Native density evaluation failed", failure);
            }
            if (status != 0) {
                throw new IllegalStateException("Native density evaluation returned status " + status);
            }
            MemorySegment.copy(buffer, ValueLayout.JAVA_FLOAT, 0, output, 0, output.length);
        }
        return output;
    }

    /** Evaluates the volume and compares it with a Java-produced one, bit for bit. */
    public Parity compare(float[] javaVolume, Geometry geometry) {
        return compare(javaVolume, evaluate(geometry));
    }

    /** Compares two already-evaluated volumes bit for bit. */
    public Parity compare(float[] javaVolume, float[] nativeVolume) {
        if (javaVolume.length != nativeVolume.length) {
            throw new IllegalArgumentException("Java volume length does not match the geometry");
        }
        int mismatches = 0;
        int first = -1;
        float javaValue = 0.0f;
        float nativeValue = 0.0f;
        for (int i = 0; i < javaVolume.length; ++i) {
            if (Float.floatToRawIntBits(javaVolume[i]) != Float.floatToRawIntBits(nativeVolume[i])) {
                if (first < 0) {
                    first = i;
                    javaValue = javaVolume[i];
                    nativeValue = nativeVolume[i];
                }
                ++mismatches;
            }
        }
        return new Parity(javaVolume.length, mismatches, first, javaValue, nativeValue);
    }

    static byte[] encodeNodes(java.util.List<DensityGraphIr.Node> nodes) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer
                .allocate(nodes.size() * 24)
                .order(java.nio.ByteOrder.nativeOrder());
        for (DensityGraphIr.Node node : nodes) {
            buffer.putInt(node.opcode());
            // NO_INPUT is -1, which is already 0xffffffff. Do not collapse every
            // negative value: gradient nodes store raw negative coordinates here.
            buffer.putInt(node.inputA());
            buffer.putInt(node.inputB());
            buffer.putInt(node.opcode() == DensityGraphIr.OPAQUE ? 1 : node.reserved());
            buffer.putDouble(node.immediate());
        }
        return buffer.array();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            release.invokeExact(handle);
        } catch (Throwable ignored) {
            // The arena close below still releases the library; a failed release
            // only leaves the plan to the runtime's own cache eviction.
        } finally {
            bridge.close();
            arena.close();
        }
    }
}
