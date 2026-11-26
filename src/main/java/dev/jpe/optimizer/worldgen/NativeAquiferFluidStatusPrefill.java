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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prepares one chunk-scoped native Aquifer context. The context owns all six
 * density plans and a FluidStatus cache. Once opened, computeSubstance for the
 * complete DensityVolume is executed by one C++ call.
 *
 * A vanilla computeFluid oracle is run before returning in verification mode.
 * The Java oracle is never used to produce native outputs or as a fallback from
 * the native full-volume path.
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

    /** Opens a context for this chunk fill, or returns null when optional native mode declines. */
    public static Prepared open(
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

        final boolean strict = Boolean.getBoolean("jpe.nativeAquiferFluidStatusStrict")
                || Boolean.getBoolean("jpe.nativeAquiferSubstanceStrict");
        final boolean verify = Boolean.getBoolean("jpe.nativeAquiferFluidStatusVerify")
                || Boolean.getBoolean("jpe.nativeAquiferSubstanceVerify");
        if (strict && !verify) {
            throw new IllegalStateException(
                    "Strict native Aquifer mode requires the full-volume vanilla parity oracle");
        }

        NativeRuntime runtime;
        try {
            runtime = NativePerlinNoiseBackend.runtimeForDensityGraph();
        } catch (RuntimeException | LinkageError unavailable) {
            return fail(strict, "Native density-graph runtime is unavailable", unavailable);
        }
        if (runtime == null || !runtime.supportsAquiferContext()) {
            return fail(strict, "Native Aquifer context/full-volume ABI is unavailable", null);
        }
        if (plans == null || !plans.belongsTo(runtime)) {
            return fail(strict, "Six native Aquifer sampler plans were not prepared for this runtime", null);
        }

        // Vanilla allocates this location cache for the same [gridY][gridZ][gridX]
        // domain that native nearest-four searches. Use only its capacity: C++ now
        // generates center positions and computes/caches FluidStatus on demand.
        long[] vanillaLocationCache = aquifer.jpe$getAquiferLocationCache();
        if (vanillaLocationCache == null || vanillaLocationCache.length == 0
                || vanillaLocationCache.length > 1_048_576) {
            return fail(strict, "Vanilla Aquifer location-cache dimensions are invalid", null);
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

        BlockState defaultFluid = settings.defaultFluid();
        if (!matchesVanillaGlobalFluidPicker(aquifer.jpe$getGlobalFluidPicker(), settings)) {
            return fail(strict,
                    "A custom or non-vanilla global FluidPicker is not supported by the native Aquifer kernel",
                    null);
        }
        int defaultFluidToken = tokenForDefaultFluid(defaultFluid);
        byte[] fingerprint = makeEphemeralFingerprint(
                noiseChunk, chunk, aquifer, randomMode, seedLo, seedHi);

        long started = System.nanoTime();
        long contextHandle = 0L;
        try {
            contextHandle = runtime.createAquiferContext(
                    plans.copyHandles(), fingerprint, SEMANTIC_FINGERPRINT,
                    seedLo, seedHi, randomMode,
                    AIR_TOKEN, WATER_TOKEN, LAVA_TOKEN,
                    Math.max(1, vanillaLocationCache.length));
            runtime.configureAquiferGlobalFluidPicker(
                    contextHandle, fingerprint, SEMANTIC_FINGERPRINT,
                    settings.seaLevel(), DimensionType.MIN_Y,
                    defaultFluidToken, defaultFluid.isAir(),
                    SharedConstants.DEBUG_DISABLE_FLUID_GENERATION);

            Prepared prepared = new Prepared(runtime, contextHandle, fingerprint, defaultFluid);
            contextHandle = 0L; // Ownership transfers to Prepared.
            if (REPORTED_SUCCESS.compareAndSet(false, true)) {
                JPEOptimizer.LOGGER.info(
                        "JPE_NATIVE_AQUIFER_CONTEXT_READY statusCacheCapacity={} "
                                + "statusComputedOnDemand=true fullVolumeOracleRequested={} "
                                + "randomMode={} elapsedUs={}",
                        vanillaLocationCache.length, verify, randomMode,
                        (System.nanoTime() - started) / 1_000.0);
            }
            return prepared;
        } catch (RuntimeException | LinkageError failure) {
            return fail(strict, "Native Aquifer context preparation failed", failure);
        } finally {
            if (contextHandle != 0L) {
                try {
                    runtime.destroyAquiferContext(contextHandle);
                } catch (RuntimeException | LinkageError failure) {
                    if (strict) {
                        throw new IllegalStateException(
                                "Strict native Aquifer context cleanup failed", failure);
                    }
                    if (REPORTED_FAILURE.compareAndSet(false, true)) {
                        JPEOptimizer.LOGGER.warn("Native Aquifer context cleanup failed: {}", failure.toString());
                    }
                }
            }
        }
    }

    public static final class Prepared implements AutoCloseable {
        private final NativeRuntime runtime;
        private final long contextHandle;
        private final byte[] fingerprint;
        private final BlockState defaultFluid;
        private boolean closed;

        private Prepared(
                NativeRuntime runtime, long contextHandle, byte[] fingerprint, BlockState defaultFluid
        ) {
            this.runtime = runtime;
            this.contextHandle = contextHandle;
            this.fingerprint = fingerprint.clone();
            this.defaultFluid = defaultFluid;
        }

        public NativeRuntime.AquiferSubstanceVolumeResult computeSubstanceVolumeF32(
                AquiferNoiseBasedAccessor aquifer,
                DensityVolume volume,
                ScopedDensityBuffer density
        ) {
            ensureOpen();
            return runtime.computeAquiferSubstanceVolumeF32(
                    contextHandle, fingerprint, SEMANTIC_FINGERPRINT,
                    aquifer.jpe$getSkipSamplingAboveY(), volume, density);
        }

        public BlockState blockStateForToken(int token) {
            ensureOpen();
            return stateForToken(token, defaultFluid);
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Native Aquifer context is closed");
        }

        @Override
        public void close() {
            ensureOpen();
            closed = true;
            runtime.destroyAquiferContext(contextHandle);
        }
    }

    /**
     * Refuses to approximate a modded FluidPicker. The native C++ kernel models the
     * vanilla 26.3 picker exactly; sample several horizontal coordinates and every
     * vertical transition to detect coordinate-dependent or otherwise replaced rules.
     */
    private static boolean matchesVanillaGlobalFluidPicker(
            Aquifer.FluidPicker picker, NoiseGeneratorSettings settings
    ) {
        if (picker == null) return false;
        int seaLevel = settings.seaLevel();
        int lavaThreshold = Math.min(-54, seaLevel);
        int[] ySamples = {
                DimensionType.MIN_Y,
                lavaThreshold - 1, lavaThreshold, lavaThreshold + 1,
                -55, -54, -53,
                seaLevel - 1, seaLevel, seaLevel + 1
        };
        int[][] horizontalSamples = {
                {0, 0}, {16, -16}, {-31, 65}, {257, -513}
        };
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState lava = Blocks.LAVA.defaultBlockState();
        for (int[] horizontal : horizontalSamples) {
            for (int y : ySamples) {
                final int expectedLevel;
                final BlockState expectedType;
                if (SharedConstants.DEBUG_DISABLE_FLUID_GENERATION) {
                    expectedLevel = DimensionType.MIN_Y * 2;
                    expectedType = air;
                } else if (y < lavaThreshold) {
                    expectedLevel = -54;
                    expectedType = lava;
                } else {
                    expectedLevel = seaLevel;
                    expectedType = settings.defaultFluid();
                }
                Aquifer.FluidStatus actual = picker.computeFluid(horizontal[0], y, horizontal[1]);
                if (actual == null || actual.fluidLevel() != expectedLevel
                        || !expectedType.equals(actual.fluidType())) {
                    return false;
                }
            }
        }
        return true;
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
            ByteBuffer input = ByteBuffer.allocate(80);
            input.putLong(((RandomStateAccessor) (Object) randomState).jpe$getSeed());
            input.putLong(seedLo);
            input.putLong(seedHi);
            input.putInt(randomMode);
            input.putInt(chunk.getPos().x());
            input.putInt(chunk.getPos().z());
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

    private static Prepared fail(boolean strict, String message, Throwable cause) {
        if (strict) {
            throw cause == null
                    ? new IllegalStateException("[JPE strict native Aquifer mode] " + message)
                    : new IllegalStateException("[JPE strict native Aquifer mode] " + message, cause);
        }
        if (REPORTED_FAILURE.compareAndSet(false, true)) {
            if (cause == null) JPEOptimizer.LOGGER.warn("{}; vanilla Aquifer remains available", message);
            else JPEOptimizer.LOGGER.warn("{}; vanilla Aquifer remains available: {}",
                    message, cause.toString());
        }
        return null;
    }
}
