package dev.jpe.optimizer.server;

import dev.jpe.optimizer.runtime.DensityGraphIr;
import dev.jpe.optimizer.runtime.NativeFinalDensityPlan;
import java.util.Arrays;
import java.util.List;

/**
 * Scalar Java evaluation of a density IR, used by the server as the parity
 * reference.
 *
 * <p>It follows the same float32 order as the native evaluator: every operand is
 * narrowed to float before use, {@code sqrt} goes through double, and the spline
 * uses the same Hermite form. It is a reference for the node families the server
 * exposes, not a replacement for the full 26.3 sampler semantics.
 */
public final class JavaDensityReference {
    private final DensityGraphIr graph;
    private final float[] values;
    private final boolean[] computed;
    private float x;
    private float y;
    private float z;

    private JavaDensityReference(DensityGraphIr graph) {
        this.graph = graph;
        this.values = new float[graph.nodes().size()];
        this.computed = new boolean[graph.nodes().size()];
    }

    public static float[] evaluate(DensityGraphIr graph, NativeFinalDensityPlan.Geometry geometry) {
        JavaDensityReference reference = new JavaDensityReference(graph);
        float[] output = new float[geometry.sampleCount()];
        int index = 0;
        for (int iz = 0; iz < geometry.sizeZ(); ++iz) {
            for (int ix = 0; ix < geometry.sizeX(); ++ix) {
                for (int iy = 0; iy < geometry.sizeY(); ++iy) {
                    reference.x = geometry.minX() + (float) ((long) ix * geometry.stepX());
                    reference.y = geometry.minY() + (float) ((long) iy * geometry.stepY());
                    reference.z = geometry.minZ() + (float) ((long) iz * geometry.stepZ());
                    reference.reset();
                    output[index++] = reference.node(graph.rootNode());
                }
            }
        }
        return output;
    }

    private void reset() {
        Arrays.fill(computed, false);
    }

    private float node(int index) {
        if (computed[index]) return values[index];
        DensityGraphIr.Node node = graph.nodes().get(index);
        float result = switch (node.opcode()) {
            case DensityGraphIr.CONST -> (float) node.immediate();
            case DensityGraphIr.X -> x;
            case DensityGraphIr.Y -> y;
            case DensityGraphIr.Z -> z;
            case DensityGraphIr.ADD -> node(node.inputA()) + node(node.inputB());
            case DensityGraphIr.SUB -> node(node.inputA()) - node(node.inputB());
            case DensityGraphIr.MUL -> node(node.inputA()) * node(node.inputB());
            case DensityGraphIr.DIV -> node(node.inputA()) / node(node.inputB());
            case DensityGraphIr.MIN -> {
                float left = node(node.inputA());
                float right = node(node.inputB());
                yield right < left ? right : left;
            }
            case DensityGraphIr.MAX -> {
                float left = node(node.inputA());
                float right = node(node.inputB());
                yield right > left ? right : left;
            }
            case DensityGraphIr.CLAMP -> clamp(node(node.inputA()),
                    Float.intBitsToFloat((int) (Double.doubleToRawLongBits(node.immediate()) >>> 32)),
                    Float.intBitsToFloat((int) Double.doubleToRawLongBits(node.immediate())));
            case DensityGraphIr.NEGATE -> -node(node.inputA());
            case DensityGraphIr.ABS -> Math.abs(node(node.inputA()));
            case DensityGraphIr.SQUARE -> {
                float v = node(node.inputA());
                yield v * v;
            }
            case DensityGraphIr.CUBE -> {
                float v = node(node.inputA());
                yield v * v * v;
            }
            case DensityGraphIr.SQRT -> (float) Math.sqrt(node(node.inputA()));
            case DensityGraphIr.RECIPROCAL -> 1.0f / node(node.inputA());
            case DensityGraphIr.SIGN -> Math.signum(node(node.inputA()));
            case DensityGraphIr.SQUEEZE -> squeeze(node(node.inputA()));
            case DensityGraphIr.LEAKY_RELU -> {
                float v = node(node.inputA());
                yield v > 0.0f ? v : (float) node.immediate() * v;
            }
            case DensityGraphIr.LERP -> {
                float alpha = node(node.inputA());
                float first = node(node.inputB());
                float second = node(node.reserved());
                yield first + alpha * (second - first);
            }
            case DensityGraphIr.GRADIENT_CLAMPED_X -> gradient(node, 0);
            case DensityGraphIr.GRADIENT_CLAMPED_Y -> gradient(node, 1);
            case DensityGraphIr.GRADIENT_CLAMPED_Z -> gradient(node, 2);
            case DensityGraphIr.SPLINE -> spline(node.inputA(), 0);
            case DensityGraphIr.INTERVAL_SELECT -> interval(node.inputA());
            case DensityGraphIr.DISTANCE_TO_POINT -> distance(node.inputA());
            default -> throw new UnsupportedOperationException(
                    "Java reference does not implement opcode " + node.opcode());
        };
        values[index] = result;
        computed[index] = true;
        return result;
    }

