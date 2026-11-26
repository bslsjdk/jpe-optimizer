package dev.jpe.optimizer.benchmark;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import dev.jpe.optimizer.mixin.MultiNoiseBiomeSourceInvoker;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Measures Climate.RTree fan-out using deterministic synthetic biome parameter
 * points. This is a data-structure microbenchmark, not a claim about full
 * Minecraft world-generation performance.
 *
 * Candidate order is shuffled between rounds. Each reported search sample is a
 * complete pass over the same query corpus, allowing median and p95 reporting
 * without mixing tree construction into query latency.
 */
public final class ClimateRTreeBenchmark {
    private static final int[] FAN_OUTS = {8, 12, 16, 19, 24, 32};
    private static final int PARAMETER_POINTS = 256;
    private static final int MEASUREMENT_ROUNDS = 5;

    private ClimateRTreeBenchmark() {}

    public static void run(int warmupIterations, int iterations, int requestedQueries) {
        int queryCount = Math.max(4_096, Math.min(32_768, requestedQueries / 8));
        int measuredIterations = Math.max(1, iterations);
        int perRoundWarmup = Math.max(1, warmupIterations / 2);

        verifyDuplicateTieResults();
        Climate.ParameterList<Integer> source = createParameterList();
        List<Climate.TargetPoint> queries = createQueries(queryCount);
        Climate.ParameterList<Integer> reference = source.rebuildWithChildrenPerNode(19);
        int[] expected = new int[queryCount];
        for (int i = 0; i < queryCount; i++) {
            expected[i] = reference.findValueIndex(queries.get(i));
        }

        double[][] searchSamples = new double[FAN_OUTS.length][MEASUREMENT_ROUNDS * measuredIterations];
        double[][] buildSamples = new double[FAN_OUTS.length][MEASUREMENT_ROUNDS];
        int[] order = new int[FAN_OUTS.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Random orderRandom = new Random(0x2630_0D3L);
        long[] checksums = new long[FAN_OUTS.length];

        System.out.printf(
                "JPE_CLIMATE_RTREE_BENCH points=%d queries=%d fanOuts=%s rounds=%d iterationsPerRound=%d order=randomized distribution=uniform%n",
                PARAMETER_POINTS, queryCount, Arrays.toString(FAN_OUTS),
                MEASUREMENT_ROUNDS, measuredIterations
        );

        for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
            shuffle(order, orderRandom);
            for (int variant : order) {
                int fanOut = FAN_OUTS[variant];

                long buildStart = System.nanoTime();
                Climate.ParameterList<Integer> candidate = source.rebuildWithChildrenPerNode(fanOut);
                buildSamples[variant][round] = System.nanoTime() - buildStart;

                for (int i = 0; i < perRoundWarmup; i++) {
                    checksums[variant] += execute(candidate, queries, expected);
                }

                for (int i = 0; i < measuredIterations; i++) {
                    long start = System.nanoTime();
                    checksums[variant] += execute(candidate, queries, expected);
                    long elapsed = System.nanoTime() - start;
                    searchSamples[variant][round * measuredIterations + i] =
                            elapsed / (double) queryCount;
                }
            }
        }

        for (int variant = 0; variant < FAN_OUTS.length; variant++) {
            double medianNs = percentile(searchSamples[variant], 0.50);
            double p95Ns = percentile(searchSamples[variant], 0.95);
            double medianBuildMs = percentile(buildSamples[variant], 0.50) / 1_000_000.0;
            System.out.printf(
                    "fanOut=%d buildMedianMs=%.3f nsPerQueryMedian=%.3f nsPerQueryP95=%.3f "
                            + "queriesPerSecondMedian=%.0f samples=%d checksum=%d%n",
                    FAN_OUTS[variant], medianBuildMs, medianNs, p95Ns,
                    1_000_000_000.0 / medianNs, searchSamples[variant].length, checksums[variant]
            );
        }
        runCoherentWorkload(source, queryCount, perRoundWarmup, measuredIterations);
    }

