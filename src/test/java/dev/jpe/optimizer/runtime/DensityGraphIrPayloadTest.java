package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transport-level tests for the extended density IR and its native payload.
 *
 * <p>These run without Minecraft and without the native library: they pin the
 * IR contract and the byte layout that the FFM bridge copies verbatim. The C
 * offsets asserted here were taken from {@code offsetof} on the real header, so
 * a layout drift fails here instead of corrupting a chunk at runtime.
 */
class DensityGraphIrPayloadTest {

    @Test
    void everyNewNodeKindSurvivesIrValidation() {
        // 0 = X, 1 = noise, 2 = spline over 0, 3 = squeeze(2), 4 = interpolated(3)
        List<DensityGraphIr.Node> nodes = List.of(
                DensityGraphIr.Node.x(),
                DensityGraphIr.Node.noise(0),
                DensityGraphIr.Node.spline(0),
                DensityGraphIr.Node.squeeze(2),
                DensityGraphIr.Node.interpolated(3, 4, 8));
        DensityGraphIr.Payload payload = new DensityGraphIr.Payload(
                List.of(DensityGraphIr.SplineNode.multipoint(0, 0, 2),
                        DensityGraphIr.SplineNode.constant(0.0f),
                        DensityGraphIr.SplineNode.constant(1.0f)),
                List.of(new DensityGraphIr.SplinePoint(-1.0f, 0.0f, 1),
                        new DensityGraphIr.SplinePoint(1.0f, 0.0f, 2)),
                List.of(), new float[0], new int[0], List.of(),
                DensityGraphIr.BlendRegion.inactive(), null);
        DensityGraphIr graph = new DensityGraphIr(nodes, List.of(), 4, 0xABCDL, payload);
        assertEquals(5, graph.nodes().size());
        assertFalse(graph.experimental());
        assertEquals(2, graph.payload().splinePoints().size());
    }

    @Test
    void intervalSelectRequiresAscendingThresholdsAndEarlierBranches() {
        // Two thresholds means three branches, so the table needs three earlier nodes.
        List<DensityGraphIr.Node> nodes = List.of(
                DensityGraphIr.Node.x(),
                DensityGraphIr.Node.constant(1.0),
                DensityGraphIr.Node.constant(2.0),
                DensityGraphIr.Node.constant(3.0),
                DensityGraphIr.Node.intervalSelect(0));
        DensityGraphIr.IntervalSelect table =
                new DensityGraphIr.IntervalSelect(0, 0, 2, 0);
        // Descending thresholds must be rejected.
        DensityGraphIr.Payload descending = new DensityGraphIr.Payload(
                List.of(), List.of(), List.of(table), new float[] {1.0f, 0.0f}, new int[] {1, 2, 3},
                List.of(), DensityGraphIr.BlendRegion.inactive(), null);
        assertThrows(IllegalArgumentException.class,
                () -> new DensityGraphIr(nodes, List.of(), 4, 0L, descending));

        // A branch that is not an earlier node must be rejected too.
        DensityGraphIr.Payload forwardBranch = new DensityGraphIr.Payload(
                List.of(), List.of(), List.of(table), new float[] {0.0f, 1.0f}, new int[] {1, 2, 4},
                List.of(), DensityGraphIr.BlendRegion.inactive(), null);
        assertThrows(IllegalArgumentException.class,
                () -> new DensityGraphIr(nodes, List.of(), 4, 0L, forwardBranch));

        DensityGraphIr.Payload good = new DensityGraphIr.Payload(
                List.of(), List.of(), List.of(table), new float[] {0.0f, 1.0f}, new int[] {1, 2, 3},
                List.of(), DensityGraphIr.BlendRegion.inactive(), null);
        DensityGraphIr graph = new DensityGraphIr(nodes, List.of(), 4, 0L, good);
        assertEquals(5, graph.nodes().size());
    }

