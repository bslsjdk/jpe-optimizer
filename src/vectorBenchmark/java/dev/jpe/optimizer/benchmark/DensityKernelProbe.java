package dev.jpe.optimizer.benchmark;

import dev.jpe.optimizer.vector.DensityVectorKernel;
import dev.jpe.optimizer.vector.ScalarDensityKernel;
import dev.jpe.optimizer.vector.VectorApiDensityKernel;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Isolated array benchmark for the same branch-heavy kernels used by JPE. */
public final class DensityKernelProbe {
    private static volatile long blackhole;
    private DensityKernelProbe() {}

    public static void run(String[] args) {
        int size = argument(args, "--samples", 98_304);
        int warmup = argument(args, "--warmup", 5);
        int iterations = argument(args, "--iterations", 20);
        if (size < 1 || warmup < 1 || iterations < 3) {
            throw new IllegalArgumentException("Invalid density-kernel benchmark dimensions");
        }
        float[] source = new float[size], right = new float[size];
        Random random = new Random(0x4a50454cL);
        for (int i = 0; i < size; ++i) {
            int bucket = random.nextInt(16);
            source[i] = switch (bucket) {
                case 0 -> 0.0F;
                case 1 -> -0.0F;
                case 2 -> Float.NaN;
                case 3 -> Float.POSITIVE_INFINITY;
                case 4 -> Float.NEGATIVE_INFINITY;
                default -> random.nextFloat() * 4.0F - 2.0F;
            };
            right[i] = random.nextFloat() * 4.0F - 2.0F;
        }
        DensityVectorKernel scalar = ScalarDensityKernel.INSTANCE;
        DensityVectorKernel vector = VectorApiDensityKernel.INSTANCE;
        System.out.println("Density kernel probe: samples=" + size + ", warmup=" + warmup
                + ", iterations=" + iterations + ", " + vector.description());

        float[] scalarValues = new float[size], vectorValues = new float[size];
        Result scalarResult = measure(size, warmup, iterations,
                () -> System.arraycopy(source, 0, scalarValues, 0, size),
                () -> scalar.rangeChoiceConst(scalarValues, size, 0.2F, 0.8F, 1.0F, -1.0F), scalarValues);
        Result vectorResult = measure(size, warmup, iterations,
                () -> System.arraycopy(source, 0, vectorValues, 0, size),
                () -> vector.rangeChoiceConst(vectorValues, size, 0.2F, 0.8F, 1.0F, -1.0F), vectorValues);
        report("rangeChoiceConst", scalarResult, vectorResult, size);
        compare("rangeChoiceConst", scalarValues, vectorValues);

        for (boolean minimum : new boolean[]{true, false}) {
            float[] scalarLeft = new float[size], vectorLeft = new float[size];
            float[] scalarRight = right.clone(), vectorRight = right.clone();
            scalarResult = measure(size, warmup, iterations,
                    () -> System.arraycopy(source, 0, scalarLeft, 0, size),
                    () -> scalar.minMax(scalarLeft, scalarRight, size, minimum), scalarLeft);
            vectorResult = measure(size, warmup, iterations,
                    () -> System.arraycopy(source, 0, vectorLeft, 0, size),
                    () -> vector.minMax(vectorLeft, vectorRight, size, minimum), vectorLeft);
            report(minimum ? "min" : "max", scalarResult, vectorResult, size);
            compare(minimum ? "min" : "max", scalarLeft, vectorLeft);
        }
        System.out.println("Density kernel checksum=" + blackhole);
    }

    private static Result measure(int size, int warmup, int iterations,
                                  Runnable reset, Runnable operation, float[] output) {
        for (int i = 0; i < warmup; ++i) {
            reset.run(); operation.run();
            blackhole += Float.floatToRawIntBits(output[(i * 31) % size]);
        }
        long[] timings = new long[iterations];
        for (int i = 0; i < iterations; ++i) {
            reset.run();
            long start = System.nanoTime();
            operation.run();
            timings[i] = System.nanoTime() - start;
            blackhole += Float.floatToRawIntBits(output[(i * 131 + 17) % size]);
        }
        Arrays.sort(timings);
        return new Result(timings[iterations / 2], timings[(int) Math.ceil(iterations * 0.95) - 1]);
    }

    private static void report(String name, Result scalar, Result vector, int size) {
        System.out.printf(Locale.ROOT,
                "  %s scalar=%.3f ns/sample (p95 %.3f), vector=%.3f (p95 %.3f), speedup=%.3fx%n",
                name, scalar.median / (double) size, scalar.p95 / (double) size,
                vector.median / (double) size, vector.p95 / (double) size,
                scalar.median / (double) vector.median);
    }

    private static void compare(String name, float[] scalar, float[] vector) {
        for (int i = 0; i < scalar.length; ++i) {
            if (Float.floatToRawIntBits(scalar[i]) != Float.floatToRawIntBits(vector[i])) {
                throw new IllegalStateException(name + " mismatch at index " + i);
            }
        }
    }

    private static int argument(String[] args, String name, int fallback) {
        for (int i = 0; i + 1 < args.length; ++i) {
            if (name.equals(args[i])) return Integer.parseInt(args[i + 1]);
        }
        return fallback;
    }

    private record Result(long median, long p95) {}
}
