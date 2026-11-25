package dev.jpe.optimizer.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

/** Opt-in, one-shot inventory of the real compiled final-density sampler graph. */
public final class CompiledSamplerGraphDiagnostics {
    private static final System.Logger LOGGER =
            System.getLogger(CompiledSamplerGraphDiagnostics.class.getName());
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();
    private static volatile CompiledSamplerGraphTrace.Snapshot lastSnapshot;

    private CompiledSamplerGraphDiagnostics() {}

    public static boolean enabled() {
        return Boolean.getBoolean("jpe.traceCompiledSamplers");
    }

    /**
     * Called at the final-density volume boundary. It only inspects record
     * components, does not evaluate samplers, and never changes worldgen output.
     */
    public static void captureOnce(DensitySampler sampler) {
        if (!enabled() || !CAPTURED.compareAndSet(false, true)) return;
        try {
            CompiledSamplerGraphTrace.Snapshot snapshot =
                    CompiledSamplerGraphTrace.capture(sampler, DensitySampler.class::isInstance, 65_536);
            lastSnapshot = snapshot;
            LOGGER.log(System.Logger.Level.INFO,
                    "Compiled sampler graph inventory: nodes={0}, dropped={1}, root={2}",
                    snapshot.nodes().size(), snapshot.droppedNodes(), snapshot.rootId());
            for (CompiledSamplerGraphTrace.NodeInfo node : snapshot.nodes()) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "sampler[{0}] {1} children={2} values={3} limitation={4}",
                        node.id(), node.implementation(), node.children(), node.values(), node.limitation());
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Compiled sampler graph inventory failed without affecting world generation: {0}",
                    failure.toString());
        }
    }

    public static CompiledSamplerGraphTrace.Snapshot lastSnapshot() {
        return lastSnapshot;
    }
}
