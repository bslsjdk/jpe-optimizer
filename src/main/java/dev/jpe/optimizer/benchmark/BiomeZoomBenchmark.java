package dev.jpe.optimizer.benchmark;

import dev.jpe.optimizer.math.BiomeZoomMath;

import java.util.Random;

/** Deterministic microbenchmark for the exact BiomeManager zoom arithmetic. */
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

        runVariant("vanilla", warmup, iterations, seeds, xs, ys, zs, dx, dy, dz, false);
        runVariant("lookup", warmup, iterations, seeds, xs, ys, zs, dx, dy, dz, true);
    }

    private static void runVariant(
            String name, int warmup, int iterations,
            long[] seeds, int[] xs, int[] ys, int[] zs,
            double[] dx, double[] dy, double[] dz, boolean lookup
    ) {
        long checksum = 0L;
        for (int i = 0; i < warmup; i++) {
            checksum ^= execute(seeds, xs, ys, zs, dx, dy, dz, lookup);
        }
        long elapsed = 0L;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            checksum ^= execute(seeds, xs, ys, zs, dx, dy, dz, lookup);
            elapsed += System.nanoTime() - start;
        }
        double ns = (double) elapsed / ((long) iterations * seeds.length);
        System.out.printf(
                "biome_zoom_%s ns_per_corner=%.3f corners_per_second=%.0f checksum=%016x%n",
                name, ns, 1.0e9 / ns, checksum
        );
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
