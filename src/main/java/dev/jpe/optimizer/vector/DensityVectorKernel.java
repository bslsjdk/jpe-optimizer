package dev.jpe.optimizer.vector;

public interface DensityVectorKernel {
    void rangeChoiceConst(float[] values, int length, float minInclusive, float maxExclusive, float whenInRange, float whenOutOfRange);
    void rangeChoice(float[] values, float[] input, float[] whenOutOfRange, int length, float minInclusive, float maxExclusive);
    void minMax(float[] left, float[] right, int length, boolean minimum);
    void minMaxConst(float[] values, int length, float operand, boolean minimum);
    String description();
}
