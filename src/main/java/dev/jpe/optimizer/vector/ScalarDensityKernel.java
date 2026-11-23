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
    public String description() { return "scalar reference"; }
}
