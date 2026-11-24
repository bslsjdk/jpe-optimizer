package dev.jpe.optimizer.math;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DensityMathTest {
    @Test
    void sharedExpressionMustBeBitIdenticalForDeterministicInputs() {
        Random random = new Random(0x4A50454CL);
        for (int i = 0; i < 1_000_000; i++) {
            float x = random.nextFloat(-2.0f, 2.0f);
            float y = random.nextFloat(-2.0f, 2.0f);
            float z = random.nextFloat(-2.0f, 2.0f);
            assertEquals(
                    Float.floatToIntBits(DensityMath.syntheticGraph(x, y, z)),
                    Float.floatToIntBits(DensityMath.syntheticGraphShared(x, y, z)),
                    "sample " + i
            );
        }
    }
}
