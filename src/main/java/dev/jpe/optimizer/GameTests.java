package dev.jpe.optimizer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftStructure;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import dev.jpe.optimizer.mixin.BlendingDataAccessor;
import dev.jpe.optimizer.mixin.GradientNoiseAccessor;
import dev.jpe.optimizer.mixin.XoroshiroPositionalRandomFactoryAccessor;
import dev.jpe.optimizer.mixin.LegacyPositionalRandomFactoryAccessor;
import dev.jpe.optimizer.runtime.NativeRuntime;
import dev.jpe.optimizer.runtime.DensityGraphIr;
import dev.jpe.optimizer.runtime.NativePerlinNoiseBackend;
import dev.jpe.optimizer.runtime.NativeBlenderDensityBackend;
import dev.jpe.optimizer.vector.DensityVectorRuntime;
import dev.jpe.optimizer.vector.PerlinNoiseVectorizer;
import dev.jpe.optimizer.world.MinecraftChunkReader;
import dev.jpe.optimizer.world.RustWorldBridge;
import dev.jpe.world.JpeWorldNative;

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

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> STRUCTURE_INDEX_BENCHMARK =
            TEST_FUNCTIONS.register("structure_index_benchmark", () -> GameTests::structureIndexBenchmark);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PERLIN_VECTOR_PARITY =
            TEST_FUNCTIONS.register("perlin_vector_parity", () -> GameTests::perlinVectorParity);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PERLIN_NATIVE_PARITY =
            TEST_FUNCTIONS.register("perlin_native_parity", () -> GameTests::perlinNativeParity);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DENSITY_GRAPH_NATIVE_PARITY =
            TEST_FUNCTIONS.register("density_graph_native_parity", () -> GameTests::densityGraphNativeParity);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BLENDER_NATIVE_PARITY =
            TEST_FUNCTIONS.register("blender_native_parity", () -> GameTests::blenderNativeParity);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> AQUIFER_RANDOM_NATIVE_PARITY =
            TEST_FUNCTIONS.register("aquifer_xoroshiro_native_parity", () -> GameTests::aquiferXoroshiroNativeParity);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PALETTE_HASH_CONFIGURATION =
            TEST_FUNCTIONS.register("palette_hash_configuration", () -> GameTests::paletteHashConfiguration);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> RUST_WORLD_PIPELINE =
            TEST_FUNCTIONS.register("rust_world_pipeline", () -> GameTests::rustWorldPipeline);

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
                Identifier.parse("jpe_optimizer:structure_index_benchmark"),
                new FunctionGameTestInstance(STRUCTURE_INDEX_BENCHMARK.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:perlin_vector_parity"),
                new FunctionGameTestInstance(PERLIN_VECTOR_PARITY.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:perlin_native_parity"),
                new FunctionGameTestInstance(PERLIN_NATIVE_PARITY.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:density_graph_native_parity"),
                new FunctionGameTestInstance(DENSITY_GRAPH_NATIVE_PARITY.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:blender_native_parity"),
                new FunctionGameTestInstance(BLENDER_NATIVE_PARITY.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:aquifer_random_native_parity"),
                new FunctionGameTestInstance(AQUIFER_RANDOM_NATIVE_PARITY.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:palette_hash_configuration"),
                new FunctionGameTestInstance(PALETTE_HASH_CONFIGURATION.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:rust_world_pipeline"),
                new FunctionGameTestInstance(RUST_WORLD_PIPELINE.getKey(), data)
        );
    }

    private static void paletteHashConfiguration(GameTestHelper helper) {
        boolean requested = Boolean.getBoolean("jpe.paletteHashEnabled");
        boolean applied = dev.jpe.optimizer.worldgen.PaletteHashExperiment.applied();
        if (requested != applied) {
            throw new AssertionError("Palette hash experiment mismatch: requested=" + requested + ", applied=" + applied);
        }
        JPEOptimizer.LOGGER.info("JPE_PALETTE_HASH_CONFIGURATION requested={} applied={}", requested, applied);
        helper.succeed();
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

        // Give the server thread and density kernels enough fresh chunks to warm up JIT compilation.
        for (int i = 0; i < 8; ++i) {
            level.getChunk(8192 + i * 96, -8192 - (i % 2) * 96);
        }

        // Phase counters begin after warmup and describe the same measured batch.
        var worldgenBefore = dev.jpe.optimizer.worldgen.WorldgenMetrics.snapshot();
        // All resource counters begin after warmup and cover exactly the same 24 measured chunks.
        List<Long> elapsed = new ArrayList<>();
        long processCpuBefore = processCpuTime();
        long cpuBefore = threadBean.isCurrentThreadCpuTimeSupported()
                ? threadBean.getCurrentThreadCpuTime() : -1L;
        long allocatedBefore = allocatedBytes(threadBean, threadId);
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTime();

        for (int i = 0; i < 24; ++i) {
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
        long processCpuAfter = processCpuTime();
        long allocatedAfter = allocatedBytes(threadBean, threadId);
        long gcCountAfter = gcCount();
        long gcTimeAfter = gcTime();
        long total = elapsed.stream().mapToLong(Long::longValue).sum();
        long[] sorted = elapsed.stream().mapToLong(Long::longValue).sorted().toArray();

        var structureMetrics = dev.jpe.optimizer.worldgen.StructureSpatialIndex.metricsSnapshot();
        var worldgenAfter = dev.jpe.optimizer.worldgen.WorldgenMetrics.snapshot();
        long doFillCalls = worldgenAfter.calls() - worldgenBefore.calls();
        long densitySampleNanos = worldgenAfter.densitySampleNanos() - worldgenBefore.densitySampleNanos();
        long blockFillNanos = worldgenAfter.blockFillNanos() - worldgenBefore.blockFillNanos();
        long doFillSamples = worldgenAfter.samples() - worldgenBefore.samples();
        JPEOptimizer.LOGGER.info(
                "JPE_SERVER_WORLDGEN_BENCH seed={} generator={} mode={} chunks=24 meanMs={} medianMs={} p95Ms={} " +
                "serverThreadCpuMs={} processCpuMs={} serverThreadAllocatedMiB={} gcCollections={} gcMs={} warmupChunks=8 " +
                "doFillCalls={} doFillDensityMs={} doFillBlockMs={} doFillSamples={} " +
                "structureQueries={} structureIndexBuilds={} structureIndexedPieces={} structureCandidateChecks={} " +
                "structureLinearFallbacks={} structureVanillaLinearChecks={}",
                seed,
                level.getChunkSource().getGenerator().getClass().getName(),
                dev.jpe.optimizer.vector.DensityVectorRuntime.enabled() ? "vector" : "scalar",
                String.format(java.util.Locale.ROOT, "%.3f", total / 24_000_000.0),
                String.format(java.util.Locale.ROOT, "%.3f", (sorted[11] + sorted[12]) / 2_000_000.0),
                String.format(java.util.Locale.ROOT, "%.3f", sorted[22] / 1_000_000.0),
                cpuBefore >= 0 && cpuAfter >= 0
                        ? String.format(java.util.Locale.ROOT, "%.3f", (cpuAfter - cpuBefore) / 1_000_000.0)
                        : "unsupported",
                processCpuBefore >= 0 && processCpuAfter >= processCpuBefore
                        ? String.format(java.util.Locale.ROOT, "%.3f", (processCpuAfter - processCpuBefore) / 1_000_000.0)
                        : "unsupported",
                allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
                        ? String.format(java.util.Locale.ROOT, "%.2f", (allocatedAfter - allocatedBefore) / 1048576.0)
                        : "unsupported",
                gcCountAfter - gcCountBefore,
                gcTimeAfter - gcTimeBefore,
                doFillCalls,
                String.format(java.util.Locale.ROOT, "%.3f", densitySampleNanos / 1_000_000.0),
                String.format(java.util.Locale.ROOT, "%.3f", blockFillNanos / 1_000_000.0),
                doFillSamples,
                structureMetrics.queries(),
                structureMetrics.builds(),
                structureMetrics.indexedPieces(),
                structureMetrics.candidateChecks(),
                structureMetrics.linearFallbacks(),
                structureMetrics.vanillaLinearChecks()
        );
        helper.succeed();
    }

    /**
     * Verifies the opt-in Perlin volume path against vanilla bit-for-bit.
     * The 1/16 Y scale creates runs long enough to exercise the preferred 16-lane path.
     */
    private static void perlinVectorParity(GameTestHelper helper) {
        if (!DensityVectorRuntime.enabled()) {
            throw new AssertionError("Perlin vector parity test requires an active Vector API kernel: " + DensityVectorRuntime.diagnostic());
        }
        if (!PerlinNoiseVectorizer.enabled()) {
            throw new AssertionError("Perlin vector parity test requires -Djpe.perlinVectorEnabled=true");
        }

        PerlinNoise noise = new PerlinNoise(RandomSource.create(0x4A50454C263L));
        DensityVolume volume = new DensityVolume(2, 64, 2, -16, -64, 32);
        DensityBuffer vanilla = DensityBuffer.createUnpooled(volume.size());
        DensityBuffer optimized = DensityBuffer.createUnpooled(volume.size());

        PerlinNoiseVectorizer.setVanillaBypass(true);
        try {
            noise.addToVolume(vanilla, volume, 0.25, 0.0625, 0.75F);
        } finally {
            PerlinNoiseVectorizer.setVanillaBypass(false);
        }

        NativePerlinNoiseBackend.setBypass(true);
        try {
            noise.addToVolume(optimized, volume, 0.25, 0.0625, 0.75F);
        } finally {
            NativePerlinNoiseBackend.setBypass(false);
        }
        if (!PerlinNoiseVectorizer.wasLastCallVectorized()) {
            throw new AssertionError("Perlin vector path did not process a 16-lane Y run");
        }

        float[] expected = ((DensityBufferAccessor) vanilla).jpe$values();
        float[] actual = ((DensityBufferAccessor) optimized).jpe$values();
        if (expected.length != actual.length) {
            throw new AssertionError("Perlin vector buffer length mismatch");
        }
        for (int i = 0; i < expected.length; ++i) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                throw new AssertionError("Perlin vector mismatch at density index " + i
                        + ": vanilla=" + expected[i] + ", optimized=" + actual[i]);
            }
        }
        JPEOptimizer.LOGGER.info("JPE_PERLIN_VECTOR_PARITY samples={} bitExact=true",
                expected.length);
        helper.succeed();
    }


    /** Compares Native Aquifer cell-center offsets with Minecraft's actual XOROSHIRO factory. */
    private static void aquiferXoroshiroNativeParity(GameTestHelper helper) {
        String library = System.getProperty("jpe.nativeLibraryPath");
        if (library == null || library.isBlank()) {
            throw new AssertionError("Set -Djpe.nativeLibraryPath to the freshly built native runtime library");
        }

        long[][] sourceSeeds = {
                {0x0123456789abcdefL, 0xfedcba9876543210L},
                {0L, 0L},
                {0x9e3779b97f4a7c15L, 0x6a09e667f3bcc909L},
                {123456789L, 987654321L}
        };
        int[][] cells = {
                {1, 2, 3},
                {0, 0, 0},
                {-17, -64, 33},
                {-2, -1, -3},
                {31, 7, -49},
                {-128, -25, 96},
                {Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE},
                {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE},
                {0x40000001, -0x40000001, 0x7ffffffe}
        };

        try (NativeRuntime runtime = NativeRuntime.load(java.nio.file.Path.of(library))) {
            if (!runtime.supportsAquiferXoroshiroOffsets() || !runtime.supportsAquiferLegacyOffsets()) {
                throw new AssertionError("Native library does not export both Aquifer RNG algorithms");
            }
            int comparisons = 0;
            for (long[] sourceSeed : sourceSeeds) {
                var factory = new XoroshiroRandomSource(sourceSeed[0], sourceSeed[1]).forkPositional();
                var accessor = (XoroshiroPositionalRandomFactoryAccessor) (Object) factory;
                long factorySeedLo = accessor.jpe$seedLo();
                long factorySeedHi = accessor.jpe$seedHi();

                for (int[] cell : cells) {
                    RandomSource vanilla = factory.at(cell[0], cell[1], cell[2]);
                    int[] expected = {
                            vanilla.nextInt(10),
                            vanilla.nextInt(9),
                            vanilla.nextInt(10)
                    };
                    int[] actual = runtime.aquiferXoroshiroOffsets(
                            factorySeedLo, factorySeedHi, cell[0], cell[1], cell[2]);
                    if (!java.util.Arrays.equals(expected, actual)) {
                        throw new AssertionError("Aquifer RNG mismatch at cell=("
                                + cell[0] + "," + cell[1] + "," + cell[2] + ")"
                                + " expected=" + java.util.Arrays.toString(expected)
                                + " actual=" + java.util.Arrays.toString(actual));
                    }
                    comparisons++;
                }
            }
            JPEOptimizer.LOGGER.info("JPE_AQUIFER_XOROSHIRO_PARITY comparisons={} bitExact=true", comparisons);

            long[] legacySourceSeeds = {0x0123456789abcdefL, 0L, 987654321L, 0x9e3779b97f4a7c15L};
            int legacyComparisons = 0;
            for (long sourceSeed : legacySourceSeeds) {
                var factory = new LegacyRandomSource(sourceSeed).forkPositional();
                var accessor = (LegacyPositionalRandomFactoryAccessor) (Object) factory;
                long factorySeed = accessor.jpe$seed();

                for (int[] cell : cells) {
                    RandomSource vanilla = factory.at(cell[0], cell[1], cell[2]);
                    int[] expected = {
                            vanilla.nextInt(10),
                            vanilla.nextInt(9),
                            vanilla.nextInt(10)
                    };
                    int[] actual = runtime.aquiferLegacyOffsets(
                            factorySeed, cell[0], cell[1], cell[2]);
                    if (!java.util.Arrays.equals(expected, actual)) {
                        throw new AssertionError("Legacy Aquifer RNG mismatch at cell=("
                                + cell[0] + "," + cell[1] + "," + cell[2] + ")"
                                + " expected=" + java.util.Arrays.toString(expected)
                                + " actual=" + java.util.Arrays.toString(actual));
                    }
                    legacyComparisons++;
                }
            }
            JPEOptimizer.LOGGER.info("JPE_AQUIFER_LEGACY_PARITY comparisons={} bitExact=true", legacyComparisons);

            if (!runtime.supportsAquiferNearestFour()) {
                throw new AssertionError("Native library does not export Aquifer nearest-four batch support");
            }
            var xorFactory = new XoroshiroRandomSource(sourceSeeds[0][0], sourceSeeds[0][1]).forkPositional();
            var xorAccessor = (XoroshiroPositionalRandomFactoryAccessor) (Object) xorFactory;
            assertAquiferNearestFourBatch(runtime, xorFactory, 0,
                    xorAccessor.jpe$seedLo(), xorAccessor.jpe$seedHi());
            var legacyFactory = new LegacyRandomSource(legacySourceSeeds[0]).forkPositional();
            var legacyAccessor = (LegacyPositionalRandomFactoryAccessor) (Object) legacyFactory;
            assertAquiferNearestFourBatch(runtime, legacyFactory, 1,
                    legacyAccessor.jpe$seed(), 0L);
            JPEOptimizer.LOGGER.info("JPE_AQUIFER_NEAREST_FOUR_BATCH modes=2 samples=9 neighbors=36 exact=true");
            if (!runtime.supportsAquiferGridCenters()) {
                throw new AssertionError("Native library does not export Aquifer grid-center batch support");
            }
            assertAquiferGridCenterBatch(runtime, xorFactory, 0,
                    xorAccessor.jpe$seedLo(), xorAccessor.jpe$seedHi());
            assertAquiferGridCenterBatch(runtime, legacyFactory, 1,
                    legacyAccessor.jpe$seed(), 0L);
            JPEOptimizer.LOGGER.info("JPE_AQUIFER_GRID_CENTER_BATCH modes=2 cells=825 packedBlockPos=true exact=true");
        } catch (Exception failure) {
            throw new AssertionError("Native Aquifer XOROSHIRO parity failed", failure);
        }
        helper.succeed();
    }


    /** Checks complete flat Aquifer location-cache generation against vanilla positional RNG. */
    private static void assertAquiferGridCenterBatch(
            NativeRuntime runtime, PositionalRandomFactory factory,
            int randomMode, long seedLo, long seedHi) {
        int minX = -7, minY = -11, minZ = 13;
        int sizeX = 5, sizeZ = 5, sizeY = 33;
        long[] actual = new long[sizeX * sizeY * sizeZ];
        runtime.aquiferGridCenters(randomMode, seedLo, seedHi,
                minX, minY, minZ, sizeX, sizeZ, actual);
        int index = 0;
        for (int gyIndex = 0; gyIndex < sizeY; ++gyIndex) {
            int gy = minY + gyIndex;
            for (int gzIndex = 0; gzIndex < sizeZ; ++gzIndex) {
                int gz = minZ + gzIndex;
                for (int gxIndex = 0; gxIndex < sizeX; ++gxIndex) {
                    int gx = minX + gxIndex;
                    RandomSource random = factory.at(gx, gy, gz);
                    int x = gx * 16 + random.nextInt(10);
                    int y = gy * 12 + random.nextInt(9);
                    int z = gz * 16 + random.nextInt(10);
                    long expected = BlockPos.asLong(x, y, z);
                    if (actual[index] != expected) {
                        throw new AssertionError("Aquifer packed center mismatch at flat index " + index
                                + " grid=(" + gx + "," + gy + "," + gz + ")"
                                + " expected=" + expected + " actual=" + actual[index]);
                    }
                    ++index;
                }
            }
        }
        // Full 5x33x5 cache-shaped comparison. Include all FFM copying and
        // native execution; do not time an isolated C++ loop and call it a win.
        long[] nativeNanos = new long[101];
        long[] vanillaNanos = new long[101];
        long[] vanillaOutput = new long[actual.length];
        for (int round = 0; round < 111; ++round) {
            long started = System.nanoTime();
            runtime.aquiferGridCenters(randomMode, seedLo, seedHi,
                    minX, minY, minZ, sizeX, sizeZ, actual);
            long nativeElapsed = System.nanoTime() - started;
            started = System.nanoTime();
            int out = 0;
            for (int gyIndex = 0; gyIndex < sizeY; ++gyIndex) {
                int gy = minY + gyIndex;
                for (int gzIndex = 0; gzIndex < sizeZ; ++gzIndex) {
                    int gz = minZ + gzIndex;
                    for (int gxIndex = 0; gxIndex < sizeX; ++gxIndex) {
                        int gx = minX + gxIndex;
                        RandomSource random = factory.at(gx, gy, gz);
                        vanillaOutput[out++] = BlockPos.asLong(
                                gx * 16 + random.nextInt(10),
                                gy * 12 + random.nextInt(9),
                                gz * 16 + random.nextInt(10));
                    }
                }
            }
            long vanillaElapsed = System.nanoTime() - started;
            if (round >= 10) {
                nativeNanos[round - 10] = nativeElapsed;
                vanillaNanos[round - 10] = vanillaElapsed;
            }
            if (!java.util.Arrays.equals(actual, vanillaOutput)) {
                throw new AssertionError("Aquifer grid-center parity changed during timed round " + round);
            }
        }
        java.util.Arrays.sort(nativeNanos);
        java.util.Arrays.sort(vanillaNanos);
        JPEOptimizer.LOGGER.info(
                "JPE_AQUIFER_GRID_CENTER_BENCH mode={} cells={} rounds=101 nativeMedianUs={} vanillaMedianUs={} " +
                        "nativeIncludesFfmCopy=true exact=true",
                randomMode, actual.length,
                nativeNanos[50] / 1_000.0, vanillaNanos[50] / 1_000.0);
    }

    /** Independently checks the Java FFM batch ABI against Minecraft's positional factories. */
    private static void assertAquiferNearestFourBatch(
            NativeRuntime runtime, PositionalRandomFactory factory,
            int randomMode, long seedLo, long seedHi) {
        int[] blockX = {0, -1, -17, 31, -128, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x40000001, 245};
        int[] blockY = {0, -1, -64, 7, -25, Integer.MAX_VALUE, Integer.MIN_VALUE, -0x40000001, 319};
        int[] blockZ = {0, -3, 33, -49, 96, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x7ffffffe, -511};
        NativeRuntime.AquiferNearestFour actual = runtime.aquiferNearestFour(
                randomMode, seedLo, seedHi, blockX, blockY, blockZ);
        int[] actualX = actual.centerX();
        int[] actualY = actual.centerY();
        int[] actualZ = actual.centerZ();
        int[] actualDistances = actual.squaredDistances();

        for (int sample = 0; sample < blockX.length; ++sample) {
            int anchorX = Math.floorDiv(blockX[sample] - 5, 16);
            int anchorY = Math.floorDiv(blockY[sample] + 1, 12);
            int anchorZ = Math.floorDiv(blockZ[sample] - 5, 16);
            int[] candidateX = new int[12];
            int[] candidateY = new int[12];
            int[] candidateZ = new int[12];
            int[] candidateDistances = new int[12];
            int candidate = 0;
            for (int dx = 0; dx <= 1; ++dx) {
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dz = 0; dz <= 1; ++dz) {
                        int gridX = anchorX + dx;
                        int gridY = anchorY + dy;
                        int gridZ = anchorZ + dz;
                        RandomSource random = factory.at(gridX, gridY, gridZ);
                        int centerX = gridX * 16 + random.nextInt(10);
                        int centerY = gridY * 12 + random.nextInt(9);
                        int centerZ = gridZ * 16 + random.nextInt(10);
                        int diffX = blockX[sample] - centerX;
                        int diffY = blockY[sample] - centerY;
                        int diffZ = blockZ[sample] - centerZ;
                        candidateX[candidate] = centerX;
                        candidateY[candidate] = centerY;
                        candidateZ[candidate] = centerZ;
                        candidateDistances[candidate] = diffX * diffX + diffY * diffY + diffZ * diffZ;
                        candidate++;
                    }
                }
            }
            Integer[] order = new Integer[12];
            for (int i = 0; i < order.length; ++i) order[i] = i;
            java.util.Arrays.sort(order, (left, right) -> {
                int byDistance = Integer.compare(candidateDistances[left], candidateDistances[right]);
                return byDistance != 0 ? byDistance : Integer.compare(right, left);
            });
            for (int neighbor = 0; neighbor < 4; ++neighbor) {
                int expectedIndex = order[neighbor];
                int actualIndex = sample * 4 + neighbor;
                if (actualX[actualIndex] != candidateX[expectedIndex]
                        || actualY[actualIndex] != candidateY[expectedIndex]
                        || actualZ[actualIndex] != candidateZ[expectedIndex]
                        || actualDistances[actualIndex] != candidateDistances[expectedIndex]) {
                    throw new AssertionError("Aquifer nearest-four batch mismatch mode=" + randomMode
                            + " sample=" + sample + " neighbor=" + neighbor
                            + " expectedCenter=(" + candidateX[expectedIndex] + ","
                            + candidateY[expectedIndex] + "," + candidateZ[expectedIndex] + ")"
                            + " expectedDistance=" + candidateDistances[expectedIndex]
                            + " actualCenter=(" + actualX[actualIndex] + ","
                            + actualY[actualIndex] + "," + actualZ[actualIndex] + ")"
                            + " actualDistance=" + actualDistances[actualIndex]);
                }
            }
        }
    }

    /** Verifies the versioned Java FFM graph API against a known full-volume result. */
    private static void densityGraphNativeParity(GameTestHelper helper) {
        String library = System.getProperty("jpe.nativeLibraryPath");
        if (library == null || library.isBlank()) {
            throw new AssertionError("Set -Djpe.nativeLibraryPath to the freshly built native runtime library");
        }

        DensityGraphIr graph = new DensityGraphIr(List.of(
                DensityGraphIr.Node.x(),
                DensityGraphIr.Node.y(),
                DensityGraphIr.Node.constant(2.0),
                DensityGraphIr.Node.add(0, 2),
                DensityGraphIr.Node.multiply(3, 1)
        ), 4, 0x4A50454752415048L);
        float[] output = new float[4];
        try (NativeRuntime runtime = NativeRuntime.load(java.nio.file.Path.of(library));
             NativeRuntime.NativeDensityGraph compiled = runtime.compileDensityGraph(graph)) {
            compiled.evaluate(2, 2, 1, 10, 3, 0, 1, 1, 1, output);
        } catch (Exception failure) {
            throw new AssertionError("Native density graph FFM parity test failed", failure);
        }

        float[] expected = {36.0F, 48.0F, 39.0F, 52.0F};
        for (int i = 0; i < expected.length; ++i) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(output[i])) {
                throw new AssertionError("Density graph output mismatch at index " + i
                        + ": expected=" + expected[i] + ", actual=" + output[i]);
            }
        }
        JPEOptimizer.LOGGER.info("JPE_DENSITY_GRAPH_NATIVE_PARITY nodes={} samples={} bitExact=true",
                graph.nodes().size(), output.length);
        helper.succeed();
    }

    /** Compares the Rust/C++ Perlin volume backend against vanilla output bit-for-bit. */
    private static void perlinNativeParity(GameTestHelper helper) {
        String library = System.getProperty("jpe.nativeLibraryPath");
        if (library == null || library.isBlank()) {
            throw new AssertionError("Set -Djpe.nativeLibraryPath to the freshly built native runtime library");
        }

        PerlinNoise noise = new PerlinNoise(RandomSource.create(0x4A50454C263L));
        DensityVolume volume = new DensityVolume(3, 64, 2, -16, -64, 32, 4, 2, 8);
        DensityBuffer vanilla = DensityBuffer.createUnpooled(volume.size());

        PerlinNoiseVectorizer.setVanillaBypass(true);
        try {
            noise.addToVolume(vanilla, volume, 0.25, 0.0625, 0.75F);
        } finally {
            PerlinNoiseVectorizer.setVanillaBypass(false);
        }

        float[] expected = ((DensityBufferAccessor) vanilla).jpe$values();
        float[] synchronous = new float[volume.size()];
        GradientNoiseAccessor access = (GradientNoiseAccessor) (Object) noise;
        try (NativeRuntime runtime = NativeRuntime.load(java.nio.file.Path.of(library))) {
            runtime.perlinAddVolume(
                    access.jpe$perms(), volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                    access.jpe$offsetX(), access.jpe$offsetY(), access.jpe$offsetZ(),
                    0.25, 0.0625, 0.75F, synchronous,
                    net.minecraft.SharedConstants.DEBUG_ENABLE_FARLANDS
            );
            assertNativePerlinParity(expected, synchronous, "synchronous");

            NativeRuntime.NativeJob job = runtime.submitPerlinVolume(
                    access.jpe$perms(), volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                    access.jpe$offsetX(), access.jpe$offsetY(), access.jpe$offsetZ(),
                    0.25, 0.0625, 0.75F, new float[volume.size()],
                    net.minecraft.SharedConstants.DEBUG_ENABLE_FARLANDS
            );
            if (job == null) throw new AssertionError("Native async queue unexpectedly rejected the parity job");
            try (job) {
                long deadline = System.nanoTime() + 10_000_000_000L;
                int status;
                boolean timedOut = false;
                boolean interrupted = false;
                do {
                    status = job.waitForStatus(10_000_000L);
                    if (status == NativeRuntime.JOB_COMPLETE
                            || status == NativeRuntime.JOB_FAILED
                            || status == NativeRuntime.JOB_CANCELLED) break;
                    if (!timedOut && System.nanoTime() >= deadline) {
                        timedOut = true;
                        job.cancel();
                    }
                    try {
                        Thread.sleep(1L);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                        job.cancel();
                    }
                } while (true);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for native async parity job");
                }
                if (timedOut) throw new AssertionError("Native async Perlin job timed out");
                if (status != NativeRuntime.JOB_COMPLETE) {
                    throw new AssertionError("Native async Perlin job ended with status " + status);
                }
                assertNativePerlinParity(expected, job.copyOutput(), "asynchronous");
            }
        } catch (Exception failure) {
            throw new AssertionError("Unable to execute native Perlin parity test", failure);
        }

        if (!NativePerlinNoiseBackend.enabled()) {
            throw new AssertionError("Live native Perlin GameTest requires -Djpe.nativePerlinEnabled=true");
        }
        DensityBuffer liveNative = DensityBuffer.createUnpooled(volume.size());
        noise.addToVolume(liveNative, volume, 0.25, 0.0625, 0.75F);
        if (!NativePerlinNoiseBackend.wasLastCallNative()) {
            throw new AssertionError("PerlinNoise.addToVolume did not dispatch through the live native backend");
        }
        assertNativePerlinParity(expected, ((DensityBufferAccessor) liveNative).jpe$values(), "live-native-async");

        String previousMode = System.getProperty("jpe.nativePerlinSynchronous");
        try {
            System.setProperty("jpe.nativePerlinSynchronous", "true");
            DensityBuffer liveSynchronous = DensityBuffer.createUnpooled(volume.size());
            noise.addToVolume(liveSynchronous, volume, 0.25, 0.0625, 0.75F);
            if (!NativePerlinNoiseBackend.wasLastCallNative()) {
                throw new AssertionError("PerlinNoise.addToVolume did not dispatch through synchronous native mode");
            }
            assertNativePerlinParity(expected, ((DensityBufferAccessor) liveSynchronous).jpe$values(), "live-native-sync");
        } finally {
            if (previousMode == null) System.clearProperty("jpe.nativePerlinSynchronous");
            else System.setProperty("jpe.nativePerlinSynchronous", previousMode);
        }
        JPEOptimizer.LOGGER.info("JPE_NATIVE_PERLIN_PARITY samples={} synchronous=true asynchronous=true liveWorldgen=true bitExact=true",
                expected.length);
        helper.succeed();
    }


    /** Compares the live Java Blender implementation against one full-volume C++ call. */
    private static void blenderNativeParity(GameTestHelper helper) {
        String library = System.getProperty("jpe.nativeLibraryPath");
        if (library == null || library.isBlank()) {
            throw new AssertionError("Set -Djpe.nativeLibraryPath to the freshly built native runtime library");
        }
        String[] propertyNames = {"jpe.nativeBlenderEnabled", "jpe.verifyNativeBlenderParity", "jpe.nativeBlenderTiming"};
        String[] previousProperties = new String[propertyNames.length];
        for (int i = 0; i < propertyNames.length; ++i) {
            previousProperties[i] = System.getProperty(propertyNames[i]);
        }
        System.setProperty("jpe.nativeBlenderEnabled", "true");
        System.setProperty("jpe.verifyNativeBlenderParity", "true");
        System.setProperty("jpe.nativeBlenderTiming", "true");
        try (NativeRuntime runtime = NativeRuntime.load(java.nio.file.Path.of(library))) {
            if (!runtime.supportsBlenderDensityVolume()) {
                throw new AssertionError("Native library does not export Blender density volume support");
            }

            BlendingData fixedData = createSyntheticBlendingData(3, 5.0F);
            Long2ObjectOpenHashMap<BlendingData> fixedMap = new Long2ObjectOpenHashMap<>();
            fixedMap.put(ChunkPos.pack(0, 0), fixedData);
            Blender fixedBlender = createSyntheticBlender(fixedMap, new Long2ObjectOpenHashMap<>());
            verifyBlenderCase(runtime, fixedBlender,
                    new DensityVolume(1, 1, 1, 0, -32, 0, 1, 1, 1),
                    new float[]{7.25F}, "fixed-density");

            BlendingData neighborData = createSyntheticBlendingData(3, 10.0F);
            Long2ObjectOpenHashMap<BlendingData> densityMap = new Long2ObjectOpenHashMap<>();
            densityMap.put(ChunkPos.pack(0, 0), neighborData);
            Blender neighborBlender = createSyntheticBlender(new Long2ObjectOpenHashMap<>(), densityMap);
            verifyBlenderCase(runtime, neighborBlender,
                    new DensityVolume(1, 1, 1, 4, -32, 0, 1, 1, 1),
                    new float[]{4.0F}, "weighted-neighborhood");

            BlendingData patternedData = createPatternedBlendingData();
            Long2ObjectOpenHashMap<BlendingData> patternedMap = new Long2ObjectOpenHashMap<>();
            patternedMap.put(ChunkPos.pack(0, 0), patternedData);
            Blender patternedBlender = createSyntheticBlender(
                    new Long2ObjectOpenHashMap<>(), patternedMap);
            // Match the complete Minecraft density lattice, not a shortened Y slice.
            DensityVolume volume = new DensityVolume(5, 49, 5, 0, -64, 0, 4, 8, 4);
            float[] noise = new float[volume.size()];
            for (int i = 0; i < noise.length; ++i) {
                noise[i] = (i % 31 - 15) * 0.0375F;
            }
            verifyBlenderCase(runtime, patternedBlender, volume, noise,
                    "full-chunk-patterned-volume-1225");

            BlendingData boundaryData = createSyntheticBlendingData(11, 12.5F);
            Long2ObjectOpenHashMap<BlendingData> boundaryMap = new Long2ObjectOpenHashMap<>();
            boundaryMap.put(ChunkPos.pack(-1, -1), boundaryData);
            Blender boundaryBlender = createSyntheticBlender(boundaryMap, new Long2ObjectOpenHashMap<>());
            verifyBlenderCase(runtime, boundaryBlender,
                    new DensityVolume(1, 1, 1, 0, -32, 0, 1, 1, 1),
                    new float[]{9.0F}, "negative-section-boundary");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to create exact Minecraft Blender parity fixtures", failure);
        } catch (Exception failure) {
            throw new AssertionError("Native Blender volume parity failed", failure);
        } finally {
            for (int i = 0; i < propertyNames.length; ++i) {
                if (previousProperties[i] == null) System.clearProperty(propertyNames[i]);
                else System.setProperty(propertyNames[i], previousProperties[i]);
            }
        }
        String backendMetrics = NativeBlenderDensityBackend.metrics();
        JPEOptimizer.LOGGER.info("JPE_NATIVE_BLENDER_BACKEND_SUMMARY {}", backendMetrics);
        if (!backendMetrics.contains("attempts=8 completed=8 fallbacks=0")
                || !backendMetrics.contains("parityMismatches=0")
                || !backendMetrics.matches("(?s).*snapshotCacheHits=(?:[4-9]|[1-9][0-9]+).*")) {
            throw new AssertionError("Production Native Blender backend did not complete all synthetic legacy cases: "
                    + backendMetrics);
        }
        helper.succeed();
    }

    private static BlendingData createSyntheticBlendingData(int columnIndex, float rawValue)
            throws ReflectiveOperationException {
        Constructor<BlendingData> constructor = BlendingData.class.getDeclaredConstructor(
                int.class, int.class, Optional.class);
        if (!constructor.trySetAccessible()) {
            throw new IllegalStateException("BlendingData constructor is not reflectively accessible");
        }
        BlendingData data = constructor.newInstance(-4, 4, Optional.empty());
        float[][] columns = ((BlendingDataAccessor) data).jpe$densities();
        float[] values = new float[16];
        values[3] = rawValue;
        columns[columnIndex] = values;
        ((BlendingDataAccessor) data).jpe$setHasCalculatedData(true);
        return data;
    }

    private static BlendingData createPatternedBlendingData()
            throws ReflectiveOperationException {
        Constructor<BlendingData> constructor = BlendingData.class.getDeclaredConstructor(
                int.class, int.class, Optional.class);
        if (!constructor.trySetAccessible()) {
            throw new IllegalStateException("BlendingData constructor is not reflectively accessible");
        }
        BlendingData data = constructor.newInstance(-4, 20, Optional.empty());
        float[][] columns = ((BlendingDataAccessor) data).jpe$densities();
        for (int column = 0; column < columns.length; ++column) {
            float[] values = new float[48];
            for (int y = 0; y < values.length; ++y) {
                values[y] = 10.0F + column * 0.375F + y * 0.125F;
            }
            columns[column] = values;
        }
        ((BlendingDataAccessor) data).jpe$setHasCalculatedData(true);
        return data;
    }

    private static Blender createSyntheticBlender(
            Long2ObjectOpenHashMap<BlendingData> heightData,
            Long2ObjectOpenHashMap<BlendingData> densityData) throws ReflectiveOperationException {
        for (Constructor<?> constructor : Blender.class.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 3
                    && parameters[0] == Long2ObjectOpenHashMap.class
                    && parameters[1] == Long2ObjectOpenHashMap.class) {
                if (!constructor.trySetAccessible()) {
                    throw new IllegalStateException("Blender constructor is not reflectively accessible");
                }
                return (Blender) constructor.newInstance(heightData, densityData, null);
            }
        }
        throw new NoSuchMethodException("Minecraft 26.3 Blender map constructor was not found");
    }

    private static void verifyBlenderCase(NativeRuntime runtime, Blender blender,
                                          DensityVolume volume, float[] input, String label) {
        float[] expected = new float[volume.size()];
        float[] actual = new float[volume.size()];
        int index = 0;
        for (int z = 0; z < volume.sizeZ(); ++z) {
            int blockZ = volume.blockZ(z);
            for (int x = 0; x < volume.sizeX(); ++x) {
                int blockX = volume.blockX(x);
                for (int y = 0; y < volume.sizeY(); ++y) {
                    expected[index] = blender.blendDensity(
                            blockX, volume.blockY(y), blockZ, input[index]);
                    ++index;
                }
            }
        }
        runtime.blendDensityVolume(blender, volume, input, actual);
        float[] backendInput = input.clone();
        if (!NativeBlenderDensityBackend.tryBlendForDiagnostics(blender, volume, backendInput)) {
            throw new AssertionError("Production Native Blender backend declined synthetic case " + label);
        }
        float[] repeatedBackendInput = input.clone();
        if (!NativeBlenderDensityBackend.tryBlendForDiagnostics(blender, volume, repeatedBackendInput)) {
            throw new AssertionError("Production Native Blender backend declined repeated cached case " + label);
        }
        for (int i = 0; i < expected.length; ++i) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                throw new AssertionError("Native Blender " + label + " direct runtime mismatch at " + i
                        + ": java=" + expected[i] + ", native=" + actual[i]);
            }
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(backendInput[i])) {
                throw new AssertionError("Native Blender " + label + " production backend mismatch at " + i
                        + ": java=" + expected[i] + ", backend=" + backendInput[i]);
            }
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(repeatedBackendInput[i])) {
                throw new AssertionError("Native Blender " + label + " cached backend mismatch at " + i
                        + ": java=" + expected[i] + ", cached=" + repeatedBackendInput[i]);
            }
        }
        JPEOptimizer.LOGGER.info("JPE_NATIVE_BLENDER_PARITY case={} samples={} bitExact=true productionBackend=true cacheRepeat=true",
                label, expected.length);
    }

    private static void assertNativePerlinParity(float[] expected, float[] actual, String mode) {
        if (expected.length != actual.length) {
            throw new AssertionError("Native " + mode + " Perlin output length mismatch");
        }
        for (int i = 0; i < expected.length; ++i) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                throw new AssertionError("Native " + mode + " Perlin mismatch at index " + i
                        + ": vanilla=" + expected[i] + ", native=" + actual[i]);
            }
        }
    }

    /**
     * Exercises the actual Minecraft StructurePiecesBuilder Mixin using real
     * mineshaft room pieces. The expected answer is independently computed by
     * vanilla's static linear scan, so this checks both hook execution and
     * first-match equivalence rather than only testing the standalone index.
     */
    private static void structureIndexBenchmark(GameTestHelper helper) {
        StructurePiecesBuilder builder = new StructurePiecesBuilder();
        List<StructurePiece> pieces = new ArrayList<>();
        var random = net.minecraft.util.RandomSource.create(0x4A50454CL);

        for (int i = 0; i < 64; ++i) {
            int x = (i % 8) * 128;
            int z = (i / 8) * 128;
            StructurePiece piece = new MineshaftPieces.MineShaftRoom(
                    0, random, x, z, MineshaftStructure.Type.NORMAL
            );
            pieces.add(piece);
            builder.addPiece(piece);
        }

        var before = dev.jpe.optimizer.worldgen.StructureSpatialIndex.metricsSnapshot();
        long start = System.nanoTime();
        int queries = 0;
        for (int i = 0; i < pieces.size(); ++i) {
            BoundingBox query = pieces.get(i).getBoundingBox();
            StructurePiece expected = StructurePiece.findCollisionPiece(pieces, query);
            StructurePiece actual = builder.findCollisionPiece(query);
            if (actual != expected) {
                throw new AssertionError("Structure index changed vanilla first-match result at query " + i
                        + ": expected=" + expected + ", actual=" + actual);
            }
            ++queries;

            BoundingBox miss = new BoundingBox(
                    8192 + i * 64, -64, 8192 + i * 64,
                    8192 + i * 64 + 2, 320, 8192 + i * 64 + 2
            );
            expected = StructurePiece.findCollisionPiece(pieces, miss);
            actual = builder.findCollisionPiece(miss);
            if (actual != expected) {
                throw new AssertionError("Structure index changed vanilla miss result at query " + i);
            }
            ++queries;
        }

        // A piece can move directly, outside StructurePiecesBuilder's own movement methods.
        // The cached broadphase must be invalidated when its mutable BoundingBox changes.
        StructurePiece movedPiece = pieces.get(pieces.size() - 1);
        movedPiece.move(20_000, 0, 20_000);
        BoundingBox movedQuery = movedPiece.getBoundingBox();
        StructurePiece movedExpected = StructurePiece.findCollisionPiece(pieces, movedQuery);
        StructurePiece movedActual = builder.findCollisionPiece(movedQuery);
        if (movedActual != movedExpected) {
            throw new AssertionError("Moving a piece left a stale spatial index: expected="
                    + movedExpected + ", actual=" + movedActual);
        }
        ++queries;

        // The first query after movement must safely use vanilla; the second may rebuild.
        movedExpected = StructurePiece.findCollisionPiece(pieces, movedQuery);
        movedActual = builder.findCollisionPiece(movedQuery);
        if (movedActual != movedExpected) {
            throw new AssertionError("Rebuilt spatial index changed the post-move result");
        }
        ++queries;

        long elapsedNs = System.nanoTime() - start;
        var after = dev.jpe.optimizer.worldgen.StructureSpatialIndex.metricsSnapshot();

        long indexBuilds = after.builds() - before.builds();
        long candidateChecks = after.candidateChecks() - before.candidateChecks();
        long vanillaChecks = after.vanillaLinearChecks() - before.vanillaLinearChecks();
        if (dev.jpe.optimizer.worldgen.StructureSpatialIndex.enabled() && indexBuilds < 2) {
            throw new AssertionError("Spatial index was not rebuilt after a direct piece move: builds="
                    + indexBuilds);
        }
        if (candidateChecks >= vanillaChecks) {
            throw new AssertionError("Spatial index did not reduce candidate checks: candidates="
                    + candidateChecks + ", vanillaLinear=" + vanillaChecks);
        }

        JPEOptimizer.LOGGER.info(
                "JPE_STRUCTURE_INDEX_BENCH pieces={} queries={} builds={} indexedPieces={} " +
                "candidateChecks={} vanillaLinearChecks={} linearFallbacks={} elapsedMs={}",
                pieces.size(), queries, indexBuilds,
                after.indexedPieces() - before.indexedPieces(),
                candidateChecks, vanillaChecks,
                after.linearFallbacks() - before.linearFallbacks(),
                String.format(java.util.Locale.ROOT, "%.3f", elapsedNs / 1_000_000.0)
        );
        helper.succeed();
    }

    /**
     * Stage 6 in-Minecraft verification. Block data comes out of real, server-generated
     * chunks; the Rust world engine then owns meshing, GPU section residency,
     * visibility, culling and the per-frame draw loop, and a real Minecraft block
     * update is pushed through the same hand-off.
     *
     * <p>Deliberately narrow claims. Minecraft still generates the terrain; the engine
     * draws into its own offscreen target (not Minecraft's window or swapchain); block
     * indices are assigned by {@link MinecraftChunkReader} instead of the C++ terrain
     * engine. This proves the Java-to-Rust hand-off works inside the game process, not
     * that vanilla rendering has been replaced.
     *
     * <p>Skipped (logged, not silently) unless {@code -Djpe.worldLibrary} is set; with
     * {@code -Djpe.worldRequired=true} a missing library or a missing GPU backend fails.
     */
    private static void rustWorldPipeline(GameTestHelper helper) {
        java.util.Optional<java.nio.file.Path> library = RustWorldBridge.configuredLibrary();
        boolean required = RustWorldBridge.required();
        if (library.isEmpty()) {
            JPEOptimizer.LOGGER.info(
                    "JPE_RUST_WORLD_GAME_TEST result=skipped reason=library_not_configured "
                            + "property={} required={}",
                    RustWorldBridge.LIBRARY_PROPERTY, required);
            if (required) {
                throw new AssertionError("Set -D" + RustWorldBridge.LIBRARY_PROPERTY
                        + " to the built libjpe_world when -D" + RustWorldBridge.REQUIRED_PROPERTY
                        + "=true");
            }
            helper.succeed();
            return;
        }

        var level = helper.getLevel();
        final int windowSections = 3;
        // More than one 2x2 patch is scanned on purpose. A band made of a single block
        // (a sandstone plateau, a frozen ocean floor) cannot expose a transposed index
        // layout, so the search only stops at terrain with at least three distinct
        // block states inside the window that gets handed over.
        int[][] candidates = {
                {256, -256}, {264, -256}, {256, -264}, {272, -248}, {248, -272},
                {288, -240}, {240, -240}, {304, -272}, {272, -288}, {296, -296},
                {320, -256}, {336, -240}, {192, -192}, {160, -224}
        };
        int chunkX = 0;
        int chunkZ = 0;
        int baseSectionY = 0;
        int bandVariety = 0;
        int vanillaHeightmapY = 0;
        int topFilledSection = 0;
        short[][] patchBlocks = new short[4][];
        java.util.Set<Long> expectedKeys = new java.util.HashSet<>();
        int nonEmptySections = 0;
        int firstChunkNonEmpty = 0;
        int cellsChecked = 0;
        int columnsChecked = 0;
        int columnsSolid = 0;
        int patchesConsidered = 0;
        MinecraftChunkReader reader = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int[] candidate : candidates) {
            chunkX = candidate[0];
            chunkZ = candidate[1];
            // The candidate chunks sit far from the GameTest origin, so the plain
            // two-argument getChunk (which loads and generates synchronously at
            // FULL status) is required; getChunk(..., FULL, false) returns null for
            // every unloaded position and the whole search would consider 0 patches.
            var probe = level.getChunk(chunkX, chunkZ);
            if (probe == null) {
                continue;
            }
            patchesConsidered++;
            int minSection = SectionPos.blockToSectionCoord(probe.getMinY());
            int maxSection = minSection + (probe.getHeight() >> 4) - 1;
            LevelChunkSection[] probeSections = probe.getSections();
            int topFilled = -1;
            for (int i = probeSections.length - 1; i >= 0; --i) {
                if (probeSections[i] != null && !probeSections[i].hasOnlyAir()) {
                    topFilled = minSection + i;
                    break;
                }
            }
            if (topFilled < 0) {
                continue;
            }
            vanillaHeightmapY = probe.getHeight(Heightmap.Types.OCEAN_FLOOR, 8, 8);
            topFilledSection = topFilled;

            // Take the surface band (or the first band below it that still fits the
            // window). Natural variety at these coordinates is not guaranteed — a
            // frozen-ocean floor is one block state — so the layout parity is made
            // non-vacuous below by stamping an asymmetric multi-state pattern into
            // the patch through Minecraft's own setBlock, rather than by hoping the
            // seed happens to be varied.
            int chosen = -1;
            for (int base = topFilled; base >= topFilled - 2; --base) {
                if (base < minSection || base + windowSections > maxSection + 1) {
                    continue;
                }
                chosen = base;
                break;
            }
            if (chosen < 0) {
                continue;
            }
            baseSectionY = chosen;

            // Three distinct blocks at positions that differ along every axis, so a
            // transposed (y<<8)|(z<<4)|x index layout, or an x/z or y/z swap, changes
            // which sampled cell holds which state and the parity check below fails.
            stampParityBlocks(level, chunkX, chunkZ, baseSectionY, cursor);

            reader = new MinecraftChunkReader();
            expectedKeys.clear();
            nonEmptySections = 0;
            firstChunkNonEmpty = 0;
            for (int dx = 0; dx < 2; ++dx) {
                for (int dz = 0; dz < 2; ++dz) {
                    var chunk = level.getChunk(chunkX + dx, chunkZ + dz);
                    short[] blocks = reader.read(chunk, baseSectionY, windowSections);
                    patchBlocks[dx * 2 + dz] = blocks;
                    int perChunk = 0;
                    for (int s = 0; s < windowSections; ++s) {
                        for (int i = 0; i < MinecraftChunkReader.SECTION_VOLUME; ++i) {
                            if (blocks[s * MinecraftChunkReader.SECTION_VOLUME + i] != 0) {
                                expectedKeys.add(SectionPos.asLong(chunkX + dx, baseSectionY + s,
                                        chunkZ + dz));
                                nonEmptySections++;
                                perChunk++;
                                break;
                            }
                        }
                    }
                    if (dx == 0 && dz == 0) {
                        firstChunkNonEmpty = perChunk;
                    }
                }
            }
            if (nonEmptySections == 0) {
                throw new AssertionError("Real terrain at (" + chunkX + "," + chunkZ
                        + ") produced no blocks to mesh");
            }
            if (!reader.paletteParity()) {
                throw new AssertionError("Block hand-off counts disagree with Minecraft palettes: "
                        + reader.describe());
            }
            // Distinct states actually handed over (natural terrain plus the stamped
            // pattern). >= 3 makes the layout-parity result non-vacuous.
            bandVariety = reader.distinctStates();

            // Layout parity against Minecraft itself. Every sampled cell must carry the
            // id Minecraft reports for that world block position when read through
            // LevelChunk.getBlockState, and the topmost block of each sampled column
            // must be the block a direct vanilla scan finds. That checks the
            // (y<<8)|(z<<4)|x layout, the section order inside a chunk, and the
            // "0 means air" convention.
            int windowBottomY = baseSectionY * 16;
            int windowTopY = windowBottomY + windowSections * 16 - 1;
            cellsChecked = 0;
            columnsChecked = 0;
            columnsSolid = 0;
            for (int dx = 0; dx < 2; ++dx) {
                for (int dz = 0; dz < 2; ++dz) {
                    var chunk = level.getChunk(chunkX + dx, chunkZ + dz);
                    short[] handed = patchBlocks[dx * 2 + dz];
                    int baseBlockX = (chunkX + dx) * 16;
                    int baseBlockZ = (chunkZ + dz) * 16;
                    for (int lx = 0; lx < 16; lx += 5) {
                        for (int lz = 0; lz < 16; lz += 5) {
                            for (int y = windowBottomY; y <= windowTopY; y += 7) {
                                int section = Math.floorDiv(y, 16) - baseSectionY;
                                int localY = Math.floorMod(y, 16);
                                int index = section * MinecraftChunkReader.SECTION_VOLUME
                                        + (localY << 8) + (lz << 4) + lx;
                                int expectedId = reader.idOf(chunk.getBlockState(
                                        cursor.set(baseBlockX + lx, y, baseBlockZ + lz)));
                                if ((handed[index] & 0xFFFF) != expectedId) {
                                    throw new AssertionError("Hand-off cell ("
                                            + (baseBlockX + lx) + "," + y + ","
                                            + (baseBlockZ + lz) + ") holds id " + handed[index]
                                            + " but Minecraft reports state id " + expectedId
                                            + " at flat index " + index);
                                }
                                cellsChecked++;
                            }
                            int vanillaTop = windowBottomY - 1;
                            for (int y = windowTopY; y >= windowBottomY; --y) {
                                if (!chunk.getBlockState(cursor.set(baseBlockX + lx, y,
                                        baseBlockZ + lz)).isAir()) {
                                    vanillaTop = y;
                                    break;
                                }
                            }
                            int engineTop = windowBottomY - 1;
                            scanned:
                            for (int s = windowSections - 1; s >= 0; --s) {
                                for (int y = 15; y >= 0; --y) {
                                    if (handed[s * MinecraftChunkReader.SECTION_VOLUME
                                            + (y << 8) + (lz << 4) + lx] != 0) {
                                        engineTop = (baseSectionY + s) * 16 + y;
                                        break scanned;
                                    }
                                }
                            }
                            if (engineTop != vanillaTop) {
                                throw new AssertionError("Top of column ("
                                        + (baseBlockX + lx) + "," + (baseBlockZ + lz)
                                        + ") disagrees with a direct vanilla scan: hand-off="
                                        + engineTop + ", minecraft=" + vanillaTop);
                            }
                            columnsChecked++;
                            if (vanillaTop >= windowBottomY) {
                                columnsSolid++;
                            }
                        }
                    }
                }
            }
            if (columnsChecked != 64 || columnsSolid == 0 || cellsChecked < 200) {
                throw new AssertionError("Parity sampling did not cover the patch: columns="
                        + columnsChecked + " cells=" + cellsChecked + " solid=" + columnsSolid);
            }
            if (reader.distinctStates() >= 3) {
                break;
            }
        }
        if (reader == null || reader.distinctStates() < 3) {
            throw new AssertionError("Scanned " + patchesConsidered + " of " + candidates.length
                    + " terrain patches and none could carry a non-empty band with the stamped"
                    + " distinct block states the layout parity check needs; last patch"
                    + " topFilledSection=" + topFilledSection + " heightmapY=" + vanillaHeightmapY
                    + " handedStates=" + bandVariety);
        }
        int windowBottomY = baseSectionY * 16;
        int windowTopY = windowBottomY + windowSections * 16 - 1;

        RustWorldBridge bridge;
        try {
            bridge = RustWorldBridge.open(library.get(),
                    RustWorldBridge.Options.testRig(baseSectionY, baseSectionY + windowSections - 1));
        } catch (RuntimeException | Error unavailable) {
            JPEOptimizer.LOGGER.info(
                    "JPE_RUST_WORLD_GAME_TEST result=skipped reason=bridge_unavailable required={} detail={}",
                    required, unavailable.getMessage());
            if (required) {
                throw new AssertionError("Rust world engine is required but did not start",
                        unavailable);
            }
            helper.succeed();
            return;
        }

        try {
            for (int dx = 0; dx < 2; ++dx) {
                for (int dz = 0; dz < 2; ++dz) {
                    int submitted = bridge.submitChunk(chunkX + dx, chunkZ + dz, baseSectionY,
                            patchBlocks[dx * 2 + dz]);
                    if (submitted != JpeWorldNative.SUBMIT_NEW) {
                        throw new AssertionError("First hand-off of chunk (" + (chunkX + dx) + ","
                                + (chunkZ + dz) + ") returned submit code " + submitted
                                + ", expected SUBMIT_NEW");
                    }
                }
            }

            float[] up = {0.0f, 1.0f, 0.0f};
            float[] target = {(chunkX + 1) * 16.0f, windowBottomY + windowSections * 8.0f,
                    (chunkZ + 1) * 16.0f};
            float[] eye = {target[0], target[1] + 32.0f, target[2] + 64.0f};
            float[] viewProj = bridge.ortho(eye, target, up, 40.0f, 40.0f, 1.0f, 400.0f);
            if (viewProj == null) {
                throw new AssertionError("Rust camera helper rejected the rig");
            }

            JpeWorldNative.FrameStats visibleFrame = null;
            for (int frame = 0; frame < 6; ++frame) {
                visibleFrame = bridge.frame(viewProj, eye);
            }
            if (visibleFrame == null) {
                throw new AssertionError("no frames ran");
            }
            var settledStats = bridge.stats();
            java.util.Set<Long> residentKeys = new java.util.HashSet<>();
            for (long key : bridge.residentKeys(64)) {
                residentKeys.add(key);
            }

            RustWorldBridge.FramePixels visible = bridge.readPixels();
            int litPixels = visible.litPixels();

            // Culling: look the other way and nothing may be drawn.
            float[] awayEye = {target[0] + 400.0f, target[1] + 32.0f, target[2]};
            float[] awayTarget = {target[0] + 800.0f, target[1], target[2]};
            float[] awayView = bridge.ortho(awayEye, awayTarget, up, 40.0f, 40.0f, 1.0f, 400.0f);
            JpeWorldNative.FrameStats away = bridge.frame(awayView, awayEye);
            int awayLit = bridge.readPixels().litPixels();

            // A real Minecraft block update, pushed through the same hand-off: three
            // distinct blocks are placed with Level.setBlock, the chunk is read again
            // from live Minecraft data, and the engine must remesh and replace the GPU
            // geometry it already holds.
            int probeLocalX = 3;
            int probeLocalZ = 3;
            int probeBlockX = chunkX * 16 + probeLocalX;
            int probeBlockZ = chunkZ * 16 + probeLocalZ;
            int probeY = Math.min(windowTopY - 2,
                    Math.max(windowBottomY, level.getHeight(Heightmap.Types.OCEAN_FLOOR,
                            probeBlockX, probeBlockZ)));
            int updateFlags = Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS;
            level.setBlock(cursor.set(probeBlockX, probeY, probeBlockZ),
                    Blocks.GOLD_BLOCK.defaultBlockState(), updateFlags, 0);
            level.setBlock(cursor.set(probeBlockX, probeY + 1, probeBlockZ),
                    Blocks.GLASS.defaultBlockState(), updateFlags, 0);
            level.setBlock(cursor.set(probeBlockX, probeY + 2, probeBlockZ),
                    Blocks.OAK_PLANKS.defaultBlockState(), updateFlags, 0);
            var probedChunk = level.getChunk(chunkX, chunkZ);
            short[] updatedBlocks = reader.read(probedChunk, baseSectionY, windowSections);
            patchBlocks[0] = updatedBlocks;
            int updatedSubmit = bridge.submitChunk(chunkX, chunkZ, baseSectionY, updatedBlocks);
            for (int frame = 0; frame < 4; ++frame) {
                bridge.frame(viewProj, eye);
            }
            var afterUpdateStats = bridge.stats();

            // The three placed blocks must be exactly where the engine now holds them.
            int placedVerified = 0;
            for (int dy = 0; dy < 3; ++dy) {
                int y = probeY + dy;
                int section = Math.floorDiv(y, 16) - baseSectionY;
                int localY = Math.floorMod(y, 16);
                int index = section * MinecraftChunkReader.SECTION_VOLUME
                        + (localY << 8) + (probeLocalZ << 4) + probeLocalX;
                int expectedId = reader.idOf(probedChunk.getBlockState(
                        cursor.set(probeBlockX, y, probeBlockZ)));
                if ((updatedBlocks[index] & 0xFFFF) != expectedId) {
                    throw new AssertionError("Block update at (" + probeBlockX + "," + y + ","
                            + probeBlockZ + ") reached the engine as id " + updatedBlocks[index]
                            + " but Minecraft reports " + expectedId);
                }
                placedVerified++;
            }

            // Air hand-off for one chunk: its meshes must be retired, not left drawn.
            int airSubmit = bridge.submitChunk(chunkX, chunkZ, baseSectionY,
                    new short[windowSections * MinecraftChunkReader.SECTION_VOLUME]);
            for (int frame = 0; frame < 4; ++frame) {
                bridge.frame(viewProj, eye);
            }
            var afterAirStats = bridge.stats();
            long afterAirResident = bridge.residentKeys(64).length;
            long expectedAfterAir = Math.max(0, expectedKeys.size() - firstChunkNonEmpty);

            // Lifetime control surface: player focus, tickets, radius unload, epoch reset.
            int playerStatus = bridge.setPlayer(chunkX, chunkZ);
            int ticketAcquire = bridge.ticket(chunkX, chunkZ, true);
            int untrackedTicket = bridge.ticket(chunkX + 5000, chunkZ - 5000, true);
            int ticketRelease = bridge.ticket(chunkX, chunkZ, false);
            int unloadStatus = bridge.unloadOutside(chunkX, chunkZ, 0);
            bridge.frame(viewProj, eye);
            long unloadedResident = bridge.residentKeys(64).length;
            var finalStats = bridge.stats();
            long epochAfterReset = bridge.reset();

            // ---- assertions ----------------------------------------------------
            if (visibleFrame.resident() != expectedKeys.size()) {
                throw new AssertionError("Resident sections " + visibleFrame.resident()
                        + " != non-empty sections handed over " + expectedKeys.size());
            }
            if (settledStats.published() < expectedKeys.size()) {
                throw new AssertionError("Published count " + settledStats.published()
                        + " is below the handed-over sections " + expectedKeys.size());
            }
            if (!residentKeys.equals(expectedKeys)) {
                throw new AssertionError("Registry keys are not the Minecraft sections that were"
                        + " submitted: resident=" + residentKeys + " expected=" + expectedKeys);
            }
            if (visibleFrame.visible() != expectedKeys.size() || visibleFrame.culled() != 0) {
                throw new AssertionError("Facing camera should draw everything: visible="
                        + visibleFrame.visible() + " culled=" + visibleFrame.culled());
            }
            if (visibleFrame.batches() == 0) {
                throw new AssertionError("Facing camera queued no draw batches");
            }
            if (litPixels <= 200) {
                throw new AssertionError("Real Minecraft blocks drew too little: " + litPixels
                        + " lit pixels of " + visible.size());
            }
            if (visible.distinctColours() < 2) {
                throw new AssertionError("Frame is a single flat colour, shading did not run: "
                        + visible.distinctColours());
            }
            if (away.visible() != 0 || away.culled() != expectedKeys.size() || away.batches() != 0) {
                throw new AssertionError("Away camera must cull everything: visible=" + away.visible()
                        + " culled=" + away.culled() + " batches=" + away.batches());
            }
            if (awayLit != 0) {
                throw new AssertionError("Culled frame still lit " + awayLit + " pixels");
            }
            if (updatedSubmit < 0) {
                throw new AssertionError("Block-update hand-off rejected with " + updatedSubmit);
            }
            if (placedVerified != 3) {
                throw new AssertionError("Placed blocks were not all verified in the engine copy");
            }
            if (afterUpdateStats.replaced() <= settledStats.replaced()) {
                throw new AssertionError("A real Minecraft block update did not replace any GPU"
                        + " mesh: before=" + settledStats.replaced()
                        + " after=" + afterUpdateStats.replaced());
            }
            if (airSubmit < 0) {
                throw new AssertionError("Air hand-off rejected with " + airSubmit);
            }
            if (afterAirResident != expectedAfterAir) {
                throw new AssertionError("Air update retired the wrong number of sections: resident="
                        + afterAirResident + " expected=" + expectedAfterAir);
            }
            if (afterAirStats.meshesDestroyed() < firstChunkNonEmpty) {
                throw new AssertionError("Retired meshes were not destroyed on the GPU: "
                        + afterAirStats.meshesDestroyed());
            }
            if (afterAirStats.remeshChunks() == 0) {
                throw new AssertionError("Neighbouring chunks were never re-meshed after the"
                        + " block update");
            }
            if (settledStats.pendingMesh() != 0 || settledStats.pendingRemesh() != 0) {
                throw new AssertionError("Pipeline had queued work after 6 frames: pendingMesh="
                        + settledStats.pendingMesh() + " pendingRemesh="
                        + settledStats.pendingRemesh());
            }
            if (playerStatus != 0 || ticketAcquire != 1 || ticketRelease != 0) {
                // Documented ABI: the call returns the ticket count, so the first
                // acquire is 1 and the last release is 0.
                throw new AssertionError("Player/ticket control failed: player=" + playerStatus
                        + " acquire=" + ticketAcquire + " release=" + ticketRelease);
            }
            if (untrackedTicket >= 0) {
                throw new AssertionError("Ticket was accepted for an untracked chunk: "
                        + untrackedTicket);
            }
            if (unloadStatus != 0 || unloadedResident != 0) {
                throw new AssertionError("Radius unload left sections resident: status="
                        + unloadStatus + " resident=" + unloadedResident);
            }
            if (epochAfterReset != 2) {
                throw new AssertionError("Reset must bump the world epoch to 2, got " + epochAfterReset);
            }
            if (bridge.engineThreadId() == Thread.currentThread().threadId()) {
                throw new AssertionError("The renderer must own its own thread, not the server thread");
            }
            if (bridge.backend().code() == JpeWorldNative.BACKEND_AUTO) {
                throw new AssertionError("Capabilities reported the selector, not a concrete backend");
            }

            JPEOptimizer.LOGGER.info(
                    "JPE_RUST_WORLD_GAME_TEST result=passed backend={} api={}.{} library={} "
                            + "patchesScanned={} patch=({},{}@{}) windowSections={} baseSectionY={} "
                            + "topFilledSection={} vanillaHeightmapY={} handedStates={} "
                            + "nonEmptySections={} resident={} published={} replaced={} "
                            + "remeshChunks={} meshesDestroyed={} emptySections={} pendingMesh={} "
                            + "pendingRemesh={} sourceSections={} sourceMissingReads={} "
                            + "sectionsUnloaded={} chunksUnloaded={} visible={} culled={} "
                            + "batches={} litPixels={} of={} distinctColours={} awayLitPixels={} "
                            + "updateSubmitCode={} afterAirResident={} afterUnloadResident={} "
                            + "epochAfterReset={} columnsChecked={} columnsSolid={} cellsChecked={} "
                            + "distinctBlockStates={} states={} blocksScanned={} nonAirBlocks={} "
                            + "paletteParity=true engineThread={} serverThread={} takeover=false",
                    bridge.backend().name(),
                    bridge.backend().apiMajor(),
                    bridge.backend().apiMinor(),
                    bridge.library(),
                    patchesConsidered,
                    chunkX,
                    chunkZ,
                    baseSectionY,
                    windowSections,
                    baseSectionY,
                    topFilledSection,
                    vanillaHeightmapY,
                    bandVariety,
                    nonEmptySections,
                    visibleFrame.resident(),
                    settledStats.published(),
                    afterAirStats.replaced(),
                    afterAirStats.remeshChunks(),
                    afterAirStats.meshesDestroyed(),
                    afterAirStats.emptySections(),
                    settledStats.pendingMesh(),
                    settledStats.pendingRemesh(),
                    afterAirStats.sourceSections(),
                    afterAirStats.sourceMissingReads(),
                    finalStats.sectionsUnloaded(),
                    finalStats.chunksUnloaded(),
                    visibleFrame.visible(),
                    visibleFrame.culled(),
                    visibleFrame.batches(),
                    litPixels,
                    visible.size(),
                    visible.distinctColours(),
                    awayLit,
                    updatedSubmit,
                    afterAirResident,
                    unloadedResident,
                    epochAfterReset,
                    columnsChecked,
                    columnsSolid,
                    cellsChecked,
                    reader.distinctStates(),
                    reader.stateNames(8),
                    reader.blocksScanned(),
                    reader.nonAirBlocks(),
                    bridge.engineThreadId(),
                    Thread.currentThread().threadId());
            helper.succeed();
        } finally {
            bridge.close();
        }
    }

    /**
     * Writes three distinct, hard-to-confuse block states into the first section of the
     * chosen band through Minecraft's own {@code Level.setBlock}. The positions differ
     * along all three axes (x, y and z), so if the hand-off applied a transposed flat
     * index — an x/z swap, or either axis traded for y — the parity scan would find the
     * wrong state where Minecraft reports the stamped one. This is what makes the layout
     * check non-vacuous regardless of how uniform the surrounding generated terrain is.
     */
    private static void stampParityBlocks(net.minecraft.world.level.Level level, int chunkX,
                                          int chunkZ, int baseSectionY,
                                          BlockPos.MutableBlockPos cursor) {
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        int baseY = baseSectionY * 16;
        int flags = Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS;
        level.setBlock(cursor.set(baseX + 2, baseY + 1, baseZ + 5),
                Blocks.GOLD_BLOCK.defaultBlockState(), flags, 0);
        level.setBlock(cursor.set(baseX + 7, baseY + 2, baseZ + 2),
                Blocks.DIAMOND_BLOCK.defaultBlockState(), flags, 0);
        level.setBlock(cursor.set(baseX + 2, baseY + 3, baseZ + 5),
                Blocks.OAK_PLANKS.defaultBlockState(), flags, 0);
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getProcessCpuTime();
        }
        return -1L;
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
