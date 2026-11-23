package dev.jpe.optimizer.math;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastMathTest {
    @Test
    void smoothstepErrorIsTiny() {
        Random r = new Random(0x5F00D12EL);
        float maxErr = 0f;
        for (int i = 0; i < 2_000_000; i++) {
            float x = r.nextFloat(); // [0,1)
            float exact = FastMath.smoothstepExact(x);
            float fast = FastMath.smoothstep(x);
            float err = Math.abs(exact - fast);
            if (err > maxErr) maxErr = err;
        }
        // 4096-segment linear interp of a smooth quartic: error should be tiny.
        assertTrue(maxErr < 1e-5f, "max smoothstep error = " + maxErr);
        System.out.println("MAX_SMOOTHSTEP_ERROR=" + maxErr);
    }

    @Test
    void smoothstepExactAtNodes() {
        for (int i = 0; i <= 64; i++) {
            float x = i / 64.0f;
            assertTrue(Float.floatToIntBits(FastMath.smoothstepExact(x))
                    == Float.floatToIntBits(FastMath.smoothstep(x)),
                    "node " + i);
        }
    }
}