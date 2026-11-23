package dev.jpe.optimizer.alloc;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Samples process-wide allocation rate (bytes/s) and GC counters at 250ms to
 * capture the short peak during fresh-world / chunk generation, writing a CSV
 * to the game directory via a daemon thread. Game threads never do I/O.
 */
public final class AllocationSampler implements AutoCloseable {
    private static final long SAMPLE_INTERVAL_MS = 250;

    private final com.sun.management.ThreadMXBean bean;
    private final java.nio.file.Path csv;
    private final java.util.concurrent.ArrayBlockingQueue<String> queue =
            new java.util.concurrent.ArrayBlockingQueue<>(16384);
    private final java.util.concurrent.atomic.LongAdder dropped =
            new java.util.concurrent.atomic.LongAdder();
    private volatile boolean running = true;
    private long lastAlloc = -1;
    private long lastGcCount = -1;
    private long lastGcTime = -1;
    private long lastNanos = System.nanoTime();
    private final Thread writer;

    public AllocationSampler(java.nio.file.Path gameDir) {
        this.bean = resolveBean();
        java.nio.file.Path dir = gameDir.resolve("logs").resolve("jpe-optimizer");
        this.csv = dir.resolve("alloc-" + System.currentTimeMillis() + ".csv");
        try {
            java.nio.file.Files.createDirectories(dir);
            try (var out = java.nio.file.Files.newBufferedWriter(csv,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW)) {
                out.write("t_ms,alloc_bytes_per_sec,gc_count_delta,gc_time_ms_delta,dropped,heap_used_bytes");
                out.newLine();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create optimizer alloc log", e);
        }
        writer = new Thread(this::writeLoop, "jpe-optimizer-alloc-writer");
        writer.setDaemon(true);
        writer.start();
        Thread sampler = new Thread(this::sampleLoop, "jpe-optimizer-alloc-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static com.sun.management.ThreadMXBean resolveBean() {
        try {
            ThreadMXBean b = ManagementFactory.getThreadMXBean();
            if (b instanceof com.sun.management.ThreadMXBean t
                    && t.isThreadAllocatedMemorySupported()) {
                t.setThreadAllocatedMemoryEnabled(true);
                return t;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public boolean allocationSupported() { return bean != null; }
    public java.nio.file.Path outputFile() { return csv; }

    private long totalAllocated() {
        if (bean == null) return -1;
        long total = 0;
        for (long id : bean.getAllThreadIds()) {
            long v = bean.getThreadAllocatedBytes(id);
            if (v > 0) total += v;
        }
        return total;
    }

    private void sampleLoop() {
        while (running) {
            long t0 = System.nanoTime();
            try {
                sample();
            } catch (Throwable t) {
                dev.jpe.optimizer.JPEOptimizer.LOGGER.warn("alloc sample failed", t);
            }
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            long sleep = SAMPLE_INTERVAL_MS - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); }
                catch (InterruptedException e) { if (!running) break; }
            }
        }
    }

    private void sample() {
        long now = System.nanoTime();
        long alloc = totalAllocated();
        long gcCount = 0, gcTime = 0;
        for (var b : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, b.getCollectionCount());
            gcTime += Math.max(0, b.getCollectionTime());
        }
        long rate = -1;
        if (lastAlloc >= 0 && alloc >= lastAlloc) {
            double secs = (now - lastNanos) / 1_000_000_000.0;
            if (secs > 0) rate = (long) ((alloc - lastAlloc) / secs);
        }
        long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long dc = (lastGcCount >= 0 && gcCount >= lastGcCount) ? gcCount - lastGcCount : 0;
        long dt = (lastGcTime >= 0 && gcTime >= lastGcTime) ? gcTime - lastGcTime : 0;
        lastAlloc = alloc; lastGcCount = gcCount; lastGcTime = gcTime; lastNanos = now;
        offer(System.currentTimeMillis() + "," + rate + "," + dc + "," + dt + ","
                + dropped.sum() + "," + heapUsed);
    }

    private void offer(String line) {
        if (!queue.offer(line)) dropped.increment();
    }

    private void writeLoop() {
        try (var out = java.nio.file.Files.newBufferedWriter(csv,
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line != null) { out.write(line); out.newLine(); }
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException e) {
            dev.jpe.optimizer.JPEOptimizer.LOGGER.error("alloc writer failed", e);
        }
    }

    @Override
    public void close() {
        running = false;
        writer.interrupt();
    }
}