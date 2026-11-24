package dev.jpe.optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.jpe.optimizer.worldgen.MaterialDensityBufferPoolScope;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Set;

/**
 * Establishes a chunk-local lease scope around surface-rule evaluation.
 * Captured density buffers remain valid for the whole rule loop and are
 * returned even if surface generation throws.
 */
@Mixin(MaterialSystem.class)
public abstract class MaterialSystemBufferPoolMixin {
    @WrapMethod(method = "buildSurface")
    private void jpe$withScopedMaterialBuffers(
            RandomState randomState,
            BiomeManager biomeManager,
            WorldGenerationContext generationContext,
            ChunkAccess protoChunk,
            NoiseChunk noiseChunk,
            MaterialRule ruleSource,
            @Nullable Set<Holder<Biome>> possibleBiomes,
            Operation<Void> original
    ) {
        if (!MaterialDensityBufferPoolScope.enabled()) {
            original.call(randomState, biomeManager, generationContext, protoChunk,
                    noiseChunk, ruleSource, possibleBiomes);
            return;
        }

        DensityBufferPool pool = ((NoiseChunkAccessor) (Object) noiseChunk).jpe$getBufferPool();
        try (MaterialDensityBufferPoolScope ignored = MaterialDensityBufferPoolScope.enter(pool)) {
            original.call(randomState, biomeManager, generationContext, protoChunk,
                    noiseChunk, ruleSource, possibleBiomes);
        }
    }
}
