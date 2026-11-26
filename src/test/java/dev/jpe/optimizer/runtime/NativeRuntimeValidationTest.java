package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeRuntimeValidationTest {
    @Test
    void densityGraphRangeChoiceEncodesThreeInputsAndFloatBounds() {
        NativeRuntime.DensityNode node =
                NativeRuntime.DensityNode.rangeChoice(1, -0.25F, 0.75F, 2, 3);
        assertEquals(27, node.opcode());
        assertEquals(1, node.inputA());
        assertEquals(2, node.inputB());
        assertEquals(3, node.reserved());
        assertEquals(-0.25D, node.immediate());
        assertEquals(0.75D, node.auxiliary());
    }

    @Test
    void densityGraphLerpSqueezeAndGradientNodesEncodeTheirOperands() {
        NativeRuntime.DensityNode lerp = NativeRuntime.DensityNode.lerp(2, 3, 4);
        assertEquals(24, lerp.opcode());
        assertEquals(2, lerp.inputA());
        assertEquals(3, lerp.inputB());
        assertEquals(4, lerp.reserved());

        NativeRuntime.DensityNode squeeze = NativeRuntime.DensityNode.squeeze(7);
        assertEquals(23, squeeze.opcode());
        assertEquals(7, squeeze.inputA());

        NativeRuntime.DensityNode gradient =
                NativeRuntime.DensityNode.gradient(1, -64, -40, 0.0F, 1.0F);
        assertEquals(25, gradient.opcode());
        assertEquals(1, gradient.inputA());
        assertEquals(0, gradient.inputB());
        assertEquals(Float.floatToRawIntBits(0.0F), gradient.reserved());
        assertEquals(-64.0D, gradient.immediate());
        assertEquals(-40.0D, gradient.auxiliary());
        assertEquals(1.0D, gradient.tertiary());
    }

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
