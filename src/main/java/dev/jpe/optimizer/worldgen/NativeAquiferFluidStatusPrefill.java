package dev.jpe.optimizer.worldgen;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.mixin.AquiferNoiseBasedAccessor;
import dev.jpe.optimizer.mixin.LegacyPositionalRandomFactoryAccessor;
import dev.jpe.optimizer.mixin.NoiseChunkAccessor;
import dev.jpe.optimizer.mixin.RandomStateAccessor;
import dev.jpe.optimizer.mixin.XoroshiroPositionalRandomFactoryAccessor;
import dev.jpe.optimizer.runtime.NativePerlinNoiseBackend;
import dev.jpe.optimizer.runtime.NativeRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DimensionType;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental one-shot native prefill for a NoiseBasedAquifer's complete cell-center
 * FluidStatus cache. C++ evaluates the six prepared density graphs, all thirteen
 * surface probes, the global FluidPicker, and the FluidStatus rules in one batched
 * call. The Java reference implementation is optionally used as a parity oracle
 * before any native result is committed.
 *
 * The native context is deliberately scoped to this one chunk fill and is destroyed
 * before the voxel placement loop begins. No native handle is stored in world data.
 */
public final class NativeAquiferFluidStatusPrefill {
    private static final int AIR_TOKEN = 100;
    private static final int WATER_TOKEN = 200;
    private static final int LAVA_TOKEN = 300;
    private static final int DEFAULT_FLUID_TOKEN = 400;
    private static final long SEMANTIC_FINGERPRINT = 0x4A50_4541_5155_4632L;
    private static final AtomicBoolean REPORTED_SUCCESS = new AtomicBoolean();
    private static final AtomicBoolean REPORTED_FAILURE = new AtomicBoolean();

    private NativeAquiferFluidStatusPrefill() {}

