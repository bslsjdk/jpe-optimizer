package dev.jpe.optimizer.server;

import dev.jpe.optimizer.runtime.DensityGraphIr;
import dev.jpe.optimizer.runtime.DensityGraphPayloadCapture;
import dev.jpe.optimizer.runtime.NativeFinalDensityPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in graphs the server can compile without any client input.
 *
 * <p>Each one exercises a different part of the native program: table-backed
 * splines, unary chains, interval branching, distance metrics, and the
 * integer-clamped gradient. They exist so the service is useful the moment it
 * starts, not only after a caller posts a graph.
 */
public final class Scenarios {
    public record Scenario(String id, String name, String description,
                           DensityGraphIr graph, NativeFinalDensityPlan.Geometry geometry) {
    }

    private Scenarios() {}

    public static List<Scenario> all() {
        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new Scenario("terrain-spline", "Spline terrain column",
                "Y through a four-point cubic spline; exercises the spline payload table.",
                splineTerrain(), geometry(8, 32, 8, -4, -16, -4)));
        scenarios.add(new Scenario("ridge", "Ridge noise surrogate",
                "sqrt(|x|) + z * 0.25 - y / 64; a pure arithmetic/unary chain.",
                ridge(), geometry(16, 16, 16, -8, -8, -8)));
        scenarios.add(new Scenario("interval-bands", "Interval bands",
                "Interval select on Y with two thresholds and three branches.",
                intervalBands(), geometry(8, 24, 8, -4, -16, -4)));
        scenarios.add(new Scenario("distance-field", "Distance field",
                "1 / (1 + distance to the origin) using the Euclidean metric.",
                distanceField(), geometry(16, 16, 16, -8, -8, -8)));
        scenarios.add(new Scenario("clamped-gradient", "Clamped gradient",
                "Integer-clamped Y gradient with wrapped subtraction and float32 scale.",
                clampedGradient(), geometry(4, 32, 4, 0, -8, 0)));
        return scenarios;
    }

    public static Map<String, Object> describe(Scenario scenario) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", scenario.id());
        entry.put("name", scenario.name());
        entry.put("description", scenario.description());
        entry.put("nodes", scenario.graph().nodes().size());
        entry.put("root", scenario.graph().rootNode());
        entry.put("splines", scenario.graph().payload().splines().size());
        entry.put("intervalSelects", scenario.graph().payload().intervalSelects().size());
        entry.put("distancePoints", scenario.graph().payload().distancePoints().size());
        entry.put("geometry", geometryJson(scenario.geometry()));
        return entry;
    }

    public static Map<String, Object> geometryJson(NativeFinalDensityPlan.Geometry geometry) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("size", List.of(geometry.sizeX(), geometry.sizeY(), geometry.sizeZ()));
        entry.put("min", List.of(geometry.minX(), geometry.minY(), geometry.minZ()));
        entry.put("step", List.of(geometry.stepX(), geometry.stepY(), geometry.stepZ()));
        return entry;
    }

    private static NativeFinalDensityPlan.Geometry geometry(int sx, int sy, int sz, int mx, int my, int mz) {
        return new NativeFinalDensityPlan.Geometry(sx, sy, sz, mx, my, mz, 1, 1, 1);
    }

    private static DensityGraphIr splineTerrain() {
        DensityGraphPayloadCapture tables = new DensityGraphPayloadCapture();
        int root = tables.reserveSpline();
        int low = tables.reserveSpline();
        int mid = tables.reserveSpline();
        int high = tables.reserveSpline();
        int peak = tables.reserveSpline();
        tables.defineConstantSpline(low, 2.0f);
        tables.defineConstantSpline(mid, 0.5f);
        tables.defineConstantSpline(high, -1.5f);
        tables.defineConstantSpline(peak, 3.0f);
        tables.defineMultipointSpline(root, 0,
                new float[] {-16.0f, 0.0f, 16.0f, 32.0f},
                new float[] {0.0f, 0.0f, 0.0f, 0.0f},
                new int[] {low, mid, high, peak});
        return new DensityGraphIr(
                List.of(DensityGraphIr.Node.y(), DensityGraphIr.Node.spline(root)),
                List.of(), 1, 0x51_7E_11, tables.build());
    }

    private static DensityGraphIr ridge() {
        // sqrt(abs(x)) + z * 0.25 - y / 64
        List<DensityGraphIr.Node> nodes = new ArrayList<>();
        nodes.add(DensityGraphIr.Node.x());
        nodes.add(DensityGraphIr.Node.abs(0));
        nodes.add(DensityGraphIr.Node.sqrt(1));
        nodes.add(DensityGraphIr.Node.z());
        nodes.add(DensityGraphIr.Node.constant(0.25));
        nodes.add(DensityGraphIr.Node.multiply(3, 4));
        nodes.add(DensityGraphIr.Node.add(2, 5));
        nodes.add(DensityGraphIr.Node.y());
        nodes.add(DensityGraphIr.Node.constant(64.0));
        nodes.add(DensityGraphIr.Node.divide(7, 8));
        nodes.add(DensityGraphIr.Node.subtract(6, 9));
        return new DensityGraphIr(nodes, 10, 0x51_7E_12);
    }

    private static DensityGraphIr intervalBands() {
        DensityGraphPayloadCapture tables = new DensityGraphPayloadCapture();
        List<DensityGraphIr.Node> nodes = new ArrayList<>();
        nodes.add(DensityGraphIr.Node.y());
        nodes.add(DensityGraphIr.Node.constant(1.0));
        nodes.add(DensityGraphIr.Node.constant(0.0));
        nodes.add(DensityGraphIr.Node.constant(-1.0));
        int table = tables.addIntervalSelect(0,
                new float[] {-8.0f, 8.0f}, new int[] {3, 2, 1});
        nodes.add(DensityGraphIr.Node.intervalSelect(table));
        return new DensityGraphIr(nodes, List.of(), 4, 0x51_7E_13, tables.build());
    }

    private static DensityGraphIr distanceField() {
        DensityGraphPayloadCapture tables = new DensityGraphPayloadCapture();
        int point = tables.addDistancePoint(0, 0, 0, 0);
        List<DensityGraphIr.Node> nodes = new ArrayList<>();
        nodes.add(DensityGraphIr.Node.distanceToPoint(point));
        nodes.add(DensityGraphIr.Node.constant(1.0));
        nodes.add(DensityGraphIr.Node.add(0, 1));
        nodes.add(DensityGraphIr.Node.reciprocal(2));
        return new DensityGraphIr(nodes, List.of(), 3, 0x51_7E_14, tables.build());
    }

    private static DensityGraphIr clampedGradient() {
        List<DensityGraphIr.Node> nodes = new ArrayList<>();
        nodes.add(DensityGraphIr.Node.gradientClamped(1, -8, -4, 12, 0.5f, 2.0f));
        return new DensityGraphIr(nodes, 0, 0x51_7E_15);
    }
}
