package dev.jpe.optimizer.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldgenMetricsConcurrencyTest {
    @Test
    void tracksPeakConcurrentDoFillCallsAndResetsToCurrentActivity() {
        WorldgenMetrics.resetConcurrentPeak();

        WorldgenMetrics.beginDoFill();
        WorldgenMetrics.beginDoFill();
        assertEquals(2, WorldgenMetrics.snapshot().maxConcurrentDoFill());

        WorldgenMetrics.endDoFill();
        assertEquals(2, WorldgenMetrics.snapshot().maxConcurrentDoFill());

        WorldgenMetrics.resetConcurrentPeak();
        assertEquals(1, WorldgenMetrics.snapshot().maxConcurrentDoFill());

        WorldgenMetrics.endDoFill();
        WorldgenMetrics.resetConcurrentPeak();
        assertEquals(0, WorldgenMetrics.snapshot().maxConcurrentDoFill());
    }
}
