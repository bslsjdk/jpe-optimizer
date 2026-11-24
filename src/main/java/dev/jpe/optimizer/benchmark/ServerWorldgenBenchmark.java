package dev.jpe.optimizer.benchmark;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.worldgen.StructureSpatialIndex;
import dev.jpe.optimizer.worldgen.WorldgenMetrics;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.GarbageCollectorMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in benchmark that runs inside a real dedicated server using its normal
 * overworld generator. Unlike GameTestServer, this path rejects FlatLevelSource.
 */
public final class ServerWorldgenBenchmark {
    private static final int WARMUP_CHUNKS = 8;
    private static final int MEASURED_CHUNKS = 24;
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private ServerWorldgenBenchmark() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("jpe.serverWorldgenBenchmark") || !STARTED.compareAndSet(false, true)) {
            return;
        }

        // Wait until the first post-tick so server startup has settled.
        MinecraftServer server = event.getServer();
        try {
            run(server);
        } catch (Throwable failure) {
            JPEOptimizer.LOGGER.error("JPE dedicated worldgen benchmark failed", failure);
        } finally {
            // The CI run is a one-shot benchmark process, not a persistent server.
            server.halt(false);
        }
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        var generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator)) {
            throw new IllegalStateException("Expected NoiseBasedChunkGenerator, got "
                    + generator.getClass().getName());
        }

        if (Boolean.getBoolean("jpe.climateRTreeBenchmark")) {
            ClimateRTreeBenchmark.runActualOverworld(level, 3, 15);
            if (Boolean.getBoolean("jpe.climateRTreeBenchmarkOnly")) {
                return;
            }
        }

        long seed = level.getSeed();
        long threadId = Thread.currentThread().threadId();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        // Warm the JIT in a separate, contiguous region. Requests are FULL chunks.
        for (int i = 0; i < WARMUP_CHUNKS; i++) {
            requireFullChunk(level, 1024 + i, 1024);
        }

        WorldgenMetrics.Snapshot worldgenBefore = WorldgenMetrics.snapshot();
        StructureSpatialIndex.Metrics structureBefore = StructureSpatialIndex.metricsSnapshot();
        List<Long> elapsed = new ArrayList<>(MEASURED_CHUNKS);
        long processCpuBefore = processCpuTime();
        long cpuBefore = threadBean.isCurrentThreadCpuTimeSupported()
                ? threadBean.getCurrentThreadCpuTime() : -1L;
        long allocatedBefore = allocatedBytes(threadBean, threadId);
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTime();

        // A compact 6x4 region shares nearby generation dependencies while
        // every requested target chunk is new in this freshly-created world.
        for (int i = 0; i < MEASURED_CHUNKS; i++) {
            int chunkX = 2048 + i % 6;
            int chunkZ = 2048 + i / 6;
            long start = System.nanoTime();
            requireFullChunk(level, chunkX, chunkZ);
            elapsed.add(System.nanoTime() - start);
        }

        long cpuAfter = threadBean.isCurrentThreadCpuTimeSupported()
                ? threadBean.getCurrentThreadCpuTime() : -1L;
        long processCpuAfter = processCpuTime();
        long allocatedAfter = allocatedBytes(threadBean, threadId);
        long gcCountAfter = gcCount();
        long gcTimeAfter = gcTime();
        long total = elapsed.stream().mapToLong(Long::longValue).sum();
        long[] sorted = elapsed.stream().mapToLong(Long::longValue).sorted().toArray();

        WorldgenMetrics.Snapshot worldgenAfter = WorldgenMetrics.snapshot();
        StructureSpatialIndex.Metrics structureAfter = StructureSpatialIndex.metricsSnapshot();
        long doFillCalls = worldgenAfter.calls() - worldgenBefore.calls();
        long densitySampleNanos = worldgenAfter.densitySampleNanos() - worldgenBefore.densitySampleNanos();
        long blockFillNanos = worldgenAfter.blockFillNanos() - worldgenBefore.blockFillNanos();
        long doFillSamples = worldgenAfter.samples() - worldgenBefore.samples();

        if (doFillCalls < MEASURED_CHUNKS) {
            throw new IllegalStateException("Expected at least " + MEASURED_CHUNKS
                    + " NoiseBasedChunkGenerator.doFill calls, observed " + doFillCalls);
        }

        JPEOptimizer.LOGGER.info(
                "JPE_SERVER_WORLDGEN_BENCH seed={} generator={} mode={} chunks={} meanMs={} medianMs={} p95Ms={} "
                        + "serverThreadCpuMs={} processCpuMs={} serverThreadAllocatedMiB={} gcCollections={} gcMs={} "
                        + "warmupChunks={} doFillCalls={} doFillDensityMs={} doFillBlockMs={} doFillSamples={} "
                        + "structureQueries={} structureIndexBuilds={} structureIndexedPieces={} "
                        + "structureCandidateChecks={} structureLinearFallbacks={} structureVanillaLinearChecks={}",
                seed, generator.getClass().getName(),
                dev.jpe.optimizer.vector.DensityVectorRuntime.enabled() ? "vector" : "scalar",
                MEASURED_CHUNKS,
                format(total / (double) MEASURED_CHUNKS / 1_000_000.0),
                format((sorted[11] + sorted[12]) / 2_000_000.0),
                format(sorted[22] / 1_000_000.0),
                cpuBefore >= 0 && cpuAfter >= cpuBefore
                        ? format((cpuAfter - cpuBefore) / 1_000_000.0) : "unsupported",
                processCpuBefore >= 0 && processCpuAfter >= processCpuBefore
                        ? format((processCpuAfter - processCpuBefore) / 1_000_000.0) : "unsupported",
                allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
                        ? format((allocatedAfter - allocatedBefore) / 1048576.0) : "unsupported",
                gcCountAfter - gcCountBefore, gcTimeAfter - gcTimeBefore, WARMUP_CHUNKS,
                doFillCalls, format(densitySampleNanos / 1_000_000.0),
                format(blockFillNanos / 1_000_000.0), doFillSamples,
                structureAfter.queries() - structureBefore.queries(),
                structureAfter.builds() - structureBefore.builds(),
                structureAfter.indexedPieces() - structureBefore.indexedPieces(),
                structureAfter.candidateChecks() - structureBefore.candidateChecks(),
                structureAfter.linearFallbacks() - structureBefore.linearFallbacks(),
                structureAfter.vanillaLinearChecks() - structureBefore.vanillaLinearChecks()
        );
    }

    private static void requireFullChunk(ServerLevel level, int x, int z) {
        // The two-argument Level#getChunk path is not an explicit generation request.
        // Ask the server chunk cache for FULL and allow it to load or generate.
        var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, true);
        if (!(chunk instanceof LevelChunk)) {
            throw new IllegalStateException("Expected generated FULL LevelChunk at " + x + "," + z
                    + ", got " + (chunk == null ? "null" : chunk.getClass().getName()));
        }
        if (chunk.getPos().x() != x || chunk.getPos().z() != z) {
            throw new IllegalStateException("Server returned wrong chunk for " + x + "," + z
                    + ": " + chunk.getPos());
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean sunBean
                ? sunBean.getProcessCpuTime() : -1L;
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
                .filter(value -> value >= 0).sum();
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(value -> value >= 0).sum();
    }
}
