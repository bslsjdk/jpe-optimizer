package dev.jpe.optimizer.benchmark;

import dev.jpe.optimizer.math.BiomeZoomMath;

import java.util.Arrays;
import java.util.Random;

/** Paired ABBA microbenchmark for the exact BiomeManager zoom arithmetic. */
public final class BiomeZoomBenchmark {
    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;
    private static final double[] OFFSETS = {
            0.0, 0.25, 0.5, 0.75, -1.0, -0.75, -0.5, -0.25
    };

    private BiomeZoomBenchmark() {}

    public static void run(int warmup, int iterations, int samples) {
        Random random = new Random(0x4A504542494F4D45L);
        long[] seeds = new long[samples];
        int[] xs = new int[samples];
        int[] ys = new int[samples];
        int[] zs = new int[samples];
        double[] dx = new double[samples];
        double[] dy = new double[samples];
        double[] dz = new double[samples];

        for (int i = 0; i < samples; i++) {
            seeds[i] = random.nextLong();
            xs[i] = random.nextInt();
            ys[i] = random.nextInt();
            zs[i] = random.nextInt();
            dx[i] = OFFSETS[random.nextInt(8)];
            dy[i] = OFFSETS[random.nextInt(8)];
            dz[i] = OFFSETS[random.nextInt(8)];
        }

        for (int i = 0; i < warmup; i++) {
            if ((i & 1) == 0) {
                execute(seeds, xs, ys, zs, dx, dy, dz, false);
                execute(seeds, xs, ys, zs, dx, dy, dz, true);
            } else {
                execute(seeds, xs, ys, zs, dx, dy, dz, true);
                execute(seeds, xs, ys, zs, dx, dy, dz, false);
            }
        }

        double[] vanillaNs = new double[iterations];
        double[] lookupNs = new double[iterations];
        long checksum = 0L;
        for (int i = 0; i < iterations; i++) {
            long vanillaResult;
            long lookupResult;
            long vanillaElapsed;
            long lookupElapsed;

            if ((i & 1) == 0) {
                long start = System.nanoTime();
                vanillaResult = execute(seeds, xs, ys, zs, dx, dy, dz, false);
                vanillaElapsed = System.nanoTime() - start;
                start = System.nanoTime();
                lookupResult = execute(seeds, xs, ys, zs, dx, dy, dz, true);
                lookupElapsed = System.nanoTime() - start;
            } else {
                long start = System.nanoTime();
                lookupResult = execute(seeds, xs, ys, zs, dx, dy, dz, true);
                lookupElapsed = System.nanoTime() - start;
                start = System.nanoTime();
                vanillaResult = execute(seeds, xs, ys, zs, dx, dy, dz, false);
                vanillaElapsed = System.nanoTime() - start;
            }

            if (vanillaResult != lookupResult) {
                throw new AssertionError("Biome zoom benchmark checksums differ: vanilla="
                        + Long.toUnsignedString(vanillaResult) + ", lookup="
                        + Long.toUnsignedString(lookupResult));
            }
            checksum ^= vanillaResult;
            vanillaNs[i] = (double) vanillaElapsed / samples;
            lookupNs[i] = (double) lookupElapsed / samples;
        }

        double vanillaMedian = percentile(vanillaNs, 0.50);
        double lookupMedian = percentile(lookupNs, 0.50);
        System.out.printf(
                "biome_zoom samples=%d rounds=%d vanilla_median_ns=%.3f vanilla_p95_ns=%.3f "
                        + "lookup_median_ns=%.3f lookup_p95_ns=%.3f speedup=%.3fx checksum=%016x%n",
                samples, iterations,
                vanillaMedian, percentile(vanillaNs, 0.95),
                lookupMedian, percentile(lookupNs, 0.95),
                vanillaMedian / lookupMedian, checksum
        );
    }

    private static double percentile(double[] values, double percentile) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1,
                Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1));
        return sorted[index];
    }

    private static long execute(
            long[] seeds, int[] xs, int[] ys, int[] zs,
            double[] dx, double[] dy, double[] dz, boolean lookup
    ) {
        long checksum = 0L;
        for (int i = 0; i < seeds.length; i++) {
            double value = lookup
                    ? BiomeZoomMath.fiddledDistance(seeds[i], xs[i], ys[i], zs[i], dx[i], dy[i], dz[i])
                    : vanilla(seeds[i], xs[i], ys[i], zs[i], dx[i], dy[i], dz[i]);
            checksum += Double.doubleToRawLongBits(value);
        }
        return checksum;
    }

    private static double vanilla(
            long seed, int xRandom, int yRandom, int zRandom,
            double distanceX, double distanceY, double distanceZ
    ) {
        long value = seed;
        value = next(value, xRandom);
        value = next(value, yRandom);
        value = next(value, zRandom);
        value = next(value, xRandom);
        value = next(value, yRandom);
        value = next(value, zRandom);
        double fiddleX = fiddle(value);
        value = next(value, seed);
        double fiddleY = fiddle(value);
        value = next(value, seed);
        double fiddleZ = fiddle(value);
        return square(distanceZ + fiddleZ)
                + square(distanceY + fiddleY)
                + square(distanceX + fiddleX);
    }

    private static long next(long value, long salt) {
        value *= value * MULTIPLIER + INCREMENT;
        return value + salt;
    }

    private static double fiddle(long value) {
        double uniform = Math.floorMod(value >> 24, 1024) / 1024.0;
        return (uniform - 0.5) * 0.9;
    }

    private static double square(double value) {
        return value * value;
    }
}
