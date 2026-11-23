package dev.jpe.optimizer.benchmark;

import java.util.Arrays;
import java.util.Random;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Standalone scalar-versus-Vector-API probe.
 *
 * This is intentionally isolated from the shipped mod. It measures a simple
 * lane-wise arithmetic kernel and verifies bit-exact output before timing.
 * It does not prove that Minecraft's noise graph is vectorizable.
 */
public final class VectorApiBenchmark {
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;
    private static volatile double blackhole;

    private VectorApiBenchmark() {}

    public static void main(String[] args) {
        int warmup = intArg(args, "--warmup", 5);
        int iterations = intArg(args, "--iterations", 20);
        int samples = intArg(args, "--samples", 262_144);
        if (warmup < 1 || iterations < 3 || samples < SPECIES.length()) {
            throw new IllegalArgumentException("Require warmup >= 1, iterations >= 3, and samples >= vector lanes");
        }

        float[] a = new float[samples];
        float[] b = new float[samples];
        float[] c = new float[samples];
        float[] scalarOut = new float[samples];
        float[] vectorOut = new float[samples];
        fillInputs(a, b, c);

        scalar(a, b, c, scalarOut);
        vector(a, b, c, vectorOut);
        verifyExact(scalarOut, vectorOut);

        for (int i = 0; i < warmup; i++) {
            if ((i & 1) == 0) {
                scalar(a, b, c, scalarOut);
                vector(a, b, c, vectorOut);
            } else {
                vector(a, b, c, vectorOut);
                scalar(a, b, c, scalarOut);
            }
            blackhole = checksum(scalarOut) + checksum(vectorOut);
        }

        long[] scalarTimes = new long[iterations];
        long[] vectorTimes = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            if ((i & 1) == 0) {
                scalarTimes[i] = time(() -> scalar(a, b, c, scalarOut));
                blackhole = checksum(scalarOut);
                vectorTimes[i] = time(() -> vector(a, b, c, vectorOut));
                blackhole = checksum(vectorOut);
            } else {
                vectorTimes[i] = time(() -> vector(a, b, c, vectorOut));
                blackhole = checksum(vectorOut);
                scalarTimes[i] = time(() -> scalar(a, b, c, scalarOut));
                blackhole = checksum(scalarOut);
            }
        }

        verifyExact(scalarOut, vectorOut);
        double scalarMedian = percentile(scalarTimes, 0.50) / (double) samples;
        double vectorMedian = percentile(vectorTimes, 0.50) / (double) samples;
        double scalarP95 = percentile(scalarTimes, 0.95) / (double) samples;
        double vectorP95 = percentile(vectorTimes, 0.95) / (double) samples;

        System.out.println("Vector API benchmark (diagnostic only)");
        System.out.printf("species=%s, floatLanes=%d, vectorBits=%d, samples=%d, warmup=%d, iterations=%d%n",
                SPECIES, SPECIES.length(), SPECIES.vectorBitSize(), samples, warmup, iterations);
        System.out.printf("scalar median=%.3f ns/sample, p95=%.3f ns/sample%n", scalarMedian, scalarP95);
        System.out.printf("vector median=%.3f ns/sample, p95=%.3f ns/sample%n", vectorMedian, vectorP95);
        System.out.printf("median speedup=%.3fx, checksum=%.9f%n",
                scalarMedian / vectorMedian, checksum(vectorOut));
        System.out.println("Correctness: bit-exact scalar/vector outputs");
        System.out.println("Note: throughput does not prove a specific SIMD instruction was emitted.");
    }

    private static void scalar(float[] a, float[] b, float[] c, float[] out) {
        for (int i = 0; i < out.length; i++) {
            float product = a[i] * b[i];
            out[i] = product + c[i];
        }
    }

    private static void vector(float[] a, float[] b, float[] c, float[] out) {
        int bound = SPECIES.loopBound(out.length);
        for (int i = 0; i < bound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            FloatVector vc = FloatVector.fromArray(SPECIES, c, i);
            va.mul(vb).add(vc).intoArray(out, i);
        }
        for (int i = bound; i < out.length; i++) {
            float product = a[i] * b[i];
            out[i] = product + c[i];
        }
    }

    private static void fillInputs(float[] a, float[] b, float[] c) {
        Random random = new Random(0x4A50454F50544CL);
        for (int i = 0; i < a.length; i++) {
            a[i] = random.nextFloat() * 2.0f - 1.0f;
            b[i] = random.nextFloat() * 2.0f - 1.0f;
            c[i] = random.nextFloat() * 2.0f - 1.0f;
        }
    }

    private static void verifyExact(float[] expected, float[] actual) {
        for (int i = 0; i < expected.length; i++) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                throw new IllegalStateException("Scalar/vector mismatch at index " + i
                        + ": " + expected[i] + " != " + actual[i]);
            }
        }
    }

    private static double checksum(float[] values) {
        double sum = 0.0;
        for (float value : values) {
            sum += value;
        }
        return sum;
    }

    private static long time(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    private static long percentile(long[] values, double percentile) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private static int intArg(String[] args, String key, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return fallback;
    }
}
