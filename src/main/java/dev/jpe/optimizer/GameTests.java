package dev.jpe.optimizer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class GameTests {
    private GameTests() {}

    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, JPEOptimizer.MOD_ID);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> FLOAT_MATH_SMOKE =
            TEST_FUNCTIONS.register("float_math_smoke", () -> GameTests::floatMathSmoke);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> TERRAIN_GENERATION_SMOKE =
            TEST_FUNCTIONS.register("terrain_generation_smoke", () -> GameTests::terrainGenerationSmoke);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SERVER_WORLDGEN_BENCHMARK =
            TEST_FUNCTIONS.register("server_worldgen_benchmark", () -> GameTests::serverWorldgenBenchmark);

    public static void registerFunctions(net.neoforged.bus.api.IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
    }

    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.parse("jpe_optimizer:default"),
                new TestEnvironmentDefinition.AllOf()
        );

        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                Identifier.parse("minecraft:empty"),
                1200,
                0,
                true,
                Rotation.NONE
        );

        event.registerTest(
                Identifier.parse("jpe_optimizer:math_smoke"),
                new FunctionGameTestInstance(FLOAT_MATH_SMOKE.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:terrain_generation_smoke"),
                new FunctionGameTestInstance(TERRAIN_GENERATION_SMOKE.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:server_worldgen_benchmark"),
                new FunctionGameTestInstance(SERVER_WORLDGEN_BENCHMARK.getKey(), data)
        );
    }

    private static void floatMathSmoke(GameTestHelper helper) {
        float x = 0.25f;
        float y = -0.5f;
        float z = 0.75f;
        float reference = dev.jpe.optimizer.math.DensityMath.syntheticGraph(x, y, z);
        float candidate = dev.jpe.optimizer.math.DensityMath.syntheticGraphShared(x, y, z);
        if (Float.floatToIntBits(reference) != Float.floatToIntBits(candidate)) {
            throw new AssertionError("Float32 CSE candidate mismatch: " + reference + " vs " + candidate);
        }
        helper.succeed();
    }

    private static void terrainGenerationSmoke(GameTestHelper helper) {
        int chunkX = 4096;
        int chunkZ = -4096;
        var level = helper.getLevel();
        var chunk = level.getChunk(chunkX, chunkZ);

        if (chunk.getPos().x() != chunkX || chunk.getPos().z() != chunkZ) {
            throw new AssertionError("Server returned the wrong chunk: " + chunk.getPos());
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean foundTerrain = false;
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        for (int x = 0; x < 16 && !foundTerrain; ++x) {
            for (int z = 0; z < 16 && !foundTerrain; ++z) {
                for (int y = minY; y < maxY; ++y) {
                    pos.set(chunkX * 16 + x, y, chunkZ * 16 + z);
                    if (!chunk.getBlockState(pos).isAir()) {
                        foundTerrain = true;
                        break;
                    }
                }
            }
        }

        if (!foundTerrain) {
            throw new AssertionError("Fresh overworld chunk contains no generated terrain blocks");
        }
        helper.succeed();
    }

    /**
     * Measures real synchronous FULL-chunk generation on the dedicated
     * GameTestServer. The world seed and server-side resource counters are
     * emitted with timings for scalar/SIMD A/B comparison.
     */
    private static void serverWorldgenBenchmark(GameTestHelper helper) {
        var level = helper.getLevel();
        long seed = level.getSeed();
        long threadId = Thread.currentThread().threadId();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        level.getChunk(8192, -8192); // one fresh warmup chunk

        List<Long> elapsed = new ArrayList<>();
        long cpuBefore = threadBean.isCurrentThreadCpuTimeSupported()
                ? threadBean.getCurrentThreadCpuTime() : -1L;
        long allocatedBefore = allocatedBytes(threadBean, threadId);
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTime();

        for (int i = 0; i < 6; ++i) {
            int chunkX = 12288 + i * 96;
            int chunkZ = -12288 - (i % 2) * 96;
            long start = System.nanoTime();
            var chunk = level.getChunk(chunkX, chunkZ);
            long duration = System.nanoTime() - start;
            if (chunk.getPos().x() != chunkX || chunk.getPos().z() != chunkZ) {
                throw new AssertionError("Server returned wrong benchmark chunk " + chunk.getPos());
            }
            elapsed.add(duration);
        }

        long cpuAfter = threadBean.isCurrentThreadCpuTimeSupported()
                ? threadBean.getCurrentThreadCpuTime() : -1L;
        long allocatedAfter = allocatedBytes(threadBean, threadId);
        long gcCountAfter = gcCount();
        long gcTimeAfter = gcTime();
        long total = elapsed.stream().mapToLong(Long::longValue).sum();
        long[] sorted = elapsed.stream().mapToLong(Long::longValue).sorted().toArray();

        JPEOptimizer.LOGGER.info(
                "JPE_SERVER_WORLDGEN_BENCH seed={} mode={} chunks=6 meanMs={} medianMs={} p95Ms={} " +
                "cpuMs={} allocatedMiB={} gcCollections={} gcMs={} warmupChunks=1",
                seed,
                dev.jpe.optimizer.vector.DensityVectorRuntime.enabled() ? "vector" : "scalar",
                String.format(java.util.Locale.ROOT, "%.3f", total / 6_000_000.0),
                String.format(java.util.Locale.ROOT, "%.3f", (sorted[2] + sorted[3]) / 2_000_000.0),
                String.format(java.util.Locale.ROOT, "%.3f", sorted[5] / 1_000_000.0),
                cpuBefore >= 0 && cpuAfter >= 0
                        ? String.format(java.util.Locale.ROOT, "%.3f", (cpuAfter - cpuBefore) / 1_000_000.0)
                        : "unsupported",
                allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
                        ? String.format(java.util.Locale.ROOT, "%.2f", (allocatedAfter - allocatedBefore) / 1048576.0)
                        : "unsupported",
                gcCountAfter - gcCountBefore,
                gcTimeAfter - gcTimeBefore
        );
        helper.succeed();
    }

    private static long allocatedBytes(ThreadMXBean bean, long threadId) {
        if (bean instanceof com.sun.management.ThreadMXBean sunBean
                && sunBean.isThreadAllocatedMemorySupported()) {
            if (!sunBean.isThreadAllocatedMemoryEnabled()) {
                try {
                    sunBean.setThreadAllocatedMemoryEnabled(true);
                } catch (UnsupportedOperationException | SecurityException ignored) {
                    return -1L;
                }
            }
            return sunBean.getThreadAllocatedBytes(threadId);
        }
        return -1L;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(value -> value >= 0)
                .sum();
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(value -> value >= 0)
                .sum();
    }
}
