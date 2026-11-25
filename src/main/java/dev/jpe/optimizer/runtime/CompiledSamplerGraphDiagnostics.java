package dev.jpe.optimizer.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

/** Opt-in, one-shot inventory of the real compiled final-density sampler graph. */
public final class CompiledSamplerGraphDiagnostics {
    private static final System.Logger LOGGER =
            System.getLogger(CompiledSamplerGraphDiagnostics.class.getName());
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();
    private static final boolean ENABLED = Boolean.getBoolean("jpe.traceCompiledSamplers");
    private static volatile CompiledSamplerGraphTrace.Snapshot lastSnapshot;
    private static volatile CompiledSamplerGraphIr.Graph lastGraph;
    private static volatile CompiledSamplerArithmeticIslands.Plan lastArithmeticPlan;

    private CompiledSamplerGraphDiagnostics() {}

    public static boolean enabled() {
        return ENABLED;
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
            CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(snapshot);
            lastGraph = graph;
            CompiledSamplerArithmeticIslands.Plan arithmeticPlan =
                    CompiledSamplerArithmeticIslands.plan(graph);
            lastArithmeticPlan = arithmeticPlan;
            EnumMap<CompiledSamplerGraphIr.Op, Integer> counts =
                    new EnumMap<>(CompiledSamplerGraphIr.Op.class);
            java.util.TreeMap<String, Integer> opaqueClasses = new java.util.TreeMap<>();
            for (CompiledSamplerGraphIr.Node node : graph.nodes()) {
                counts.merge(node.op(), 1, Integer::sum);
                if (node.op() == CompiledSamplerGraphIr.Op.OPAQUE) {
                    opaqueClasses.merge(node.sourceClass(), 1, Integer::sum);
                }
            }
            LOGGER.log(System.Logger.Level.INFO,
                    "Compiled sampler graph inventory: nodes={0}, dropped={1}, root={2}, ops={3}, opaqueClasses={4}, arithmeticIslands={5}, arithmeticNodes={6}, boundaryNodes={7}",
                    snapshot.nodes().size(), snapshot.droppedNodes(), snapshot.rootId(), counts, opaqueClasses,
                    arithmeticPlan.islands().size(), arithmeticPlan.eligibleNodeCount(),
                    arithmeticPlan.boundaryNodeCount());
            for (CompiledSamplerArithmeticIslands.Island island : arithmeticPlan.islands()) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "arithmetic-island[{0}] nodes={1} inputs={2} outputs={3}",
                        island.id(), island.nodeIds(), island.externalInputs(), island.outputNodeIds());
            }
            for (CompiledSamplerGraphIr.Node node : graph.nodes()) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "sampler[{0}] op={1} source={2} children={3} edges={4} values={5} note={6}",
                        node.id(), node.op(), node.sourceClass(), node.children(), node.childEdges(),
                        node.scalarValues(), node.note());
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

    public static CompiledSamplerGraphIr.Graph lastGraph() {
        return lastGraph;
    }

    public static CompiledSamplerArithmeticIslands.Plan lastArithmeticPlan() {
        return lastArithmeticPlan;
    }
}
