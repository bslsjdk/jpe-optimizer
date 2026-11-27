package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Drives the final-density plan through the real cdylib: compile a spline graph,
 * evaluate a volume, and compare it bit-for-bit with a Java-side expectation.
 */
class NativeFinalDensityPlanTest {
    private static DensityGraphIr splineGraph() {
        DensityGraphPayloadCapture tables = new DensityGraphPayloadCapture();
        int root = tables.reserveSpline();
        int low = tables.reserveSpline();
        int high = tables.reserveSpline();
        tables.defineConstantSpline(low, 1.0f);
        tables.defineConstantSpline(high, 5.0f);
        tables.defineMultipointSpline(root, 0,
                new float[] {0.0f, 8.0f}, new float[] {0.0f, 0.0f}, new int[] {low, high});
        return new DensityGraphIr(
                List.of(DensityGraphIr.Node.y(), DensityGraphIr.Node.spline(root)),
                List.of(), 1, 0x5151L, tables.build());
    }

    @Test
    void compilesEvaluatesAndComparesAVolumeThroughTheNativeLibrary() {
        Path library = NativeFinalDensityPlan.defaultLibrary();
        Assumptions.assumeTrue(library != null, "native runtime library is not built");

        DensityGraphIr graph = splineGraph();
        NativeFinalDensityPlan.Geometry geometry =
                new NativeFinalDensityPlan.Geometry(1, 1, 1, 0, 4, 0, 1, 1, 1);

        try (NativeFinalDensityPlan plan = NativeFinalDensityPlan.compile(library, graph)) {
            float[] values = plan.evaluate(geometry);
            assertEquals(1, values.length);
            assertEquals(3.0f, values[0]);
            assertEquals(0x5151L, plan.configurationFingerprint());

            assertTrue(plan.compare(new float[] {3.0f}, geometry).exact());

            NativeFinalDensityPlan.Parity parity = plan.compare(new float[] {3.5f}, geometry);
            assertEquals(1, parity.mismatches());
            assertEquals(0, parity.firstMismatch());
            assertEquals(3.5f, parity.javaValue());
            assertEquals(3.0f, parity.nativeValue());
        }
    }

    @Test
    void refusesToCompileAnExperimentalGraphForChunkOutput() {
        DensityGraphIr graph = new DensityGraphIr(
                List.of(DensityGraphIr.Node.opaque()), 0, 0L);
        Path library = NativeFinalDensityPlan.defaultLibrary();
        Assumptions.assumeTrue(library != null, "native runtime library is not built");
        assertThrows(IllegalArgumentException.class,
                () -> NativeFinalDensityPlan.compile(library, graph));
    }

    @Test
    void defaultLibraryResolutionNeverReturnsAMissingPath() {
        Path library = NativeFinalDensityPlan.defaultLibrary();
        if (library != null) {
            assertNotNull(library);
            assertTrue(library.toFile().exists());
        }
    }
}
