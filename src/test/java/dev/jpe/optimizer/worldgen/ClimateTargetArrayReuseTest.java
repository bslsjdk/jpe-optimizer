package dev.jpe.optimizer.worldgen;

import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClimateTargetArrayReuseTest {
    @Test
    void reusableArrayIsFilledWithAllSixCoordinatesAndZeroOffset() {
        Climate.TargetPoint target = Climate.target(0.25F, -0.5F, 0.75F, -1.0F, 0.0F, 1.25F);
        long[] scratch = new long[7];

        long[] result = ClimateTargetArrayReuse.toParameterArray(target, true, false, scratch);

        assertSame(scratch, result);
        assertArrayEquals(new long[] {
                target.temperature(), target.humidity(), target.continentalness(),
                target.erosion(), target.depth(), target.weirdness(), 0L
        }, result);
    }

    @Test
    void disabledOrNestedSearchUsesAnIndependentArray() {
        Climate.TargetPoint target = Climate.target(0.25F, -0.5F, 0.75F, -1.0F, 0.0F, 1.25F);
        long[] scratch = new long[7];

        long[] disabled = ClimateTargetArrayReuse.toParameterArray(target, false, false, scratch);
        long[] nested = ClimateTargetArrayReuse.toParameterArray(target, true, true, scratch);

        assertNotSame(scratch, disabled);
        assertNotSame(scratch, nested);
        assertNotSame(disabled, nested);
        assertArrayEquals(new long[] {
                target.temperature(), target.humidity(), target.continentalness(),
                target.erosion(), target.depth(), target.weirdness(), 0L
        }, disabled);
        assertArrayEquals(disabled, nested);
    }
}
