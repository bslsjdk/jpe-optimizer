package dev.jpe.optimizer.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recordsSimultaneousEntriesFromIndependentWorkerThreads() throws Exception {
        WorldgenMetrics.resetConcurrentPeak();

        int workers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        try {
            for (int i = 0; i < workers; i++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    WorldgenMetrics.beginDoFill();
                    try {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting to release worker");
                        }
                    } finally {
                        WorldgenMetrics.endDoFill();
                    }
                    return null;
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Workers did not become ready");
            start.countDown();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Workers did not enter doFill together");
            assertEquals(workers, WorldgenMetrics.snapshot().maxConcurrentDoFill());
        } finally {
            release.countDown();
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        WorldgenMetrics.resetConcurrentPeak();
        assertEquals(0, WorldgenMetrics.snapshot().maxConcurrentDoFill());
    }
}
