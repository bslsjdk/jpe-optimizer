package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.mixin.AquiferNoiseBasedAccessor;
import dev.jpe.optimizer.worldgen.DensityVolumeIndex;
import dev.jpe.optimizer.worldgen.NativeDensityGraphCompiler;
import dev.jpe.optimizer.worldgen.NativeAquiferFluidStatusPrefill;
import dev.jpe.optimizer.worldgen.SectionCachePolicy;
import dev.jpe.optimizer.runtime.NativeRuntime;
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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rewrites the vanilla block-fill loop while preserving its density, aquifer,
 * debug-surface, heightmap, and post-processing operations.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    private static final boolean POSITIVE_DENSITY_AQUIFER_FAST_PATH =
            !Boolean.getBoolean("jpe.disablePositiveDensityAquiferFastPath");
    private static final AtomicBoolean REPORTED_NATIVE_AQUIFER_VOLUME_MISMATCH = new AtomicBoolean();
    private static final AtomicBoolean REPORTED_NATIVE_AQUIFER_VOLUME_SUCCESS = new AtomicBoolean();
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
        net.minecraft.world.level.levelgen.densityfunction.DensityFunction finalDensityFunction =
                generatorSettings.noiseRouter().finalDensity();
        DensitySampler.Bound finalDensity = noiseChunk.cachingSamplers().get(finalDensityFunction);

        // Compile the six resolved Aquifer samplers once into immutable C++ plans.
        // The native full-volume path owns per-voxel decisions; Java only commits
        // returned block-state tokens into Minecraft's chunk sections.
        NativeDensityGraphCompiler.AquiferSamplerPlans aquiferSamplerPlans = null;
        boolean nativeAquiferVolumeEnabled = Boolean.getBoolean("jpe.nativeAquiferSubstanceEnabled")
                || Boolean.getBoolean("jpe.nativeAquiferFluidStatusEnabled");
        boolean nativeAquiferVolumeStrict = Boolean.getBoolean("jpe.nativeAquiferSubstanceStrict")
                || Boolean.getBoolean("jpe.nativeAquiferFluidStatusStrict");
        boolean prepareNativeAquifer = Boolean.getBoolean("jpe.nativeAquiferSamplerPlansEnabled")
                || nativeAquiferVolumeEnabled;
        if (prepareNativeAquifer) {
            boolean strictAquiferPlans = Boolean.getBoolean("jpe.nativeAquiferSamplerPlansStrict")
                    || nativeAquiferVolumeStrict;
            if (noiseAquifer == null) {
                if (strictAquiferPlans) {
                    throw new IllegalStateException(
                            "Strict native Aquifer sampler preparation requires NoiseBasedAquifer");
                }
            } else {
                var aquiferConfig = generatorSettings.aquifers();
                if (aquiferConfig.isEmpty()) {
                    if (strictAquiferPlans) {
                        throw new IllegalStateException(
                                "Strict native Aquifer sampler preparation requires Aquifer.Config");
                    }
                } else {
                    aquiferSamplerPlans =
                            NativeDensityGraphCompiler.prepareAquiferSamplerPlans(aquiferConfig.get(), noiseChunk);
                    if (aquiferSamplerPlans == null && strictAquiferPlans) {
                        throw new IllegalStateException(
                                "Strict native Aquifer sampler preparation rejected one or more density graphs");
                    }
                }
            }
        }
        dev.jpe.optimizer.worldgen.NativeAquiferFluidStatusPrefill.Prepared nativeAquiferPrepared = null;
        if (nativeAquiferVolumeEnabled && noiseAquifer != null) {
            nativeAquiferPrepared = NativeAquiferFluidStatusPrefill.open(
                    noiseAquifer, noiseChunk, generatorSettings, aquiferSamplerPlans);
            if (nativeAquiferPrepared == null && nativeAquiferVolumeStrict) {
                throw new IllegalStateException(
                        "Strict native full-volume Aquifer preparation was not completed");
            }
        } else if (nativeAquiferVolumeStrict) {
            throw new IllegalStateException(
                    "Strict native full-volume Aquifer mode requires NoiseBasedAquifer");
        }

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
            boolean nativeAquiferFusedVerify = Boolean.getBoolean("jpe.nativeAquiferFusedVerify");
            if (nativeAquiferFusedVerify
                    && (!Boolean.getBoolean("jpe.nativeDensityGraphVerify")
                    || !Boolean.getBoolean("jpe.nativeDensityGraphStrict")
                    || !nativeAquiferVolumeStrict
                    || nativeAquiferPrepared == null
                    || !nativeAquiferPrepared.supportsFusedDensityPath())) {
                throw new IllegalStateException(
                        "Fused Aquifer verification requires native density/Aquifer strict parity and the fused ABI");
            }
            boolean nativeAquiferVolumeVerify = Boolean.getBoolean("jpe.nativeAquiferSubstanceVerify")
                    || Boolean.getBoolean("jpe.nativeDensityGraphVerify")
                    || nativeAquiferVolumeStrict
                    || nativeAquiferFusedVerify;
            NativeRuntime.AquiferSubstanceVolumeResult precomputedNativeVolume = null;

            // Normal runtime: evaluate finalDensity and Aquifer in one C++ call.
            // Verification mode deliberately retains the Java density view so the
            // independent vanilla oracle can compare every voxel.
            if (nativeAquiferPrepared != null
                    && (!nativeAquiferVolumeVerify || nativeAquiferFusedVerify)
                    && nativeAquiferPrepared.supportsFusedDensityPath()) {
                try {
                    long finalDensityGraphHandle =
                            NativeDensityGraphCompiler.prepareDensityGraphHandle(finalDensityFunction, noiseChunk);
                    if (finalDensityGraphHandle == 0L) {
                        throw new IllegalStateException("Final density graph was not compiled for native execution");
                    }
                    precomputedNativeVolume =
                            nativeAquiferPrepared.computeDensityAndSubstanceVolumeF32(
                                    noiseAquifer, finalDensityGraphHandle, volume);
                } catch (RuntimeException | LinkageError failure) {
                    if (nativeAquiferVolumeStrict) {
                        throw new IllegalStateException(
                                "Strict native fused density/Aquifer execution failed; Java fallback is disabled",
                                failure);
                    }
                    JPEOptimizer.LOGGER.warn(
                            "Native fused density/Aquifer execution failed; trying the verified split path: {}",
                            failure.toString());
                }
            }

            ScopedDensityBuffer sampledDensity = null;
            if (precomputedNativeVolume == null || nativeAquiferVolumeVerify) {
                sampledDensity = NativeDensityGraphCompiler.trySampleVolume(
                        finalDensityFunction, finalDensity, noiseChunk, volume);
                if (sampledDensity == null) {
                    if (Boolean.getBoolean("jpe.nativeDensityGraphStrict")) {
                        throw new IllegalStateException(
                                "Strict native density graph mode blocked vanilla density fallback"
                        );
                    }
                    sampledDensity = finalDensity.sampleVolume(volume);
                }
            }
            try (ScopedDensityBuffer densityBuffer = sampledDensity) {
            NativeRuntime.AquiferSubstanceVolumeResult computedNativeVolume = precomputedNativeVolume;
            if (computedNativeVolume == null && nativeAquiferPrepared != null) {
                try {
                    computedNativeVolume = nativeAquiferPrepared.computeSubstanceVolumeF32(
                            noiseAquifer, volume, densityBuffer);
                } catch (RuntimeException | LinkageError failure) {
                    if (nativeAquiferVolumeStrict) {
                        throw new IllegalStateException(
                                "Strict native Aquifer volume execution failed; Java fallback is disabled",
                                failure);
                    }
                    JPEOptimizer.LOGGER.warn(
                            "Native Aquifer volume execution failed; this chunk will use vanilla: {}",
                            failure.toString());
                }
            }
            try (NativeRuntime.AquiferSubstanceVolumeResult nativeAquiferResults = computedNativeVolume) {
            boolean nativeVolumeAccepted = nativeAquiferResults != null;
            if (nativeVolumeAccepted && (Boolean.getBoolean("jpe.nativeAquiferSubstanceVerify")
                    || nativeAquiferFusedVerify || nativeAquiferVolumeStrict)) {
                String mismatch = jpe$verifyNativeAquiferVolume(
                        aquifer, nativeAquiferPrepared, nativeAquiferResults, volume, densityBuffer);
                if (mismatch != null) {
                    if (nativeAquiferVolumeStrict) {
                        throw new IllegalStateException(
                                "Strict native Aquifer full-volume parity failed: " + mismatch);
                    }
                    nativeVolumeAccepted = false;
                    if (REPORTED_NATIVE_AQUIFER_VOLUME_MISMATCH.compareAndSet(false, true)) {
                        JPEOptimizer.LOGGER.warn(
                                "Native Aquifer full-volume parity mismatch; this and subsequent chunks use vanilla: {}",
                                mismatch);
                    }
                }
            }
            if (nativeVolumeAccepted
                    && REPORTED_NATIVE_AQUIFER_VOLUME_SUCCESS.compareAndSet(false, true)) {
                boolean verified = Boolean.getBoolean("jpe.nativeAquiferSubstanceVerify")
                        || nativeAquiferFusedVerify || nativeAquiferVolumeStrict;
                JPEOptimizer.LOGGER.info(
                        "JPE_NATIVE_AQUIFER_SUBSTANCE_VOLUME voxels={} verified={} strict={}",
                        nativeAquiferResults.size(), verified, nativeAquiferVolumeStrict);
            }
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
                            float density = nativeVolumeAccepted && densityBuffer == null
                                    ? 0.0F : densityBuffer.get(densityIndex);
                            // Sample one voxel in 64. Two clock reads per sampled voxel keep
                            // profiling overhead bounded while separating Aquifer from placement.
                            boolean sampleTiming = profile && (densityIndex & 63) == 0;
                            long aquiferStart = sampleTiming ? System.nanoTime() : 0L;
                            BlockState state;
                            boolean scheduleFluidUpdate;
                            if (nativeVolumeAccepted) {
                                if (nativeAquiferResults.hasBlockState(densityIndex)) {
                                    state = nativeAquiferPrepared.blockStateForToken(
                                            nativeAquiferResults.blockStateToken(densityIndex));
                                } else {
                                    state = null;
                                }
                                scheduleFluidUpdate = nativeAquiferResults.shouldScheduleFluidUpdate(densityIndex);
                                if (profile) ++aquiferCallsExecuted;
                            } else if (POSITIVE_DENSITY_AQUIFER_FAST_PATH
                                    && density > 0.0F && noiseAquifer != null) {
                                // Vanilla positive-density behavior is null plus a cleared update flag.
                                noiseAquifer.jpe$setShouldScheduleFluidUpdate(false);
                                state = defaultBlock;
                                scheduleFluidUpdate = false;
                                if (profile) ++aquiferCallsSkipped;
                            } else {
                                state = aquifer.computeSubstance(worldX, blockY, blockZ, density);
                                scheduleFluidUpdate = aquifer.shouldScheduleFluidUpdate();
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

                                if (scheduleFluidUpdate && !state.getFluidState().isEmpty()) {
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
            }
        } finally {
            try {
                if (nativeAquiferPrepared != null) nativeAquiferPrepared.close();
            } finally {
                if (profile) WorldgenMetrics.endDoFill();
            }
        }

        if (profile) {
            WorldgenMetrics.record(densityNanos, System.nanoTime() - fillStart, volume.size(),
                    sampledAquiferNanos, sampledPlacementNanos, timingSampleCount,
                    aquiferCallsExecuted, aquiferCallsSkipped);
        }
    }

    private static String jpe$verifyNativeAquiferVolume(
            Aquifer aquifer,
            NativeAquiferFluidStatusPrefill.Prepared nativeAquifer,
            NativeRuntime.AquiferSubstanceVolumeResult nativeResults,
            DensityVolume volume,
            ScopedDensityBuffer density
    ) {
        for (int z = 0; z < volume.sizeZ(); ++z) {
            int blockZ = volume.blockZ(z);
            for (int x = 0; x < volume.sizeX(); ++x) {
                int blockX = volume.blockX(x);
                for (int y = 0; y < volume.sizeY(); ++y) {
                    int blockY = volume.blockY(y);
                    int index = volume.indexUnchecked(x, y, z);
                    BlockState vanillaState = aquifer.computeSubstance(
                            blockX, blockY, blockZ, density.get(index));
                    boolean vanillaSchedule = aquifer.shouldScheduleFluidUpdate();
                    boolean nativeHasState = nativeResults.hasBlockState(index);
                    BlockState nativeState = nativeHasState
                            ? nativeAquifer.blockStateForToken(nativeResults.blockStateToken(index))
                            : null;
                    boolean nativeSchedule = nativeResults.shouldScheduleFluidUpdate(index);
                    if ((vanillaState == null) != !nativeHasState
                            || (vanillaState != null && !vanillaState.equals(nativeState))
                            || vanillaSchedule != nativeSchedule) {
                        return "index=" + index + " pos=(" + blockX + "," + blockY + "," + blockZ
                                + ") vanilla=" + vanillaState + " native=" + nativeState
                                + " vanillaSchedule=" + vanillaSchedule
                                + " nativeSchedule=" + nativeSchedule;
                    }
                }
            }
        }
        return null;
    }

}