    private float gradient(DensityGraphIr.Node node, int axis) {
        int coordinate = (int) (axis == 0 ? x : axis == 1 ? y : z);
        int clamped = Math.max(node.inputB(), Math.min(node.reserved(), coordinate));
        int delta = clamped - node.inputA();
        long bits = Double.doubleToRawLongBits(node.immediate());
        float fromValue = Float.intBitsToFloat((int) (bits >>> 32));
        float factor = Float.intBitsToFloat((int) bits);
        return fromValue + (float) delta * factor;
    }

    private float interval(int tableIndex) {
        DensityGraphIr.IntervalSelect table = graph.payload().intervalSelects().get(tableIndex);
        float input = node(table.inputNode());
        float[] thresholds = graph.payload().intervalThresholds();
        int[] branches = graph.payload().intervalBranches();
        int chosen = table.thresholdCount();
        for (int k = 0; k < table.thresholdCount(); ++k) {
            if (input < thresholds[table.firstThreshold() + k]) {
                chosen = k;
                break;
            }
        }
        return node(branches[table.firstBranch() + chosen]);
    }

    private float distance(int pointIndex) {
        DensityGraphIr.DistancePoint point = graph.payload().distancePoints().get(pointIndex);
        float dx = x - point.x();
        float dy = y - point.y();
        float dz = z - point.z();
        return switch (point.metric()) {
            case 0 -> (float) Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            case 1 -> dx * dx + dy * dy + dz * dz;
            case 2 -> Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            default -> Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        };
    }

    private float spline(int index, int depthGuard) {
        if (depthGuard > 128) throw new IllegalStateException("Spline tree is too deep");
        List<DensityGraphIr.SplineNode> splines = graph.payload().splines();
        DensityGraphIr.SplineNode node = splines.get(index);
        if (node.kind() == DensityGraphIr.SPLINE_CONSTANT) return node.constant();
        List<DensityGraphIr.SplinePoint> points = graph.payload().splinePoints();
        int count = node.pointCount();
        if (count == 0) return Float.NaN;
        float input = node(node.coordinate());
        int start = -1;
        for (int k = 0; k < count; ++k) {
            if (input < points.get(node.firstPoint() + k).location()) {
                start = k - 1;
                break;
            }
        }
        if (start < 0) start = -1;
        int last = count - 1;
        if (start < 0 || start == last) {
            int edge = start < 0 ? 0 : last;
            DensityGraphIr.SplinePoint point = points.get(node.firstPoint() + edge);
            float value = spline(point.child(), depthGuard + 1);
            float derivative = point.derivative();
            return derivative == 0.0f ? value : value + derivative * (input - point.location());
        }
        DensityGraphIr.SplinePoint first = points.get(node.firstPoint() + start);
        DensityGraphIr.SplinePoint second = points.get(node.firstPoint() + start + 1);
        float span = second.location() - first.location();
        float t = (input - first.location()) / span;
        float y1 = spline(first.child(), depthGuard + 1);
        float y2 = spline(second.child(), depthGuard + 1);
        float a = first.derivative() * span - (y2 - y1);
        float b = -second.derivative() * span + (y2 - y1);
        return lerp(t, y1, y2) + t * (1.0f - t) * lerp(t, a, b);
    }

    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float squeeze(float value) {
        float clamped = clamp(value, -1.0f, 1.0f);
        return clamped / 2.0f - clamped * clamped * clamped / 24.0f;
    }
}
