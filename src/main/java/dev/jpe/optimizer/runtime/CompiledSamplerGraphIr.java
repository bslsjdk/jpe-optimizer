package dev.jpe.optimizer.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Semantic classification of the compiled Minecraft sampler inventory.
 * This is diagnostic IR only: it does not execute or replace Minecraft samplers.
 */
public final class CompiledSamplerGraphIr {
    public enum Op {
        ADD,
        SUB,
        CONSTANT,
        MUL_ZERO_SHORT_CIRCUIT,
        DIV_ZERO_SHORT_CIRCUIT,
        MIN_RIGHT_BOUND,
        MAX_RIGHT_BOUND,
        CONST_ADD_RIGHT,
        CONST_SUB_LEFT,
        CONST_MUL_RIGHT,
        CONST_DIV_LEFT,
        CONST_MIN_RIGHT,
        CONST_MAX_RIGHT,
        CLAMP,
        CACHE_BOUNDARY,
        CONTEXT_BOUNDARY,
        OPAQUE
    }

    public record Node(int id, Op op, String sourceClass, List<Integer> children,
                       List<CompiledSamplerGraphTrace.ChildEdge> childEdges,
                       Map<String, String> scalarValues, String note) {
        public Node {
            children = List.copyOf(children);
            childEdges = List.copyOf(childEdges);
            scalarValues = Map.copyOf(scalarValues);
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(sourceClass, "sourceClass");
            Objects.requireNonNull(note, "note");
        }
        public Node(int id, Op op, String sourceClass, List<Integer> children,
                    Map<String, String> scalarValues, String note) {
            this(id, op, sourceClass, children, List.of(), scalarValues, note);
        }
    }

    public record Graph(List<Node> nodes, int rootId, long droppedNodes) {
        public Graph { nodes = List.copyOf(nodes); }
    }

    private CompiledSamplerGraphIr() {}

    public static Graph lower(CompiledSamplerGraphTrace.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ArrayList<Node> nodes = new ArrayList<>(snapshot.nodes().size());
        for (CompiledSamplerGraphTrace.NodeInfo source : snapshot.nodes()) {
            String name = source.implementation();
            Op op = classify(name);
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (CompiledSamplerGraphTrace.ComponentValue value : source.values()) {
                values.put(value.name(), value.value());
            }
            String note = switch (op) {
                case MUL_ZERO_SHORT_CIRCUIT, DIV_ZERO_SHORT_CIRCUIT ->
                        "Preserve left-first evaluation; left == +/-0 returns +0 without evaluating right";
                case MIN_RIGHT_BOUND -> "Volume semantics: replace left only when right < left; NaN and ties preserve left";
                case MAX_RIGHT_BOUND -> "Volume semantics: replace left only when right > left; NaN and ties preserve left";
                case CONST_ADD_RIGHT -> "Exact left + right-float operation; preserve the sampler's float32 constant";
                case CONST_SUB_LEFT -> "Exact left-float - right operation; preserve operand order and float32 constant";
                case CONST_MUL_RIGHT -> "Exact left * right-float operation; no generic binary zero shortcut";
                case CONST_DIV_LEFT ->
                        "Preserve operand order and Java float32 arithmetic; no generic zero shortcut";
                case CONSTANT -> "Exact float32 constant sampler; value remains a float scalar";
                case CLAMP -> "Minecraft Mth.clamp: value < min ? min : Math.min(value, max), preserving Java NaN and signed-zero semantics";
                case CACHE_BOUNDARY -> "Do not inline across Minecraft cache identity or volume lifetime";
                case CONTEXT_BOUNDARY -> "Resolve through SamplerContext; fallback is not an unconditional child";
                case OPAQUE -> source.limitation().isEmpty()
                        ? "No exact sampler lowering registered" : source.limitation();
                default -> "Compiled sampler operation identified; exact native execution still requires parity tests";
            };
            nodes.add(new Node(source.id(), op, name, source.children(), source.childEdges(), values, note));
        }
        return new Graph(nodes, snapshot.rootId(), snapshot.droppedNodes());
    }


    /**
     * Stable semantic digest for a complete pointer-free sampler inventory.
     * Child order is significant (subtraction and short-circuit operations),
     * while map insertion order and diagnostic prose are deliberately ignored.
     */
    public static String semanticFingerprint(Graph graph) {
        Objects.requireNonNull(graph, "graph");
        if (graph.droppedNodes() != 0) {
            throw new IllegalArgumentException("Cannot fingerprint an incomplete sampler graph");
        }
        Map<Integer, Node> byId = new java.util.HashMap<>();
        for (Node node : graph.nodes()) {
            if (byId.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate sampler node id " + node.id());
            }
        }
        if (!byId.containsKey(graph.rootId())) {
            throw new IllegalArgumentException("Sampler graph root is missing");
        }
        for (Node node : graph.nodes()) {
            for (int child : node.children()) {
                if (!byId.containsKey(child)) {
                    throw new IllegalArgumentException("Sampler graph has missing child " + child);
                }
            }
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(DensityGraphIr.VERSION);
                out.writeInt(graph.rootId());
                out.writeInt(graph.nodes().size());
                for (Node node : graph.nodes()) {
                    out.writeInt(node.id());
                    writeText(out, node.op().name());
                    writeText(out, node.sourceClass());
                    out.writeInt(node.children().size());
                    for (int child : node.children()) out.writeInt(child);
                    out.writeInt(node.childEdges().size());
                    for (CompiledSamplerGraphTrace.ChildEdge edge : node.childEdges()) {
                        writeText(out, edge.componentName());
                        out.writeInt(edge.childId());
                    }
                    TreeMap<String, String> values = new TreeMap<>(node.scalarValues());
                    out.writeInt(values.size());
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        writeText(out, entry.getKey());
                        writeText(out, entry.getValue());
                    }
                }
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 0xF, 16));
                result.append(Character.forDigit(value & 0xF, 16));
            }
            return result.toString();
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to encode sampler graph fingerprint", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static Op classify(String name) {
        return switch (name) {
            case "net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction$Sampler" -> Op.CONSTANT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$AddSampler" -> Op.ADD;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$SubSampler" -> Op.SUB;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MulSampler" ->
                    Op.MUL_ZERO_SHORT_CIRCUIT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$DivSampler" ->
                    Op.DIV_ZERO_SHORT_CIRCUIT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MinSampler" ->
                    Op.MIN_RIGHT_BOUND;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$MaxSampler" ->
                    Op.MAX_RIGHT_BOUND;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstAddSampler" ->
                    Op.CONST_ADD_RIGHT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstSubSampler" ->
                    Op.CONST_SUB_LEFT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstMulSampler" ->
                    Op.CONST_MUL_RIGHT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstDivSampler" ->
                    Op.CONST_DIV_LEFT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstMinSampler" ->
                    Op.CONST_MIN_RIGHT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction$ConstMaxSampler" ->
                    Op.CONST_MAX_RIGHT;
            case "net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction$Sampler" -> Op.CLAMP;
            case "net.minecraft.world.level.levelgen.densityfunction.CachingDensitySampler" ->
                    Op.CACHE_BOUNDARY;
            case "net.minecraft.world.level.levelgen.densityfunction.ContextBoundSampler" ->
                    Op.CONTEXT_BOUNDARY;
            default -> Op.OPAQUE;
        };
    }
}
