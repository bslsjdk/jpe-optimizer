package dev.jpe.optimizer.math;

/**
 * Fast smoothstep via a precomputed lookup table, the "memory for speed" trade
 * Rust-style. Vanilla: x*x*x*(x*(x*6-15)+10) for x in [0,1].
 *
 * The table has 4097 entries over [0,1] with linear interpolation between
 * neighbours, giving max abs error < 1e-6 (visually identical terrain). For x
 * outside [0,1] we defer to the exact polynomial, preserving vanilla behavior
 * at the boundaries.
 *
 * This is intended for Mixin @Redirect into net.minecraft.util.Mth.smoothstep,
 * which is called billions of times during world generation.
 */
public final class FastMath {
    private FastMath() {}

    private static final int BITS = 12;
    private static final int SIZE = 1 << BITS;          // 4096 segments
    private static final float SCALE = (float) SIZE;
    private static final float[] TABLE = new float[SIZE + 1];
    private static final float[] DERIV_TABLE = new float[SIZE + 1];

    static {
        for (int i = 0; i <= SIZE; i++) {
            float x = (float) i / SCALE;
            TABLE[i] = x * x * x * (x * (x * 6.0f - 15.0f) + 10.0f);
            DERIV_TABLE[i] = 30.0f * x * x * (x - 1.0f) * (x - 1.0f);
        }
    }

    /** Bit-exact reference (vanilla Mth.smoothstep). */
    public static float smoothstepExact(float x) {
        return x * x * x * (x * (x * 6.0f - 15.0f) + 10.0f);
    }

    /**
     * Table-based smoothstep with linear interpolation. Exact at table nodes,
     * < ~1e-6 error in between. Outside [0,1] falls back to exact polynomial.
     */
    public static float smoothstep(float x) {
        if (x <= 0.0f || x >= 1.0f) {
            return smoothstepExact(x);
        }
        float scaled = x * SCALE;
        int i = (int) scaled;
        float frac = scaled - i;
        float a = TABLE[i];
        float b = TABLE[i + 1];
        return a + frac * (b - a);
    }

    /** No-interp variant for benchmarking the pure table cost. */
    public static float smoothstepNearest(float x) {
        if (x <= 0.0f || x >= 1.0f) return smoothstepExact(x);
        return TABLE[(int) (x * SCALE)];
    }

    public static int tableSize() { return TABLE.length; }

    /** Bit-exact reference (vanilla Mth.smoothstepDerivative). */
    public static float smoothstepDerivativeExact(float x) {
        return 30.0f * x * x * (x - 1.0f) * (x - 1.0f);
    }

    /** LUT smoothstepDerivative with linear interpolation. */
    public static float smoothstepDerivative(float x) {
        if (x <= 0.0f || x >= 1.0f) return smoothstepDerivativeExact(x);
        float scaled = x * SCALE;
        int i = (int) scaled;
        float frac = scaled - i;
        float a = DERIV_TABLE[i];
        float b = DERIV_TABLE[i + 1];
        return a + frac * (b - a);
    }
}