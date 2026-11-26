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
    private static final int MAX_STATUS_BATCH = 65_536;

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

        final boolean strict = Boolean.getBoolean("jpe.nativeAquiferFluidStatusStrict");
        final boolean verify = Boolean.getBoolean("jpe.nativeAquiferFluidStatusVerify");
        if (strict && !verify) {
            throw new IllegalStateException(
                    "Strict native Aquifer mode requires the vanilla FluidStatus parity oracle");
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

        long[] locationCache = aquifer.jpe$getAquiferLocationCache();
        if (locationCache == null || locationCache.length == 0 || locationCache.length > 1_048_576) {
            return fail(strict, "Vanilla Aquifer location cache dimensions are invalid", null);
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
            long[] cacheKeys = new long[locationCache.length];
            int[] pointX = new int[locationCache.length];
            int[] pointY = new int[locationCache.length];
            int[] pointZ = new int[locationCache.length];
            for (int i = 0; i < locationCache.length; i++) {
                long packed = locationCache[i];
                if (packed == Long.MAX_VALUE) {
                    throw new IllegalStateException("Native Aquifer location cache contains an unfilled cell");
                }
                cacheKeys[i] = packed;
                pointX[i] = BlockPos.getX(packed);
                pointY[i] = BlockPos.getY(packed);
                pointZ[i] = BlockPos.getZ(packed);
            }

            BlockState defaultFluid = settings.defaultFluid();
            int defaultFluidToken = tokenForDefaultFluid(defaultFluid);
            byte[] fingerprint = makeEphemeralFingerprint(
                    noiseChunk, chunk, aquifer, randomMode, seedLo, seedHi);
            contextHandle = runtime.createAquiferContext(
                    plans.copyHandles(), fingerprint, SEMANTIC_FINGERPRINT,
                    seedLo, seedHi, randomMode,
                    AIR_TOKEN, WATER_TOKEN, LAVA_TOKEN,
                    Math.max(1, locationCache.length));
            runtime.configureAquiferGlobalFluidPicker(
                    contextHandle, fingerprint, SEMANTIC_FINGERPRINT,
                    settings.seaLevel(), DimensionType.MIN_Y,
                    defaultFluidToken, defaultFluid.isAir(),
                    SharedConstants.DEBUG_DISABLE_FLUID_GENERATION);

            int total = cacheKeys.length;
            int[] fluidLevels = new int[Math.min(total, MAX_STATUS_BATCH)];
            int[] fluidTypes = new int[Math.min(total, MAX_STATUS_BATCH)];
            if (verify) {
                for (int start = 0; start < total; start += MAX_STATUS_BATCH) {
                    int count = Math.min(MAX_STATUS_BATCH, total - start);
                    long[] keys = java.util.Arrays.copyOfRange(cacheKeys, start, start + count);
                    int[] xs = java.util.Arrays.copyOfRange(pointX, start, start + count);
                    int[] ys = java.util.Arrays.copyOfRange(pointY, start, start + count);
                    int[] zs = java.util.Arrays.copyOfRange(pointZ, start, start + count);
                    if (fluidLevels.length < count) {
                        fluidLevels = new int[count];
                        fluidTypes = new int[count];
                    }
                    runtime.computeAquiferFluidStatusPointsBatch(
                            contextHandle, fingerprint, SEMANTIC_FINGERPRINT,
                            keys, xs, ys, zs, fluidLevels, fluidTypes);
                    for (int i = 0; i < count; i++) {
                        Aquifer.FluidStatus expected = aquifer.jpe$computeFluid(xs[i], ys[i], zs[i]);
                        BlockState actualState = stateForToken(fluidTypes[i], defaultFluid);
                        if (expected.fluidLevel() != fluidLevels[i]
                                || !expected.fluidType().equals(actualState)) {
                            throw new IllegalStateException(
                                    "Native FluidStatus parity mismatch at cell index " + (start + i)
                                            + " center=(" + xs[i] + "," + ys[i] + "," + zs[i] + ")"
                                            + " vanilla=(" + expected.fluidLevel() + "," + expected.fluidType() + ")"
                                            + " native=(" + fluidLevels[i] + "," + actualState + ")");
                        }
                    }
                }
            } else {
                // The point-status call both computes missing values and fills the
                // context cache. Split requests to keep every C ABI batch bounded.
                for (int start = 0; start < total; start += MAX_STATUS_BATCH) {
                    int count = Math.min(MAX_STATUS_BATCH, total - start);
                    runtime.computeAquiferFluidStatusPointsBatch(
                            contextHandle, fingerprint, SEMANTIC_FINGERPRINT,
                            java.util.Arrays.copyOfRange(cacheKeys, start, start + count),
                            java.util.Arrays.copyOfRange(pointX, start, start + count),
                            java.util.Arrays.copyOfRange(pointY, start, start + count),
                            java.util.Arrays.copyOfRange(pointZ, start, start + count),
                            fluidLevels, fluidTypes);
                }
            }

            Prepared prepared = new Prepared(runtime, contextHandle, fingerprint, defaultFluid);
            contextHandle = 0L; // Ownership transfers to Prepared.
            if (REPORTED_SUCCESS.compareAndSet(false, true)) {
                JPEOptimizer.LOGGER.info(
                        "JPE_NATIVE_AQUIFER_CONTEXT_READY cells={} verified={} randomMode={} elapsedUs={}",
                        total, verify, randomMode, (System.nanoTime() - started) / 1_000.0);
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
            ByteBuffer input = ByteBuffer.allocate(80);
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
