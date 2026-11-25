package dev.jpe.optimizer.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompiledSamplerBatchCacheKeyTest {
    private static DensityGraphCache.Identity identity() {
        return new DensityGraphCache.Identity("26.3", "NeoForge-26.3", "native-build-A",
                "mods-A", "minecraft:overworld", 12L, 34L);
    }

    private static CompiledSamplerGraphIr.Graph graph() {
        return new CompiledSamplerGraphIr.Graph(List.of(
                new CompiledSamplerGraphIr.Node(0, CompiledSamplerGraphIr.Op.ADD,
                        "sampler.Add", List.of(1, 2), List.of(), Map.of(), ""),
                new CompiledSamplerGraphIr.Node(1, CompiledSamplerGraphIr.Op.OPAQUE,
                        "sampler.ExternalA", List.of(), List.of(), Map.of(), ""),
                new CompiledSamplerGraphIr.Node(2, CompiledSamplerGraphIr.Op.OPAQUE,
                        "sampler.ExternalB", List.of(), List.of(), Map.of(), "")
        ), 0, 0);
    }

    private static List<CompiledSamplerArithmeticIslands.Island> islands() {
        return List.of(new CompiledSamplerArithmeticIslands.Island(
                0, List.of(0), List.of(1, 2), List.of(0)));
    }

    @Test
    void sameIdentitySeedGraphAndBatchHaveSameKey() {
        String first = CompiledSamplerBatchCacheKey.create(identity(), graph(), islands(), 12648430L);
        String second = CompiledSamplerBatchCacheKey.create(identity(), graph(), islands(), 12648430L);
        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void seedChangesKeyUntilSeedDerivedValuesAreExternalized() {
        String first = CompiledSamplerBatchCacheKey.create(identity(), graph(), islands(), 12648430L);
        String otherSeed = CompiledSamplerBatchCacheKey.create(identity(), graph(), islands(), 12648431L);
        assertNotEquals(first, otherSeed);
    }

    @Test
    void dimensionAndModIdentityChangeKey() {
        var otherDimension = new DensityGraphCache.Identity("26.3", "NeoForge-26.3",
                "native-build-A", "mods-A", "minecraft:the_nether", 12L, 34L);
        var otherMods = new DensityGraphCache.Identity("26.3", "NeoForge-26.3",
                "native-build-A", "mods-B", "minecraft:overworld", 12L, 34L);
        String key = CompiledSamplerBatchCacheKey.create(identity(), graph(), islands(), 12648430L);
        assertNotEquals(key, CompiledSamplerBatchCacheKey.create(otherDimension, graph(), islands(), 12648430L));
        assertNotEquals(key, CompiledSamplerBatchCacheKey.create(otherMods, graph(), islands(), 12648430L));
    }

    @Test
    void outputOrderAndIslandMembershipArePartOfTheKey() {
        var twoOutputs = List.of(new CompiledSamplerArithmeticIslands.Island(
                0, List.of(0, 3), List.of(1, 2), List.of(0, 3)));
        var reordered = List.of(new CompiledSamplerArithmeticIslands.Island(
                0, List.of(0, 3), List.of(1, 2), List.of(3, 0)));
        String first = CompiledSamplerBatchCacheKey.create(identity(), graphWithSecondOutput(), twoOutputs, 12648430L);
        String second = CompiledSamplerBatchCacheKey.create(identity(), graphWithSecondOutput(), reordered, 12648430L);
        assertNotEquals(first, second);
    }

    private static CompiledSamplerGraphIr.Graph graphWithSecondOutput() {
        return new CompiledSamplerGraphIr.Graph(List.of(
                new CompiledSamplerGraphIr.Node(0, CompiledSamplerGraphIr.Op.ADD,
                        "sampler.Add", List.of(1, 2), List.of(), Map.of(), ""),
                new CompiledSamplerGraphIr.Node(1, CompiledSamplerGraphIr.Op.OPAQUE,
                        "sampler.ExternalA", List.of(), List.of(), Map.of(), ""),
                new CompiledSamplerGraphIr.Node(2, CompiledSamplerGraphIr.Op.OPAQUE,
                        "sampler.ExternalB", List.of(), List.of(), Map.of(), ""),
                new CompiledSamplerGraphIr.Node(3, CompiledSamplerGraphIr.Op.SUB,
                        "sampler.Sub", List.of(1, 2), List.of(), Map.of(), "")
        ), 0, 0);
    }

    @Test
    void overlappingIslandsAreRejected() {
        var overlap = List.of(
                new CompiledSamplerArithmeticIslands.Island(0, List.of(0), List.of(1, 2), List.of(0)),
                new CompiledSamplerArithmeticIslands.Island(1, List.of(0), List.of(1, 2), List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> CompiledSamplerBatchCacheKey.create(identity(), graph(), overlap, 12648430L));
    }
}
