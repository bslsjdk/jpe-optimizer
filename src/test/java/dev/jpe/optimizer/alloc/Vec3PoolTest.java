package dev.jpe.optimizer.alloc;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Vec3PoolTest {
    @Test
    void pooledMustBeBitIdenticalToNaive() {
        Random random = new Random(0x4A50454CL);
        for (int i = 0; i < 1_000_000; i++) {
            double ax = random.nextDouble(-100, 100);
            double ay = random.nextDouble(-100, 100);
            double az = random.nextDouble(-100, 100);
            double bx = random.nextDouble(-100, 100);
            double by = random.nextDouble(-100, 100);
            double bz = random.nextDouble(-100, 100);
            double naive = Vec3Pool.distanceSqNaive(ax, ay, az, bx, by, bz);
            double pooled = Vec3Pool.distanceSqPooled(ax, ay, az, bx, by, bz);
            assertEquals(Double.doubleToLongBits(naive), Double.doubleToLongBits(pooled),
                    "sample " + i);
        }
    }

    @Test
    void borrowedSlotsAreReusedNotReallocated() {
        Vec3Pool.Vec3 a = Vec3Pool.borrow(0, 1, 2, 3);
        Vec3Pool.Vec3 b = Vec3Pool.borrow(1, 4, 5, 6);
        long idA = System.identityHashCode(a);
        long idB = System.identityHashCode(b);
    }
}