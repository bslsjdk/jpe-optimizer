package dev.jpe.optimizer.math;

/**
 * Exact lookup-table replacement for the three squared jitter offsets used by
 * Minecraft 26.3 BiomeManager's biome zoom.
 *
 * The table contains all 8 quarter-block fractional offsets and all 1024
 * possible fiddle values. Values are built with the same double operations
 * as vanilla's getFiddle + Mth.square path.
 */
public final class BiomeZoomMath {
    private static final long LCG_MULTIPLIER = 6364136223846793005L;
    private static final long LCG_INCREMENT = 1442695040888963407L;
    private static final int FIDDLE_COUNT = 1024;
    private static final int OFFSET_COUNT = 8;
    private static final double[] SQUARED_OFFSETS = new double[OFFSET_COUNT * FIDDLE_COUNT];

    static {
        for (int offset = 0; offset < OFFSET_COUNT; offset++) {
            double distance = offset < 4
                    ? offset / 4.0
                    : (offset - 4) / 4.0 - 1.0;
            int base = offset * FIDDLE_COUNT;
            for (int fiddleIndex = 0; fiddleIndex < FIDDLE_COUNT; fiddleIndex++) {
                double uniform = fiddleIndex / 1024.0;
                double fiddle = (uniform - 0.5) * 0.9;
                double shifted = distance + fiddle;
                SQUARED_OFFSETS[base + fiddleIndex] = shifted * shifted;
            }
        }
    }

    private BiomeZoomMath() {}

    /**
     * Equivalent to Minecraft 26.3 BiomeManager.getFiddledDistance.
     * The distance arguments are the eight exact quarter-step offsets
     * produced by BiomeManager.getBiome.
     */
    public static double fiddledDistance(
            long seed,
            int xRandom,
            int yRandom,
            int zRandom,
            double distanceX,
            double distanceY,
            double distanceZ
    ) {
        long value = seed;
        value = next(value, xRandom);
        value = next(value, yRandom);
        value = next(value, zRandom);
        value = next(value, xRandom);
        value = next(value, yRandom);
        value = next(value, zRandom);

        int fiddleX = fiddleIndex(value);
        value = next(value, seed);
        int fiddleY = fiddleIndex(value);
        value = next(value, seed);
        int fiddleZ = fiddleIndex(value);

        int xBase = offsetBase(distanceX);
        int yBase = offsetBase(distanceY);
        int zBase = offsetBase(distanceZ);

        // Preserve vanilla's Z + Y + X addition order exactly.
        return SQUARED_OFFSETS[zBase + fiddleZ]
                + SQUARED_OFFSETS[yBase + fiddleY]
                + SQUARED_OFFSETS[xBase + fiddleX];
    }

    private static long next(long value, long salt) {
        value *= value * LCG_MULTIPLIER + LCG_INCREMENT;
        return value + salt;
    }

    private static int fiddleIndex(long value) {
        return (int) Math.floorMod(value >> 24, FIDDLE_COUNT);
    }

    private static int offsetBase(double distance) {
        int quarterOffset = (int) (distance * 4.0);
        int tableOffset = quarterOffset < 0 ? quarterOffset + 8 : quarterOffset;
        return tableOffset * FIDDLE_COUNT;
    }
}
