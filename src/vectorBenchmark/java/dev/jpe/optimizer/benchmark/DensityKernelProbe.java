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
    private static final int ITERATIONS = 41;

    private DensityKernelProbe() {}

    public static void run(String[] args) {
        DensityVectorKernel scalar = ScalarDensityKernel.INSTANCE;
        DensityVectorKernel vector = VectorApiDensityKernel.INSTANCE;
        System.out.println("Density kernel probe: sizes=" + Arrays.toString(SIZES)
                + ", outsideRatios=" + Arrays.toString(OUTSIDE_PERCENT)
                + ", warmupPairs=" + WARMUP + ", measuredPairs=" + ITERATIONS
                + ", order=alternating-SV/VS, " + vector.description());

        for (int size : SIZES) {
            for (int outsidePercent : OUTSIDE_PERCENT) {
                runRangeChoice(size, outsidePercent, scalar, vector);
            }
            runRangeChoiceConst(size, scalar, vector);
            runMinMax(size, scalar, vector, true);
            runMinMax(size, scalar, vector, false);
        }
        System.out.println("Density kernel checksum=" + blackhole);
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
