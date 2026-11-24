package dev.jpe.optimizer.benchmark;

import dev.jpe.optimizer.vector.DensityVectorKernel;
import dev.jpe.optimizer.vector.ScalarDensityKernel;
import dev.jpe.optimizer.vector.VectorApiDensityKernel;
import java.util.Arrays;
import java.util.Locale;

/** Diagnostic microbenchmark with alternating scalar/vector order and controlled branch ratios. */
public final class DensityKernelProbe {
    private static volatile long blackhole;
    private static final int[] SIZES = {256, 1024, 4096, 16384, 65536};
    private static final int[] OUTSIDE_PERCENT = {0, 25, 50, 75, 100};
    private static final int WARMUP = 40;
    private static final int JIT_WARMUP_CALLS = 10_000;
    private static final int ITERATIONS = 41;

    private DensityKernelProbe() {}

    public static void run(String[] args) {
        DensityVectorKernel scalar = ScalarDensityKernel.INSTANCE;
        DensityVectorKernel vector = VectorApiDensityKernel.INSTANCE;
        System.out.println("Density kernel probe: sizes=" + Arrays.toString(SIZES)
                + ", outsideRatios=" + Arrays.toString(OUTSIDE_PERCENT)
                + ", warmupPairs=" + WARMUP + ", jitWarmupCalls=" + JIT_WARMUP_CALLS
                + ", measuredPairs=" + ITERATIONS
                + ", order=alternating-SV/VS, " + vector.description());
        warmUpKernels(scalar, vector);

        for (int size : SIZES) {
            for (int outsidePercent : OUTSIDE_PERCENT) {
                runRangeChoice(size, outsidePercent, scalar, vector);
            }
            runRangeChoiceConst(size, scalar, vector);
            runMinMax(size, scalar, vector, true);
            runMinMax(size, scalar, vector, false);
            for (DensityVectorKernel.BinaryOp operation : DensityVectorKernel.BinaryOp.values()) {
                runBinary(size, scalar, vector, operation);
                runBinaryConst(size, scalar, vector, operation);
                runBinaryConstLeft(size, scalar, vector, operation);
            }
            runPerlinLerpAdd(size, scalar, vector);
        }
        System.out.println("Density kernel checksum=" + blackhole);
    }

    private static void warmUpKernels(DensityVectorKernel scalar, DensityVectorKernel vector) {
        int n = 256;
        float[] in = new float[n], out = new float[n], a = new float[n], b = new float[n], ry = new float[n];
        float[] cx = {0.125F,-0.75F,1.25F,-1.5F,0.875F,-0.25F,1.75F,-1.125F};
        float[] cy = {-0.5F,0.25F,0.75F,-1.0F,0.375F,1.125F,-0.625F,0.875F};
        Arrays.fill(in, 0.5F); Arrays.fill(out, -0.25F); Arrays.fill(b, 1.0F);
        for (int i = 0; i < n; ++i) ry[i] = (i % 101) / 100.0F;
        for (int i = 0; i < JIT_WARMUP_CALLS; ++i) {
            scalar.rangeChoice(a, in, out, n, 0.0F, 1.0F); vector.rangeChoice(b, in, out, n, 0.0F, 1.0F);
            scalar.rangeChoiceConst(a, n, 0.0F, 1.0F, 0.75F, -0.25F); vector.rangeChoiceConst(b, n, 0.0F, 1.0F, 0.75F, -0.25F);
            scalar.minMax(a, b, n, true); vector.minMax(b, a, n, true);
            scalar.minMaxConst(a, n, 0.25F, false); vector.minMaxConst(b, n, 0.25F, false);
            for (DensityVectorKernel.BinaryOp op : DensityVectorKernel.BinaryOp.values()) {
                scalar.binary(a, b, n, op); vector.binary(b, a, n, op);
                scalar.binaryConst(a, n, 1.0F, op); vector.binaryConst(b, n, 1.0F, op);
                scalar.binaryConstLeft(a, n, 1.0F, op); vector.binaryConstLeft(b, n, 1.0F, op);
            }
            scalar.perlinLerpAdd(a, 0, ry, n, 0.37F, 0.61F, 0.85F, cx, cy);
            vector.perlinLerpAdd(b, 0, ry, n, 0.37F, 0.61F, 0.85F, cx, cy);
        }
        blackhole += checksum(a) + checksum(b);
    }

