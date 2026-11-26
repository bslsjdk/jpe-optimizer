package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeRuntimeValidationTest {
    @Test
    void acceptsMatchingAquiferBatchCoordinatesForBothRandomModes() {
        assertEquals(3, NativeRuntime.validateAquiferNearestFour(0,
                new int[3], new int[3], new int[3]));
        assertEquals(2, NativeRuntime.validateAquiferNearestFour(1,
                new int[2], new int[2], new int[2]));
    }

    @Test
    void rejectsMismatchedAquiferBatchCoordinates() {
        assertThrows(IllegalArgumentException.class, () ->
                NativeRuntime.validateAquiferNearestFour(0,
                        new int[3], new int[2], new int[3]));
    }

    @Test
    void rejectsInvalidAquiferBatchModeAndEmptyBatch() {
        assertThrows(IllegalArgumentException.class, () ->
                NativeRuntime.validateAquiferNearestFour(2,
                        new int[1], new int[1], new int[1]));
        assertThrows(IllegalArgumentException.class, () ->
                NativeRuntime.validateAquiferNearestFour(0,
                        new int[0], new int[0], new int[0]));
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
