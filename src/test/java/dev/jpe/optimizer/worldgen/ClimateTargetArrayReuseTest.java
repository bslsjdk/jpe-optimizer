package dev.jpe.optimizer.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClimateTargetArrayReuseTest {
    private static final long[] COORDINATES = { 11L, -22L, 33L, -44L, 55L, -66L };

    @Test
    void reusableArrayIsFilledWithAllSixCoordinatesAndZeroOffset() {
        long[] scratch = new long[7];

        long[] result = ClimateTargetArrayReuse.toParameterArray(
                COORDINATES[0], COORDINATES[1], COORDINATES[2],
                COORDINATES[3], COORDINATES[4], COORDINATES[5],
                true, false, scratch
        );

        assertSame(scratch, result);
        assertArrayEquals(new long[] { 11L, -22L, 33L, -44L, 55L, -66L, 0L }, result);
    }

    @Test
    void disabledOrNestedSearchUsesAnIndependentArray() {
        long[] scratch = new long[7];

        long[] disabled = ClimateTargetArrayReuse.toParameterArray(
                COORDINATES[0], COORDINATES[1], COORDINATES[2],
                COORDINATES[3], COORDINATES[4], COORDINATES[5],
                false, false, scratch
        );
        long[] nested = ClimateTargetArrayReuse.toParameterArray(
                COORDINATES[0], COORDINATES[1], COORDINATES[2],
                COORDINATES[3], COORDINATES[4], COORDINATES[5],
                true, true, scratch
        );

        assertNotSame(scratch, disabled);
        assertNotSame(scratch, nested);
        assertNotSame(disabled, nested);
        assertArrayEquals(new long[] { 11L, -22L, 33L, -44L, 55L, -66L, 0L }, disabled);
        assertArrayEquals(disabled, nested);
    }
}
