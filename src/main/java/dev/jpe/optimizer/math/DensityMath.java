package dev.jpe.optimizer.math;

/** Float-only math candidates for the Minecraft 26.3 precision model. */
public final class DensityMath {
    private DensityMath() {}

    public static float syntheticGraph(float x, float y, float z) {
        float a = x * x + y * y;
        float b = z * z + a;
        float c = (x + y) * (x + y);
        float d = b + c;
        float e = (x * x + y * y) * 0.25f;
        return d + e;
    }

    public static float syntheticGraphShared(float x, float y, float z) {
        float x2 = x * x;
        float y2 = y * y;
        float xySum = x2 + y2;
        float xPlusY = x + y;
        float b = z * z + xySum;
        float c = xPlusY * xPlusY;
        float e = xySum * 0.25f;
        return (b + c) + e;
    }
}
