package dev.jpe.optimizer.vector;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import dev.jpe.optimizer.mixin.GradientNoiseAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/** SIMD implementation of the contiguous Y-axis interpolation pass in Perlin volume sampling. */
public final class PerlinNoiseVectorizer {
    private static final int MAX_LANES = 64;
    private static final int[] GX = {1,-1,1,-1,1,-1,1,-1,0,0,0,0,1,0,-1,0};
    private static final int[] GY = {1,1,-1,-1,0,0,0,0,1,-1,1,-1,1,-1,1,-1};
    private static final int[] GZ = {0,0,0,0,1,1,-1,-1,1,1,-1,-1,0,1,0,-1};
    private static final double HALF_ROUND_OFF = Math.nextDown(1.6777216E7);
    private static final double ROUND_OFF = 3.3554432E7;
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);
    private static final ThreadLocal<Boolean> VANILLA_BYPASS = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> LAST_VECTOR = ThreadLocal.withInitial(() -> false);

    private PerlinNoiseVectorizer() {}
    public static void setVanillaBypass(boolean value) { VANILLA_BYPASS.set(value); }
    public static boolean vanillaBypass() { return VANILLA_BYPASS.get(); }
    public static boolean wasLastCallVectorized() { return LAST_VECTOR.get(); }

    public static boolean addToVolume(PerlinNoise noise, DensityBuffer buffer, DensityVolume volume,
                                      double xzScale, double yScale, float amplitude) {
        LAST_VECTOR.set(false);
        int lanes = DensityVectorRuntime.laneCount();
        if (lanes < 2 || lanes > MAX_LANES || volume.sizeY() < lanes) return false;

        GradientNoiseAccessor access = (GradientNoiseAccessor) (Object) noise;
        byte[] perms = access.jpe$perms();
        double offsetX = access.jpe$offsetX(), offsetY = access.jpe$offsetY(), offsetZ = access.jpe$offsetZ();
        Workspace w = WORKSPACE.get();
        w.ensure(volume.sizeY());
        boolean vectorizable = false;
        int previousFloor = Integer.MIN_VALUE, run = 0;
        for (int y = 0; y < volume.sizeY(); ++y) {
            double coord = wrap(volume.blockY(y) * yScale) + offsetY;
            int floor = Mth.floor(coord);
            w.floorY[y] = floor;
            w.relativeYAll[y] = (float) (coord - floor);
            if (floor == previousFloor) ++run;
            else { previousFloor = floor; run = 1; }
            if (run >= lanes) vectorizable = true;
        }
        if (!vectorizable) return false;

        float[] output = ((DensityBufferAccessor) buffer).jpe$values();
        int sizeY = volume.sizeY();
        for (int iz = 0; iz < volume.sizeZ(); ++iz) {
            double z = wrap(volume.blockZ(iz) * xzScale) + offsetZ;
            int floorZ = Mth.floor(z);
            float relativeZ = (float) (z - floorZ);
            float alphaZ = Mth.smoothstep(relativeZ);
            for (int ix = 0; ix < volume.sizeX(); ++ix) {
                double x = wrap(volume.blockX(ix) * xzScale) + offsetX;
                int floorX = Mth.floor(x);
                float relativeX = (float) (x - floorX);
                float alphaX = Mth.smoothstep(relativeX);
                int x0 = permute(perms, floorX), x1 = permute(perms, floorX + 1);
                int base = (iz * volume.sizeX() + ix) * sizeY;
                int iy = 0;
                while (iy < sizeY) {
                    int floorY = w.floorY[iy], end = iy + 1;
                    while (end < sizeY && w.floorY[end] == floorY) ++end;
                    fillCorners(perms, x0, x1, floorY, floorZ, relativeX, relativeZ, w);
                    int count = end - iy;
                    System.arraycopy(w.relativeYAll, iy, w.relativeY, 0, count);
                    DensityVectorRuntime.perlinLerpAdd(output, base + iy, w.relativeY, count,
                            alphaX, alphaZ, amplitude, w.cornerXZ, w.cornerY);
                    iy = end;
                }
            }
        }
        LAST_VECTOR.set(true);
        return true;
    }

    private static void fillCorners(byte[] perms, int x0, int x1, int y, int z,
                                     float rx, float rz, Workspace w) {
        int xy00 = permute(perms, x0 + y), xy01 = permute(perms, x0 + y + 1);
        int xy10 = permute(perms, x1 + y), xy11 = permute(perms, x1 + y + 1);
        put(w, 0, perms, xy00 + z, rx, rz);
        put(w, 1, perms, xy10 + z, rx - 1.0F, rz);
        put(w, 2, perms, xy01 + z, rx, rz);
        put(w, 3, perms, xy11 + z, rx - 1.0F, rz);
        put(w, 4, perms, xy00 + z + 1, rx, rz - 1.0F);
        put(w, 5, perms, xy10 + z + 1, rx - 1.0F, rz - 1.0F);
        put(w, 6, perms, xy01 + z + 1, rx, rz - 1.0F);
        put(w, 7, perms, xy11 + z + 1, rx - 1.0F, rz - 1.0F);
    }

    private static void put(Workspace w, int slot, byte[] perms, int hash, float x, float z) {
        int g = perms[hash & 255] & 15;
        w.cornerXZ[slot] = GX[g] * x + GZ[g] * z;
        w.cornerY[slot] = GY[g];
    }

    private static int permute(byte[] perms, int value) {
        return perms[value & 255] & 255;
    }

    private static double wrap(double value) {
        if (SharedConstants.DEBUG_ENABLE_FARLANDS) return value;
        return value >= -HALF_ROUND_OFF && value < HALF_ROUND_OFF
                ? value : value - Math.floor(value / ROUND_OFF + 0.5) * ROUND_OFF;
    }

    private static final class Workspace {
        private int[] floorY = new int[256];
        private float[] relativeYAll = new float[256];
        private float[] relativeY = new float[256];
        private final float[] cornerXZ = new float[8];
        private final float[] cornerY = new float[8];
        private void ensure(int size) {
            if (floorY.length < size) {
                floorY = new int[size];
                relativeYAll = new float[size];
                relativeY = new float[size];
            }
        }
    }
}
