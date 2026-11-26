package dev.jpe.optimizer.runtime;

import java.util.concurrent.atomic.LongAdder;

/** Low-overhead counters for the opt-in native Aquifer cache prefill. */
public final class AquiferGridCenterMetrics {
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder SUCCESSES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder CELLS = new LongAdder();
    private static final LongAdder NATIVE_NANOS = new LongAdder();

    private AquiferGridCenterMetrics() {}

    public static void recordSuccess(int cells, long elapsedNanos) {
        ATTEMPTS.increment();
        SUCCESSES.increment();
        CELLS.add(cells);
        NATIVE_NANOS.add(Math.max(0L, elapsedNanos));
    }

    public static void recordFailure(int cells, long elapsedNanos) {
        ATTEMPTS.increment();
        FAILURES.increment();
        CELLS.add(Math.max(0, cells));
        NATIVE_NANOS.add(Math.max(0L, elapsedNanos));
    }

    public static Snapshot snapshot() {
        return new Snapshot(ATTEMPTS.sum(), SUCCESSES.sum(), FAILURES.sum(),
                CELLS.sum(), NATIVE_NANOS.sum());
    }

    public record Snapshot(long attempts, long successes, long failures,
                           long cells, long nativeNanos) {}
}
