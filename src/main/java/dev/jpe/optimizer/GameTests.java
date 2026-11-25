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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.util.RandomSource;
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
import dev.jpe.optimizer.runtime.NativeRuntime;
import dev.jpe.optimizer.runtime.DensityGraphIr;
import dev.jpe.optimizer.runtime.NativePerlinNoiseBackend;
import dev.jpe.optimizer.vector.DensityVectorRuntime;
import dev.jpe.optimizer.vector.PerlinNoiseVectorizer;

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

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PALETTE_HASH_CONFIGURATION =
            TEST_FUNCTIONS.register("palette_hash_configuration", () -> GameTests::paletteHashConfiguration);

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
                Identifier.parse("jpe_optimizer:palette_hash_configuration"),
                new FunctionGameTestInstance(PALETTE_HASH_CONFIGURATION.getKey(), data)
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
        try (NativeRuntime runtime = NativeRuntime.load(java.nio.file.Path.of(library))) {
            if (!runtime.supportsBlenderDensityVolume()) {
                throw new AssertionError("Native library does not export Blender density volume support");
            }

            BlendingData fixedData = createSyntheticBlendingData(3, 5.0F);
            Long2ObjectOpenHashMap<BlendingData> fixedMap = new Long2ObjectOpenHashMap<>();
            fixedMap.put(ChunkPos.asLong(0, 0), fixedData);
            Blender fixedBlender = createSyntheticBlender(fixedMap, new Long2ObjectOpenHashMap<>());
            verifyBlenderCase(runtime, fixedBlender,
                    new DensityVolume(1, 1, 1, 0, -32, 0, 1, 1, 1),
                    new float[]{7.25F}, "fixed-density");

            BlendingData neighborData = createSyntheticBlendingData(3, 10.0F);
            Long2ObjectOpenHashMap<BlendingData> densityMap = new Long2ObjectOpenHashMap<>();
            densityMap.put(ChunkPos.asLong(0, 0), neighborData);
            Blender neighborBlender = createSyntheticBlender(new Long2ObjectOpenHashMap<>(), densityMap);
            verifyBlenderCase(runtime, neighborBlender,
                    new DensityVolume(1, 1, 1, 4, -32, 0, 1, 1, 1),
                    new float[]{4.0F}, "weighted-neighborhood");

            BlendingData boundaryData = createSyntheticBlendingData(11, 12.5F);
            Long2ObjectOpenHashMap<BlendingData> boundaryMap = new Long2ObjectOpenHashMap<>();
            boundaryMap.put(ChunkPos.asLong(-1, -1), boundaryData);
            Blender boundaryBlender = createSyntheticBlender(boundaryMap, new Long2ObjectOpenHashMap<>());
            verifyBlenderCase(runtime, boundaryBlender,
                    new DensityVolume(1, 1, 1, 0, -32, 0, 1, 1, 1),
                    new float[]{9.0F}, "negative-section-boundary");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to create exact Minecraft Blender parity fixtures", failure);
        } catch (Exception failure) {
            throw new AssertionError("Native Blender volume parity failed", failure);
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
        for (int i = 0; i < expected.length; ++i) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                throw new AssertionError("Native Blender " + label + " mismatch at " + i
                        + ": java=" + expected[i] + ", native=" + actual[i]);
            }
        }
        JPEOptimizer.LOGGER.info("JPE_NATIVE_BLENDER_PARITY case={} samples={} bitExact=true",
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
