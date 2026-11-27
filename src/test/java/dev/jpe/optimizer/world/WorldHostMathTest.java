package dev.jpe.optimizer.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure tests for the streaming/FOV policy helpers. */
class WorldHostMathTest {

    @Test
    void followsRenderDistanceWhenNoOverride() {
        // configured <= 0 means "follow the live render distance" (still capped).
        assertEquals(6, WorldHostMath.effectiveStreamRadius(0, 6, 8));
        assertEquals(8, WorldHostMath.effectiveStreamRadius(-1, 12, 8));
    }

    @Test
    void capsAtTheMemoryBound() {
        assertEquals(8, WorldHostMath.effectiveStreamRadius(0, 12, 8));
        assertEquals(8, WorldHostMath.effectiveStreamRadius(20, 12, 8));
    }

    @Test
    void honoursAPositiveOverrideWithinTheCap() {
        assertEquals(3, WorldHostMath.effectiveStreamRadius(3, 12, 8));
        assertEquals(8, WorldHostMath.effectiveStreamRadius(9, 12, 8));
    }

    @Test
    void neverReturnsLessThanOne() {
        assertEquals(1, WorldHostMath.effectiveStreamRadius(0, 0, 8));
        assertEquals(1, WorldHostMath.effectiveStreamRadius(0, -4, 8));
        assertEquals(1, WorldHostMath.effectiveStreamRadius(0, 12, 0));
    }

    @Test
    void clampsFovToSanePerspectiveRange() {
        assertEquals(30.0f, WorldHostMath.clampFov(10));
        assertEquals(70.0f, WorldHostMath.clampFov(70));
        assertEquals(110.0f, WorldHostMath.clampFov(110));
        assertEquals(130.0f, WorldHostMath.clampFov(150));
    }

    @Test
    void farPlaneTracksRadiusWithAFloor() {
        assertEquals(128.0f, WorldHostMath.farPlane(4, 128));   // 4*16=64, floor 128
        assertEquals(192.0f, WorldHostMath.farPlane(12, 128));  // 12*16=192
        assertEquals(16.0f, WorldHostMath.farPlane(1, 8));      // 1*16=16, floor 8
    }
}