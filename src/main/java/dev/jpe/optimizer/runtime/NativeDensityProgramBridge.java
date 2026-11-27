package dev.jpe.optimizer.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

/**
 * Assembles a native {@code JpeGraphProgram} and invokes the payload compiler.
 *
 * <p>Deliberately free of Minecraft types: everything it needs arrives as
 * {@link DensityGraphProgram} bytes plus an optional noise-plan segment, so the
 * bridge can be compiled and reviewed without the game on the classpath.
 *
 * <p>The native side copies every table during compile, which is what makes the
 * temporary arena here safe to close on return. Nothing in this class retains
 * native memory past the call.
 */
public final class NativeDensityProgramBridge implements AutoCloseable {
    private static final long POINTER_BYTES = ValueLayout.ADDRESS.byteSize();

    private final MethodHandle compileProgram;
    private final Arena arena;

    private NativeDensityProgramBridge(MethodHandle compileProgram, Arena arena) {
        this.compileProgram = compileProgram;
        this.arena = arena;
    }

    /** Binds {@code jpe_runtime_density_graph_compile_program} from an already opened library. */
    public static NativeDensityProgramBridge bind(SymbolLookup lookup, Linker linker) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(linker, "linker");
        MethodHandle handle = linker.downcallHandle(
                lookup.find("jpe_runtime_density_graph_compile_program")
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing density graph program compile symbol")),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        return new NativeDensityProgramBridge(handle, Arena.ofShared());
    }

    /**
     * Compiles one graph plus its payload tables.
     *
     * @param nodes            caller-owned segment of {@code JpeDensityNode} records
     * @param noisePlans       caller-owned {@code JpeNoisePlan} array, or {@link MemorySegment#NULL}
     * @return a retained native handle, never zero
     */
    public long compile(MemorySegment nodes, int nodeCount, int rootNode,
                        long configurationFingerprint,
                        MemorySegment noisePlans, int noisePlanCount,
                        DensityGraphProgram program) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(program, "program");
        if (nodeCount <= 0 || nodeCount > DensityGraphIr.MAX_NODES) {
            throw new IllegalArgumentException("Node count must be in [1, " + DensityGraphIr.MAX_NODES + "]");
        }
        if (rootNode < 0 || rootNode >= nodeCount) {
            throw new IllegalArgumentException("Root node is outside the node array");
        }
        if (noisePlanCount < 0 || noisePlanCount > 64) {
            throw new IllegalArgumentException("Noise plan count must be in [0, 64]");
        }
        if (noisePlanCount > 0 && noisePlans == null) {
            throw new IllegalArgumentException("Noise plans are required when the count is positive");
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment splines = copy(scratch, program.splines(), 4);
            MemorySegment points = copy(scratch, program.splinePoints(), 4);
            MemorySegment selects = copy(scratch, program.intervalSelects(), 4);
            MemorySegment thresholds = copy(scratch, program.intervalThresholds(), 4);
            MemorySegment branches = copy(scratch, program.intervalBranches(), 4);
            MemorySegment distances = copy(scratch, program.distancePoints(), 4);
            MemorySegment permutation = copy(scratch, program.endIslandPermutation(), 1);
            MemorySegment blend = buildBlend(scratch, program);

            MemorySegment payload = scratch.allocate(DensityGraphProgram.GRAPH_PROGRAM_BYTES, 8);
            payload.fill((byte) 0);
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_NOISE_PLANS,
                    noisePlanCount > 0 ? noisePlans : MemorySegment.NULL);
            payload.set(ValueLayout.JAVA_INT, DensityGraphProgram.GRAPH_PROGRAM_NOISE_PLAN_COUNT,
                    noisePlanCount);
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_SPLINES, splines);
            payload.set(ValueLayout.JAVA_INT, DensityGraphProgram.GRAPH_PROGRAM_SPLINE_COUNT,
                    program.splineCount());
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_SPLINE_POINTS, points);
            payload.set(ValueLayout.JAVA_INT, DensityGraphProgram.GRAPH_PROGRAM_SPLINE_POINT_COUNT,
                    program.splinePointCount());
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_INTERVAL_SELECTS, selects);
            payload.set(ValueLayout.JAVA_INT, DensityGraphProgram.GRAPH_PROGRAM_INTERVAL_SELECT_COUNT,
                    program.intervalSelectCount());
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_INTERVAL_THRESHOLDS, thresholds);
            payload.set(ValueLayout.JAVA_INT, DensityGraphProgram.GRAPH_PROGRAM_INTERVAL_THRESHOLD_COUNT,
                    program.intervalThresholdCount());
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_INTERVAL_BRANCHES, branches);
            payload.set(ValueLayout.JAVA_INT, DensityGraphProgram.GRAPH_PROGRAM_INTERVAL_BRANCH_COUNT,
                    program.intervalBranchCount());
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_DISTANCE_POINTS, distances);
            payload.set(ValueLayout.JAVA_INT, DensityGraphProgram.GRAPH_PROGRAM_DISTANCE_POINT_COUNT,
                    program.distancePointCount());
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_BLEND, blend);
            writePointer(payload, DensityGraphProgram.GRAPH_PROGRAM_END_ISLAND, permutation);

            long handle;
            try {
                handle = (long) compileProgram.invokeExact(nodes, nodeCount, rootNode,
                        DensityGraphIr.VERSION, configurationFingerprint, payload);
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to compile native density program", failure);
            }
            if (handle == 0L) {
                throw new IllegalStateException("Native density program compiler rejected the payload");
            }
            return handle;
        }
    }

    private static MemorySegment copy(Arena arena, byte[] bytes, long alignment) {
        if (bytes.length == 0) return MemorySegment.NULL;
        MemorySegment segment = arena.allocate(bytes.length, alignment);
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return segment;
    }

    private static void writePointer(MemorySegment target, long offset, MemorySegment value) {
        target.set(ValueLayout.ADDRESS, offset, value);
    }

    private static MemorySegment buildBlend(Arena arena, DensityGraphProgram program) {
        DensityGraphIr.BlendRegion region = program.blend();
        if (!region.active()) return MemorySegment.NULL;
        byte[] header = program.blendInputBytes();
        MemorySegment blend = arena.allocate(DensityGraphProgram.BLEND_INPUT_BYTES, 8);
        MemorySegment.copy(header, 0, blend, ValueLayout.JAVA_BYTE, 0, header.length);

        MemorySegment fixed = copy(arena, DensityGraphProgram.encodeFloats(region.fixedDensity()), 4);
        MemorySegment density = copy(arena, DensityGraphProgram.encodeFloats(region.density()), 4);
        MemorySegment mask = copy(arena, region.densityMask(), 1);
        writePointer(blend, DensityGraphProgram.BLEND_INPUT_FIXED_DENSITY, fixed);
        writePointer(blend, DensityGraphProgram.BLEND_INPUT_DENSITY, density);
        writePointer(blend, DensityGraphProgram.BLEND_INPUT_DENSITY_MASK, mask);
        return blend;
    }

    @Override
    public void close() {
        arena.close();
    }
}
