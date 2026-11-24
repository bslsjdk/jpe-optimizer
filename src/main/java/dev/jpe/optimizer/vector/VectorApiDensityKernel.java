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
        int bound = SPECIES.loopBound(length), i = 0;
        switch (operation) {
            case ADD -> {
                for (; i < bound; i += SPECIES.length())
                    FloatVector.fromArray(SPECIES, left, i).add(FloatVector.fromArray(SPECIES, right, i)).intoArray(left, i);
                for (; i < length; ++i) left[i] = left[i] + right[i];
            }
            case SUBTRACT -> {
                for (; i < bound; i += SPECIES.length())
                    FloatVector.fromArray(SPECIES, left, i).sub(FloatVector.fromArray(SPECIES, right, i)).intoArray(left, i);
                for (; i < length; ++i) left[i] = left[i] - right[i];
            }
            case MULTIPLY -> {
                for (; i < bound; i += SPECIES.length())
                    FloatVector.fromArray(SPECIES, left, i).mul(FloatVector.fromArray(SPECIES, right, i)).intoArray(left, i);
                for (; i < length; ++i) left[i] = left[i] * right[i];
            }
            case DIVIDE -> {
                for (; i < bound; i += SPECIES.length())
                    FloatVector.fromArray(SPECIES, left, i).div(FloatVector.fromArray(SPECIES, right, i)).intoArray(left, i);
                for (; i < length; ++i) left[i] = left[i] / right[i];
            }
        }
    }

    @Override
    public void binaryConst(float[] values, int length, float operand, BinaryOp operation) {
        Objects.checkFromIndexSize(0, length, values.length);
        FloatVector constant = FloatVector.broadcast(SPECIES, operand);
        int bound = SPECIES.loopBound(length), i = 0;
        switch (operation) {
            case ADD -> {
                for (; i < bound; i += SPECIES.length())
                    FloatVector.fromArray(SPECIES, values, i).add(constant).intoArray(values, i);
                for (; i < length; ++i) values[i] = values[i] + operand;
            }
            case SUBTRACT -> {
                for (; i < bound; i += SPECIES.length())
                    FloatVector.fromArray(SPECIES, values, i).sub(constant).intoArray(values, i);
                for (; i < length; ++i) values[i] = values[i] - operand;
            }
            case MULTIPLY -> {
                for (; i < bound; i += SPECIES.length())
                    FloatVector.fromArray(SPECIES, values, i).mul(constant).intoArray(values, i);
                for (; i < length; ++i) values[i] = values[i] * operand;
            }
            case DIVIDE -> {
                for (; i < bound; i += SPECIES.length())
                    FloatVector.fromArray(SPECIES, values, i).div(constant).intoArray(values, i);
                for (; i < length; ++i) values[i] = values[i] / operand;
            }
        }
    }

    @Override
    public void binaryConstLeft(float[] values, int length, float operand, BinaryOp operation) {
        Objects.checkFromIndexSize(0, length, values.length);
        FloatVector constant = FloatVector.broadcast(SPECIES, operand);
        int bound = SPECIES.loopBound(length), i = 0;
        switch (operation) {
            case ADD -> {
                for (; i < bound; i += SPECIES.length())
                    constant.add(FloatVector.fromArray(SPECIES, values, i)).intoArray(values, i);
                for (; i < length; ++i) values[i] = operand + values[i];
            }
            case SUBTRACT -> {
                for (; i < bound; i += SPECIES.length())
                    constant.sub(FloatVector.fromArray(SPECIES, values, i)).intoArray(values, i);
                for (; i < length; ++i) values[i] = operand - values[i];
            }
            case MULTIPLY -> {
                for (; i < bound; i += SPECIES.length())
                    constant.mul(FloatVector.fromArray(SPECIES, values, i)).intoArray(values, i);
                for (; i < length; ++i) values[i] = operand * values[i];
            }
            case DIVIDE -> {
                for (; i < bound; i += SPECIES.length())
                    constant.div(FloatVector.fromArray(SPECIES, values, i)).intoArray(values, i);
                for (; i < length; ++i) values[i] = operand / values[i];
            }
        }
    }

    @Override
    public void perlinLerpAdd(float[] values, int outputOffset, float[] relativeY, int length, float alphaX, float alphaZ, float amplitude, float[] cornerXZ, float[] cornerY) {
        Objects.checkFromIndexSize(outputOffset, length, values.length);
        Objects.checkFromIndexSize(0, length, relativeY.length);
        FloatVector ax = FloatVector.broadcast(SPECIES, alphaX), az = FloatVector.broadcast(SPECIES, alphaZ);
        FloatVector amp = FloatVector.broadcast(SPECIES, amplitude), one = FloatVector.broadcast(SPECIES, 1.0F);
        FloatVector six = FloatVector.broadcast(SPECIES, 6.0F), fifteen = FloatVector.broadcast(SPECIES, 15.0F), ten = FloatVector.broadcast(SPECIES, 10.0F);
        FloatVector xz0 = FloatVector.broadcast(SPECIES, cornerXZ[0]), xz1 = FloatVector.broadcast(SPECIES, cornerXZ[1]);
        FloatVector xz2 = FloatVector.broadcast(SPECIES, cornerXZ[2]), xz3 = FloatVector.broadcast(SPECIES, cornerXZ[3]);
        FloatVector xz4 = FloatVector.broadcast(SPECIES, cornerXZ[4]), xz5 = FloatVector.broadcast(SPECIES, cornerXZ[5]);
        FloatVector xz6 = FloatVector.broadcast(SPECIES, cornerXZ[6]), xz7 = FloatVector.broadcast(SPECIES, cornerXZ[7]);
        FloatVector gy0 = FloatVector.broadcast(SPECIES, cornerY[0]), gy1 = FloatVector.broadcast(SPECIES, cornerY[1]);
        FloatVector gy2 = FloatVector.broadcast(SPECIES, cornerY[2]), gy3 = FloatVector.broadcast(SPECIES, cornerY[3]);
        FloatVector gy4 = FloatVector.broadcast(SPECIES, cornerY[4]), gy5 = FloatVector.broadcast(SPECIES, cornerY[5]);
        FloatVector gy6 = FloatVector.broadcast(SPECIES, cornerY[6]), gy7 = FloatVector.broadcast(SPECIES, cornerY[7]);
        int bound = SPECIES.loopBound(length), i = 0;
        for (; i < bound; i += SPECIES.length()) {
            FloatVector ry = FloatVector.fromArray(SPECIES, relativeY, i), ry1 = ry.sub(one);
            FloatVector ay = ry.mul(ry).mul(ry).mul(ry.mul(ry.mul(six).sub(fifteen)).add(ten));
            FloatVector x000 = xz0.add(gy0.mul(ry)), x100 = xz1.add(gy1.mul(ry));
            FloatVector x010 = xz2.add(gy2.mul(ry1)), x110 = xz3.add(gy3.mul(ry1));
            FloatVector x001 = xz4.add(gy4.mul(ry)), x101 = xz5.add(gy5.mul(ry));
            FloatVector x011 = xz6.add(gy6.mul(ry1)), x111 = xz7.add(gy7.mul(ry1));
            FloatVector low = lerp(ay, lerp(ax, x000, x100), lerp(ax, x010, x110));
            FloatVector high = lerp(ay, lerp(ax, x001, x101), lerp(ax, x011, x111));
            FloatVector out = FloatVector.fromArray(SPECIES, values, outputOffset + i);
            out.add(amp.mul(lerp(az, low, high))).intoArray(values, outputOffset + i);
        }
        for (; i < length; ++i) {
            float ry = relativeY[i], ry1 = ry - 1.0F;
            float x000 = cornerXZ[0] + cornerY[0] * ry, x100 = cornerXZ[1] + cornerY[1] * ry;
            float x010 = cornerXZ[2] + cornerY[2] * ry1, x110 = cornerXZ[3] + cornerY[3] * ry1;
            float x001 = cornerXZ[4] + cornerY[4] * ry, x101 = cornerXZ[5] + cornerY[5] * ry;
            float x011 = cornerXZ[6] + cornerY[6] * ry1, x111 = cornerXZ[7] + cornerY[7] * ry1;
            float ay = ry * ry * ry * (ry * (ry * 6.0F - 15.0F) + 10.0F);
            float low = lerp(ay, lerp(alphaX, x000, x100), lerp(alphaX, x010, x110));
            float high = lerp(ay, lerp(alphaX, x001, x101), lerp(alphaX, x011, x111));
            values[outputOffset + i] = values[outputOffset + i] + amplitude * lerp(alphaZ, low, high);
        }
    }
    private static FloatVector lerp(FloatVector a, FloatVector x, FloatVector y) { return x.add(a.mul(y.sub(x))); }
    private static float lerp(float a, float x, float y) { return x + a * (y - x); }
    @Override public int lanes() { return SPECIES.length(); }
    @Override
    public String description() {
        return "Java Vector API " + SPECIES + " (lanes=" + SPECIES.length() + ")";
    }
}
