package dev.jpe.optimizer.benchmark;

import java.util.Arrays;
import java.util.Random;

/** Diagnostic for the exact 256-entry gradient lookup in Minecraft 26.3; runs eight corner lookups per sample. */
public final class GradientLookupProbe {
    private static final int[] GX = {1,-1,1,-1,1,-1,1,-1,0,0,0,0,1,0,-1,0};
    private static final int[] GY = {1,1,-1,-1,0,0,0,0,1,-1,1,-1,1,-1,1,-1};
    private static final int[] GZ = {0,0,0,0,1,1,-1,-1,1,1,-1,-1,0,1,0,-1};
    private static volatile long blackhole;

    private GradientLookupProbe() {}

    public static void run(String[] args) {
        int warmup = intArg(args, "--warmup", 5);
        int iterations = intArg(args, "--iterations", 20);
        int samples = intArg(args, "--samples", 262_144);
        if (warmup < 2 || iterations < 6 || samples < 1024) {
            throw new IllegalArgumentException("Require warmup >= 2, iterations >= 6, samples >= 1024");
        }

        byte[] perms = shuffledPermutation();
        byte[] packed = new byte[256];
        long buildStart = System.nanoTime();
        for (int i = 0; i < 256; ++i) {
            int g = perms[i] & 15;
            packed[i] = pack(GX[g], GY[g], GZ[g]);
        }
        long buildNanos = System.nanoTime() - buildStart;

        int count = Math.multiplyExact(samples, 8);
        int[] hashes = new int[count];
        float[] x = new float[count], y = new float[count], z = new float[count];
        float[] vanillaOut = new float[count], packedOut = new float[count];
        fillInputs(hashes, x, y, z);
        vanilla(perms, hashes, x, y, z, vanillaOut);
        packed(packed, hashes, x, y, z, packedOut);
        verifyExact(vanillaOut, packedOut);

        for (int i = 0; i < warmup; ++i) {
            if ((i & 1) == 0) {
                vanilla(perms, hashes, x, y, z, vanillaOut);
                packed(packed, hashes, x, y, z, packedOut);
            } else {
                packed(packed, hashes, x, y, z, packedOut);
                vanilla(perms, hashes, x, y, z, vanillaOut);
            }
            blackhole = checksum(vanillaOut) ^ checksum(packedOut);
        }

        long[] vanillaTimes = new long[iterations], packedTimes = new long[iterations];
        for (int i = 0; i < iterations; ++i) {
            if ((i & 1) == 0) {
                vanillaTimes[i] = time(() -> vanilla(perms, hashes, x, y, z, vanillaOut));
                blackhole = checksum(vanillaOut);
                packedTimes[i] = time(() -> packed(packed, hashes, x, y, z, packedOut));
                blackhole = checksum(packedOut);
            } else {
                packedTimes[i] = time(() -> packed(packed, hashes, x, y, z, packedOut));
                blackhole = checksum(packedOut);
                vanillaTimes[i] = time(() -> vanilla(perms, hashes, x, y, z, vanillaOut));
                blackhole = checksum(vanillaOut);
            }
        }
        verifyExact(vanillaOut, packedOut);

        double vanillaMedian = percentile(vanillaTimes, 0.50) / (double) count;
        double packedMedian = percentile(packedTimes, 0.50) / (double) count;
        double vanillaP95 = percentile(vanillaTimes, 0.95) / (double) count;
        double packedP95 = percentile(packedTimes, 0.95) / (double) count;
        System.out.println("Minecraft 26.3 GradientNoise lookup probe (diagnostic only)");
        System.out.printf("samples=%d gradientLookups=%d warmup=%d iterations=%d cacheBytes=%d cacheBuildUs=%.3f%n",
                samples, count, warmup, iterations, packed.length, buildNanos / 1_000.0);
        System.out.printf("vanilla median=%.3f ns/lookup p95=%.3f ns/lookup%n", vanillaMedian, vanillaP95);
        System.out.printf("packed  median=%.3f ns/lookup p95=%.3f ns/lookup%n", packedMedian, packedP95);
        System.out.printf("median speedup=%.3fx checksum=%016x%n",
                vanillaMedian / packedMedian, checksum(packedOut));
        System.out.println("Correctness: bit-exact gradient dot products");
        System.out.println("Caveat: excludes full Perlin interpolation, chunk generation, and mobile hardware.");
    }

    private static void vanilla(byte[] perms, int[] hashes, float[] x, float[] y, float[] z, float[] out) {
        for (int i = 0; i < hashes.length; ++i) {
            int g = perms[hashes[i] & 255] & 15;
            out[i] = GX[g] * x[i] + GY[g] * y[i] + GZ[g] * z[i];
        }
    }

    private static void packed(byte[] packed, int[] hashes, float[] x, float[] y, float[] z, float[] out) {
        for (int i = 0; i < hashes.length; ++i) {
            byte code = packed[hashes[i] & 255];
            int gx = (byte) (code << 6) >> 6;
            int gy = (byte) (code << 4) >> 6;
            int gz = (byte) (code << 2) >> 6;
            out[i] = gx * x[i] + gy * y[i] + gz * z[i];
        }
    }

    private static byte pack(int x, int y, int z) {
        return (byte) (encode(x) | (encode(y) << 2) | (encode(z) << 4));
    }

    private static int encode(int value) { return value < 0 ? 3 : value; }

    private static byte[] shuffledPermutation() {
        byte[] values = new byte[256];
        for (int i = 0; i < values.length; ++i) values[i] = (byte) i;
        Random random = new Random(0x4A50454C263L);
        for (int i = 0; i < values.length; ++i) {
            int swap = i + random.nextInt(values.length - i);
            byte value = values[i]; values[i] = values[swap]; values[swap] = value;
        }
        return values;
    }

    private static void fillInputs(int[] hashes, float[] x, float[] y, float[] z) {
        Random random = new Random(0x4752414449454E54L);
        for (int i = 0; i < hashes.length; ++i) {
            hashes[i] = random.nextInt();
            x[i] = random.nextFloat() * 2.0F - 1.0F;
            y[i] = random.nextFloat() * 2.0F - 1.0F;
            z[i] = random.nextFloat() * 2.0F - 1.0F;
        }
    }

    private static void verifyExact(float[] expected, float[] actual) {
        for (int i = 0; i < expected.length; ++i) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                throw new IllegalStateException("Gradient lookup mismatch at " + i);
            }
        }
    }

    private static long checksum(float[] values) {
        long result = 0;
        for (float value : values) result = Long.rotateLeft(result, 7) ^ Float.floatToRawIntBits(value);
        return result;
    }

    private static long time(Runnable action) {
        long start = System.nanoTime(); action.run(); return System.nanoTime() - start;
    }

    private static long percentile(long[] values, double p) {
        long[] sorted = values.clone(); Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static int intArg(String[] args, String key, int fallback) {
        for (int i = 0; i < args.length - 1; ++i) {
            if (key.equals(args[i])) return Integer.parseInt(args[i + 1]);
        }
        return fallback;
    }
}
