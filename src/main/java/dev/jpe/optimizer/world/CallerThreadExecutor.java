package dev.jpe.optimizer.world;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An {@link java.util.concurrent.ExecutorService} that runs every task inline on the
 * calling thread instead of handing it to a worker.
 *
 * <p>This is the render-thread ownership building block for the presentation path. The
 * native GL/GLES renderer is thread-affine and can only make its context current on, and
 * draw through, the thread that owns that context. Minecraft keeps its GL context current
 * on the main client/render thread, so a bridge that must present on screen has to run
 * every native call on that thread. Substituting this executor for the private engine
 * thread makes {@code submit(...).get(...)} execute synchronously on the caller, so the
 * existing bridge marshalling code is reused unchanged and all calls stay on the render
 * thread.
 *
 * <p>It is deliberately a plain {@link AbstractExecutorService}: {@code submit} wraps the
 * task in a {@link java.util.concurrent.FutureTask}, {@link #execute} runs it now, and the
 * returned future is already complete. A task that throws is captured in the future
 * exactly as a thread-pool executor would, so callers still see an
 * {@link java.util.concurrent.ExecutionException}.
 */
public final class CallerThreadExecutor extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
        if (shutdown) {
            throw new java.util.concurrent.RejectedExecutionException("executor is shut down");
        }
        command.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown;
    }
}