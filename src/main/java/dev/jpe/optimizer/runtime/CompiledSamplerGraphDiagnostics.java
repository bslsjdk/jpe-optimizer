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
            CompiledSamplerGraphTrace.CapturedGraph captured =
                    CompiledSamplerGraphTrace.captureDetailed(
                            bound.sampler(), DensitySampler.class::isInstance, 65_536);
            CompiledSamplerGraphIr.Graph graph = CompiledSamplerGraphIr.lower(captured.snapshot());
            String semanticFingerprint = CompiledSamplerGraphIr.semanticFingerprint(graph);
            CompiledSamplerArithmeticIslands.Plan plan = CompiledSamplerArithmeticIslands.plan(graph);
            java.util.Map<Integer, CompiledSamplerGraphIr.Node> nodesById = new java.util.HashMap<>();
            for (CompiledSamplerGraphIr.Node node : graph.nodes()) nodesById.put(node.id(), node);
            CompiledSamplerArithmeticIslands.Island selected = plan.islands().stream()
                    .filter(island -> !island.outputNodeIds().isEmpty())
                    .filter(island -> island.nodeIds().stream().anyMatch(id -> {
                        CompiledSamplerGraphIr.Op op = nodesById.get(id).op();
                        return op != CompiledSamplerGraphIr.Op.CONSTANT;
                    }))
                    .max(java.util.Comparator.comparingInt(island -> island.nodeIds().size()))
                    .orElse(null);
            EnumMap<CompiledSamplerGraphIr.Op, Integer> parityOpCounts =
                    new EnumMap<>(CompiledSamplerGraphIr.Op.class);
            for (CompiledSamplerGraphIr.Node node : graph.nodes()) {
                parityOpCounts.merge(node.op(), 1, Integer::sum);
            }
            LOGGER.log(System.Logger.Level.INFO,
                    "Native sampler parity graph: nodes={0}, ops={1}, islands={2}, eligibleNodes={3}, boundaryNodes={4}, selectedIsland={5}",
                    graph.nodes().size(), parityOpCounts, plan.islands().size(),
                    plan.eligibleNodeCount(), plan.boundaryNodeCount(),
                    selected == null ? "none" : selected.id());
            if (selected != null) {
                java.util.ArrayList<String> selectedOps = new java.util.ArrayList<>();
                for (int id : selected.nodeIds()) {
                    CompiledSamplerGraphIr.Node node = nodesById.get(id);
                    selectedOps.add(node.op() + ":" + node.sourceClass());
                }
                LOGGER.log(System.Logger.Level.INFO,
                        "Native sampler parity island[{0}]: nodes={1}, inputs={2}, outputs={3}, operations={4}",
                        selected.id(), selected.nodeIds().size(), selected.externalInputs().size(),
                        selected.outputNodeIds().size(), selectedOps);
            }
            if (selected == null) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Native sampler parity found no eligible arithmetic island; nodes={0}, boundaries={1}",
                        graph.nodes().size(), plan.boundaryNodeCount());
                return;
            }

            long fingerprint = Long.parseUnsignedLong(semanticFingerprint.substring(0, 16), 16);
            CompiledSamplerArithmeticLowering.LoweredIsland lowered =
                    CompiledSamplerArithmeticLowering.lower(graph, selected, fingerprint);
            try (NativeRuntime.NativeDensityGraph executable = runtime.compileDensityGraph(lowered.graph())) {
                CompiledSamplerNativeParity.Report report = CompiledSamplerNativeParity.verify(
                        executable, captured, lowered, bound, volume,
                        volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                        volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                        volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ());
                JPEOptimizer.LOGGER.info(
                        "JPE_NATIVE_SAMPLER_PARITY exact={} outputs={} samplesPerOutput={} compared={} mismatches={} firstOutput={} firstSample={} expectedBits=0x{} actualBits=0x{} islandNodes={} graphFingerprint={}",
                        report.exact(), report.outputCount(), report.samplesPerOutput(),
                        report.comparedValues(), report.mismatches(), report.firstOutputIndex(),
                        report.firstSampleIndex(), Integer.toHexString(report.expectedBits()),
                        Integer.toHexString(report.actualBits()), selected.nodeIds().size(),
                        semanticFingerprint);
            }
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