    private static void runRangeChoice(int size, int outsidePercent,
                                       DensityVectorKernel scalar, DensityVectorKernel vector) {
        float[] input = new float[size];
        float[] initial = new float[size];
        float[] outOfRange = new float[size];
        for (int i = 0; i < size; ++i) {
            boolean outside = ((long) (i + 1) * outsidePercent / 100)
                    != ((long) i * outsidePercent / 100);
            input[i] = outside ? 1.5F : 0.5F;
            initial[i] = (i % 997) * 0.001F;
            outOfRange[i] = -((i % 991) * 0.001F + 0.25F);
        }

        float[] scalarValues = new float[size];
        float[] vectorValues = new float[size];
        PairResult result = measurePair(
                () -> System.arraycopy(initial, 0, scalarValues, 0, size),
                () -> scalar.rangeChoice(scalarValues, input, outOfRange, size, 0.0F, 1.0F),
                scalarValues,
                () -> System.arraycopy(initial, 0, vectorValues, 0, size),
                () -> vector.rangeChoice(vectorValues, input, outOfRange, size, 0.0F, 1.0F),
                vectorValues);
        compare("rangeChoice", scalarValues, vectorValues);
        report("rangeChoice outside=" + outsidePercent + "%", result, size);
    }

    private static void runRangeChoiceConst(int size,
                                            DensityVectorKernel scalar, DensityVectorKernel vector) {
        float[] input = new float[size];
        for (int i = 0; i < size; ++i) input[i] = (i & 1) == 0 ? 0.5F : 1.5F;
        float[] scalarValues = new float[size];
        float[] vectorValues = new float[size];
        PairResult result = measurePair(
                () -> System.arraycopy(input, 0, scalarValues, 0, size),
                () -> scalar.rangeChoiceConst(scalarValues, size, 0.0F, 1.0F, 1.0F, -1.0F),
                scalarValues,
                () -> System.arraycopy(input, 0, vectorValues, 0, size),
                () -> vector.rangeChoiceConst(vectorValues, size, 0.0F, 1.0F, 1.0F, -1.0F),
                vectorValues);
        compare("rangeChoiceConst", scalarValues, vectorValues);
        report("rangeChoiceConst", result, size);
    }

    private static void runMinMax(int size, DensityVectorKernel scalar,
                                  DensityVectorKernel vector, boolean minimum) {
        float[] left = new float[size];
        float[] right = new float[size];
        for (int i = 0; i < size; ++i) {
            left[i] = (i % 2001) * 0.001F - 1.0F;
            right[i] = ((i * 37) % 1999) * 0.001F - 1.0F;
        }
        float[] scalarValues = new float[size];
        float[] vectorValues = new float[size];
        PairResult result = measurePair(
                () -> System.arraycopy(left, 0, scalarValues, 0, size),
                () -> scalar.minMax(scalarValues, right, size, minimum),
                scalarValues,
                () -> System.arraycopy(left, 0, vectorValues, 0, size),
                () -> vector.minMax(vectorValues, right, size, minimum),
                vectorValues);
        compare(minimum ? "min" : "max", scalarValues, vectorValues);
        report(minimum ? "min" : "max", result, size);
    }

    private static void runBinary(int size, DensityVectorKernel scalar,
                                  DensityVectorKernel vector, DensityVectorKernel.BinaryOp operation) {
        float[] initial = new float[size];
        float[] right = new float[size];
        for (int i = 0; i < size; ++i) {
            initial[i] = (i % 2001) * 0.001F - 1.0F;
            right[i] = ((i * 37) % 1999) * 0.001F + 0.125F;
        }
        float[] scalarValues = new float[size];
        float[] vectorValues = new float[size];
        PairResult result = measurePair(
                () -> System.arraycopy(initial, 0, scalarValues, 0, size),
                () -> scalar.binary(scalarValues, right, size, operation),
                scalarValues,
                () -> System.arraycopy(initial, 0, vectorValues, 0, size),
                () -> vector.binary(vectorValues, right, size, operation),
                vectorValues);
        compare("binary-" + operation, scalarValues, vectorValues);
        report("binary " + operation, result, size);
    }

    private static void runBinaryConst(int size, DensityVectorKernel scalar,
                                       DensityVectorKernel vector, DensityVectorKernel.BinaryOp operation) {
        float[] initial = new float[size];
        for (int i = 0; i < size; ++i) initial[i] = (i % 2001) * 0.001F - 1.0F;
        float[] scalarValues = new float[size];
        float[] vectorValues = new float[size];
        float operand = 0.375F;
        PairResult result = measurePair(
                () -> System.arraycopy(initial, 0, scalarValues, 0, size),
                () -> scalar.binaryConst(scalarValues, size, operand, operation),
                scalarValues,
                () -> System.arraycopy(initial, 0, vectorValues, 0, size),
                () -> vector.binaryConst(vectorValues, size, operand, operation),
                vectorValues);
        compare("binary-constant-" + operation, scalarValues, vectorValues);
        report("binaryConst " + operation, result, size);
    }