    /**
     * Returns the number of cache entries filled, zero when already complete, and
     * -1 when the optional native prefill is unavailable or declined.
     */
    public static int prefill(
            AquiferNoiseBasedAccessor aquifer,
            NoiseChunk noiseChunk,
            ChunkAccess chunk,
            NoiseGeneratorSettings settings,
            NativeDensityGraphCompiler.AquiferSamplerPlans plans
    ) {
        Objects.requireNonNull(aquifer, "aquifer");
        Objects.requireNonNull(noiseChunk, "noiseChunk");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(settings, "settings");

        final boolean strict = Boolean.getBoolean("jpe.nativeAquiferFluidStatusStrict");
        final boolean verify = Boolean.getBoolean("jpe.nativeAquiferFluidStatusVerify");
        if (strict && !verify) {
            throw new IllegalStateException(
                    "Strict native Aquifer FluidStatus mode requires the vanilla parity oracle");
        }

        NativeRuntime runtime;
        try {
            runtime = NativePerlinNoiseBackend.runtimeForDensityGraph();
        } catch (RuntimeException | LinkageError unavailable) {
            return fail(strict, "Native density-graph runtime is unavailable", unavailable);
        }
        if (runtime == null || !runtime.supportsAquiferContext()) {
            return fail(strict, "Native Aquifer context ABI is unavailable", null);
        }
        if (plans == null || !plans.belongsTo(runtime)) {
            return fail(strict, "Six native Aquifer sampler plans were not prepared for this runtime", null);
        }

        long[] locationCache = aquifer.jpe$getAquiferLocationCache();
        Aquifer.FluidStatus[] statusCache = aquifer.jpe$getAquiferCache();
        if (locationCache == null || statusCache == null || locationCache.length == 0
                || locationCache.length != statusCache.length
                || locationCache.length > 1_048_576) {
            return fail(strict, "Vanilla Aquifer location/status cache dimensions are invalid", null);
        }

        final int randomMode;
        final long seedLo;
        final long seedHi;
        PositionalRandomFactory factory = aquifer.jpe$getPositionalRandomFactory();
        if (factory instanceof XoroshiroPositionalRandomFactoryAccessor xoroshiro) {
            randomMode = 0;
            seedLo = xoroshiro.jpe$seedLo();
            seedHi = xoroshiro.jpe$seedHi();
        } else if (factory instanceof LegacyPositionalRandomFactoryAccessor legacy) {
            randomMode = 1;
            seedLo = legacy.jpe$seed();
            seedHi = 0L;
        } else {
            return fail(strict, "Unsupported positional random factory: " + factory.getClass().getName(), null);
        }

        long started = System.nanoTime();
        long contextHandle = 0L;
        try {
            ensureLocationCache(runtime, aquifer, locationCache, randomMode, seedLo, seedHi);

            int missingCount = 0;
            for (int i = 0; i < locationCache.length; i++) {
                if (locationCache[i] == Long.MAX_VALUE) {
                    throw new IllegalStateException("Native Aquifer location cache contains an unfilled cell");
                }
                if (statusCache[i] == null) missingCount++;
            }
            if (missingCount == 0) return 0;

            long[] cacheKeys = new long[missingCount];
            int[] pointX = new int[missingCount];
            int[] pointY = new int[missingCount];
            int[] pointZ = new int[missingCount];
            int[] cacheIndices = new int[missingCount];
            int out = 0;
            for (int i = 0; i < locationCache.length; i++) {
                if (statusCache[i] != null) continue;
                long packed = locationCache[i];
                cacheKeys[out] = packed;
                pointX[out] = BlockPos.getX(packed);
                pointY[out] = BlockPos.getY(packed);
                pointZ[out] = BlockPos.getZ(packed);
                cacheIndices[out] = i;
                out++;
            }

            BlockState defaultFluid = settings.defaultFluid();
            int defaultFluidToken = tokenForDefaultFluid(defaultFluid);
            byte[] fingerprint = makeEphemeralFingerprint(
                    noiseChunk, chunk, aquifer, randomMode, seedLo, seedHi);
            long[] graphHandles = plans.copyHandles();
            contextHandle = runtime.createAquiferContext(
                    graphHandles, fingerprint, SEMANTIC_FINGERPRINT,
                    seedLo, seedHi, randomMode,
                    AIR_TOKEN, WATER_TOKEN, LAVA_TOKEN,
                    Math.max(1, missingCount));
            runtime.configureAquiferGlobalFluidPicker(
                    contextHandle, fingerprint, SEMANTIC_FINGERPRINT,
                    settings.seaLevel(), DimensionType.MIN_Y,
                    defaultFluidToken, defaultFluid.isAir(),
                    SharedConstants.DEBUG_DISABLE_FLUID_GENERATION);

            int[] fluidLevels = new int[missingCount];
            int[] fluidTypes = new int[missingCount];
            runtime.computeAquiferFluidStatusPointsBatch(
                    contextHandle, fingerprint, SEMANTIC_FINGERPRINT,
                    cacheKeys, pointX, pointY, pointZ, fluidLevels, fluidTypes);

            BlockState[] decodedStates = new BlockState[missingCount];
            for (int i = 0; i < missingCount; i++) {
                decodedStates[i] = stateForToken(fluidTypes[i], defaultFluid);
            }

            if (verify) {
                Aquifer.NoiseBasedAquifer vanillaAquifer = (Aquifer.NoiseBasedAquifer) (Object) aquifer;
                for (int i = 0; i < missingCount; i++) {
                    Aquifer.FluidStatus expected =
                            aquifer.jpe$computeFluid(pointX[i], pointY[i], pointZ[i]);
                    if (expected.fluidLevel() != fluidLevels[i]
                            || !expected.fluidType().equals(decodedStates[i])) {
                        throw new IllegalStateException(
                                "Native FluidStatus parity mismatch at cell index " + cacheIndices[i]
                                        + " center=(" + pointX[i] + "," + pointY[i] + "," + pointZ[i] + ")"
                                        + " vanilla=(" + expected.fluidLevel() + "," + expected.fluidType() + ")"
                                        + " native=(" + fluidLevels[i] + "," + decodedStates[i] + ")");
                    }
                }
            }

            // Commit only after the complete native batch and optional oracle pass.
            for (int i = 0; i < missingCount; i++) {
                statusCache[cacheIndices[i]] = new Aquifer.FluidStatus(fluidLevels[i], decodedStates[i]);
            }
            if (REPORTED_SUCCESS.compareAndSet(false, true)) {
                JPEOptimizer.LOGGER.info(
                        "JPE_NATIVE_AQUIFER_FLUID_STATUS_PREFILL cells={} verified={} randomMode={} elapsedUs={}",
                        missingCount, verify, randomMode, (System.nanoTime() - started) / 1_000.0);
            }
            return missingCount;
        } catch (RuntimeException | LinkageError failure) {
            return fail(strict, "Native Aquifer FluidStatus prefill failed", failure);
        } finally {
            if (contextHandle != 0L) {
                try {
                    runtime.destroyAquiferContext(contextHandle);
                } catch (RuntimeException | LinkageError failure) {
                    if (strict) {
                        throw new IllegalStateException(
                                "Strict native Aquifer FluidStatus context cleanup failed", failure);
                    }
                    if (REPORTED_FAILURE.compareAndSet(false, true)) {
                        JPEOptimizer.LOGGER.warn(
                                "Native Aquifer context cleanup failed: {}", failure.toString());
                    }
                }
            }
        }
    }