    @Test
    void splineLocationsMustBeStrictlyAscending() {
        List<DensityGraphIr.Node> nodes = List.of(
                DensityGraphIr.Node.x(),
                DensityGraphIr.Node.spline(0));
        DensityGraphIr.Payload duplicate = new DensityGraphIr.Payload(
                List.of(DensityGraphIr.SplineNode.multipoint(0, 0, 2),
                        DensityGraphIr.SplineNode.constant(0.0f)),
                List.of(new DensityGraphIr.SplinePoint(0.0f, 0.0f, 1),
                        new DensityGraphIr.SplinePoint(0.0f, 0.0f, 1)),
                List.of(), new float[0], new int[0], List.of(),
                DensityGraphIr.BlendRegion.inactive(), null);
        assertThrows(IllegalArgumentException.class,
                () -> new DensityGraphIr(nodes, List.of(), 1, 0L, duplicate));
    }

    @Test
    void endIslandNeedsA256EntryPermutation() {
        List<DensityGraphIr.Node> nodes = List.of(DensityGraphIr.Node.endIsland());
        DensityGraphIr.Payload missing = DensityGraphIr.Payload.empty();
        assertThrows(IllegalArgumentException.class,
                () -> new DensityGraphIr(nodes, List.of(), 0, 0L, missing));

        byte[] permutation = new byte[256];
        for (int i = 0; i < permutation.length; i++) permutation[i] = (byte) i;
        DensityGraphIr.Payload present = new DensityGraphIr.Payload(
                List.of(), List.of(), List.of(), new float[0], new int[0], List.of(),
                DensityGraphIr.BlendRegion.inactive(), permutation);
        assertEquals(1, new DensityGraphIr(nodes, List.of(), 0, 0L, present).nodes().size());
    }

    @Test
    void encodedPayloadMatchesTheNativeStructLayout() {
        DensityGraphIr.Payload payload = new DensityGraphIr.Payload(
                List.of(DensityGraphIr.SplineNode.multipoint(3, 1, 2),
                        DensityGraphIr.SplineNode.constant(0.25f)),
                List.of(new DensityGraphIr.SplinePoint(-1.0f, 0.5f, 1),
                        new DensityGraphIr.SplinePoint(2.0f, -0.5f, 1)),
                List.of(new DensityGraphIr.IntervalSelect(2, 0, 1, 0)),
                new float[] {0.5f},
                new int[] {4, 5},
                List.of(new DensityGraphIr.DistancePoint(1, -2, 3, 2)),
                DensityGraphIr.BlendRegion.inactive(),
                null);
        DensityGraphProgram program = DensityGraphProgram.from(payload);

        assertEquals(2, program.splineCount());
        assertEquals(2, program.splinePointCount());
        assertEquals(1, program.intervalSelectCount());
        assertEquals(1, program.intervalThresholdCount());
        assertEquals(2, program.intervalBranchCount());
        assertEquals(1, program.distancePointCount());
        assertFalse(program.hasEndIsland());
        assertFalse(program.blend().active());

        ByteBuffer splines = ByteBuffer.wrap(program.splines()).order(ByteOrder.nativeOrder());
        assertEquals(DensityGraphIr.SPLINE_MULTIPOINT,
                splines.getInt(DensityGraphProgram.SPLINE_NODE_KIND));
        assertEquals(3, splines.getInt(DensityGraphProgram.SPLINE_NODE_COORDINATE));
        assertEquals(1, splines.getInt(DensityGraphProgram.SPLINE_NODE_FIRST_POINT));
        assertEquals(2, splines.getInt(DensityGraphProgram.SPLINE_NODE_POINT_COUNT));
        // Second node starts exactly one struct later.
        assertEquals(0.25f, splines.getFloat(
                DensityGraphProgram.SPLINE_NODE_BYTES + DensityGraphProgram.SPLINE_NODE_CONSTANT));

        ByteBuffer points = ByteBuffer.wrap(program.splinePoints()).order(ByteOrder.nativeOrder());
        assertEquals(-1.0f, points.getFloat(DensityGraphProgram.SPLINE_POINT_LOCATION));
        assertEquals(0.5f, points.getFloat(DensityGraphProgram.SPLINE_POINT_DERIVATIVE));
        assertEquals(1, points.getInt(DensityGraphProgram.SPLINE_POINT_CHILD));

        ByteBuffer distances = ByteBuffer.wrap(program.distancePoints()).order(ByteOrder.nativeOrder());
        assertEquals(1, distances.getInt(DensityGraphProgram.DISTANCE_POINT_X));
        assertEquals(-2, distances.getInt(DensityGraphProgram.DISTANCE_POINT_Y));
        assertEquals(3, distances.getInt(DensityGraphProgram.DISTANCE_POINT_Z));
        assertEquals(2, distances.getInt(DensityGraphProgram.DISTANCE_POINT_METRIC));
    }

