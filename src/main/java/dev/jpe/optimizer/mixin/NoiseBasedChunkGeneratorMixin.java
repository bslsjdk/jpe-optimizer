package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.DensityVolumeIndex;
import dev.jpe.optimizer.worldgen.SectionCachePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Rewrites the vanilla block-fill loop while preserving its density, aquifer,
 * debug-surface, heightmap, and post-processing operations.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;
    @Shadow @Final private static BlockState AIR;
    @Shadow
    private BlockState debugPreliminarySurfaceLevel(
            NoiseChunk noiseChunk, int posX, int posY, int posZ, BlockState state) {
        throw new AssertionError("Shadow method should be replaced by Mixin");
    }

    /**
     * Caches the current section and walks the density buffer with a descending
     * linear index. Y is the innermost contiguous axis of DensityVolume.
     */
    @Overwrite
    private void doFill(NoiseChunk noiseChunk, ChunkAccess chunk) {
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Aquifer aquifer = noiseChunk.aquifer();
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        DensityVolume volume = noiseChunk.volume();

        NoiseGeneratorSettings generatorSettings = this.settings.value();
        BlockState defaultBlock = generatorSettings.defaultBlock();
        DensitySampler.Bound finalDensity = noiseChunk.cachingSamplers()
                .get(generatorSettings.noiseRouter().finalDensity());

        try (ScopedDensityBuffer densityBuffer = finalDensity.sampleVolume(volume)) {
            for (int z = 0; z < volume.sizeZ(); ++z) {
                int blockZ = volume.blockZ(z);
                for (int x = 0; x < volume.sizeX(); ++x) {
                    int blockX = volume.blockX(x);
                    LevelChunkSection section = null;
                    int densityIndex = DensityVolumeIndex.descendingStartIndex(
                            volume.sizeX(), volume.sizeY(), x, z
                    );

                    for (int y = volume.sizeY() - 1; y >= 0; --y, --densityIndex) {
                        int blockY = volume.blockY(y);
                        int localY = blockY & 15;
                        if (SectionCachePolicy.shouldRefresh(section != null, blockY)) {
                            section = chunk.getSection(chunk.getSectionIndex(blockY));
                        }

                        float density = densityBuffer.get(densityIndex);
                        BlockState state = aquifer.computeSubstance(blockX, blockY, blockZ, density);
                        if (state == null) {
                            state = defaultBlock;
                        }
                        state = this.debugPreliminarySurfaceLevel(noiseChunk, blockX, blockY, blockZ, state);
                        if (state == AIR) {
                            continue;
                        }

                        section.setBlockState(x, localY, z, state, false);
                        oceanFloor.update(x, blockY, z, state);
                        worldSurface.update(x, blockY, z, state);

                        if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                            blockPos.set(blockX, blockY, blockZ);
                            chunk.markPosForPostProcessing(blockPos);
                        }
                    }
                }
            }
        }
    }
}
