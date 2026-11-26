package dev.jpe.optimizer.benchmark;

import dev.jpe.optimizer.math.DensityMath;

public final class MathBenchmark {
    private MathBenchmark() {}

    public static void main(String[] args) {
        int warmup = arg(args, "--warmup", 5);
        int iterations = arg(args, "--iterations", 20);
        int samples = arg(args, "--samples", 262144);
        float[] input = new float[samples * 3];
        long state = 0x4D595DF4D0F33173L;
        for (int i = 0; i < input.length; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            input[i] = (float) ((state >>> 40) * (1.0 / (1L << 24)) * 2.0 - 1.0);
        }
        System.out.printf("JPE_BENCH samples=%d warmup=%d iterations=%d%n", samples, warmup, iterations);
        run("reference", DensityMath::syntheticGraph, input, warmup, iterations);
        run("shared_cse", DensityMath::syntheticGraphShared, input, warmup, iterations);
        BiomeZoomBenchmark.run(warmup, iterations, samples);
        ClimateRTreeBenchmark.run(warmup, iterations, samples);
    }

    private static void run(String name, Fn fn, float[] input, int warmup, int iterations) {
        float checksum = 0;
        for (int i = 0; i < warmup; i++) checksum += execute(fn, input);
        long elapsed = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            checksum += execute(fn, input);
            elapsed += System.nanoTime() - start;
        }
        double ns = (double) elapsed / ((long) iterations * (input.length / 3));
        System.out.printf("%s ns_per_sample=%.3f samples_per_second=%.0f checksum=%s%n",
                name, ns, 1e9 / ns, Float.toHexString(checksum));
    }

    private static float execute(Fn fn, float[] input) {
        float sum = 0;
        for (int i = 0; i < input.length; i += 3) {
            sum += fn.eval(input[i], input[i + 1], input[i + 2]);
        }
        return sum;
    }

    private static int arg(String[] args, String key, int fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (key.equals(args[i])) return Integer.parseInt(args[i + 1]);
        }
        return fallback;
    }

    @FunctionalInterface
    private interface Fn {
        float eval(float x, float y, float z);
    }
}
