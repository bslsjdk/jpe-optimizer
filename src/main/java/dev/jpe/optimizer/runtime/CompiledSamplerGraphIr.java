package dev.jpe.optimizer.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Semantic classification of the compiled Minecraft sampler inventory.
 * This is diagnostic IR only: it does not execute or replace Minecraft samplers.
 */
public final class CompiledSamplerGraphIr {
    public enum Op {
        ADD,
        SUB,
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
        CACHE_BOUNDARY,
        CONTEXT_BOUNDARY,
        OPAQUE
    }

    public record Node(int id, Op op, String sourceClass, List<Integer> children,
                       Map<String, String> scalarValues, String note) {
        public Node {
            children = List.copyOf(children);
            scalarValues = Map.copyOf(scalarValues);
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(sourceClass, "sourceClass");
            Objects.requireNonNull(note, "note");
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
                case MIN_RIGHT_BOUND -> "Preserve rightMinValue guard and Java Math.min(float,float)";
                case MAX_RIGHT_BOUND -> "Preserve rightMaxValue guard and Java Math.max(float,float)";
                case CONST_MUL_RIGHT, CONST_DIV_LEFT ->
                        "Preserve operand order and Java float32 arithmetic; no generic zero shortcut";
                case CACHE_BOUNDARY -> "Do not inline across Minecraft cache identity or volume lifetime";
                case CONTEXT_BOUNDARY -> "Resolve through SamplerContext; fallback is not an unconditional child";
                case OPAQUE -> source.limitation().isEmpty()
                        ? "No exact sampler lowering registered" : source.limitation();
                default -> "Compiled sampler operation identified; exact native execution still requires parity tests";
            };
            nodes.add(new Node(source.id(), op, name, source.children(), values, note));
        }
        return new Graph(nodes, snapshot.rootId(), snapshot.droppedNodes());
    }

    private static Op classify(String name) {
        return switch (name) {
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
            case "net.minecraft.world.level.levelgen.densityfunction.CachingDensitySampler" ->
                    Op.CACHE_BOUNDARY;
            case "net.minecraft.world.level.levelgen.densityfunction.ContextBoundSampler" ->
                    Op.CONTEXT_BOUNDARY;
            default -> Op.OPAQUE;
        };
    }
}