    private static void runBinaryConstLeft(int size, DensityVectorKernel scalar, DensityVectorKernel vector, DensityVectorKernel.BinaryOp op) {
        float[] initial = new float[size], sv = new float[size], vv = new float[size];
        for (int i = 0; i < size; ++i) initial[i] = (i % 2001) * 0.001F - 1.0F;
        PairResult r = measurePair(() -> System.arraycopy(initial,0,sv,0,size), () -> scalar.binaryConstLeft(sv,size,0.375F,op), sv,
                () -> System.arraycopy(initial,0,vv,0,size), () -> vector.binaryConstLeft(vv,size,0.375F,op), vv);
        compare("binary-constant-left-" + op, sv, vv); report("binaryConstLeft " + op, r, size);
    }

    private static void runPerlinLerpAdd(int size, DensityVectorKernel scalar, DensityVectorKernel vector) {
        float[] initial = new float[size], ry = new float[size], sv = new float[size], vv = new float[size];
        float[] cx = {0.125F,-0.75F,1.25F,-1.5F,0.875F,-0.25F,1.75F,-1.125F};
        float[] cy = {-0.5F,0.25F,0.75F,-1.0F,0.375F,1.125F,-0.625F,0.875F};
        for (int i = 0; i < size; ++i) { initial[i] = (i % 31) * 0.01F; ry[i] = (i % 101) / 100.0F; }
        PairResult r = measurePair(() -> System.arraycopy(initial,0,sv,0,size),
                () -> scalar.perlinLerpAdd(sv,0,ry,size,0.37F,0.61F,0.85F,cx,cy), sv,
                () -> System.arraycopy(initial,0,vv,0,size),
                () -> vector.perlinLerpAdd(vv,0,ry,size,0.37F,0.61F,0.85F,cx,cy), vv);
        compare("perlin-lerp-add", sv, vv); report("perlinLerpAdd", r, size);
    }

    private static PairResult measurePair(Runnable scalarReset, Runnable scalarOperation, float[] scalarOutput,
                                          Runnable vectorReset, Runnable vectorOperation, float[] vectorOutput) {
        long[] scalarTimes = new long[ITERATIONS];
        long[] vectorTimes = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; ++i) {
            scalarReset.run();
            scalarOperation.run();
            blackhole += checksum(scalarOutput);
            vectorReset.run();
            vectorOperation.run();
            blackhole += checksum(vectorOutput);
        }
        for (int i = 0; i < ITERATIONS; ++i) {
            if ((i & 1) == 0) {
                scalarTimes[i] = time(scalarReset, scalarOperation, scalarOutput);
                vectorTimes[i] = time(vectorReset, vectorOperation, vectorOutput);
            } else {
                vectorTimes[i] = time(vectorReset, vectorOperation, vectorOutput);
                scalarTimes[i] = time(scalarReset, scalarOperation, scalarOutput);
            }
        }
        return new PairResult(summarize(scalarTimes), summarize(vectorTimes));
    }

    private static long time(Runnable reset, Runnable operation, float[] output) {
        reset.run();
        long start = System.nanoTime();
        operation.run();
        long elapsed = System.nanoTime() - start;
        blackhole += checksum(output);
        return elapsed;
    }

    private static long checksum(float[] values) {
        long sum = 0;
        for (float value : values) sum += Float.floatToRawIntBits(value);
        return sum;
    }

    private static Stats summarize(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return new Stats(sorted[sorted.length / 2], sorted[(int) Math.ceil(sorted.length * 0.95) - 1]);
    }

    private static void report(String name, PairResult result, int size) {
        double scalarMedian = result.scalar.median / (double) size;
        double vectorMedian = result.vector.median / (double) size;
        System.out.printf(Locale.ROOT,
                "  size=%d %-30s scalar median=%.3f p95=%.3f ns/sample | "
                        + "vector median=%.3f p95=%.3f | speedup=%.3fx%n",
                size, name, scalarMedian, result.scalar.p95 / (double) size,
                vectorMedian, result.vector.p95 / (double) size,
                result.scalar.median / (double) result.vector.median);
    }

    private static void compare(String name, float[] scalar, float[] vector) {
        for (int i = 0; i < scalar.length; ++i) {
            if (Float.floatToRawIntBits(scalar[i]) != Float.floatToRawIntBits(vector[i])) {
                throw new IllegalStateException(name + " mismatch at index " + i);
            }
        }
    }

    private record Stats(long median, long p95) {}
    private record PairResult(Stats scalar, Stats vector) {}
}
