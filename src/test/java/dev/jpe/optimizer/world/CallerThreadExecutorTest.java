package dev.jpe.optimizer.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

/**
 * The caller-thread executor must run tasks inline on the submitting thread and still
 * behave like an executor (futures, failure propagation, shutdown). This is what lets the
 * presentation bridge keep every native call on Minecraft's render thread.
 */
class CallerThreadExecutorTest {

    @Test
    void runsInlineOnTheCallingThread() throws Exception {
        CallerThreadExecutor executor = new CallerThreadExecutor();
        Thread caller = Thread.currentThread();
        Future<Thread> f = executor.submit(Thread::currentThread);
        // A real thread pool would make this future incomplete until a worker ran; here it
        // is already done and the task observed the submitting thread.
        assertTrue(f.isDone());
        assertSame(caller, f.get());
    }

    @Test
    void capturesFailuresInTheFuture() {
        CallerThreadExecutor executor = new CallerThreadExecutor();
        Future<?> f = executor.submit(() -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(f.isDone());
        ExecutionException thrown = assertThrows(ExecutionException.class, f::get);
        assertTrue(thrown.getCause() instanceof IllegalStateException);
    }

    @Test
    void executeRunsSynchronously() {
        CallerThreadExecutor executor = new CallerThreadExecutor();
        int[] counter = {0};
        executor.execute(() -> counter[0]++);
        assertEquals(1, counter[0]);
    }

    @Test
    void rejectsAfterShutdown() {
        CallerThreadExecutor executor = new CallerThreadExecutor();
        assertFalse(executor.isShutdown());
        executor.shutdown();
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
    }
}