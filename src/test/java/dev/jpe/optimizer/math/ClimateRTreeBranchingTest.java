package dev.jpe.optimizer.math;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClimateRTreeBranchingTest {
    private static final int[] FAN_OUTS = {8, 12, 16, 19, 24, 32};

    @Test
    void fanOutVariantsPreserveTheDefaultTreeResults() {
        Random random = new Random(0x2630_5EEDL);
        List<Pair<Climate.ParameterPoint, Integer>> values = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            values.add(Pair.of(Climate.parameters(
                    coordinate(random), coordinate(random), coordinate(random),
                    coordinate(random), coordinate(random), coordinate(random),
                    random.nextFloat()), i));
        }

        Climate.ParameterList<Integer> source = new Climate.ParameterList<>(values);
        Climate.ParameterList<Integer> baseline = source.rebuildWithChildrenPerNode(19);
        List<Climate.ParameterList<Integer>> variants = FAN_OUTS.length == 0
                ? List.of() : java.util.Arrays.stream(FAN_OUTS)
                .mapToObj(source::rebuildWithChildrenPerNode).toList();

        for (int sample = 0; sample < 5_000; sample++) {
            Climate.TargetPoint target = Climate.target(
                    coordinate(random), coordinate(random), coordinate(random),
                    coordinate(random), coordinate(random), coordinate(random));
            int expected = baseline.findValueIndex(target);
            for (int i = 0; i < FAN_OUTS.length; i++) {
                assertEquals(expected, variants.get(i).findValueIndex(target),
                        "fanOut=" + FAN_OUTS[i] + " sample=" + sample);
            }
        }
    }

    @Test
    void fanOutVariantsPreserveExactDuplicatePointTieResults() {
        List<Pair<Climate.ParameterPoint, Integer>> values = new ArrayList<>();
        Climate.ParameterPoint repeated = Climate.parameters(
                0.25F, -0.5F, 0.75F, -1.0F, 0.0F, 1.25F, 0.5F);
        for (int i = 0; i < 64; i++) {
            values.add(Pair.of(repeated, i));
        }
        for (int i = 64; i < 256; i++) {
            float value = (i - 160) / 100.0F;
            values.add(Pair.of(Climate.parameters(value, value, value, value, value, value, 0.0F), i));
        }

        Climate.ParameterList<Integer> source = new Climate.ParameterList<>(values);
        Climate.ParameterList<Integer> baseline = source.rebuildWithChildrenPerNode(19);
        List<Climate.ParameterList<Integer>> variants = java.util.Arrays.stream(FAN_OUTS)
                .mapToObj(source::rebuildWithChildrenPerNode).toList();
        Climate.TargetPoint tieTarget = Climate.target(
                0.25F, -0.5F, 0.75F, -1.0F, 0.0F, 1.25F);
        int expected = baseline.findValueIndex(tieTarget);
        for (int i = 0; i < FAN_OUTS.length; i++) {
            assertEquals(expected, variants.get(i).findValueIndex(tieTarget),
                    "fanOut=" + FAN_OUTS[i]);
        }
    }

    private static float coordinate(Random random) {
        return random.nextFloat() * 4.0F - 2.0F;
    }
}
