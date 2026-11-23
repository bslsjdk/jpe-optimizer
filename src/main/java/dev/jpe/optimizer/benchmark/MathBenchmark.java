package dev.jpe.optimizer.benchmark;

import dev.jpe.optimizer.math.DensityMath;
import dev.jpe.optimizer.math.FastMath;

public final class MathBenchmark {
    private MathBenchmark() {}

    private static volatile float sink;

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
        benchSmoothstep(samples, warmup, iterations);
    }

    private static void benchSmoothstep(int samples, int warmup, int iterations) {
        float[] x = new float[samples];
        for (int i = 0; i < samples; i++) x[i] = (float) i / samples;

        float poly = bench("smoothstep_poly", () -> {
            float s = 0.0f;
            for (int i = 0; i < x.length; i++) {
                float a = x[i];
                s += a * a * a * (a * (a * 6.0f - 15.0f) + 10.0f);
            }
            return s;
        }, warmup, iterations, samples);
        float interp = bench("smoothstep_interp", () -> {
            float s = 0.0f;
            for (int i = 0; i < x.length; i++) s += FastMath.smoothstep(x[i]);
            return s;
        }, warmup, iterations, samples);
        float big = bench("smoothstep_big", () -> {
            float s = 0.0f;
            for (int i = 0; i < x.length; i++) s += FastMath.smoothstepBig(x[i]);
            return s;
        }, warmup, iterations, samples);
        System.out.printf("SMOOTHSTEP poly=%.3f interp=%.3f (%.2fx) big=%.3f (%.2fx)%n",
                poly, interp, poly / interp, big, poly / big);

        float dpoly = bench("derivative_poly", () -> {
            float s = 0.0f;
            for (int i = 0; i < x.length; i++) {
                float a = x[i];
                s += 30.0f * a * a * (a - 1.0f) * (a - 1.0f);
            }
            return s;
        }, warmup, iterations, samples);
        float dinterp = bench("derivative_interp", () -> {
            float s = 0.0f;
            for (int i = 0; i < x.length; i++) s += FastMath.smoothstepDerivative(x[i]);
            return s;
        }, warmup, iterations, samples);
        float dbig = bench("derivative_big", () -> {
            float s = 0.0f;
            for (int i = 0; i < x.length; i++) s += FastMath.smoothstepDerivativeBig(x[i]);
            return s;
        }, warmup, iterations, samples);
        System.out.printf("DERIVATIVE poly=%.3f interp=%.3f (%.2fx) big=%.3f (%.2fx)%n",
                dpoly, dinterp, dpoly / dinterp, dbig, dpoly / dbig);
    }

    /** Runs several rounds and reports the minimum per-sample ns (least noise). */
    private static float bench(String name, Sink fn, int warmup, int iterations, int samples) {
        for (int i = 0; i < warmup; i++) sink = fn.eval();
        double best = Double.MAX_VALUE;
        float checksum = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            checksum = fn.eval();
            long elapsed = System.nanoTime() - start;
            sink = checksum;
            double ns = (double) elapsed / samples;
            if (ns < best) best = ns;
        }
        System.out.printf("%s best_ns=%.3f checksum=%s%n", name, best, Float.toHexString(checksum));
        return (float) best;
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
        System.out.printf("%s ns_per_sample=%.3f checksum=%s%n", name, ns, Float.toHexString(checksum));
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

    @FunctionalInterface
    private interface Sink {
        float eval();
    }
}