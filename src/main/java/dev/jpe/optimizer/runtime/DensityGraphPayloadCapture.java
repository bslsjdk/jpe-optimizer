package dev.jpe.optimizer.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the table side of a native density program.
 *
 * <p>Some node families keep their parameters outside the 24-byte hot node
 * record: cubic splines, interval selects, distance points, the blend region and
 * the end-island permutation. Those tables can only be flattened once every
 * referenced node has a final index, so adapters reserve a table slot while
 * walking the source graph and define it afterwards.
 *
 * <p>Deliberately Minecraft-free: it takes plain arrays and indices, which makes
 * the table layout testable without the game on the classpath.
 */
public final class DensityGraphPayloadCapture {
    private static final class SplineDraft {
        boolean defined;
        int kind;
        int coordinate;
        float constant;
        float[] locations = new float[0];
        float[] derivatives = new float[0];
        int[] children = new int[0];
    }

    private static final class IntervalDraft {
        boolean defined;
        int inputNode = DensityGraphIr.NO_INPUT;
        float[] thresholds = new float[0];
        int[] branches = new int[0];
    }

    private final ArrayList<SplineDraft> splines = new ArrayList<>();
    private final ArrayList<IntervalDraft> intervalSelects = new ArrayList<>();
    private final ArrayList<DensityGraphIr.DistancePoint> distancePoints = new ArrayList<>();
    private DensityGraphIr.BlendRegion blend = DensityGraphIr.BlendRegion.inactive();
    private byte[] endIslandPermutation;

    /** Reserves a spline table slot before its coordinate node has an index. */
    public int reserveSpline() {
        splines.add(new SplineDraft());
        return splines.size() - 1;
    }

    private SplineDraft spline(int slot) {
        if (slot < 0 || slot >= splines.size()) {
            throw new IllegalArgumentException("Unknown spline slot " + slot);
        }
        return splines.get(slot);
    }

    public void defineConstantSpline(int slot, float value) {
        SplineDraft draft = spline(slot);
        if (draft.defined) throw new IllegalStateException("Spline slot " + slot + " is already defined");
        draft.defined = true;
        draft.kind = DensityGraphIr.SPLINE_CONSTANT;
        draft.constant = value;
        draft.locations = new float[0];
        draft.derivatives = new float[0];
        draft.children = new int[0];
    }

    /**
     * Defines a multipoint spline. Locations must be strictly ascending; the
     * native evaluator relies on that order for its binary search.
     */
    public void defineMultipointSpline(int slot, int coordinateNode,
                                       float[] locations, float[] derivatives, int[] childSplines) {
        Objects.requireNonNull(locations, "locations");
        Objects.requireNonNull(derivatives, "derivatives");
        Objects.requireNonNull(childSplines, "childSplines");
        if (coordinateNode < 0) throw new IllegalArgumentException("Spline coordinate node must be non-negative");
        if (locations.length == 0) throw new IllegalArgumentException("Multipoint spline needs at least one point");
        if (derivatives.length != locations.length || childSplines.length != locations.length) {
            throw new IllegalArgumentException("Spline point arrays must have the same length");
        }
        for (int i = 1; i < locations.length; ++i) {
            if (!(locations[i - 1] < locations[i])) {
                throw new IllegalArgumentException("Spline locations must be strictly ascending at index " + i);
            }
        }
        for (int child : childSplines) {
            if (child < 0 || child >= splines.size()) {
                throw new IllegalArgumentException("Spline child " + child + " is outside the reserved table");
            }
        }
        SplineDraft draft = spline(slot);
        if (draft.defined) throw new IllegalStateException("Spline slot " + slot + " is already defined");
        draft.defined = true;
        draft.kind = DensityGraphIr.SPLINE_MULTIPOINT;
        draft.coordinate = coordinateNode;
        draft.locations = locations.clone();
        draft.derivatives = derivatives.clone();
        draft.children = childSplines.clone();
    }

    /** Convenience for adapters that already know the coordinate index. */
    public int addConstantSpline(float value) {
        int slot = reserveSpline();
        defineConstantSpline(slot, value);
        return slot;
    }

    public int addMultipointSpline(int coordinateNode, float[] locations,
                                   float[] derivatives, int[] childSplines) {
        int slot = reserveSpline();
        defineMultipointSpline(slot, coordinateNode, locations, derivatives, childSplines);
        return slot;
    }

    public int reserveIntervalSelect() {
        intervalSelects.add(new IntervalDraft());
        return intervalSelects.size() - 1;
    }

