package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the Java to native path for the extended node set.
 *
 * <p>Loads the real cdylib and drives it through {@link NativeDensityProgramBridge},
 * so a layout mistake in the payload tables or a missing symbol fails here rather
 * than in game. The test skips when the library has not been built, which keeps a
 * plain {@code gradle test} green without a toolchain.
 */
class NativeDensityProgramBridgeTest {
    private static final int NODE_BYTES = 24;
    private static final ByteOrder ORDER = ByteOrder.nativeOrder();

    private static Path locateLibrary() {
        String override = System.getProperty("jpe.native.library");
        if (override != null && !override.isBlank()) return Path.of(override);
        String[] candidates = {
                "native/rust-runtime/target/release/libjpe_runtime.so",
                "native/rust-runtime/target/release/libjpe_runtime.dylib",
                "native/rust-runtime/target/release/jpe_runtime.dll",
                "native/rust-runtime/target/debug/libjpe_runtime.so"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) return path;
        }
        return null;
    }

    private static byte[] encodeNodes(List<DensityGraphIr.Node> nodes) {
        ByteBuffer buffer = ByteBuffer.allocate(nodes.size() * NODE_BYTES).order(ORDER);
        for (DensityGraphIr.Node node : nodes) {
            buffer.putInt(node.opcode());
            buffer.putInt(node.inputA());
            buffer.putInt(node.inputB());
            buffer.putInt(node.opcode() == DensityGraphIr.OPAQUE ? 1 : node.reserved());
            buffer.putDouble(node.immediate());
        }
        return buffer.array();
    }

    private static final class Session implements AutoCloseable {
        final Arena arena = Arena.ofShared();
        final NativeDensityProgramBridge bridge;
        final MethodHandle evaluate;
        final MethodHandle release;

        Session(Path library) throws Exception {
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
            Linker linker = Linker.nativeLinker();
            bridge = NativeDensityProgramBridge.bind(lookup, linker);
            evaluate = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_evaluate").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            release = linker.downcallHandle(
                    lookup.find("jpe_runtime_density_graph_release").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        }

        float evaluateAt(List<DensityGraphIr.Node> nodes, DensityGraphIr.Payload payload,
                         int root, int x, int y, int z) throws Throwable {
            try (Arena scratch = Arena.ofConfined()) {
                byte[] encoded = encodeNodes(nodes);
                MemorySegment nodeSegment = scratch.allocate(encoded.length, 8);
                MemorySegment.copy(encoded, 0, nodeSegment, ValueLayout.JAVA_BYTE, 0, encoded.length);
                long handle = bridge.compile(nodeSegment, nodes.size(), root, 0x99L,
                        MemorySegment.NULL, 0, DensityGraphProgram.from(payload));
                try {
                    MemorySegment out = scratch.allocate(4, 4);
                    int status = (int) evaluate.invokeExact(handle, 1, 1, 1, x, y, z, 1, 1, 1, out, 1L);
                    assertEquals(0, status, "native evaluate failed");
                    return out.get(ValueLayout.JAVA_FLOAT, 0);
                } finally {
                    assertEquals(0, (int) release.invokeExact(handle), "native release failed");
                }
            }
        }

        @Override
        public void close() {
            bridge.close();
            arena.close();
        }
    }

    @Test
    void extendedNodesEvaluateThroughTheNativeLibrary() throws Throwable {
        Path library = locateLibrary();
        Assumptions.assumeTrue(library != null, "native density library has not been built");
        try (Session session = new Session(library)) {
            // Spline over X: two zero-derivative points interpolate linearly.
            List<DensityGraphIr.Node> splineNodes = List.of(
                    DensityGraphIr.Node.x(), DensityGraphIr.Node.spline(0));
            DensityGraphIr.Payload splinePayload = new DensityGraphIr.Payload(
                    List.of(DensityGraphIr.SplineNode.multipoint(0, 0, 2),
                            DensityGraphIr.SplineNode.constant(0.0f),
                            DensityGraphIr.SplineNode.constant(10.0f)),
                    List.of(new DensityGraphIr.SplinePoint(0.0f, 0.0f, 1),
                            new DensityGraphIr.SplinePoint(10.0f, 0.0f, 2)),
                    List.of(), new float[0], new int[0], List.of(),
                    DensityGraphIr.BlendRegion.inactive(), null);
            assertEquals(5.0f, session.evaluateAt(splineNodes, splinePayload, 1, 5, 0, 0));

            // Truncate rounds toward zero for negatives.
            List<DensityGraphIr.Node> roundNodes = List.of(
                    DensityGraphIr.Node.constant(-2.5),
                    DensityGraphIr.Node.round(0, DensityGraphIr.NO_INPUT, DensityGraphIr.ROUND_TRUNCATE));
            assertEquals(-2.0f, session.evaluateAt(roundNodes, DensityGraphIr.Payload.empty(), 1, 0, 0, 0));

            // A Y slice ignores the requested Y entirely.
            List<DensityGraphIr.Node> sliceNodes = List.of(
                    DensityGraphIr.Node.y(), DensityGraphIr.Node.slice(1, 0, 7));
            assertEquals(7.0f, session.evaluateAt(sliceNodes, DensityGraphIr.Payload.empty(), 1, 0, 99, 0));

            // Threshold ownership: value < threshold selects the first branch.
            List<DensityGraphIr.Node> intervalNodes = List.of(
                    DensityGraphIr.Node.x(),
                    DensityGraphIr.Node.constant(1.0),
                    DensityGraphIr.Node.constant(2.0),
                    DensityGraphIr.Node.intervalSelect(0));
            DensityGraphIr.Payload intervalPayload = new DensityGraphIr.Payload(
                    List.of(), List.of(),
                    List.of(new DensityGraphIr.IntervalSelect(0, 0, 1, 0)),
                    new float[] {0.0f}, new int[] {1, 2}, List.of(),
                    DensityGraphIr.BlendRegion.inactive(), null);
            assertEquals(1.0f, session.evaluateAt(intervalNodes, intervalPayload, 3, -4, 0, 0));
            assertEquals(2.0f, session.evaluateAt(intervalNodes, intervalPayload, 3, 4, 0, 0));

            // Euclidean distance from (0,0,0) to (3,4,0).
            List<DensityGraphIr.Node> distanceNodes = List.of(DensityGraphIr.Node.distanceToPoint(0));
            DensityGraphIr.Payload distancePayload = new DensityGraphIr.Payload(
                    List.of(), List.of(), List.of(), new float[0], new int[0],
                    List.of(new DensityGraphIr.DistancePoint(0, 0, 0, 0)),
                    DensityGraphIr.BlendRegion.inactive(), null);
            assertEquals(5.0f, session.evaluateAt(distanceNodes, distancePayload, 0, 3, 4, 0));

            // EndIsland is column-constant and stays inside its declared range.
            byte[] permutation = new byte[256];
            for (int i = 0; i < permutation.length; i++) permutation[i] = (byte) i;
            List<DensityGraphIr.Node> islandNodes = List.of(DensityGraphIr.Node.endIsland());
            DensityGraphIr.Payload islandPayload = new DensityGraphIr.Payload(
                    List.of(), List.of(), List.of(), new float[0], new int[0], List.of(),
                    DensityGraphIr.BlendRegion.inactive(), permutation);
            float low = session.evaluateAt(islandNodes, islandPayload, 0, 16, 0, 0);
            float high = session.evaluateAt(islandNodes, islandPayload, 0, 16, 200, 0);
            assertEquals(low, high, "end island must not depend on Y");
            assertTrue(low >= -0.84375f && low <= 0.5625f, "end island out of range: " + low);

            // density = 10 - Y over a 0..32 bound: the first positive probe is 9.
            List<DensityGraphIr.Node> topNodes = List.of(
                    DensityGraphIr.Node.constant(10.0),
                    DensityGraphIr.Node.y(),
                    DensityGraphIr.Node.subtract(0, 1),
                    DensityGraphIr.Node.constant(32.0),
                    DensityGraphIr.Node.findTopSurface(2, 3, 0, 1));
            assertEquals(9.0f, session.evaluateAt(topNodes, DensityGraphIr.Payload.empty(), 4, 0, 0, 0));
        }
    }
}
