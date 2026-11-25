package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.mixin.AquiferNoiseBasedAccessor;
import dev.jpe.optimizer.worldgen.DensityVolumeIndex;
import dev.jpe.optimizer.worldgen.SectionCachePolicy;
import dev.jpe.optimizer.worldgen.WorldgenMetrics;
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
     * Traverses the Y-contiguous density buffer backwards. For unit-Y volumes,
     * each chunk section is acquired once and processed as one uninterrupted
     * run, keeping section-boundary decisions out of the per-block hot loop.
     */
    @Overwrite
    private void doFill(NoiseChunk noiseChunk, ChunkAccess chunk) {
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Aquifer aquifer = noiseChunk.aquifer();
        // NoiseBasedAquifer's positive-density branch only clears this flag and returns null.
        // Mirror that exact fast path without entering the full aquifer lookup.
        AquiferNoiseBasedAccessor noiseAquifer = aquifer instanceof Aquifer.NoiseBasedAquifer
                ? (AquiferNoiseBasedAccessor) aquifer : null;
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        DensityVolume volume = noiseChunk.volume();

        NoiseGeneratorSettings generatorSettings = this.settings.value();
        BlockState defaultBlock = generatorSettings.defaultBlock();
        DensitySampler.Bound finalDensity = noiseChunk.cachingSamplers()
                .get(generatorSettings.noiseRouter().finalDensity());

        boolean profile = WorldgenMetrics.enabled();
        if (profile) {
            WorldgenMetrics.beginDoFill();
        }
        long densityStart = profile ? System.nanoTime() : 0L;
        long densityNanos = 0L;
        long fillStart = 0L;
        long sampledAquiferNanos = 0L;
        long sampledPlacementNanos = 0L;
        long timingSampleCount = 0L;
        long aquiferCallsExecuted = 0L;
        long aquiferCallsSkipped = 0L;

        try {
            try (ScopedDensityBuffer densityBuffer = finalDensity.sampleVolume(volume)) {
            if (profile) {
                densityNanos = System.nanoTime() - densityStart;
                fillStart = System.nanoTime();
            }

            int sizeY = volume.sizeY();
            boolean contiguousY = volume.stepBlockY() == 1;

            for (int z = 0; z < volume.sizeZ(); ++z) {
                int blockZ = volume.blockZ(z);
                for (int x = 0; x < volume.sizeX(); ++x) {
                    int worldX = volume.blockX(x);
                    int y = sizeY - 1;
                    int blockY = volume.blockY(y);
                    int densityIndex = DensityVolumeIndex.descendingStartIndex(
                            volume.sizeX(), sizeY, x, z
                    );

                    while (y >= 0) {
                        int sectionIndex = chunk.getSectionIndex(blockY);
                        int runLength;
                        if (contiguousY) {
                            runLength = SectionCachePolicy.descendingRunLength(y + 1, blockY);
                        } else {
                            // Defensive fallback for non-unit Y volumes supplied by other integrations.
                            runLength = 1;
                            while (runLength <= y
                                    && chunk.getSectionIndex(volume.blockY(y - runLength)) == sectionIndex) {
                                ++runLength;
                            }
                        }

                        LevelChunkSection section = chunk.getSection(sectionIndex);
                        for (int inSection = 0; inSection < runLength; ++inSection) {
                            int localY = blockY & 15;
                            float density = densityBuffer.get(densityIndex);
                            // Sample one voxel in 64. Two clock reads per sampled voxel keep
                            // profiling overhead bounded while separating Aquifer from placement.
                            boolean sampleTiming = profile && (densityIndex & 63) == 0;
                            long aquiferStart = sampleTiming ? System.nanoTime() : 0L;
                            BlockState state;
                            if (density > 0.0F && noiseAquifer != null) {
                                // Vanilla NoiseBasedAquifer.computeSubstance() does exactly this:
                                // clear the fluid-update flag and return null for positive density.
                                noiseAquifer.jpe$setShouldScheduleFluidUpdate(false);
                                state = defaultBlock;
                                if (profile) ++aquiferCallsSkipped;
                            } else {
                                state = aquifer.computeSubstance(worldX, blockY, blockZ, density);
                                if (profile) ++aquiferCallsExecuted;
                            }
                            if (sampleTiming) {
                                sampledAquiferNanos += System.nanoTime() - aquiferStart;
                            }
                            long placementStart = sampleTiming ? System.nanoTime() : 0L;
                            if (state == null) {
                                state = defaultBlock;
                            }
                            state = this.debugPreliminarySurfaceLevel(noiseChunk, worldX, blockY, blockZ, state);
                            if (state != AIR) {
                                section.setBlockState(x, localY, z, state, false);
                                oceanFloor.update(x, blockY, z, state);
                                worldSurface.update(x, blockY, z, state);

                                if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                                    blockPos.set(worldX, blockY, blockZ);
                                    chunk.markPosForPostProcessing(blockPos);
                                }
                            }
                            if (sampleTiming) {
                                sampledPlacementNanos += System.nanoTime() - placementStart;
                                ++timingSampleCount;
                            }

                            --y;
                            --densityIndex;
                            if (contiguousY) {
                                --blockY;
                            } else if (y >= 0) {
                                blockY = volume.blockY(y);
                            }
                        }
                    }
                }
            }
            }
        } finally {
            if (profile) {
                WorldgenMetrics.endDoFill();
            }
        }

        if (profile) {
            WorldgenMetrics.record(densityNanos, System.nanoTime() - fillStart, volume.size(),
                    sampledAquiferNanos, sampledPlacementNanos, timingSampleCount,
                    aquiferCallsExecuted, aquiferCallsSkipped);
        }
    }

}