    /**
     * Defines an interval select. Thresholds are the interior boundaries, so the
     * branch table holds {@code thresholds.length + 1} entries.
     */
    public void defineIntervalSelect(int slot, int inputNode, float[] thresholds, int[] branches) {
        Objects.requireNonNull(thresholds, "thresholds");
        Objects.requireNonNull(branches, "branches");
        if (inputNode < 0) throw new IllegalArgumentException("Interval input node must be non-negative");
        if (branches.length != thresholds.length + 1) {
            throw new IllegalArgumentException("Interval select needs one more branch than threshold");
        }
        for (int i = 1; i < thresholds.length; ++i) {
            if (!(thresholds[i - 1] < thresholds[i])) {
                throw new IllegalArgumentException("Interval thresholds must be strictly ascending at index " + i);
            }
        }
        for (int branch : branches) {
            if (branch < 0) throw new IllegalArgumentException("Interval branch must be non-negative");
        }
        if (slot < 0 || slot >= intervalSelects.size()) {
            throw new IllegalArgumentException("Unknown interval select slot " + slot);
        }
        IntervalDraft draft = intervalSelects.get(slot);
        if (draft.defined) throw new IllegalStateException("Interval slot " + slot + " is already defined");
        draft.defined = true;
        draft.inputNode = inputNode;
        draft.thresholds = thresholds.clone();
        draft.branches = branches.clone();
    }

    public int addIntervalSelect(int inputNode, float[] thresholds, int[] branches) {
        int slot = reserveIntervalSelect();
        defineIntervalSelect(slot, inputNode, thresholds, branches);
        return slot;
    }

    public int addDistancePoint(int x, int y, int z, int metric) {
        distancePoints.add(new DensityGraphIr.DistancePoint(x, y, z, metric));
        return distancePoints.size() - 1;
    }

    public void blend(DensityGraphIr.BlendRegion region) {
        this.blend = Objects.requireNonNull(region, "region");
    }

    public void endIsland(byte[] permutation) {
        Objects.requireNonNull(permutation, "permutation");
        if (permutation.length != 256) {
            throw new IllegalArgumentException("End-island permutation must have 256 entries");
        }
        this.endIslandPermutation = permutation.clone();
    }

    public boolean isEmpty() {
        return splines.isEmpty() && intervalSelects.isEmpty() && distancePoints.isEmpty()
                && !blend.active() && endIslandPermutation == null;
    }

    /** Flattens every reserved table. A reserved but undefined slot is a bug. */
    public DensityGraphIr.Payload build() {
        ArrayList<DensityGraphIr.SplineNode> splineNodes = new ArrayList<>(splines.size());
        ArrayList<DensityGraphIr.SplinePoint> points = new ArrayList<>();
        for (int index = 0; index < splines.size(); ++index) {
            SplineDraft draft = splines.get(index);
            if (!draft.defined) throw new IllegalStateException("Spline slot " + index + " was never defined");
            if (draft.kind == DensityGraphIr.SPLINE_CONSTANT) {
                splineNodes.add(DensityGraphIr.SplineNode.constant(draft.constant));
                continue;
            }
            int firstPoint = points.size();
            for (int i = 0; i < draft.locations.length; ++i) {
                points.add(new DensityGraphIr.SplinePoint(
                        draft.locations[i], draft.derivatives[i], draft.children[i]));
            }
            splineNodes.add(DensityGraphIr.SplineNode.multipoint(
                    draft.coordinate, firstPoint, draft.locations.length));
        }

        ArrayList<DensityGraphIr.IntervalSelect> selects = new ArrayList<>(intervalSelects.size());
        ArrayList<Float> thresholds = new ArrayList<>();
        ArrayList<Integer> branches = new ArrayList<>();
        for (int index = 0; index < intervalSelects.size(); ++index) {
            IntervalDraft draft = intervalSelects.get(index);
            if (!draft.defined) throw new IllegalStateException("Interval slot " + index + " was never defined");
            int firstThreshold = thresholds.size();
            for (float threshold : draft.thresholds) thresholds.add(threshold);
            int firstBranch = branches.size();
            for (int branch : draft.branches) branches.add(branch);
            selects.add(new DensityGraphIr.IntervalSelect(
                    draft.inputNode, firstThreshold, draft.thresholds.length, firstBranch));
        }

        float[] thresholdArray = new float[thresholds.size()];
        for (int i = 0; i < thresholdArray.length; ++i) thresholdArray[i] = thresholds.get(i);
        int[] branchArray = new int[branches.size()];
        for (int i = 0; i < branchArray.length; ++i) branchArray[i] = branches.get(i);

        return new DensityGraphIr.Payload(splineNodes, points, selects, thresholdArray, branchArray,
                List.copyOf(distancePoints), blend, endIslandPermutation);
    }
}
