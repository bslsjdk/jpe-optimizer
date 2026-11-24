package dev.jpe.optimizer.vector;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import java.util.Objects;

/** Optional Java 25 Vector API kernels with scalar tails and vanilla comparisons. */
public final class VectorApiDensityKernel implements DensityVectorKernel {
    public static final VectorApiDensityKernel INSTANCE = new VectorApiDensityKernel();
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;
    private VectorApiDensityKernel() {}

    @Override
    public void rangeChoiceConst(float[] values, int length, float minInclusive, float maxExclusive,
                                 float whenInRange, float whenOutOfRange) {
        Objects.checkFromIndexSize(0, length, values.length);
        FloatVector in = FloatVector.broadcast(SPECIES, whenInRange);
        FloatVector out = FloatVector.broadcast(SPECIES, whenOutOfRange);
        int bound = SPECIES.loopBound(length), i = 0;
        for (; i < bound; i += SPECIES.length()) {
            FloatVector input = FloatVector.fromArray(SPECIES, values, i);
            VectorMask<Float> selected = input.compare(VectorOperators.GE, minInclusive)
                    .and(input.compare(VectorOperators.LT, maxExclusive));
            out.blend(in, selected).intoArray(values, i);
        }
        for (; i < length; ++i) {
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
        int bound = SPECIES.loopBound(length), i = 0;
        for (; i < bound; i += SPECIES.length()) {
            FloatVector in = FloatVector.fromArray(SPECIES, input, i);
            FloatVector out = FloatVector.fromArray(SPECIES, whenOutOfRange, i);
            VectorMask<Float> outside = in.compare(VectorOperators.GE, minInclusive)
                    .and(in.compare(VectorOperators.LT, maxExclusive)).not();
            out.intoArray(values, i, outside);
        }
        for (; i < length; ++i) {
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
        int bound = SPECIES.loopBound(length), i = 0;
        for (; i < bound; i += SPECIES.length()) {
            FloatVector a = FloatVector.fromArray(SPECIES, left, i);
            FloatVector b = FloatVector.fromArray(SPECIES, right, i);
            VectorMask<Float> replace = minimum
                    ? b.compare(VectorOperators.LT, a)
                    : b.compare(VectorOperators.GT, a);
            b.intoArray(left, i, replace);
        }
        for (; i < length; ++i) {
            float a = left[i], b = right[i];
            if (minimum ? b < a : b > a) left[i] = b;
        }
    }

    @Override
    public void minMaxConst(float[] values, int length, float operand, boolean minimum) {
        Objects.checkFromIndexSize(0, length, values.length);
        FloatVector constant = FloatVector.broadcast(SPECIES, operand);
        int bound = SPECIES.loopBound(length), i = 0;
        for (; i < bound; i += SPECIES.length()) {
            FloatVector current = FloatVector.fromArray(SPECIES, values, i);
            VectorMask<Float> replace = minimum
                    ? current.compare(VectorOperators.GT, operand)
                    : current.compare(VectorOperators.LT, operand);
            constant.intoArray(values, i, replace);
        }
        for (; i < length; ++i) {
            float current = values[i];
            if (minimum ? operand < current : operand > current) values[i] = operand;
        }
    }

    @Override
    public void binary(float[] left, float[] right, int length, BinaryOp operation) {
        Objects.checkFromIndexSize(0, length, left.length);
        Objects.checkFromIndexSize(0, length, right.length);
        if (containsNaN(left, length) || containsNaN(right, length)) {
            ScalarDensityKernel.INSTANCE.binary(left, right, length, operation);
            return;
        }
        int bound = SPECIES.loopBound(length), i = 0;
        for (; i < bound; i += SPECIES.length()) {
            FloatVector a = FloatVector.fromArray(SPECIES, left, i);
            FloatVector b = FloatVector.fromArray(SPECIES, right, i);
            FloatVector result = switch (operation) {
                case ADD -> a.add(b);
                case SUBTRACT -> a.sub(b);
                case MULTIPLY -> a.mul(b);
                case DIVIDE -> a.div(b);
            };
            if (result.compare(VectorOperators.NE, result).anyTrue()) {
                for (int lane = 0; lane < SPECIES.length(); ++lane) {
                    int index = i + lane;
                    float aValue = left[index], bValue = right[index];
                    left[index] = switch (operation) {
                        case ADD -> aValue + bValue;
                        case SUBTRACT -> aValue - bValue;
                        case MULTIPLY -> aValue * bValue;
                        case DIVIDE -> aValue / bValue;
                    };
                }
                i += SPECIES.length();
                for (; i < length; ++i) {
                    float aValue = left[i], bValue = right[i];
                    left[i] = switch (operation) {
                        case ADD -> aValue + bValue;
                        case SUBTRACT -> aValue - bValue;
                        case MULTIPLY -> aValue * bValue;
                        case DIVIDE -> aValue / bValue;
                    };
                }
                return;
            }
            result.intoArray(left, i);
        }
        for (; i < length; ++i) {
            float a = left[i], b = right[i];
            left[i] = switch (operation) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> a / b;
            };
        }
    }

    @Override
    public void binaryConst(float[] values, int length, float operand, BinaryOp operation) {
        Objects.checkFromIndexSize(0, length, values.length);
        if (Float.isNaN(operand) || containsNaN(values, length)) {
            ScalarDensityKernel.INSTANCE.binaryConst(values, length, operand, operation);
            return;
        }
        FloatVector constant = FloatVector.broadcast(SPECIES, operand);
        int bound = SPECIES.loopBound(length), i = 0;
        for (; i < bound; i += SPECIES.length()) {
            FloatVector current = FloatVector.fromArray(SPECIES, values, i);
            FloatVector result = switch (operation) {
                case ADD -> current.add(constant);
                case SUBTRACT -> current.sub(constant);
                case MULTIPLY -> current.mul(constant);
                case DIVIDE -> current.div(constant);
            };
            if (result.compare(VectorOperators.NE, result).anyTrue()) {
                for (int lane = 0; lane < SPECIES.length(); ++lane) {
                    int index = i + lane;
                    float currentValue = values[index];
                    values[index] = switch (operation) {
                        case ADD -> currentValue + operand;
                        case SUBTRACT -> currentValue - operand;
                        case MULTIPLY -> currentValue * operand;
                        case DIVIDE -> currentValue / operand;
                    };
                }
                i += SPECIES.length();
                for (; i < length; ++i) {
                    float currentValue = values[i];
                    values[i] = switch (operation) {
                        case ADD -> currentValue + operand;
                        case SUBTRACT -> currentValue - operand;
                        case MULTIPLY -> currentValue * operand;
                        case DIVIDE -> currentValue / operand;
                    };
                }
                return;
            }
            result.intoArray(values, i);
        }
        for (; i < length; ++i) {
            float current = values[i];
            values[i] = switch (operation) {
                case ADD -> current + operand;
                case SUBTRACT -> current - operand;
                case MULTIPLY -> current * operand;
                case DIVIDE -> current / operand;
            };
        }
    }

    @Override
    public void binaryConstLeft(float[] values, int length, float operand, BinaryOp operation) {
        Objects.checkFromIndexSize(0, length, values.length);
        if (Float.isNaN(operand) || containsNaN(values, length)) {
            ScalarDensityKernel.INSTANCE.binaryConstLeft(values, length, operand, operation);
            return;
        }
        FloatVector constant = FloatVector.broadcast(SPECIES, operand);
        int bound = SPECIES.loopBound(length), i = 0;
        for (; i < bound; i += SPECIES.length()) {
            FloatVector current = FloatVector.fromArray(SPECIES, values, i);
            FloatVector result = switch (operation) {
                case ADD -> constant.add(current);
                case SUBTRACT -> constant.sub(current);
                case MULTIPLY -> constant.mul(current);
                case DIVIDE -> constant.div(current);
            };
            if (result.compare(VectorOperators.NE, result).anyTrue()) {
                for (int lane = 0; lane < SPECIES.length(); ++lane) {
                    int index = i + lane;
                    float currentValue = values[index];
                    values[index] = switch (operation) {
                        case ADD -> operand + currentValue;
                        case SUBTRACT -> operand - currentValue;
                        case MULTIPLY -> operand * currentValue;
                        case DIVIDE -> operand / currentValue;
                    };
                }
                i += SPECIES.length();
                for (; i < length; ++i) {
                    float currentValue = values[i];
                    values[i] = switch (operation) {
                        case ADD -> operand + currentValue;
                        case SUBTRACT -> operand - currentValue;
                        case MULTIPLY -> operand * currentValue;
                        case DIVIDE -> operand / currentValue;
                    };
                }
                return;
            }
            result.intoArray(values, i);
        }
        for (; i < length; ++i) {
            float current = values[i];
            values[i] = switch (operation) {
                case ADD -> operand + current;
                case SUBTRACT -> operand - current;
                case MULTIPLY -> operand * current;
                case DIVIDE -> operand / current;
            };
        }
    }

    private static boolean containsNaN(float[] values, int length) {
        for (int i = 0; i < length; ++i) {
            if (Float.isNaN(values[i])) return true;
        }
        return false;
    }

    @Override
    public void perlinLerpAdd(float[] values, int outputOffset, float[] relativeY, int length,
                              float alphaX, float alphaZ, float amplitude, float[] cornerXZ, float[] cornerY) {
        Objects.checkFromIndexSize(outputOffset, length, values.length);
        Objects.checkFromIndexSize(0, length, relativeY.length);
        Objects.checkFromIndexSize(0, 8, cornerXZ.length);
        Objects.checkFromIndexSize(0, 8, cornerY.length);

        FloatVector ax = FloatVector.broadcast(SPECIES, alphaX);
        FloatVector az = FloatVector.broadcast(SPECIES, alphaZ);
        FloatVector amp = FloatVector.broadcast(SPECIES, amplitude);
        float c0 = cornerXZ[0], c1 = cornerXZ[1], c2 = cornerXZ[2], c3 = cornerXZ[3];
        float c4 = cornerXZ[4], c5 = cornerXZ[5], c6 = cornerXZ[6], c7 = cornerXZ[7];
        float y0 = cornerY[0], y1 = cornerY[1], y2 = cornerY[2], y3 = cornerY[3];
        float y4 = cornerY[4], y5 = cornerY[5], y6 = cornerY[6], y7 = cornerY[7];
        FloatVector v0 = FloatVector.broadcast(SPECIES, c0), v1 = FloatVector.broadcast(SPECIES, c1);
        FloatVector v2 = FloatVector.broadcast(SPECIES, c2), v3 = FloatVector.broadcast(SPECIES, c3);
        FloatVector v4 = FloatVector.broadcast(SPECIES, c4), v5 = FloatVector.broadcast(SPECIES, c5);
        FloatVector v6 = FloatVector.broadcast(SPECIES, c6), v7 = FloatVector.broadcast(SPECIES, c7);
        FloatVector gy0 = FloatVector.broadcast(SPECIES, y0), gy1 = FloatVector.broadcast(SPECIES, y1);
        FloatVector gy2 = FloatVector.broadcast(SPECIES, y2), gy3 = FloatVector.broadcast(SPECIES, y3);
        FloatVector gy4 = FloatVector.broadcast(SPECIES, y4), gy5 = FloatVector.broadcast(SPECIES, y5);
        FloatVector gy6 = FloatVector.broadcast(SPECIES, y6), gy7 = FloatVector.broadcast(SPECIES, y7);
        FloatVector one = FloatVector.broadcast(SPECIES, 1.0F);
        FloatVector six = FloatVector.broadcast(SPECIES, 6.0F);
        FloatVector fifteen = FloatVector.broadcast(SPECIES, 15.0F);
        FloatVector ten = FloatVector.broadcast(SPECIES, 10.0F);
        int bound = SPECIES.loopBound(length), i = 0;

        for (; i < bound; i += SPECIES.length()) {
            FloatVector ry = FloatVector.fromArray(SPECIES, relativeY, i);
            FloatVector ry1 = ry.sub(one);
            FloatVector x000 = v0.add(ry.mul(gy0));
            FloatVector x100 = v1.add(ry.mul(gy1));
            FloatVector x010 = v2.add(ry1.mul(gy2));
            FloatVector x110 = v3.add(ry1.mul(gy3));
            FloatVector x001 = v4.add(ry.mul(gy4));
            FloatVector x101 = v5.add(ry.mul(gy5));
            FloatVector x011 = v6.add(ry1.mul(gy6));
            FloatVector x111 = v7.add(ry1.mul(gy7));

            FloatVector ry2 = ry.mul(ry);
            FloatVector alphaY = ry2.mul(ry).mul(
                    ry.mul(ry.mul(six).sub(fifteen)).add(ten));

            FloatVector lower = lerp(alphaY,
                    lerp(ax, x000, x100), lerp(ax, x010, x110));
            FloatVector upper = lerp(alphaY,
                    lerp(ax, x001, x101), lerp(ax, x011, x111));
            FloatVector contribution = amp.mul(lerp(az, lower, upper));
            FloatVector current = FloatVector.fromArray(SPECIES, values, outputOffset + i);
            current.add(contribution).intoArray(values, outputOffset + i);
        }

        for (; i < length; ++i) {
            perlinLerpAddOne(values, outputOffset + i, relativeY[i],
                    alphaX, alphaZ, amplitude, cornerXZ, cornerY);
        }
    }

    private static void perlinLerpAddOne(float[] values, int outputIndex, float ry,
                                         float alphaX, float alphaZ, float amplitude,
                                         float[] cornerXZ, float[] cornerY) {
        float ry1 = ry - 1.0F;
        float x000 = cornerXZ[0] + cornerY[0] * ry;
        float x100 = cornerXZ[1] + cornerY[1] * ry;
        float x010 = cornerXZ[2] + cornerY[2] * ry1;
        float x110 = cornerXZ[3] + cornerY[3] * ry1;
        float x001 = cornerXZ[4] + cornerY[4] * ry;
        float x101 = cornerXZ[5] + cornerY[5] * ry;
        float x011 = cornerXZ[6] + cornerY[6] * ry1;
        float x111 = cornerXZ[7] + cornerY[7] * ry1;
        float alphaY = ry * ry * ry * (ry * (ry * 6.0F - 15.0F) + 10.0F);
        float lower = lerp(alphaY, lerp(alphaX, x000, x100), lerp(alphaX, x010, x110));
        float upper = lerp(alphaY, lerp(alphaX, x001, x101), lerp(alphaX, x011, x111));
        values[outputIndex] = values[outputIndex] + amplitude * lerp(alphaZ, lower, upper);
    }

    private static FloatVector lerp(FloatVector alpha, FloatVector x, FloatVector y) {
        return x.add(alpha.mul(y.sub(x)));
    }
    private static float lerp(float a, float x, float y) { return x + a * (y - x); }
    @Override public int lanes() { return SPECIES.length(); }
    @Override
    public String description() {
        return "Java Vector API " + SPECIES + " (lanes=" + SPECIES.length() + ")";
    }
}
