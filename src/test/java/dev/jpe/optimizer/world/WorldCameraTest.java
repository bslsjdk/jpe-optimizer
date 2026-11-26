package dev.jpe.optimizer.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Proves the Java host's clip matrix matches the contract the Rust engine expects:
 * column-major storage and a Vulkan {@code 0..=1} depth range that the frustum
 * extractor relies on. Runs without a GPU or a running game.
 */
class WorldCameraTest {

    private static final float NEAR = 1.0f;
    private static final float FAR = 100.0f;
    private static final float FOV = 70.0f;
    private static final float ASPECT = 1.0f;

    // Clip-space point at world (x,y,z) -> normalised device coords.
    private static float[] ndc(float[] m, float x, float y, float z) {
        float[] c = WorldCamera.transformClip(m, x, y, z);
        assertTrue(Math.abs(c[3]) > 1e-6f, "w should be non-zero for an in-front point");
        return new float[]{c[0] / c[3], c[1] / c[3], c[2] / c[3], c[3]};
    }

    @Test
    void depthMapsNearPlaneToZero() {
        // yaw=0, pitch=0 looks along +Z, so (0,0,NEAR) is straight ahead at the
        // near plane and must land exactly on ndcZ = 0 (Vulkan range).
        float[] m = WorldCamera.perspectiveViewProj(0, 0, 0, 0.0f, 0.0f, FOV, ASPECT, NEAR, FAR);
        float[] p = ndc(m, 0.0f, 0.0f, NEAR);
        assertEquals(0.0f, p[2], 1e-4f, "near plane should map to depth 0");
    }

    @Test
    void depthMapsFarPlaneToOne() {
        float[] m = WorldCamera.perspectiveViewProj(0, 0, 0, 0.0f, 0.0f, FOV, ASPECT, NEAR, FAR);
        float[] p = ndc(m, 0.0f, 0.0f, FAR);
        assertEquals(1.0f, p[2], 1e-3f, "far plane should map to depth 1");
    }

    @Test
    void depthIsMonotonicAndWithinRange() {
        float[] m = WorldCamera.perspectiveViewProj(0, 0, 0, 0.0f, 0.0f, FOV, ASPECT, NEAR, FAR);
        float prev = -1.0f;
        for (float d = NEAR; d <= FAR; d *= 1.5f) {
            float z = ndc(m, 0.0f, 0.0f, d)[2];
            assertTrue(z >= -1e-4f && z <= 1.0f + 1e-4f, "depth out of 0..1 at d=" + d + ": " + z);
            assertTrue(z >= prev, "depth must increase with distance (prev " + prev + ", now " + z + ")");
            prev = z;
        }
    }

    @Test
    void beyondFarIsCulled() {
        float[] m = WorldCamera.perspectiveViewProj(0, 0, 0, 0.0f, 0.0f, FOV, ASPECT, NEAR, FAR);
        float z = ndc(m, 0.0f, 0.0f, FAR * 2.0f)[2];
        assertTrue(z > 1.0f, "a point past the far plane should exceed depth 1, got " + z);
    }

    @Test
    void onAxisProjectsToScreenCentre() {
        float[] m = WorldCamera.perspectiveViewProj(0, 0, 0, 0.0f, 0.0f, FOV, ASPECT, NEAR, FAR);
        float[] p = ndc(m, 0.0f, 0.0f, 10.0f);
        assertEquals(0.0f, p[0], 1e-4f, "an on-axis point should be centred horizontally");
        assertEquals(0.0f, p[1], 1e-4f, "an on-axis point should be centred vertically");
    }

    @Test
    void behindCameraHasNegativeClipW() {
        float[] m = WorldCamera.perspectiveViewProj(0, 0, 0, 0.0f, 0.0f, FOV, ASPECT, NEAR, FAR);
        // Straight behind the camera (world -Z, i.e. opposite of the +Z look vector).
        float[] c = WorldCamera.transformClip(m, 0.0f, 0.0f, -10.0f);
        assertTrue(c[3] < 0.0f, "a point behind the camera should have w < 0, got " + c[3]);
    }

    @Test
    void cameraTranslationMovesWorldOriginForward() {
        // An eye pushed back along -Z should see the world origin straight ahead at
        // distance 10, mapping to the same depth as (0,0,10) from the origin eye.
        float[] m = WorldCamera.perspectiveViewProj(0.0, 0.0, -10.0, 0.0f, 0.0f, FOV, ASPECT, NEAR, FAR);
        float z = ndc(m, 0.0f, 0.0f, 0.0f)[2];
        float reference = ndc(WorldCamera.perspectiveViewProj(0, 0, 0, 0.0f, 0.0f, FOV, ASPECT, NEAR, FAR),
                0.0f, 0.0f, 10.0f)[2];
        assertEquals(reference, z, 1e-4f);
        assertEquals(0.0f, ndc(m, 0.0f, 0.0f, 0.0f)[0], 1e-4f);
    }

    @Test
    void rejectsInvalidFrustumParameters() {
        // far must exceed near, near must be positive, fov must be in (0,180).
        assertTrue(throwsIllegalArgument(() ->
                WorldCamera.perspectiveViewProj(0, 0, 0, 0, 0, FOV, ASPECT, 1.0f, 1.0f)));
        assertTrue(throwsIllegalArgument(() ->
                WorldCamera.perspectiveViewProj(0, 0, 0, 0, 0, FOV, ASPECT, 0.0f, 10.0f)));
        assertTrue(throwsIllegalArgument(() ->
                WorldCamera.perspectiveViewProj(0, 0, 0, 0, 0, 200.0f, ASPECT, 1.0f, 10.0f)));
    }

    private static boolean throwsIllegalArgument(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
