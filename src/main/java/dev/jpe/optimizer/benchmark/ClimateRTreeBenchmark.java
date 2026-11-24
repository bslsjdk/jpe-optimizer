package dev.jpe.optimizer.benchmark;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Measures Climate.RTree fan-out using deterministic synthetic biome parameter
 * points. This is a data-structure microbenchmark, not a claim about full
 * Minecraft world-generation performance.
 */
public final class ClimateRTreeBenchmark {
    private static final int[] FAN_OUTS = {8, 12, 16, 19, 24, 32};
    private static final int PARAMETER_POINTS = 256;

    private ClimateRTreeBenchmark() {}

    public static void run(int warmupIterations, int iterations, int requestedQueries) {
        int queryCount = Math.max(4_096, Math.min(32_768, requestedQueries / 8));
        Climate.ParameterList<Integer> source = createParameterList();
        List<Climate.TargetPoint> queries = createQueries(queryCount);
        Climate.ParameterList<Integer> reference = source.rebuildWithChildrenPerNode(19);
        int[] expected = new int[queryCount];
        for (int i = 0; i < queryCount; i++) {
            expected[i] = reference.findValueIndex(queries.get(i));
        }

        System.out.printf("JPE_CLIMATE_RTREE_BENCH points=%d queries=%d fanOuts=%s%n",
                PARAMETER_POINTS, queryCount, java.util.Arrays.toString(FAN_OUTS));

        for (int fanOut : FAN_OUTS) {
            long buildStart = System.nanoTime();
            Climate.ParameterList<Integer> candidate = source.rebuildWithChildrenPerNode(fanOut);
            long buildNanos = System.nanoTime() - buildStart;

            long checksum = 0;
            for (int i = 0; i < warmupIterations; i++) {
                checksum += execute(candidate, queries, expected);
            }

            long elapsed = 0;
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                checksum += execute(candidate, queries, expected);
                elapsed += System.nanoTime() - start;
            }

            long totalQueries = (long) iterations * queryCount;
            double nsPerQuery = elapsed / (double) totalQueries;
            System.out.printf(
                    "fanOut=%d buildMs=%.3f nsPerQuery=%.3f queriesPerSecond=%.0f checksum=%d%n",
                    fanOut, buildNanos / 1_000_000.0, nsPerQuery, 1_000_000_000.0 / nsPerQuery, checksum
            );
        }
    }

    private static long execute(
            Climate.ParameterList<Integer> candidate,
            List<Climate.TargetPoint> queries,
            int[] expected
    ) {
        long checksum = 0;
        for (int i = 0; i < queries.size(); i++) {
            int actual = candidate.findValueIndex(queries.get(i));
            if (actual != expected[i]) {
                throw new IllegalStateException("Climate.RTree fan-out changed nearest biome result: query="
                        + i + " expected=" + expected[i] + " actual=" + actual);
            }
            checksum += actual;
        }
        return checksum;
    }

    private static Climate.ParameterList<Integer> createParameterList() {
        Random random = new Random(0x2630_C11A_7EL);
        List<Pair<Climate.ParameterPoint, Integer>> values = new ArrayList<>(PARAMETER_POINTS);
        for (int i = 0; i < PARAMETER_POINTS; i++) {
            values.add(Pair.of(Climate.parameters(
                    coordinate(random), coordinate(random), coordinate(random),
                    coordinate(random), coordinate(random), coordinate(random),
                    random.nextFloat()), i));
        }
        return new Climate.ParameterList<>(values);
    }

    private static List<Climate.TargetPoint> createQueries(int count) {
        Random random = new Random(0x2630_7A26E7L);
        List<Climate.TargetPoint> queries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            queries.add(Climate.target(coordinate(random), coordinate(random), coordinate(random),
                    coordinate(random), coordinate(random), coordinate(random)));
        }
        return queries;
    }

    private static float coordinate(Random random) {
        return random.nextFloat() * 4.0F - 2.0F;
    }
}