    private static void ensureLocationCache(
            NativeRuntime runtime,
            AquiferNoiseBasedAccessor aquifer,
            long[] locations,
            int randomMode,
            long seedLo,
            long seedHi
    ) {
        boolean hasUnset = false;
        boolean hasInitialized = false;
        for (long location : locations) {
            if (location == Long.MAX_VALUE) hasUnset = true;
            else hasInitialized = true;
        }
        if (!hasUnset) return;
        if (hasInitialized) {
            throw new IllegalStateException("Aquifer location cache is partially initialized");
        }
        if (!runtime.supportsAquiferGridCenters()) {
            throw new IllegalStateException("Native Aquifer grid-center API is unavailable");
        }
        runtime.aquiferGridCenters(
                randomMode, seedLo, seedHi,
                aquifer.jpe$getMinGridX(), aquifer.jpe$getMinGridY(), aquifer.jpe$getMinGridZ(),
                aquifer.jpe$getGridSizeX(), aquifer.jpe$getGridSizeZ(), locations);
    }

    private static int tokenForDefaultFluid(BlockState defaultFluid) {
        if (defaultFluid.equals(Blocks.AIR.defaultBlockState())) return AIR_TOKEN;
        if (defaultFluid.equals(Blocks.WATER.defaultBlockState())) return WATER_TOKEN;
        if (defaultFluid.equals(Blocks.LAVA.defaultBlockState())) return LAVA_TOKEN;
        return DEFAULT_FLUID_TOKEN;
    }

    private static BlockState stateForToken(int token, BlockState defaultFluid) {
        if (token == AIR_TOKEN) return Blocks.AIR.defaultBlockState();
        if (token == WATER_TOKEN) return Blocks.WATER.defaultBlockState();
        if (token == LAVA_TOKEN) return Blocks.LAVA.defaultBlockState();
        if (token == DEFAULT_FLUID_TOKEN) return defaultFluid;
        throw new IllegalStateException("Native Aquifer returned unknown fluid-state token " + token);
    }

    private static byte[] makeEphemeralFingerprint(
            NoiseChunk noiseChunk,
            ChunkAccess chunk,
            AquiferNoiseBasedAccessor aquifer,
            int randomMode,
            long seedLo,
            long seedHi
    ) {
        try {
            RandomState randomState = ((NoiseChunkAccessor) (Object) noiseChunk).jpe$getRandomState();
            DensityVolume volume = noiseChunk.volume();
            ByteBuffer input = ByteBuffer.allocate(64);
            input.putLong(((RandomStateAccessor) (Object) randomState).jpe$getSeed());
            input.putLong(seedLo);
            input.putLong(seedHi);
            input.putInt(randomMode);
            input.putInt(chunk.getPos().x);
            input.putInt(chunk.getPos().z);
            input.putInt(volume.minBlockX());
            input.putInt(volume.minBlockY());
            input.putInt(volume.minBlockZ());
            input.putInt(aquifer.jpe$getMinGridX());
            input.putInt(aquifer.jpe$getMinGridY());
            input.putInt(aquifer.jpe$getMinGridZ());
            input.putLong(SEMANTIC_FINGERPRINT);
            return MessageDigest.getInstance("SHA-256").digest(
                    java.util.Arrays.copyOf(input.array(), input.position()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static int fail(boolean strict, String message, Throwable cause) {
        if (strict) {
            throw cause == null
                    ? new IllegalStateException("[JPE strict native Aquifer mode] " + message)
                    : new IllegalStateException("[JPE strict native Aquifer mode] " + message, cause);
        }
        if (REPORTED_FAILURE.compareAndSet(false, true)) {
            if (cause == null) JPEOptimizer.LOGGER.warn("{}; vanilla FluidStatus cache remains untouched", message);
            else JPEOptimizer.LOGGER.warn("{}; vanilla FluidStatus cache remains untouched: {}",
                    message, cause.toString());
        }
        return -1;
    }
}
