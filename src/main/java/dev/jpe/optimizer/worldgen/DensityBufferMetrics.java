package dev.jpe.optimizer.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in counters for pooled and unpooled density-buffer allocation paths.
 * Counters are sampled by the dedicated benchmark; the default game path pays
 * only one predictable disabled-flag branch at each instrumented call site.
 */
public final class DensityBufferMetrics {
    private static final boolean ENABLED = Boolean.getBoolean("jpe.densityBufferMetrics");
    private static final AtomicLong UNPOOLED_CALLS = new AtomicLong();
    private static final AtomicLong UNPOOLED_ELEMENTS = new AtomicLong();
    private static final AtomicLong POOL_HITS = new AtomicLong();
    private static final AtomicLong POOL_MISSES = new AtomicLong();
    private static final AtomicLong POOL_REQUESTED_ELEMENTS = new AtomicLong();
    private static final AtomicLong POOL_REUSED_CAPACITY_ELEMENTS = new AtomicLong();

    public record Snapshot(long unpooledCalls, long unpooledElements,
                           long poolHits, long poolMisses,
                           long poolRequestedElements, long poolReusedCapacityElements) {}

    private DensityBufferMetrics() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void recordUnpooled(int size) {
        if (!ENABLED) return;
        UNPOOLED_CALLS.incrementAndGet();
        UNPOOLED_ELEMENTS.addAndGet(size);
    }

    public static void recordPoolLookup(int requestedCapacity, int reusedCapacity) {
        if (!ENABLED) return;
        POOL_REQUESTED_ELEMENTS.addAndGet(requestedCapacity);
        if (reusedCapacity < 0) {
            POOL_MISSES.incrementAndGet();
        } else {
            POOL_HITS.incrementAndGet();
            POOL_REUSED_CAPACITY_ELEMENTS.addAndGet(reusedCapacity);
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(UNPOOLED_CALLS.get(), UNPOOLED_ELEMENTS.get(),
                POOL_HITS.get(), POOL_MISSES.get(),
                POOL_REQUESTED_ELEMENTS.get(), POOL_REUSED_CAPACITY_ELEMENTS.get());
    }

    public static Snapshot delta(Snapshot after, Snapshot before) {
        return new Snapshot(
                after.unpooledCalls() - before.unpooledCalls(),
                after.unpooledElements() - before.unpooledElements(),
                after.poolHits() - before.poolHits(),
                after.poolMisses() - before.poolMisses(),
                after.poolRequestedElements() - before.poolRequestedElements(),
                after.poolReusedCapacityElements() - before.poolReusedCapacityElements()
        );
    }
}
