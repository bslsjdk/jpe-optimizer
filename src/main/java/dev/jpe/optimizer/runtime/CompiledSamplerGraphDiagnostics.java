package dev.jpe.optimizer.runtime;

import dev.jpe.optimizer.JPEOptimizer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** Opt-in, one-shot inventory of the real compiled final-density sampler graph. */
public final class CompiledSamplerGraphDiagnostics {
    private static final System.Logger LOGGER =
            System.getLogger(CompiledSamplerGraphDiagnostics.class.getName());
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();
    private static final AtomicBoolean PARITY_ATTEMPTED = new AtomicBoolean();
    private static final boolean ENABLED = Boolean.getBoolean("jpe.traceCompiledSamplers");
    private static final boolean PARITY_ENABLED = Boolean.getBoolean("jpe.verifyCompiledSamplerParity");
    private static volatile CompiledSamplerGraphTrace.Snapshot lastSnapshot;
    private static volatile CompiledSamplerGraphIr.Graph lastGraph;
    private static volatile CompiledSamplerArithmeticIslands.Plan lastArithmeticPlan;
    private static volatile java.util.List<CompiledSamplerArithmeticLowering.LoweredIsland> lastLoweredIslands = java.util.List.of();

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
            java.util.ArrayList<CompiledSamplerArithmeticLowering.LoweredIsland> loweredIslands =
                    new java.util.ArrayList<>();
            for (CompiledSamplerArithmeticIslands.Island island : arithmeticPlan.islands()) {
                try {
                    // Fingerprint zero marks this as a diagnostic lowering preview only.
                    // World/settings/seed-specific identity must be supplied before execution.
                    loweredIslands.add(CompiledSamplerArithmeticLowering.lower(graph, island, 0L));
                } catch (IllegalArgumentException unsupported) {
                    LOGGER.log(System.Logger.Level.DEBUG,
                            "arithmetic-island[{0}] not lowerable yet: {1}", island.id(), unsupported.getMessage());
                }
            }
            lastLoweredIslands = java.util.List.copyOf(loweredIslands);
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
                    "Compiled sampler graph inventory: nodes={0}, dropped={1}, root={2}, ops={3}, opaqueClasses={4}, arithmeticIslands={5}, arithmeticNodes={6}, boundaryNodes={7}, lowerableIslands={8}",
                    snapshot.nodes().size(), snapshot.droppedNodes(), snapshot.rootId(), counts, opaqueClasses,
                    arithmeticPlan.islands().size(), arithmeticPlan.eligibleNodeCount(),
                    arithmeticPlan.boundaryNodeCount(), loweredIslands.size());
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

    /**
     * Runs one bounded, shadow-only parity check against a real compiled sampler
     * graph. It is deliberately separate from normal capture and disabled unless
     * jpe.verifyCompiledSamplerParity=true and a native library path is configured.
     */
    public static void verifyNativeParityOnce(DensitySampler.Bound bound, DensityVolume volume) {
        if (!PARITY_ENABLED || !PARITY_ATTEMPTED.compareAndSet(false, true)) return;
        try {
            NativeRuntime runtime = NativePerlinNoiseBackend.runtimeForDiagnostics();
            if (runtime == null) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Native sampler parity was requested but no native runtime could be loaded");
                return;
            }
            long captureStarted = System.nanoTime();
            CompiledSamplerGraphTrace.CapturedGraph captured =
                    CompiledSamplerGraphTrace.captureDetailed(
                            bound.sampler(), DensitySampler.class::isInstance, 65_536);
            long captureNanos = System.nanoTime() - captureStarted;
            long graphLowerStarted = System.nanoTime();
            CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(captured.snapshot());
            long graphLowerNanos = System.nanoTime() - graphLowerStarted;
            String semanticFingerprint = CompiledSamplerGraphIr.semanticFingerprint(graph);
            long islandPlanStarted = System.nanoTime();
            CompiledSamplerArithmeticIslands.Plan plan = CompiledSamplerArithmeticIslands.plan(graph);
            long islandPlanNanos = System.nanoTime() - islandPlanStarted;
            java.util.Map<Integer, CompiledSamplerGraphIr.Node> nodesById = new java.util.HashMap<>();
            for (CompiledSamplerGraphIr.Node node : graph.nodes()) nodesById.put(node.id(), node);
            java.util.List<CompiledSamplerArithmeticIslands.Island> candidates = plan.islands().stream()
                    .filter(island -> !island.outputNodeIds().isEmpty())
                    .filter(island -> island.nodeIds().stream().anyMatch(id ->
                            nodesById.get(id).op() != CompiledSamplerGraphIr.Op.CONSTANT))
                    .toList();
            EnumMap<CompiledSamplerGraphIr.Op, Integer> parityOpCounts =
                    new EnumMap<>(CompiledSamplerGraphIr.Op.class);
            for (CompiledSamplerGraphIr.Node node : graph.nodes()) {
                parityOpCounts.merge(node.op(), 1, Integer::sum);
            }
            LOGGER.log(System.Logger.Level.INFO,
                    "Native sampler parity graph: nodes={0}, ops={1}, islands={2}, eligibleNodes={3}, boundaryNodes={4}, candidateIslands={5}",
                    graph.nodes().size(), parityOpCounts, plan.islands().size(),
                    plan.eligibleNodeCount(), plan.boundaryNodeCount(), candidates.size());
            if (candidates.isEmpty()) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Native sampler parity found no eligible arithmetic islands; nodes={0}, boundaries={1}",
                        graph.nodes().size(), plan.boundaryNodeCount());
                return;
            }

            long fingerprint = Long.parseUnsignedLong(semanticFingerprint.substring(0, 16), 16);
            java.util.ArrayList<CompiledSamplerArithmeticIslands.Island> lowerable = new java.util.ArrayList<>();
            int failedIslands = 0;
            for (CompiledSamplerArithmeticIslands.Island candidate : candidates) {
                try {
                    CompiledSamplerArithmeticLowering.lower(graph, candidate, fingerprint);
                    lowerable.add(candidate);
                } catch (RuntimeException unsupported) {
                    failedIslands++;
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Native sampler parity island[{0}] is not lowerable; other islands remain eligible: {1}",
                            candidate.id(), unsupported.toString());
                }
            }
            if (lowerable.isEmpty()) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Native sampler parity found no lowerable arithmetic islands; candidates={0}", candidates.size());
                return;
            }

            CompiledSamplerExternalInputs sharedInputs = new CompiledSamplerExternalInputs(bound, volume);
            long compared = 0L;
            long mismatches = 0L;
            long outputFloats = 0L;
            int testedIslands = lowerable.size();
            try {
                long batchLowerStarted = System.nanoTime();
                CompiledSamplerArithmeticLowering.LoweredIsland batch =
                        CompiledSamplerArithmeticLowering.lowerAll(graph, lowerable, fingerprint);
                long batchLowerNanos = System.nanoTime() - batchLowerStarted;
                long nativeCompileStarted = System.nanoTime();
                try (NativeRuntime.NativeDensityGraph executable = runtime.compileDensityGraph(batch.graph())) {
                    long nativeCompileNanos = System.nanoTime() - nativeCompileStarted;
                    CompiledSamplerNativeParity.Report report = CompiledSamplerNativeParity.verify(
                            executable, captured, batch, bound, volume, sharedInputs, 0L,
                            volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                            volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                            volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ());
                    compared = report.comparedValues();
                    mismatches = report.mismatches();
                    outputFloats = Math.multiplyExact((long) report.outputCount(), report.samplesPerOutput());
                    JPEOptimizer.LOGGER.info(
                            "JPE_NATIVE_SAMPLER_PARITY_BATCH exact={} islands={} outputs={} samplesPerOutput={} compared={} mismatches={} graphNodes={} externalInputs={} captureMs={} graphLowerMs={} islandPlanMs={} batchLowerMs={} nativeCompileMs={} externalInputStageMs={} nativeEvaluateMs={} javaReferenceMs={} compareMs={} graphFingerprint={}",
                            report.exact(), testedIslands, report.outputCount(), report.samplesPerOutput(),
                            report.comparedValues(), report.mismatches(), batch.graph().nodes().size(),
                            batch.inputSourceNodeIds().size(), captureNanos / 1_000_000.0,
                            graphLowerNanos / 1_000_000.0, islandPlanNanos / 1_000_000.0,
                            batchLowerNanos / 1_000_000.0, nativeCompileNanos / 1_000_000.0,
                            report.externalInputNanos() / 1_000_000.0,
                            report.nativeEvaluateNanos() / 1_000_000.0,
                            report.javaReferenceNanos() / 1_000_000.0,
                            report.compareNanos() / 1_000_000.0, semanticFingerprint);
                }
            } catch (RuntimeException | LinkageError batchFailure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Batched Native sampler parity failed; retrying lowerable islands individually in shadow mode: {0}",
                        batchFailure.toString());
                testedIslands = 0;
                compared = 0L;
                mismatches = 0L;
                outputFloats = 0L;
                for (CompiledSamplerArithmeticIslands.Island candidate : lowerable) {
                    try {
                        CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                                CompiledSamplerArithmeticLowering.lower(graph, candidate, fingerprint);
                        try (NativeRuntime.NativeDensityGraph executable = runtime.compileDensityGraph(lowered.graph())) {
                            CompiledSamplerNativeParity.Report report = CompiledSamplerNativeParity.verify(
                                    executable, captured, lowered, bound, volume, sharedInputs, outputFloats,
                                    volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ());
                            outputFloats = Math.addExact(outputFloats,
                                    Math.multiplyExact((long) report.outputCount(), report.samplesPerOutput()));
                            compared = Math.addExact(compared, report.comparedValues());
                            mismatches = Math.addExact(mismatches, report.mismatches());
                            testedIslands++;
                            JPEOptimizer.LOGGER.info(
                                    "JPE_NATIVE_SAMPLER_PARITY_ISLAND id={} exact={} outputs={} samplesPerOutput={} compared={} mismatches={} islandNodes={} externalInputs={} graphFingerprint={}",
                                    candidate.id(), report.exact(), report.outputCount(), report.samplesPerOutput(),
                                    report.comparedValues(), report.mismatches(), candidate.nodeIds().size(),
                                    candidate.externalInputs().size(), semanticFingerprint);
                        }
                    } catch (RuntimeException | LinkageError islandFailure) {
                        failedIslands++;
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Native sampler parity island[{0}] failed in fallback shadow mode: {1}",
                                candidate.id(), islandFailure.toString());
                    }
                }
            }
            JPEOptimizer.LOGGER.info(
                    "JPE_NATIVE_SAMPLER_PARITY_SUMMARY testedIslands={} failedIslands={} compared={} mismatches={} sharedBoundarySamplers={} sharedInputFloats={} outputFloats={} graphFingerprint={}",
                    testedIslands, failedIslands, compared, mismatches, sharedInputs.distinctSamplerCount(),
                    sharedInputs.stagedFloatCount(), outputFloats, semanticFingerprint);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Native sampler parity failed in shadow mode; Java worldgen remains authoritative: {0}",
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

    public static java.util.List<CompiledSamplerArithmeticLowering.LoweredIsland> lastLoweredIslands() {
        return lastLoweredIslands;
    }
}