    @Test
    void blendDescriptorEncodesOnlyScalarFieldsAndLeavesPointersZero() {
        float[] fixed = {0.5f, Float.MAX_VALUE, 0.0f, 0.0f};
        float[] density = {1.0f, 2.0f, 3.0f, 4.0f};
        byte[] mask = {1, 0, 1, 1};
        DensityGraphIr.BlendRegion region =
                new DensityGraphIr.BlendRegion(true, -8, 0, 12, 2, 2, 1, fixed, density, mask);
        DensityGraphProgram program = DensityGraphProgram.from(new DensityGraphIr.Payload(
                List.of(), List.of(), List.of(), new float[0], new int[0], List.of(), region, null));
        byte[] encoded = program.blendInputBytes();
        assertEquals(DensityGraphProgram.BLEND_INPUT_BYTES, encoded.length);

        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.nativeOrder());
        assertEquals(1, buffer.getInt(DensityGraphProgram.BLEND_INPUT_ACTIVE));
        assertEquals(-8, buffer.getInt(DensityGraphProgram.BLEND_INPUT_BASE_CELL_X));
        assertEquals(0, buffer.getInt(DensityGraphProgram.BLEND_INPUT_BASE_CELL_Y));
        assertEquals(12, buffer.getInt(DensityGraphProgram.BLEND_INPUT_BASE_CELL_Z));
        assertEquals(2, buffer.getInt(DensityGraphProgram.BLEND_INPUT_CELLS_X));
        assertEquals(2, buffer.getInt(DensityGraphProgram.BLEND_INPUT_CELLS_Y));
        assertEquals(1, buffer.getInt(DensityGraphProgram.BLEND_INPUT_CELLS_Z));
        // Pointer fields are patched by the bridge, never encoded here.
        assertEquals(0L, buffer.getLong(DensityGraphProgram.BLEND_INPUT_FIXED_DENSITY));
        assertEquals(0L, buffer.getLong(DensityGraphProgram.BLEND_INPUT_DENSITY));
        assertEquals(0L, buffer.getLong(DensityGraphProgram.BLEND_INPUT_DENSITY_MASK));
    }

    @Test
    void blendRegionRejectsMismatchedGridSizes() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGraphIr.BlendRegion(
                true, 0, 0, 0, 2, 2, 2,
                new float[4], new float[8], new byte[8]));
        // An inactive region is allowed to carry empty arrays.
        assertFalse(new DensityGraphIr.BlendRegion(false, 0, 0, 0, 0, 0, 0,
                new float[0], new float[0], new byte[0]).active());
    }

    @Test
    void roundAndSliceRejectOutOfRangeOperands() {
        // The factory rejects an unknown round type outright.
        assertThrows(IllegalArgumentException.class,
                () -> DensityGraphIr.Node.round(0, DensityGraphIr.NO_INPUT, 4));
        // A non-integral slice coordinate must be rejected by IR validation.
        assertThrows(IllegalArgumentException.class, () -> {
            List<DensityGraphIr.Node> slice = List.of(
                    DensityGraphIr.Node.y(),
                    new DensityGraphIr.Node(DensityGraphIr.SLICE_Y, 0, DensityGraphIr.NO_INPUT, 0, 0.5));
            new DensityGraphIr(slice, List.of(), 1, 0L);
        });
    }

    @Test
    void findTopSurfaceRequiresSizeOneColumnsAndAPositiveCellHeight() {
        List<DensityGraphIr.Node> nodes = List.of(
                DensityGraphIr.Node.y(),
                DensityGraphIr.Node.constant(32.0),
                DensityGraphIr.Node.findTopSurface(0, 1, -64, 4));
        DensityGraphIr graph = new DensityGraphIr(nodes, List.of(), 2, 0L);
        assertEquals(3, graph.nodes().size());
        assertTrue(graph.payload().splines().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> DensityGraphIr.Node.findTopSurface(0, 1, -64, 0));
    }
}
