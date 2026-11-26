package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeRuntimeValidationTest {
    @Test
    void densityGraphPerlinNodePreservesDoublePrecisionScales() {
        double xzScale = Math.nextUp(0.1D);
        double yScale = Math.nextDown(0.2D);
        NativeRuntime.DensityNode node =
                NativeRuntime.DensityNode.perlinStack(17L, xzScale, yScale, 0.75F);

        assertEquals(17L, (long) node.immediate());
        assertEquals(Double.doubleToRawLongBits(xzScale),
                Double.doubleToRawLongBits(node.auxiliary()));
        assertEquals(Double.doubleToRawLongBits(yScale),
                Double.doubleToRawLongBits(node.tertiary()));
        assertEquals(-1, node.inputA());
        assertEquals(-1, node.inputB());
    }

    @Test
    void acceptsValidPerlinStackAndPooledOutputBuffer() {
        int length = NativeRuntime.validatePerlinStackInputs(List.of(
                new NativeRuntime.PerlinLayer(new byte[256], 0, 0, 0, 1, 1, 0.5F),
                new NativeRuntime.PerlinLayer(new byte[256], 1, 2, 3, 2, 2, 0.25F)
        ), 2, 3, 4, 1, 1, 1, new float[32]);

        assertEquals(24, length);
    }

    @Test
    void rejectsEmptyPerlinStack() {
        assertThrows(IllegalArgumentException.class, () ->
                NativeRuntime.validatePerlinStackInputs(List.of(),
                        2, 3, 4, 1, 1, 1, new float[24]));
    }

    @Test
    void rejectsInvalidPermutationInPerlinStack() {
        assertThrows(IllegalArgumentException.class, () ->
                NativeRuntime.validatePerlinStackInputs(List.of(
                        new NativeRuntime.PerlinLayer(new byte[255], 0, 0, 0, 1, 1, 1)
                ), 1, 1, 1, 1, 1, 1, new float[1]));
    }

    @Test
    void acceptsPooledOutputBufferWithExtraCapacity() {
        int length = NativeRuntime.validatePerlinInputs(
                new byte[256], 2, 3, 4, 1, 1, 1, new float[32]
        );

        assertEquals(24, length);
    }

    @Test
    void acceptsExactOutputBuffer() {
        int length = NativeRuntime.validatePerlinInputs(
                new byte[256], 2, 3, 4, 1, 1, 1, new float[24]
        );

        assertEquals(24, length);
    }

    @Test
    void rejectsOutputBufferSmallerThanRequestedVolume() {
        assertThrows(IllegalArgumentException.class, () ->
                NativeRuntime.validatePerlinInputs(
                        new byte[256], 2, 3, 4, 1, 1, 1, new float[23]
                )
        );
    }

    @Test
    void rejectsZeroAxisStep() {
        assertThrows(IllegalArgumentException.class, () ->
                NativeRuntime.validatePerlinInputs(
                        new byte[256], 2, 3, 4, 1, 0, 1, new float[24]
                )
        );
    }
}
