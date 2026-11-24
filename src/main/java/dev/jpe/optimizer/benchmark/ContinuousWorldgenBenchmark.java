package dev.jpe.optimizer.benchmark;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.worldgen.DensityBufferMetrics;
import dev.jpe.optimizer.vector.DensityVectorRuntime;
import dev.jpe.optimizer.worldgen.StructureSpatialIndex;
import dev.jpe.optimizer.worldgen.WorldgenMetrics;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synthetic moving-frontier world-generation probe.
 *
 * It submits FULL-chunk futures from the server tick loop at a configurable
 * frontier interval. This is not a real ServerPlayer ticket lifecycle and
 * does not measure network delivery, client meshing, or rendering. Request
 * submission time is recorded separately because getChunkFuture may perform
 * nontrivial synchronous work before returning its future.
 */
public final class ContinuousWorldgenBenchmark {
    private static final int VIEW_RADIUS_CHUNKS = 8;
    private static final int FRONTIER_WIDTH = VIEW_RADIUS_CHUNKS * 2 + 1;
    private static final int TICKS_PER_CHUNK_COLUMN = Math.max(1, Integer.getInteger("jpe.benchmark.ticksPerColumn", 45));
    private static final int WARMUP_ROWS = 3;
    private static final int MEASURED_ROWS = 20;
    private static final int MAX_DRAIN_TICKS = 400;
    private static final int WARMUP_START_CHUNK_X = 4096;
    private static final int MEASURE_START_CHUNK_X = 8192;
    private static final int START_CHUNK_Z = 4096;

    private static final AtomicReference<State> STATE = new AtomicReference<>();

