package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeRuntimeValidationTest {
    @Test
    void acceptsPooledOutputBufferWithExtraCapacity() {
        int length = NativeRuntime.validatePerlinInputs(
                new byte[256], new int[2], new int[3], new int[4], new float[32]
        );

        assertEquals(24, length);
    }

    @Test
    void acceptsExactOutputBuffer() {
        int length = NativeRuntime.validatePerlinInputs(
                new byte[256], new int[2], new int[3], new int[4], new float[24]
        );

        assertEquals(24, length);
    }

    @Test
    void rejectsOutputBufferSmallerThanRequestedVolume() {
        assertThrows(IllegalArgumentException.class, () ->
                NativeRuntime.validatePerlinInputs(
                        new byte[256], new int[2], new int[3], new int[4], new float[23]
                )
        );
    }
}