    /**
     * Measures the exact overworld biome parameter list against climate points
     * sampled from this server's real seed and noise router. Query order is a
     * contiguous quart-grid sweep so the RTree's per-thread last-result hint
     * sees realistic spatial locality.
     */
    public static void runActualOverworld(ServerLevel level, int warmupIterations, int iterations) {
        var generator = level.getChunkSource().getGenerator();
        if (!(generator.getBiomeSource() instanceof MultiNoiseBiomeSource biomeSource)) {
            throw new IllegalStateException("Expected MultiNoiseBiomeSource, got "
                    + generator.getBiomeSource().getClass().getName());
        }

        Climate.ParameterList<Holder<Biome>> source =
                ((MultiNoiseBiomeSourceInvoker) biomeSource).jpe$getParameters();
        Climate.Sampler sampler = level.getChunkSource().randomState()
                .createClimateSampler(SamplerContext.EMPTY_UNCACHED);

        int side = 64;
        int quartY = 0;
        int startQuartX = 512;
        int startQuartZ = 512;
        List<Climate.TargetPoint> queries = new ArrayList<>(side * side);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                queries.add(sampler.sample(startQuartX + x, quartY, startQuartZ + z));
            }
        }

        Climate.ParameterList<Holder<Biome>> reference = source.rebuildWithChildrenPerNode(19);
        List<Holder<Biome>> expected = new ArrayList<>(queries.size());
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> parameterValues = source.values();
        for (int i = 0; i < queries.size(); i++) {
            Climate.TargetPoint query = queries.get(i);
            Holder<Biome> indexed = reference.findValueIndex(query);
            long minimumFitness = Long.MAX_VALUE;
            long indexedBiomeFitness = Long.MAX_VALUE;
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : parameterValues) {
                long fitness = entry.getFirst().fitness(query);
                minimumFitness = Math.min(minimumFitness, fitness);
                if (entry.getSecond().equals(indexed)) {
                    indexedBiomeFitness = Math.min(indexedBiomeFitness, fitness);
                }
            }
            if (indexedBiomeFitness != minimumFitness) {
                throw new IllegalStateException("Vanilla 19-way RTree did not return a nearest biome at real query "
                        + i + ": indexedFitness=" + indexedBiomeFitness + " minimumFitness=" + minimumFitness);
            }
            expected.add(indexed);
        }

        double[][] samples = new double[FAN_OUTS.length][MEASUREMENT_ROUNDS * Math.max(1, iterations)];
        double[][] builds = new double[FAN_OUTS.length][MEASUREMENT_ROUNDS];
        long[] checksums = new long[FAN_OUTS.length];
        int[] order = new int[FAN_OUTS.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Random orderRandom = new Random(0x2630_26A3L);
        int warmup = Math.max(1, warmupIterations);
        int measured = Math.max(1, iterations);

        System.out.printf(
                "JPE_CLIMATE_RTREE_REAL points=%d queries=%d seed=%d fanOuts=%s rounds=%d warmupPasses=%d iterationsPerRound=%d trace=overworld-climate-quart-grid%n",
                source.values().size(), queries.size(), level.getSeed(), Arrays.toString(FAN_OUTS),
                MEASUREMENT_ROUNDS, warmup, measured
        );

        for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
            shuffle(order, orderRandom);
            for (int variant : order) {
                long buildStart = System.nanoTime();
                Climate.ParameterList<Holder<Biome>> candidate =
                        source.rebuildWithChildrenPerNode(FAN_OUTS[variant]);
                builds[variant][round] = System.nanoTime() - buildStart;

                for (int i = 0; i < warmup; i++) {
                    checksums[variant] += executeActual(candidate, queries, expected);
                }
                for (int i = 0; i < measured; i++) {
                    long start = System.nanoTime();
                    checksums[variant] += executeActual(candidate, queries, expected);
                    samples[variant][round * measured + i] =
                            (System.nanoTime() - start) / (double) queries.size();
                }
            }
        }

        for (int variant = 0; variant < FAN_OUTS.length; variant++) {
            double medianNs = percentile(samples[variant], 0.50);
            double p95Ns = percentile(samples[variant], 0.95);
            System.out.printf(
                    "JPE_CLIMATE_RTREE_REAL fanOut=%d buildMedianMs=%.3f nsPerQueryMedian=%.3f "
                            + "nsPerQueryP95=%.3f queriesPerSecondMedian=%.0f samples=%d checksum=%d%n",
                    FAN_OUTS[variant], percentile(builds[variant], 0.50) / 1_000_000.0,
                    medianNs, p95Ns, 1_000_000_000.0 / medianNs,
                    samples[variant].length, checksums[variant]
            );
        }
    }

    private static long executeActual(
            Climate.ParameterList<Holder<Biome>> candidate,
            List<Climate.TargetPoint> queries,
            List<Holder<Biome>> expected
    ) {
        long checksum = 0;
        for (int i = 0; i < queries.size(); i++) {
            Holder<Biome> actual = candidate.findValueIndex(queries.get(i));
            Holder<Biome> expectedBiome = expected.get(i);
            if (!actual.equals(expectedBiome)) {
                throw new IllegalStateException("Climate.RTree fan-out changed real overworld biome result: query="
                        + i + " expected=" + expectedBiome + " actual=" + actual);
            }
            checksum += actual.hashCode();
        }
        return checksum;
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

    /**
     * Replays a smooth six-dimensional random walk. This is deliberately a
     * synthetic locality stress test for RTree.lastResult, not a captured
     * Minecraft climate trace.
     */
    private static void runCoherentWorkload(
            Climate.ParameterList<Integer> source,
            int queryCount,
            int perRoundWarmup,
            int measuredIterations
    ) {
        List<Climate.TargetPoint> queries = createCoherentQueries(queryCount);
        Climate.ParameterList<Integer> reference = source.rebuildWithChildrenPerNode(19);
        int[] expected = new int[queryCount];
        for (int i = 0; i < queryCount; i++) {
            expected[i] = reference.findValueIndex(queries.get(i));
        }

        double[][] searchSamples = new double[FAN_OUTS.length][MEASUREMENT_ROUNDS * measuredIterations];
        double[][] buildSamples = new double[FAN_OUTS.length][MEASUREMENT_ROUNDS];
        int[] order = new int[FAN_OUTS.length];
        long[] checksums = new long[FAN_OUTS.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Random orderRandom = new Random(0x2630_C0E2L);

        System.out.printf(
                "JPE_CLIMATE_RTREE_BENCH workload=coherent-random-walk queries=%d rounds=%d iterationsPerRound=%d localityStep=0.025%n",
                queryCount, MEASUREMENT_ROUNDS, measuredIterations
        );

        for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
            shuffle(order, orderRandom);
            for (int variant : order) {
                long buildStart = System.nanoTime();
                Climate.ParameterList<Integer> candidate = source.rebuildWithChildrenPerNode(FAN_OUTS[variant]);
                buildSamples[variant][round] = System.nanoTime() - buildStart;

                for (int i = 0; i < perRoundWarmup; i++) {
                    checksums[variant] += execute(candidate, queries, expected);
                }
                for (int i = 0; i < measuredIterations; i++) {
                    long start = System.nanoTime();
                    checksums[variant] += execute(candidate, queries, expected);
                    searchSamples[variant][round * measuredIterations + i] =
                            (System.nanoTime() - start) / (double) queryCount;
                }
            }
        }

        for (int variant = 0; variant < FAN_OUTS.length; variant++) {
            double medianNs = percentile(searchSamples[variant], 0.50);
            double p95Ns = percentile(searchSamples[variant], 0.95);
            double medianBuildMs = percentile(buildSamples[variant], 0.50) / 1_000_000.0;
            System.out.printf(
                    "workload=coherent-random-walk fanOut=%d buildMedianMs=%.3f nsPerQueryMedian=%.3f "
                            + "nsPerQueryP95=%.3f queriesPerSecondMedian=%.0f samples=%d checksum=%d%n",
                    FAN_OUTS[variant], medianBuildMs, medianNs, p95Ns,
                    1_000_000_000.0 / medianNs, searchSamples[variant].length, checksums[variant]
            );
        }
    }

    private static List<Climate.TargetPoint> createCoherentQueries(int count) {
        Random random = new Random(0x2630_10CA11L);
        float[] state = new float[6];
        for (int d = 0; d < state.length; d++) state[d] = coordinate(random);
        List<Climate.TargetPoint> queries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < state.length; d++) {
                state[d] = Math.max(-2.0F, Math.min(2.0F,
                        state[d] + (random.nextFloat() - 0.5F) * 0.05F));
            }
            queries.add(Climate.target(state[0], state[1], state[2],
                    state[3], state[4], state[5]));
        }
        return queries;
    }

    private static void verifyDuplicateTieResults() {
        List<Pair<Climate.ParameterPoint, Integer>> values = new ArrayList<>();
        Climate.ParameterPoint repeated = Climate.parameters(
                0.25F, -0.5F, 0.75F, -1.0F, 0.0F, 1.25F, 0.5F);
        for (int i = 0; i < 64; i++) {
            values.add(Pair.of(repeated, i));
        }
        for (int i = 64; i < PARAMETER_POINTS; i++) {
            float value = (i - 160) / 100.0F;
            values.add(Pair.of(Climate.parameters(value, value, value, value, value, value, 0.0F), i));
        }

        Climate.ParameterList<Integer> source = new Climate.ParameterList<>(values);
        int expected = source.rebuildWithChildrenPerNode(19).findValueIndex(
                Climate.target(0.25F, -0.5F, 0.75F, -1.0F, 0.0F, 1.25F));
        for (int fanOut : FAN_OUTS) {
            int actual = source.rebuildWithChildrenPerNode(fanOut).findValueIndex(
                    Climate.target(0.25F, -0.5F, 0.75F, -1.0F, 0.0F, 1.25F));
            if (actual != expected) {
                throw new IllegalStateException("Climate.RTree fan-out changed duplicate-point tie result: "
                        + "fanOut=" + fanOut + " expected=" + expected + " actual=" + actual);
            }
        }
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

    private static void shuffle(int[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            int value = values[i];
            values[i] = values[other];
            values[other] = value;
        }
    }

    private static double percentile(double[] values, double percentile) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double position = percentile * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double fraction = position - lower;
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction;
    }

    private static float coordinate(Random random) {
        return random.nextFloat() * 4.0F - 2.0F;
    }
}
