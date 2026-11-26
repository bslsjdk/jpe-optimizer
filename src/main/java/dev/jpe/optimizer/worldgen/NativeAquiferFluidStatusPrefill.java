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
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reuses one bounded native Aquifer context per RandomState. Its immutable
 * sampler plans and global picker are shared across chunk fills; worker calls
 * use independent FFM scratch and the native FluidStatus/surface caches are bounded.
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
    private static final int STATUS_CACHE_CAPACITY = 65_536;
    private static final Object CONTEXT_LOCK = new Object();
    private static final ReferenceQueue<RandomState> RANDOM_STATE_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference<RandomState>, List<SharedContext>> WORLD_CONTEXTS =
            new HashMap<>();

    private static final class IdentityWeakReference<T> extends WeakReference<T> {
        private final int identityHash;

        private IdentityWeakReference(T value, ReferenceQueue<T> queue) {
            super(value, queue);
            identityHash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference<?> reference)) return false;
            Object left = get();
            return left != null && left == reference.get();
        }
    }

    /** A RandomState-owned native context; no strong reference to RandomState is retained here. */
    private static final class SharedContext {
        private final NativeRuntime runtime;
        private final byte[] fingerprint;
        private final long[] graphHandles;
        private final BlockState defaultFluid;
        private final int seaLevel;
        private final int randomMode;
        private final long seedLo;
        private final long seedHi;
        private final boolean debugDisableFluidGeneration;
        private long handle;
        private int leases;
        private boolean retired;

        private SharedContext(
                NativeRuntime runtime, long handle, byte[] fingerprint, long[] graphHandles,
                BlockState defaultFluid, int seaLevel, int randomMode, long seedLo, long seedHi,
                boolean debugDisableFluidGeneration
        ) {
            this.runtime = runtime;
            this.handle = handle;
            this.fingerprint = fingerprint.clone();
            this.graphHandles = graphHandles.clone();
            this.defaultFluid = defaultFluid;
            this.seaLevel = seaLevel;
            this.randomMode = randomMode;
            this.seedLo = seedLo;
            this.seedHi = seedHi;
            this.debugDisableFluidGeneration = debugDisableFluidGeneration;
        }

        private boolean matches(
                NativeRuntime candidateRuntime, byte[] candidateFingerprint, long[] candidateGraphHandles,
                BlockState candidateDefaultFluid, int candidateSeaLevel, int candidateRandomMode,
                long candidateSeedLo, long candidateSeedHi, boolean candidateDebugMode
        ) {
            return runtime == candidateRuntime
                    && Arrays.equals(fingerprint, candidateFingerprint)
                    && Arrays.equals(graphHandles, candidateGraphHandles)
                    && defaultFluid.equals(candidateDefaultFluid)
                    && seaLevel == candidateSeaLevel
                    && randomMode == candidateRandomMode
                    && seedLo == candidateSeedLo
                    && seedHi == candidateSeedHi
                    && debugDisableFluidGeneration == candidateDebugMode
                    && handle != 0L && !retired;
        }

        private synchronized void acquire() {
            if (retired || handle == 0L) {
                throw new IllegalStateException("Native Aquifer world context is retired");
            }
            ++leases;
        }

        private synchronized long handle() {
            if (handle == 0L || retired && leases == 0) {
                throw new IllegalStateException("Native Aquifer world context is unavailable");
            }
            return handle;
        }

        private synchronized void release() {
            if (leases <= 0) throw new IllegalStateException("Native Aquifer context lease underflow");
            --leases;
            if (retired && leases == 0) destroy();
        }

        private synchronized void retire() {
            retired = true;
            if (leases == 0) destroy();
        }

        private void destroy() {
            if (handle == 0L) return;
            long retiredHandle = handle;
            handle = 0L;
            try {
                runtime.destroyAquiferContext(retiredHandle);
            } catch (RuntimeException | LinkageError failure) {
                JPEOptimizer.LOGGER.warn(
                        "Unable to retire native Aquifer world context {}: {}",
                        retiredHandle, failure.toString());
            }
        }
    }

    private static void drainExpiredWorldContexts() {
        IdentityWeakReference<RandomState> expired;
        while ((expired = (IdentityWeakReference<RandomState>) RANDOM_STATE_QUEUE.poll()) != null) {
            List<SharedContext> contexts = WORLD_CONTEXTS.remove(expired);
            if (contexts != null) {
                for (SharedContext context : contexts) context.retire();
            }
        }
    }

    private NativeAquiferFluidStatusPrefill() {}

    /** Acquires the world context for this chunk, or returns null when optional native mode declines. */
    public static Prepared open(
            AquiferNoiseBasedAccessor aquifer,
            NoiseChunk noiseChunk,
            NoiseGeneratorSettings settings,
            NativeDensityGraphCompiler.AquiferSamplerPlans plans
    ) {
        Objects.requireNonNull(aquifer, "aquifer");
        Objects.requireNonNull(noiseChunk, "noiseChunk");
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

        // Keep the vanilla cache dimensions as a sanity check for the audited Aquifer
        // grid. C++ derives cell centers itself and owns a separately bounded world cache.
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
        RandomState randomState = ((NoiseChunkAccessor) (Object) noiseChunk).jpe$getRandomState();
        long[] graphHandles = plans.copyHandles();
        boolean debugDisableFluidGeneration = SharedConstants.DEBUG_DISABLE_FLUID_GENERATION;
        byte[] fingerprint = makeWorldFingerprint(
                randomState, randomMode, seedLo, seedHi, settings, defaultFluid,
                debugDisableFluidGeneration, graphHandles);

        long started = System.nanoTime();
        try {
            Prepared prepared = acquireWorldContext(
                    randomState, runtime, graphHandles, fingerprint, seedLo, seedHi, randomMode,
                    defaultFluidToken, defaultFluid, settings.seaLevel(), debugDisableFluidGeneration);
            if (REPORTED_SUCCESS.compareAndSet(false, true)) {
                JPEOptimizer.LOGGER.info(
                        "JPE_NATIVE_AQUIFER_WORLD_CONTEXT_READY statusCacheCapacity={} "
                                + "contextScope=RandomState reusedAcrossChunks=true "
                                + "fullVolumeOracleRequested={} randomMode={} elapsedUs={}",
                        STATUS_CACHE_CAPACITY, verify, randomMode,
                        (System.nanoTime() - started) / 1_000.0);
            }
            return prepared;
        } catch (RuntimeException | LinkageError failure) {
            return fail(strict, "Native Aquifer world-context preparation failed", failure);
        }
    }

    private static Prepared acquireWorldContext(
            RandomState randomState, NativeRuntime runtime, long[] graphHandles, byte[] fingerprint,
            long seedLo, long seedHi, int randomMode, int defaultFluidToken, BlockState defaultFluid,
            int seaLevel, boolean debugDisableFluidGeneration
    ) {
        synchronized (CONTEXT_LOCK) {
            drainExpiredWorldContexts();
            IdentityWeakReference<RandomState> lookup = new IdentityWeakReference<>(randomState, null);
            List<SharedContext> contexts = WORLD_CONTEXTS.get(lookup);
            if (contexts != null) {
                for (SharedContext context : contexts) {
                    if (context.matches(runtime, fingerprint, graphHandles, defaultFluid,
                            seaLevel, randomMode, seedLo, seedHi, debugDisableFluidGeneration)) {
                        context.acquire();
                        return new Prepared(context);
                    }
                }
            }

            long contextHandle = 0L;
            try {
                contextHandle = runtime.createAquiferContext(
                        graphHandles, fingerprint, SEMANTIC_FINGERPRINT,
                        seedLo, seedHi, randomMode,
                        AIR_TOKEN, WATER_TOKEN, LAVA_TOKEN, STATUS_CACHE_CAPACITY);
                runtime.configureAquiferGlobalFluidPicker(
                        contextHandle, fingerprint, SEMANTIC_FINGERPRINT,
                        seaLevel, DimensionType.MIN_Y,
                        defaultFluidToken, defaultFluid.isAir(), debugDisableFluidGeneration);

                SharedContext context = new SharedContext(
                        runtime, contextHandle, fingerprint, graphHandles, defaultFluid,
                        seaLevel, randomMode, seedLo, seedHi, debugDisableFluidGeneration);
                if (contexts == null) {
                    contexts = new ArrayList<>();
                    WORLD_CONTEXTS.put(new IdentityWeakReference<>(randomState, RANDOM_STATE_QUEUE), contexts);
                }
                contexts.add(context);
                contextHandle = 0L; // Ownership transfers to the weakly keyed world registry.
                context.acquire();
                return new Prepared(context);
            } finally {
                if (contextHandle != 0L) runtime.destroyAquiferContext(contextHandle);
            }
        }
    }

    public static final class Prepared implements AutoCloseable {
        private final SharedContext context;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Prepared(SharedContext context) {
            this.context = context;
        }

        public NativeRuntime.AquiferSubstanceVolumeResult computeSubstanceVolumeF32(
                AquiferNoiseBasedAccessor aquifer,
                DensityVolume volume,
                ScopedDensityBuffer density
        ) {
            ensureOpen();
            return context.runtime.computeAquiferSubstanceVolumeF32(
                    context.handle(), context.fingerprint, SEMANTIC_FINGERPRINT,
                    aquifer.jpe$getSkipSamplingAboveY(), volume, density);
        }

        /**
         * Runs final-density evaluation and Aquifer decisions together in C++.
         * The DensityVolume is never materialized in a Java ScopedDensityBuffer.
         */
        public NativeRuntime.AquiferSubstanceVolumeResult computeDensityAndSubstanceVolumeF32(
                AquiferNoiseBasedAccessor aquifer,
                long finalDensityGraphHandle,
                DensityVolume volume
        ) {
            ensureOpen();
            if (finalDensityGraphHandle == 0L) {
                throw new IllegalArgumentException("Final density graph handle must be non-zero");
            }
            return context.runtime.computeDensityAndAquiferSubstanceVolumeF32(
                    context.handle(), context.fingerprint, SEMANTIC_FINGERPRINT,
                    finalDensityGraphHandle, aquifer.jpe$getSkipSamplingAboveY(), volume);
        }

        public BlockState blockStateForToken(int token) {
            ensureOpen();
            return stateForToken(token, context.defaultFluid);
        }

        private void ensureOpen() {
            if (closed.get()) throw new IllegalStateException("Native Aquifer context lease is closed");
            context.handle();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) context.release();
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

    private static byte[] makeWorldFingerprint(
            RandomState randomState, int randomMode, long seedLo, long seedHi,
            NoiseGeneratorSettings settings, BlockState defaultFluid,
            boolean debugDisableFluidGeneration, long[] graphHandles
    ) {
        try {
            ByteBuffer input = ByteBuffer.allocate(112);
            input.putLong(((RandomStateAccessor) (Object) randomState).jpe$getSeed());
            input.putLong(seedLo);
            input.putLong(seedHi);
            input.putInt(randomMode);
            input.putInt(settings.seaLevel());
            input.putInt(DimensionType.MIN_Y);
            input.putInt(defaultFluid.hashCode());
            input.putInt(defaultFluid.toString().hashCode());
            input.putInt(debugDisableFluidGeneration ? 1 : 0);
            input.putLong(SEMANTIC_FINGERPRINT);
            for (long handle : graphHandles) input.putLong(handle);
            return MessageDigest.getInstance("SHA-256").digest(
                    Arrays.copyOf(input.array(), input.position()));
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
