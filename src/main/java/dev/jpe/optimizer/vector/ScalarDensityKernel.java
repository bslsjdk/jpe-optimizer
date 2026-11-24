package dev.jpe.optimizer.vector;

import java.util.Objects;

/** Scalar reference and safe fallback for vectorized density operations. */
public final class ScalarDensityKernel implements DensityVectorKernel {
    public static final ScalarDensityKernel INSTANCE = new ScalarDensityKernel();
    private ScalarDensityKernel() {}

    @Override
    public void rangeChoiceConst(float[] values, int length, float minInclusive, float maxExclusive,
                                 float whenInRange, float whenOutOfRange) {
        Objects.checkFromIndexSize(0, length, values.length);
        for (int i = 0; i < length; ++i) {
            float input = values[i];
            values[i] = input >= minInclusive && input < maxExclusive ? whenInRange : whenOutOfRange;
        }
    }

    @Override
    public void rangeChoice(float[] values, float[] input, float[] whenOutOfRange,
                            int length, float minInclusive, float maxExclusive) {
        Objects.checkFromIndexSize(0, length, values.length);
        Objects.checkFromIndexSize(0, length, input.length);
        Objects.checkFromIndexSize(0, length, whenOutOfRange.length);
        for (int i = 0; i < length; ++i) {
            float selected = input[i];
            if (!(selected >= minInclusive) || !(selected < maxExclusive)) {
                values[i] = whenOutOfRange[i];
            }
        }
    }

    @Override
    public void minMax(float[] left, float[] right, int length, boolean minimum) {
        Objects.checkFromIndexSize(0, length, left.length);
        Objects.checkFromIndexSize(0, length, right.length);
        for (int i = 0; i < length; ++i) {
            float a = left[i], b = right[i];
            if (minimum ? b < a : b > a) left[i] = b;
        }
    }

    @Override
    public void minMaxConst(float[] values, int length, float operand, boolean minimum) {
        Objects.checkFromIndexSize(0, length, values.length);
        for (int i = 0; i < length; ++i) {
            float value = values[i];
            if (minimum ? operand < value : operand > value) values[i] = operand;
        }
    }

    @Override
    public void binary(float[] left, float[] right, int length, BinaryOp operation) {
        Objects.checkFromIndexSize(0, length, left.length);
        Objects.checkFromIndexSize(0, length, right.length);
        switch (operation) {
            case ADD -> { for (int i = 0; i < length; ++i) left[i] = left[i] + right[i]; }
            case SUBTRACT -> { for (int i = 0; i < length; ++i) left[i] = left[i] - right[i]; }
            case MULTIPLY -> { for (int i = 0; i < length; ++i) left[i] = left[i] * right[i]; }
            case DIVIDE -> { for (int i = 0; i < length; ++i) left[i] = left[i] / right[i]; }
        }
    }

    @Override
    public void binaryConst(float[] values, int length, float operand, BinaryOp operation) {
        Objects.checkFromIndexSize(0, length, values.length);
        switch (operation) {
            case ADD -> { for (int i = 0; i < length; ++i) values[i] = values[i] + operand; }
            case SUBTRACT -> { for (int i = 0; i < length; ++i) values[i] = values[i] - operand; }
            case MULTIPLY -> { for (int i = 0; i < length; ++i) values[i] = values[i] * operand; }
            case DIVIDE -> { for (int i = 0; i < length; ++i) values[i] = values[i] / operand; }
        }
    }

    @Override
    public void binaryConstLeft(float[] values, int length, float operand, BinaryOp operation) {
        Objects.checkFromIndexSize(0, length, values.length);
        switch (operation) {
            case ADD -> { for (int i = 0; i < length; ++i) values[i] = operand + values[i]; }
            case SUBTRACT -> { for (int i = 0; i < length; ++i) values[i] = operand - values[i]; }
            case MULTIPLY -> { for (int i = 0; i < length; ++i) values[i] = operand * values[i]; }
            case DIVIDE -> { for (int i = 0; i < length; ++i) values[i] = operand / values[i]; }
        }
    }

    @Override
    public void perlinLerpAdd(float[] values, int outputOffset, float[] relativeY, int length, float alphaX, float alphaZ, float amplitude, float[] cornerXZ, float[] cornerY) {
        Objects.checkFromIndexSize(outputOffset, length, values.length);
        Objects.checkFromIndexSize(0, length, relativeY.length);
        Objects.checkFromIndexSize(0, 8, cornerXZ.length);
        Objects.checkFromIndexSize(0, 8, cornerY.length);
        for (int i = 0; i < length; ++i) {
            float ry = relativeY[i], ry1 = ry - 1.0F;
            float x000 = cornerXZ[0] + cornerY[0] * ry, x100 = cornerXZ[1] + cornerY[1] * ry;
            float x010 = cornerXZ[2] + cornerY[2] * ry1, x110 = cornerXZ[3] + cornerY[3] * ry1;
            float x001 = cornerXZ[4] + cornerY[4] * ry, x101 = cornerXZ[5] + cornerY[5] * ry;
            float x011 = cornerXZ[6] + cornerY[6] * ry1, x111 = cornerXZ[7] + cornerY[7] * ry1;
            float alphaY = smoothstep(ry);
            float lower = lerp(alphaY, lerp(alphaX, x000, x100), lerp(alphaX, x010, x110));
            float upper = lerp(alphaY, lerp(alphaX, x001, x101), lerp(alphaX, x011, x111));
            values[outputOffset + i] = values[outputOffset + i] + amplitude * lerp(alphaZ, lower, upper);
        }
    }
    private static float lerp(float a, float x, float y) { return x + a * (y - x); }
    private static float smoothstep(float x) { return x * x * x * (x * (x * 6.0F - 15.0F) + 10.0F); }
    @Override public int lanes() { return 1; }
    @Override
    public String description() { return "scalar reference"; }
}
