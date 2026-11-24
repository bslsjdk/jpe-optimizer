package dev.jpe.optimizer.vector;

public interface DensityVectorKernel {
    enum BinaryOp { ADD, SUBTRACT, MULTIPLY, DIVIDE }

    void rangeChoiceConst(float[] values, int length, float minInclusive, float maxExclusive, float whenInRange, float whenOutOfRange);
    void rangeChoice(float[] values, float[] input, float[] whenOutOfRange, int length, float minInclusive, float maxExclusive);
    void minMax(float[] left, float[] right, int length, boolean minimum);
    void minMaxConst(float[] values, int length, float operand, boolean minimum);
    void binary(float[] left, float[] right, int length, BinaryOp operation);
    void binaryConst(float[] values, int length, float operand, BinaryOp operation);
    void binaryConstLeft(float[] values, int length, float operand, BinaryOp operation);
    void perlinLerpAdd(float[] values, int outputOffset, float[] relativeY, int length, float alphaX, float alphaZ, float amplitude, float[] cornerXZ, float[] cornerY);
    int lanes();
    String description();
}
