package dev.jpe.optimizer.vector;

import dev.jpe.optimizer.JPEOptimizer;
import java.util.Optional;

/** Reflective Vector API activation with bit-exact self-test and runtime scalar fallback. */
public final class DensityVectorRuntime {
    private static final String MODULE = "jdk.incubator.vector";
    private static final String IMPLEMENTATION = "dev.jpe.optimizer.vector.VectorApiDensityKernel";
    private static volatile DensityVectorKernel activeKernel;
    private static volatile String diagnostic = "not initialized";
    private DensityVectorRuntime() {}

    public static synchronized String initialize() {
        if (Boolean.getBoolean("jpe.forceScalar")) {
            activeKernel = null;
            diagnostic = "scalar forced by -Djpe.forceScalar=true";
            return diagnostic;
        }
        Optional<Module> module = ModuleLayer.boot().findModule(MODULE);
        if (module.isEmpty()) {
            activeKernel = null;
            diagnostic = "scalar fallback; jdk.incubator.vector is not resolved";
            return diagnostic;
        }
        try {
            Class<?> type = Class.forName(IMPLEMENTATION, true, DensityVectorRuntime.class.getClassLoader());
            Object instance = type.getField("INSTANCE").get(null);
            if (!(instance instanceof DensityVectorKernel candidate)) {
                activeKernel = null;
                diagnostic = "scalar fallback; incompatible Vector API implementation";
                return diagnostic;
            }
            String failure = verify(candidate);
            if (failure != null) {
                activeKernel = null;
                diagnostic = "scalar fallback; SIMD self-test failed: " + failure;
                JPEOptimizer.LOGGER.warn("{}", diagnostic);
                return diagnostic;
            }
            activeKernel = candidate;
            diagnostic = "active: " + candidate.description() + " (bit-exact self-test passed)";
            return diagnostic;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            activeKernel = null;
            diagnostic = "scalar fallback; Vector API load failed: " + exception.getClass().getSimpleName();
            JPEOptimizer.LOGGER.warn("{}: {}", diagnostic, exception.toString());
            return diagnostic;
        }
    }

    public static boolean enabled() { return activeKernel != null; }
    public static String diagnostic() { return diagnostic; }

    public static void rangeChoiceConst(float[] values, int length, float minInclusive, float maxExclusive,
                                        float whenInRange, float whenOutOfRange) {
        DensityVectorKernel current = activeKernel;
        if (current == null) {
            ScalarDensityKernel.INSTANCE.rangeChoiceConst(values, length, minInclusive, maxExclusive, whenInRange, whenOutOfRange);
            return;
        }
        try {
            current.rangeChoiceConst(values, length, minInclusive, maxExclusive, whenInRange, whenOutOfRange);
        } catch (RuntimeException | LinkageError exception) {
            disable(exception);
            ScalarDensityKernel.INSTANCE.rangeChoiceConst(values, length, minInclusive, maxExclusive, whenInRange, whenOutOfRange);
        }
    }

    public static void rangeChoice(float[] values, float[] input, float[] whenOutOfRange,
                                   int length, float minInclusive, float maxExclusive) {
        DensityVectorKernel current = activeKernel;
        if (current == null) {
            ScalarDensityKernel.INSTANCE.rangeChoice(values, input, whenOutOfRange,
                    length, minInclusive, maxExclusive);
            return;
        }
        try {
            current.rangeChoice(values, input, whenOutOfRange, length, minInclusive, maxExclusive);
        } catch (RuntimeException | LinkageError exception) {
            disable(exception);
            ScalarDensityKernel.INSTANCE.rangeChoice(values, input, whenOutOfRange,
                    length, minInclusive, maxExclusive);
        }
    }

    public static void minMax(float[] left, float[] right, int length, boolean minimum) {
        DensityVectorKernel current = activeKernel;
        if (current == null) {
            ScalarDensityKernel.INSTANCE.minMax(left, right, length, minimum);
            return;
        }
        try {
            current.minMax(left, right, length, minimum);
        } catch (RuntimeException | LinkageError exception) {
            disable(exception);
            ScalarDensityKernel.INSTANCE.minMax(left, right, length, minimum);
        }
    }