    private ContinuousWorldgenBenchmark() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("jpe.continuousWorldgenBenchmark")) {
            return;
        }

        MinecraftServer server = event.getServer();
        State state = STATE.get();
        if (state == null) {
            State created = new State(server);
            if (STATE.compareAndSet(null, created)) {
                state = created;
                JPEOptimizer.LOGGER.info(
                        "JPE synthetic chunk-future frontier probe armed: speedBlocksPerSecond={} width={} warmupRows={} measuredRows={}",
                        format(320.0 / TICKS_PER_CHUNK_COLUMN), FRONTIER_WIDTH, WARMUP_ROWS, MEASURED_ROWS
                );
            } else {
                state = STATE.get();
            }
        }

        if (state != null && !state.finished) {
            try {
                state.tick();
            } catch (Throwable failure) {
                JPEOptimizer.LOGGER.error("JPE continuous worldgen benchmark failed", failure);
                state.finished = true;
                server.halt(false);
            }
        }
    }

    private static final class State {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        private final long serverThreadId = Thread.currentThread().threadId();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger measuredCompleted = new AtomicInteger();
        private final AtomicInteger measuredFailed = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final AtomicInteger maxPendingTasks = new AtomicInteger();
        private final AtomicInteger maxLoadedChunks = new AtomicInteger();
        private final ConcurrentLinkedQueue<Long> measuredLatencies = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Long> tickIntervals = new ConcurrentLinkedQueue<>();

        private int tick;
        private int nextIssueTick = 1;
        private int warmupRowsIssued;
        private int measuredRowsIssued;
        private int drainTicks;
        private long lastTickNanos;
        private long measurementStartNanos;
        private long serverCpuBefore = -1L;
        private long processCpuBefore = -1L;
        private long allocatedBefore = -1L;
        private long gcCountBefore;
        private long gcTimeBefore;
        private long requestSubmissionNanos;
        private long maxRequestSubmissionNanos;
        private long requestSubmissionCount;
        private WorldgenMetrics.Snapshot worldgenBefore;
        private DensityBufferMetrics.Snapshot densityBuffersBefore;
        private StructureSpatialIndex.Metrics structureBefore;
        private volatile boolean finished;

        private State(MinecraftServer server) {
            this.server = server;
            this.level = server.overworld();
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) {
                throw new IllegalStateException("Continuous benchmark requires NoiseBasedChunkGenerator, got "
                        + level.getChunkSource().getGenerator().getClass().getName());
            }
        }

        private void tick() {
            long now = System.nanoTime();
            tick++;
            if (measurementStartNanos != 0L && lastTickNanos != 0L) {
                tickIntervals.add(now - lastTickNanos);
            }
            lastTickNanos = now;
            maxPendingTasks.accumulateAndGet(level.getChunkSource().getPendingTasksCount(), Math::max);
            maxLoadedChunks.accumulateAndGet(level.getChunkSource().getLoadedChunksCount(), Math::max);

            if (measurementStartNanos == 0L) {
                if (warmupRowsIssued < WARMUP_ROWS && tick >= nextIssueTick) {
                    issueFrontierRow(false);
                    warmupRowsIssued++;
                    nextIssueTick = tick + TICKS_PER_CHUNK_COLUMN;
                }

                if (warmupRowsIssued == WARMUP_ROWS && inFlight.get() == 0) {
                    beginMeasurement(now);
                } else if (warmupRowsIssued == WARMUP_ROWS
                        && tick > WARMUP_ROWS * TICKS_PER_CHUNK_COLUMN + MAX_DRAIN_TICKS) {
                    throw new IllegalStateException("Warmup chunk futures did not drain; inFlight=" + inFlight.get());
                }
                return;
            }

            if (measuredRowsIssued < MEASURED_ROWS && tick >= nextIssueTick) {
                issueFrontierRow(true);
                measuredRowsIssued++;
                nextIssueTick = tick + TICKS_PER_CHUNK_COLUMN;
            }

            if (measuredRowsIssued == MEASURED_ROWS) {
                if (inFlight.get() == 0) {
                    finish();
                } else if (++drainTicks > MAX_DRAIN_TICKS) {
                    finish();
                }
            }
        }

        private void issueFrontierRow(boolean measured) {
            int rowIndex = measured ? measuredRowsIssued : warmupRowsIssued;
            int chunkX = (measured ? MEASURE_START_CHUNK_X : WARMUP_START_CHUNK_X) + rowIndex;
            for (int offset = -VIEW_RADIUS_CHUNKS; offset <= VIEW_RADIUS_CHUNKS; offset++) {
                requestFullChunk(chunkX, START_CHUNK_Z + offset, measured);
            }
        }

        private void requestFullChunk(int x, int z, boolean measured) {
            long requestedAt = System.nanoTime();
            int currentInFlight = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(currentInFlight, Math::max);

            try {
                long submitStart = System.nanoTime();
                CompletableFuture<?> future = level.getChunkSource()
                        .getChunkFuture(x, z, ChunkStatus.FULL, true);
                long submitElapsed = System.nanoTime() - submitStart;
                requestSubmissionNanos += submitElapsed;
                maxRequestSubmissionNanos = Math.max(maxRequestSubmissionNanos, submitElapsed);
                requestSubmissionCount++;
                future.whenComplete((rawResult, failure) -> {
                    try {
                        if (failure != null) {
                            if (measured) {
                                measuredFailed.incrementAndGet();
                            }
                            return;
                        }

                        if (!(rawResult instanceof net.minecraft.server.level.ChunkResult<?> result)
                                || !result.isSuccess()
                                || !(result.orElse(null) instanceof LevelChunk)) {
                            if (measured) {
                                measuredFailed.incrementAndGet();
                            }
                            return;
                        }

                        if (measured) {
                            measuredCompleted.incrementAndGet();
                            measuredLatencies.add(System.nanoTime() - requestedAt);
                        }
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (Throwable failure) {
                inFlight.decrementAndGet();
                if (measured) {
                    measuredFailed.incrementAndGet();
                }
                JPEOptimizer.LOGGER.error("Unable to request benchmark chunk {},{}", x, z, failure);
            }
        }

        private void beginMeasurement(long now) {
            if (measuredRowsIssued != 0 || measurementStartNanos != 0L) {
                return;
            }
            measurementStartNanos = now;
            maxInFlight.set(0);
            maxPendingTasks.set(0);
            maxLoadedChunks.set(0);
            requestSubmissionNanos = 0L;
            maxRequestSubmissionNanos = 0L;
            requestSubmissionCount = 0L;
            worldgenBefore = WorldgenMetrics.snapshot();
            densityBuffersBefore = DensityBufferMetrics.snapshot();
            structureBefore = StructureSpatialIndex.metricsSnapshot();
            serverCpuBefore = currentThreadCpuTime();
            processCpuBefore = processCpuTime();
            allocatedBefore = allocatedBytes(threadBean, serverThreadId);
            gcCountBefore = gcCount();
            gcTimeBefore = gcTime();
            nextIssueTick = tick;
            JPEOptimizer.LOGGER.info(
                    "JPE continuous worldgen measurement started: rows={} width={} frontierChunks={} intervalTicks={} seed={}",
                    MEASURED_ROWS, FRONTIER_WIDTH, MEASURED_ROWS * FRONTIER_WIDTH,
                    TICKS_PER_CHUNK_COLUMN, level.getSeed()
            );
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;

            long now = System.nanoTime();
            long elapsedNanos = Math.max(1L, now - measurementStartNanos);
            List<Long> latencies = new ArrayList<>(measuredLatencies);
            List<Long> ticks = new ArrayList<>(tickIntervals);
            Collections.sort(latencies);
            Collections.sort(ticks);

            WorldgenMetrics.Snapshot worldgenAfter = WorldgenMetrics.snapshot();
            DensityBufferMetrics.Snapshot densityBufferDelta = DensityBufferMetrics.delta(
                    DensityBufferMetrics.snapshot(), densityBuffersBefore);
            StructureSpatialIndex.Metrics structureAfter = StructureSpatialIndex.metricsSnapshot();
            long doFillCalls = worldgenAfter.calls() - worldgenBefore.calls();
            long requested = (long) MEASURED_ROWS * FRONTIER_WIDTH;
            int completed = measuredCompleted.get();
            int failed = measuredFailed.get();

            long serverCpuAfter = currentThreadCpuTime();
            long processCpuAfter = processCpuTime();
            long allocatedAfter = allocatedBytes(threadBean, serverThreadId);

            JPEOptimizer.LOGGER.info(
                    "JPE_CONTINUOUS_WORLDGEN_BENCH seed={} generator={} mode={} speedBlocksPerSecond={} "
                            + "viewRadiusChunks={} rows={} requestedChunks={} completedChunks={} failedChunks={} "
                            + "throughputChunksPerSecond={} meanChunkLatencyMs={} medianChunkLatencyMs={} p95ChunkLatencyMs={} "
                            + "p95ServerTickMs={} maxInFlight={} maxPendingTasks={} maxLoadedChunks={} "
                            + "avgGetChunkFutureCallMs={} maxGetChunkFutureCallMs={} elapsedSeconds={} "
                            + "serverThreadCpuMs={} processCpuMs={} serverThreadAllocatedMiB={} gcCollections={} gcMs={} "
                            + "doFillCalls={} doFillDensityMs={} doFillBlockMs={} doFillSamples={} "
                            + "structureQueries={} structureIndexBuilds={} structureIndexedPieces={} "
                            + "structureCandidateChecks={} structureVanillaLinearChecks={} "
                            + "densityMetricsEnabled={} unpooledCalls={} unpooledElements={} "
                            + "poolHits={} poolMisses={} poolRequestedElements={} poolReusedCapacityElements={}",
                    level.getSeed(), level.getChunkSource().getGenerator().getClass().getName(),
                    DensityVectorRuntime.enabled() ? "vector" : "scalar",
                    format(320.0 / TICKS_PER_CHUNK_COLUMN),
                    VIEW_RADIUS_CHUNKS, MEASURED_ROWS, requested, completed, failed,
                    format(completed / (elapsedNanos / 1_000_000_000.0)),
                    format(mean(latencies) / 1_000_000.0),
                    format(percentile(latencies, 0.50) / 1_000_000.0),
                    format(percentile(latencies, 0.95) / 1_000_000.0),
                    format(percentile(ticks, 0.95) / 1_000_000.0),
                    maxInFlight.get(), maxPendingTasks.get(), maxLoadedChunks.get(),
                    format(requestSubmissionCount == 0 ? 0.0 : requestSubmissionNanos / (double) requestSubmissionCount / 1_000_000.0),
                    format(maxRequestSubmissionNanos / 1_000_000.0),
                    format(elapsedNanos / 1_000_000_000.0),
                    deltaMs(serverCpuBefore, serverCpuAfter),
                    deltaMs(processCpuBefore, processCpuAfter),
                    allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
                            ? format((allocatedAfter - allocatedBefore) / 1048576.0) : "unsupported",
                    gcCount() - gcCountBefore, gcTime() - gcTimeBefore,
                    doFillCalls,
                    format((worldgenAfter.densitySampleNanos() - worldgenBefore.densitySampleNanos()) / 1_000_000.0),
                    format((worldgenAfter.blockFillNanos() - worldgenBefore.blockFillNanos()) / 1_000_000.0),
                    worldgenAfter.samples() - worldgenBefore.samples(),
                    structureAfter.queries() - structureBefore.queries(),
                    structureAfter.builds() - structureBefore.builds(),
                    structureAfter.indexedPieces() - structureBefore.indexedPieces(),
                    structureAfter.candidateChecks() - structureBefore.candidateChecks(),
                    structureAfter.vanillaLinearChecks() - structureBefore.vanillaLinearChecks(),
                    DensityBufferMetrics.enabled(), densityBufferDelta.unpooledCalls(),
                    densityBufferDelta.unpooledElements(), densityBufferDelta.poolHits(),
                    densityBufferDelta.poolMisses(), densityBufferDelta.poolRequestedElements(),
                    densityBufferDelta.poolReusedCapacityElements()
            );

            if (completed != requested || failed != 0 || inFlight.get() != 0 || doFillCalls < requested) {
                JPEOptimizer.LOGGER.error(
                        "JPE continuous benchmark incomplete: requested={} completed={} failed={} inFlight={} drainTicks={} doFillCalls={}",
                        requested, completed, failed, inFlight.get(), drainTicks, doFillCalls
                );
            }
            server.halt(false);
        }
    }

    private static long percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(quantile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private static double mean(List<Long> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        long total = 0L;
        for (long value : values) {
            total += value;
        }
        return total / (double) values.size();
    }

    private static String deltaMs(long before, long after) {
        return before >= 0 && after >= before ? format((after - before) / 1_000_000.0) : "unsupported";
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static long currentThreadCpuTime() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : -1L;
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
