package net.minecraft.world.level.levelgen.synth;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import dev.jpe.optimizer.vector.DensityVectorRuntime;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** SIMD implementation of the contiguous Y-axis interpolation pass in Perlin volume sampling. */
public final class PerlinNoiseVectorizer {
    private static final int MAX_LANES = 64;
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
        if (lanes < 2 || lanes > MAX_LANES || volume.sizeY() < lanes
                || !hasVectorRun(noise, volume, yScale, lanes)) return false;

        Workspace workspace = WORKSPACE.get();
        workspace.ensureRelativeY(volume.sizeY());
        float[] output = ((DensityBufferAccessor) buffer).jpe$values();
        int sizeY = volume.sizeY();

        for (int indexZ = 0; indexZ < volume.sizeZ(); ++indexZ) {
            double z = GradientNoise.wrap(volume.blockZ(indexZ) * xzScale) + noise.offsetZ;
            int floorZ = Mth.floor(z);
            float relativeZ = (float) (z - floorZ);
            float alphaZ = Mth.smoothstep(relativeZ);

            for (int indexX = 0; indexX < volume.sizeX(); ++indexX) {
                double x = GradientNoise.wrap(volume.blockX(indexX) * xzScale) + noise.offsetX;
                int floorX = Mth.floor(x);
                float relativeX = (float) (x - floorX);
                float alphaX = Mth.smoothstep(relativeX);
                int x0 = noise.permute(floorX);
                int x1 = noise.permute(floorX + 1);
                int baseIndex = (indexZ * volume.sizeX() + indexX) * sizeY;

                int indexY = 0;
                while (indexY < sizeY) {
                    int floorY = floorY(noise, volume, indexY, yScale);
                    int runEnd = indexY + 1;
                    while (runEnd < sizeY && floorY(noise, volume, runEnd, yScale) == floorY) ++runEnd;

                    fillGradientCorners(noise, x0, x1, floorY, floorZ, relativeX, relativeZ, workspace);
                    int runLength = runEnd - indexY;
                    for (int local = 0; local < runLength; ++local) {
                        double y = GradientNoise.wrap(volume.blockY(indexY + local) * yScale) + noise.offsetY;
                        workspace.relativeY[local] = (float) (y - floorY);
                    }
                    DensityVectorRuntime.perlinLerpAdd(output, baseIndex + indexY, workspace.relativeY,
                            runLength, alphaX, alphaZ, amplitude, workspace.cornerXZ, workspace.cornerY);
                    indexY = runEnd;
                }
            }
        }
        LAST_VECTOR.set(true);
        return true;
    }

    private static boolean hasVectorRun(PerlinNoise noise, DensityVolume volume, double yScale, int lanes) {
        int previousFloor = Integer.MIN_VALUE, runLength = 0;
        for (int y = 0; y < volume.sizeY(); ++y) {
            int currentFloor = floorY(noise, volume, y, yScale);
            if (currentFloor == previousFloor) ++runLength;
            else { previousFloor = currentFloor; runLength = 1; }
            if (runLength >= lanes) return true;
        }
        return false;
    }

    private static int floorY(PerlinNoise noise, DensityVolume volume, int yIndex, double yScale) {
        return Mth.floor(GradientNoise.wrap(volume.blockY(yIndex) * yScale) + noise.offsetY);
    }

    private static void fillGradientCorners(PerlinNoise noise, int x0, int x1, int floorY, int floorZ,
                                            float relativeX, float relativeZ, Workspace w) {
        int xy00 = noise.permute(x0 + floorY), xy01 = noise.permute(x0 + floorY + 1);
        int xy10 = noise.permute(x1 + floorY), xy11 = noise.permute(x1 + floorY + 1);
        putCorner(w, 0, noise.permuteToGrad(xy00 + floorZ), relativeX, relativeZ);
        putCorner(w, 1, noise.permuteToGrad(xy10 + floorZ), relativeX - 1.0F, relativeZ);
        putCorner(w, 2, noise.permuteToGrad(xy01 + floorZ), relativeX, relativeZ);
        putCorner(w, 3, noise.permuteToGrad(xy11 + floorZ), relativeX - 1.0F, relativeZ);
        putCorner(w, 4, noise.permuteToGrad(xy00 + floorZ + 1), relativeX, relativeZ - 1.0F);
        putCorner(w, 5, noise.permuteToGrad(xy10 + floorZ + 1), relativeX - 1.0F, relativeZ - 1.0F);
        putCorner(w, 6, noise.permuteToGrad(xy01 + floorZ + 1), relativeX, relativeZ - 1.0F);
        putCorner(w, 7, noise.permuteToGrad(xy11 + floorZ + 1), relativeX - 1.0F, relativeZ - 1.0F);
    }

    private static void putCorner(Workspace w, int i, GradientNoise.Gradient g, float x, float z) {
        w.cornerXZ[i] = g.dotXz(x, z);
        w.cornerY[i] = g.y();
    }

    private static final class Workspace {
        private float[] relativeY = new float[256];
        private final float[] cornerXZ = new float[8];
        private final float[] cornerY = new float[8];
        private void ensureRelativeY(int size) {
            if (relativeY.length < size) relativeY = new float[size];
        }
    }
}
