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
    public String description() {
        return "Java Vector API " + SPECIES + " (lanes=" + SPECIES.length() + ")";
    }
}