    public static void minMaxConst(float[] values, int length, float operand, boolean minimum) {
        DensityVectorKernel current = activeKernel;
        if (current == null) {
            ScalarDensityKernel.INSTANCE.minMaxConst(values, length, operand, minimum);
            return;
        }
        try {
            current.minMaxConst(values, length, operand, minimum);
        } catch (RuntimeException | LinkageError exception) {
            disable(exception);
            ScalarDensityKernel.INSTANCE.minMaxConst(values, length, operand, minimum);
        }
    }

    private static synchronized void disable(Throwable failure) {
        if (activeKernel != null) {
            activeKernel = null;
            diagnostic = "scalar fallback after SIMD runtime failure: " + failure.getClass().getSimpleName();
            JPEOptimizer.LOGGER.warn("{}: {}", diagnostic, failure.toString());
        }
    }

    private static String verify(DensityVectorKernel candidate) {
        int[] lengths = {0, 1, 3, 4, 5, 15, 16, 17, 65, 257};
        float[] edges = {0.0F, -0.0F, 1.0F, -1.0F, 0.2F, 0.8F,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.intBitsToFloat(0x7fc01234), Float.intBitsToFloat(0xffc05678),
                Float.MAX_VALUE, -Float.MAX_VALUE};
        float canary = 123456.75F;
        for (int length : lengths) {
            float[] source = new float[length + 5], right = new float[length + 5];
            for (int i = 0; i < source.length; ++i) {
                source[i] = i < length ? edges[i % edges.length] : canary + i;
                right[i] = i < length ? edges[(i * 5 + 3) % edges.length] : -canary - i;
            }
            float in = Float.intBitsToFloat(0x7fc02345), out = Float.intBitsToFloat(0xffc06789);
            float[] expected = source.clone(), actual = source.clone();
            ScalarDensityKernel.INSTANCE.rangeChoiceConst(expected, length, 0.2F, 0.8F, in, out);
            candidate.rangeChoiceConst(actual, length, 0.2F, 0.8F, in, out);
            String mismatch = compare("rangeChoiceConst", length, expected, actual);
            if (mismatch != null) return mismatch;

            float[] rangeInput = right.clone();
            float[] outOfRange = right.clone();
            for (int i = 0; i < outOfRange.length; ++i) {
                outOfRange[i] = edges[(i * 7 + 5) % edges.length];
            }
            expected = source.clone();
            actual = source.clone();
            ScalarDensityKernel.INSTANCE.rangeChoice(expected, rangeInput, outOfRange,
                    length, 0.2F, 0.8F);
            candidate.rangeChoice(actual, rangeInput, outOfRange,
                    length, 0.2F, 0.8F);
            mismatch = compare("rangeChoice", length, expected, actual);
            if (mismatch != null) return mismatch;

            for (boolean minimum : new boolean[]{true, false}) {
                expected = source.clone(); actual = source.clone();
                ScalarDensityKernel.INSTANCE.minMax(expected, right, length, minimum);
                candidate.minMax(actual, right, length, minimum);
                mismatch = compare(minimum ? "min" : "max", length, expected, actual);
                if (mismatch != null) return mismatch;

                for (float operand : edges) {
                    expected = source.clone(); actual = source.clone();
                    ScalarDensityKernel.INSTANCE.minMaxConst(expected, length, operand, minimum);
                    candidate.minMaxConst(actual, length, operand, minimum);
                    mismatch = compare(minimum ? "min-constant" : "max-constant", length, expected, actual);
                    if (mismatch != null) return mismatch;
                }
            }
        }
        return null;
    }

    private static String compare(String operation, int length, float[] expected, float[] actual) {
        for (int i = 0; i < expected.length; ++i) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                return operation + " mismatch at index " + i + " for length " + length;
            }
        }
        return null;
    }
}
