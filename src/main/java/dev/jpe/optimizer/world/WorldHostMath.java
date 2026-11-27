package dev.jpe.optimizer.world;

/**
 * Pure, Minecraft-independent policy helpers for {@link WorldEngineHost}: how far to
 * stream, and what FOV to feed the camera. Kept separate (and free of any
 * {@code net.minecraft} type) so they unit-test on a bare classpath.
 */
public final class WorldHostMath {

    private WorldHostMath() {
    }

    /**
     * The streaming radius in chunks for this tick.
     *
     * @param configuredRadius a positive override, or {@code <= 0} to follow the live
     *                         render-distance option
     * @param renderDistance   Minecraft's current render distance in chunks
     * @param cap              hard memory cap in chunks (the outermost bound)
     * @return a radius in {@code [1, max(1, cap)]}
     */
    public static int effectiveStreamRadius(int configuredRadius, int renderDistance, int cap) {
        int upperBound = Math.max(1, cap);
        int base = configuredRadius > 0 ? configuredRadius : renderDistance;
        if (base < 1) {
            base = 1;
        }
        return Math.min(base, upperBound);
    }

    /**
     * Clamp the base FOV option into a sane perspective range. Minecraft's option is
     * normally {@code 30..110}; the engine only requires {@code (0, 180)}.
     */
    public static float clampFov(int fovDegrees) {
        if (fovDegrees < 30) {
            return 30.0f;
        }
        if (fovDegrees > 130) {
            return 130.0f;
        }
        return fovDegrees;
    }

    /** Far plane for a given streaming radius, with a floor so distant terrain is kept. */
    public static float farPlane(int effectiveRadiusChunks, int minFarBlocks) {
        int fromRadius = Math.max(1, effectiveRadiusChunks) * 16;
        return Math.max(minFarBlocks, fromRadius);
    }
}