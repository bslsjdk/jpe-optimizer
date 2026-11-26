package dev.jpe.optimizer.math;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BiomeZoomMathTest {
    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;
    private static final double[] OFFSETS = {
            0.0, 0.25, 0.5, 0.75, -1.0, -0.75, -0.5, -0.25
    };

    @Test
    void lookupTableMustBeBitIdenticalToVanillaForRandomInputs() {
        Random random = new Random(0x42494F4D455A4F4FL);
        for (int i = 0; i < 1_000_000; i++) {
            long seed = random.nextLong();
            int x = random.nextInt();
            int y = random.nextInt();
            int z = random.nextInt();
            double dx = OFFSETS[random.nextInt(OFFSETS.length)];
            double dy = OFFSETS[random.nextInt(OFFSETS.length)];
            double dz = OFFSETS[random.nextInt(OFFSETS.length)];

            double expected = vanilla(seed, x, y, z, dx, dy, dz);
            double actual = BiomeZoomMath.fiddledDistance(seed, x, y, z, dx, dy, dz);
            assertEquals(
                    Double.doubleToLongBits(expected),
                    Double.doubleToLongBits(actual),
                    "sample " + i
            );
        }
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
