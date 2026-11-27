package dev.jpe.optimizer.worldgen;

import dev.jpe.optimizer.mixin.DensityBufferAccessor;
import dev.jpe.optimizer.mixin.DensityFunctionCompilerAccessor;
import dev.jpe.optimizer.mixin.RandomStateAccessor;
import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.mixin.NoiseChunkAccessor;
import dev.jpe.optimizer.runtime.NativePerlinNoiseBackend;
import dev.jpe.optimizer.runtime.NativeRuntime;
import dev.jpe.optimizer.runtime.PortableDensityGraphCache;
import dev.jpe.optimizer.runtime.DensityGraphWorldFingerprint;
import dev.jpe.optimizer.runtime.EffectiveNoiseRouterDigest;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Compiles the subset of the actual 26.3 final-density tree that has a verified
 * native implementation. Any unknown/context-dependent node rejects the whole
 * graph, leaving the existing vanilla sampler untouched.
 */
public final class NativeDensityGraphCompiler {
    private static final long CONFIGURATION_FINGERPRINT = 0x4A50452603000007L;
    private static final Object LOCK = new Object();

    // Density graphs repeatedly read the same record components while lowering
    // thousands of nodes. Resolve each accessor once per runtime class.
    private static final ClassValue<ConcurrentHashMap<String, Method>> ACCESSORS =
            new ClassValue<>() {
                @Override
                protected ConcurrentHashMap<String, Method> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    // RandomState.samplersWithContext captures the per-NoiseChunk SamplerContext
    // in its DensitySamplerSet implementation. NoiseChunk itself no longer stores
    // Blender or Beardifier fields in 26.3; those values live in this context map.
    private static final ClassValue<Field> SAMPLER_CONTEXT_FIELD = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (SamplerContext.class.isAssignableFrom(field.getType())) {
                        if (!field.trySetAccessible()) {
                            throw new IllegalStateException(
                                    "Unable to access captured SamplerContext in " + type.getName());
                        }
                        return field;
                    }
                }
            }
            throw new IllegalStateException(
                    "No captured SamplerContext field found in " + type.getName());
        }
    };

    // The compiler context is owned by RandomState and remains stable across chunks.
    // Weak keys prevent a closed world's captured samplers from being retained.
    private static final Map<DensityFunction.CompileContext, Map<NoiseFunction, Noise>> CAPTURED_NOISE =
            new WeakHashMap<>();

    private static final ReferenceQueue<RandomState> RANDOM_STATE_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference<RandomState>, StateGraphs> GRAPHS = new HashMap<>();

    private record GraphHandle(NativeRuntime runtime, long handle) {}
    private record PersistentCacheKey(Path path, byte[] fingerprint) {}
    private record PersistentCacheEnvironment(
            long worldSeed,
            byte[] routerDigest,
            String loaderVersion,
            List<DensityGraphWorldFingerprint.ModVersion> mods,
            Path directory
    ) {
        private PersistentCacheEnvironment {
            routerDigest = routerDigest.clone();
            mods = List.copyOf(mods);
        }

        @Override
        public byte[] routerDigest() {
            return routerDigest.clone();
        }
    }

    private static final class StateGraphs {
        private final IdentityHashMap<DensityFunction, GraphHandle> handles = new IdentityHashMap<>();
        private final Set<DensityFunction> disabled =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<DensityFunction> evaluated =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final IdentityHashMap<DensityFunction, CompletableFuture<GraphHandle>> compiling =
                new IdentityHashMap<>();
        // The resolved router digest and loaded-mod artifact inventory are stable for
        // this RandomState. Compute them once, not again for every router root.
        private boolean persistentCacheEnvironmentResolved;
        private PersistentCacheEnvironment persistentCacheEnvironment;
        private AquiferSamplerPlans aquiferSamplerPlans;
    }

    /** Immutable six-sampler plan, cached with the owning RandomState. */
    public static final class AquiferSamplerPlans {
        private final NativeRuntime runtime;
        private final DensityFunction[] roots;
        private final long[] handles;

        private AquiferSamplerPlans(NativeRuntime runtime, DensityFunction[] roots, long[] handles) {
            this.runtime = runtime;
            this.roots = roots.clone();
            this.handles = handles.clone();
        }

        public long handle(int samplerIndex) {
            if (samplerIndex < 0 || samplerIndex >= handles.length) {
                throw new IndexOutOfBoundsException("Aquifer sampler index " + samplerIndex);
            }
            return handles[samplerIndex];
        }

        public long[] copyHandles() {
            return handles.clone();
        }

        public boolean belongsTo(NativeRuntime candidate) {
            return runtime == candidate;
        }

        private boolean matches(NativeRuntime candidate, Aquifer.Config config) {
            return runtime == candidate
                    && roots[0] == config.barrierNoise()
                    && roots[1] == config.fluidLevelFloodednessNoise()
                    && roots[2] == config.fluidLevelSpreadNoise()
                    && roots[3] == config.lavaNoise()
                    && roots[4] == config.exclusion()
                    && roots[5] == config.surfaceLevel();
        }
    }

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

    private NativeDensityGraphCompiler() {}

    /** Called from NoiseFunction.compileSampler after vanilla creates its seed-bound Noise. */
    public static void captureNoise(
            NoiseFunction function, DensityFunction.CompileContext context, Noise noise
    ) {
        synchronized (LOCK) {
            CAPTURED_NOISE.computeIfAbsent(context, ignored -> new HashMap<>()).put(function, noise);
        }
    }

    /**
     * The vanilla compiler rewrites density-function children before compiling them.
     * A rewritten NoiseFunction can therefore be a different record instance from the
     * one observed by the capture mixin. Resolve that leaf through the same per-world
     * CompileContext instead of rejecting an otherwise valid graph.
     *
     * CompileContext owns RandomState's seeded noise memo, so this returns the exact
     * world-seeded Noise associated with the function's Holder. Keep the lookup and
     * memoization under LOCK because several graph roots may be lowered concurrently.
     */
    private static Noise resolveCapturedNoise(
            NoiseFunction function, DensityFunction.CompileContext context
    ) {
        synchronized (LOCK) {
            Map<NoiseFunction, Noise> captured = CAPTURED_NOISE.get(context);
            Noise noise = captured == null ? null : captured.get(function);
            if (noise != null) return noise;

            Holder<NormalNoise> parameters = function.noise();
            noise = context.createNoiseSampler(parameters);
            CAPTURED_NOISE.computeIfAbsent(context, ignored -> new HashMap<>()).put(function, noise);
            JPEOptimizer.LOGGER.debug(
                    "Resolved uncaptured rewritten NoiseFunction through its vanilla CompileContext (noise={})",
                    parameters);
            return noise;
        }
    }

    /**
     * Prepares an immutable native plan for a sampler used by a subsystem such as Aquifer.
     * The plan is compiled once per RandomState/root identity and remains owned by the
     * RandomState cache until that state is reclaimed. It deliberately does not evaluate
     * any samples here, so callers can invoke the C++ arbitrary-point API later without
     * computing per-point DensityFunction values in Java.
     *
     * Returns zero only when the native runtime is unavailable or the graph/context is
     * unsupported. Strict density-graph mode fails closed instead of silently returning
     * a Java fallback handle.
     */
    public static long prepareDensityGraphHandle(DensityFunction root, NoiseChunk noiseChunk) {
        long[] handles = prepareDensityGraphHandles(List.of(root), noiseChunk);
        return handles[0];
    }

    /**
     * Prepares several immutable native density plans for one NoiseChunk/RandomState.
     * Every handle is compiled once and cached by root identity within RandomState.
     * The returned array preserves caller order; zero marks a sampler that the native
     * compiler declined in non-strict mode.
     *
     * This is preparation only: no density sample values are computed in Java here.
     */
    public static long[] prepareDensityGraphHandles(
            List<? extends DensityFunction> roots, NoiseChunk noiseChunk
    ) {
        if (roots == null || noiseChunk == null) {
            throw new IllegalArgumentException("Density graph roots and NoiseChunk are required");
        }
        if (roots.isEmpty() || roots.size() > 64) {
            throw new IllegalArgumentException("Density graph sampler plan count must be in [1, 64]");
        }
        for (DensityFunction root : roots) {
            if (root == null) throw new IllegalArgumentException("Density graph sampler root must not be null");
        }

        final NativeRuntime runtime;
        try {
            runtime = NativePerlinNoiseBackend.runtimeForDensityGraph();
        } catch (RuntimeException | LinkageError unavailable) {
            if (strictMode()) {
                throw new IllegalStateException("Strict native sampler preparation: runtime unavailable", unavailable);
            }
            return new long[roots.size()];
        }
        if (runtime == null) {
            if (strictMode()) throw new IllegalStateException("Strict native sampler preparation: runtime unavailable");
            return new long[roots.size()];
        }

        try {
            NoiseChunkAccessor noiseChunkAccess = (NoiseChunkAccessor) (Object) noiseChunk;
            RandomState randomState = noiseChunkAccess.jpe$getRandomState();
            DensityFunction.CompileContext context = ((DensityFunctionCompilerAccessor)
                    (Object) ((RandomStateAccessor) (Object) randomState).jpe$getDensityFunctionCompiler())
                    .jpe$getContext();
            SamplerContext samplerContext = capturedSamplerContext(noiseChunk.cachingSamplers());
            Blender blender = samplerContext.getField(Blender.CONTEXT_KEY);
            if (blender != null && !blender.isEmpty()) {
                throw new UnsupportedOperationException("Non-empty Blender sampler context requires native Blender lowering");
            }
            Beardifier beardifier = samplerContext.getField(Beardifier.CONTEXT_KEY);
            boolean beardifierEmpty = beardifier == null || beardifier == Beardifier.EMPTY;
            if (!beardifierEmpty) {
                throw new UnsupportedOperationException(
                        "Non-empty Beardifier requires a native Beardifier node");
            }

            long[] handles = new long[roots.size()];
            for (int i = 0; i < roots.size(); i++) {
                DensityFunction root = roots.get(i);
                try {
                    handles[i] = graphHandle(
                            root, randomState, context, true, beardifierEmpty, runtime);
                } catch (RuntimeException | LinkageError unavailable) {
                    if (strictMode()) {
                        throw new IllegalStateException(
                                "Strict native sampler preparation failed for " + root.getClass().getName()
                                        + ": " + describe(unavailable), unavailable);
                    }
                    JPEOptimizer.LOGGER.debug(
                            "Native sampler plan declined for {}: {}",
                            root.getClass().getName(), describe(unavailable));
                    handles[i] = 0L;
                }
            }
            return handles;
        } catch (RuntimeException | LinkageError unavailable) {
            if (strictMode()) {
                throw new IllegalStateException(
                        "Strict native sampler preparation failed: " + describe(unavailable), unavailable);
            }
            JPEOptimizer.LOGGER.debug("Native sampler plan preparation declined: {}", describe(unavailable));
            return new long[roots.size()];
        }
    }

    /**
     * Compiles the six sampler graphs from the resolved Minecraft 26.3 Aquifer.Config.
     * The plan is retained by the weak RandomState cache, so normal chunk fills do
     * not allocate another six-handle array or recompile these graphs.
     *
     * Order is fixed to the C++ context ABI: barrier, floodedness, spread, lava,
     * exclusion, surface. A null result means native compilation was unavailable
     * or at least one sampler was unsupported in non-strict mode.
     */
    public static AquiferSamplerPlans prepareAquiferSamplerPlans(
            Aquifer.Config config, NoiseChunk noiseChunk
    ) {
        if (config == null || noiseChunk == null) {
            throw new IllegalArgumentException("Aquifer config and NoiseChunk are required");
        }
        NativeRuntime runtime;
        try {
            runtime = NativePerlinNoiseBackend.runtimeForDensityGraph();
        } catch (RuntimeException | LinkageError unavailable) {
            if (Boolean.getBoolean("jpe.nativeAquiferSamplerPlansStrict")) {
                throw new IllegalStateException("Strict native Aquifer sampler preparation: runtime unavailable",
                        unavailable);
            }
            return null;
        }
        if (runtime == null) {
            if (Boolean.getBoolean("jpe.nativeAquiferSamplerPlansStrict")) {
                throw new IllegalStateException("Strict native Aquifer sampler preparation: runtime unavailable");
            }
            return null;
        }

        RandomState randomState = ((NoiseChunkAccessor) (Object) noiseChunk).jpe$getRandomState();
        synchronized (LOCK) {
            drainQueues();
            StateGraphs cached = GRAPHS.get(new IdentityWeakReference<>(randomState, null));
            if (cached != null && cached.aquiferSamplerPlans != null
                    && cached.aquiferSamplerPlans.matches(runtime, config)) {
                return cached.aquiferSamplerPlans;
            }
        }

        DensityFunction[] roots = {
                config.barrierNoise(),
                config.fluidLevelFloodednessNoise(),
                config.fluidLevelSpreadNoise(),
                config.lavaNoise(),
                config.exclusion(),
                config.surfaceLevel()
        };
        long[] handles = prepareDensityGraphHandles(List.of(roots), noiseChunk);
        for (int i = 0; i < handles.length; i++) {
            if (handles[i] == 0L) {
                if (Boolean.getBoolean("jpe.nativeAquiferSamplerPlansStrict")) {
                    throw new IllegalStateException("Strict native Aquifer sampler preparation rejected index "
                            + i + " (" + roots[i].getClass().getName() + ")");
                }
                return null;
            }
        }

        AquiferSamplerPlans prepared = new AquiferSamplerPlans(runtime, roots, handles);
        synchronized (LOCK) {
            drainQueues();
            IdentityWeakReference<RandomState> key = new IdentityWeakReference<>(randomState, null);
            StateGraphs cached = GRAPHS.get(key);
            if (cached == null) {
                cached = new StateGraphs();
                GRAPHS.put(new IdentityWeakReference<>(randomState, RANDOM_STATE_QUEUE), cached);
            }
            if (cached.aquiferSamplerPlans == null
                    || !cached.aquiferSamplerPlans.matches(runtime, config)) {
                cached.aquiferSamplerPlans = prepared;
            }
            return cached.aquiferSamplerPlans;
        }
    }

    /**
     * Returns a pooled density buffer filled by one native DAG evaluation, or
     * null when the graph contains an unsupported node or native execution fails.
     */
    public static ScopedDensityBuffer trySampleVolume(
            DensityFunction root, DensitySampler.Bound vanillaSampler, NoiseChunk noiseChunk, DensityVolume volume
    ) {
        NativeRuntime runtime;
        try {
            runtime = NativePerlinNoiseBackend.runtimeForDensityGraph();
        } catch (RuntimeException | LinkageError unavailable) {
            return fallback("Native runtime unavailable: " + describe(unavailable));
        }
        if (runtime == null) return fallback("Native runtime is disabled or unavailable");

        NoiseChunkAccessor noiseChunkAccess = (NoiseChunkAccessor) (Object) noiseChunk;
        RandomState randomState = noiseChunkAccess.jpe$getRandomState();
        DensityFunction.CompileContext context = ((DensityFunctionCompilerAccessor)
                (Object) ((RandomStateAccessor) (Object) randomState).jpe$getDensityFunctionCompiler())
                .jpe$getContext();
        // In 26.3 NoiseChunk keeps the sampler context inside the DensitySamplerSet;
        // Blender and Beardifier are context entries, not NoiseChunk fields.
        final SamplerContext samplerContext;
        try {
            samplerContext = capturedSamplerContext(noiseChunk.cachingSamplers());
        } catch (RuntimeException | LinkageError unavailable) {
            return fallback("Unable to read NoiseChunk SamplerContext: " + describe(unavailable));
        }
        Blender blender = samplerContext.getField(Blender.CONTEXT_KEY);
        if (blender != null && !blender.isEmpty()) {
            return fallback("Non-empty Blender requires vanilla density evaluation");
        }
        Beardifier beardifier = samplerContext.getField(Beardifier.CONTEXT_KEY);
        boolean beardifierEmpty = beardifier == null || beardifier == Beardifier.EMPTY;
        // The current native DAG lowers BEARDIFIER to zero only for the empty
        // context. Handles are cached by root identity within RandomState, so
        // never allow a non-empty context to reuse a graph compiled for empty.
        // Full native Beardifier lowering is a separate implementation stage.
        if (!beardifierEmpty) {
            return fallback("Non-empty Beardifier requires a native Beardifier node");
        }
        long handle;
        try {
            handle = graphHandle(root, randomState, context, true, beardifierEmpty, runtime);
        } catch (RuntimeException | LinkageError unavailable) {
            return fallback("Density graph compilation failed: " + describe(unavailable));
        }
        if (handle == 0L) return fallback("Density graph was rejected or disabled for " + root.getClass().getName());

        DensityBufferPool pool = noiseChunkAccess.jpe$getBufferPool();
        ScopedDensityBuffer output = pool.acquire(volume.size());
        try {
            runtime.evaluateDensityGraphF32(
                    handle,
                    volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                    volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                    volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                    ((DensityBufferAccessor) output).jpe$values()
            );
            recordSuccessfulEvaluation(root, randomState, volume);
            if (Boolean.getBoolean("jpe.nativeDensityGraphVerify")) {
                ScopedDensityBuffer vanilla = vanillaSampler.sampleVolume(volume);
                float[] nativeValues = ((DensityBufferAccessor) output).jpe$values();
                float[] vanillaValues = ((DensityBufferAccessor) vanilla).jpe$values();
                int mismatch = -1;
                for (int i = 0; i < volume.size(); i++) {
                    if (Float.floatToRawIntBits(nativeValues[i]) != Float.floatToRawIntBits(vanillaValues[i])) {
                        mismatch = i;
                        break;
                    }
                }
                if (mismatch >= 0) {
                    float nativeValue = nativeValues[mismatch];
                    float vanillaValue = vanillaValues[mismatch];
                    output.close();
                    JPEOptimizer.LOGGER.error(
                            "Native density graph parity mismatch at volume index {} (native={}, vanilla={})",
                            mismatch, nativeValue, vanillaValue
                    );
                    if (strictMode()) {
                        vanilla.close();
                        throw new IllegalStateException(
                                "Strict native density graph parity failure at volume index " + mismatch
                        );
                    }
                    disableGraph(root, randomState, runtime);
                    return vanilla;
                }
                vanilla.close();
            }
            return output;
        } catch (RuntimeException | LinkageError failure) {
            output.close();
            JPEOptimizer.LOGGER.warn(
                    "Native density graph evaluation failed for {}: {}",
                    root.getClass().getName(),
                    failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage()
            );
            if (strictMode()) {
                throw new IllegalStateException("Strict native density graph execution failure", failure);
            }
            disableGraph(root, randomState, runtime);
            return null;
        }
    }

    private static SamplerContext capturedSamplerContext(DensitySamplerSet samplers) {
        if (samplers == null) throw new IllegalStateException("NoiseChunk has no DensitySamplerSet");
        try {
            return (SamplerContext) SAMPLER_CONTEXT_FIELD.get(samplers.getClass()).get(samplers);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read the 26.3 DensitySamplerSet SamplerContext", failure);
        }
    }

    private static boolean strictMode() {
        return Boolean.getBoolean("jpe.nativeDensityGraphStrict");
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getName() : message;
    }

    private static ScopedDensityBuffer fallback(String reason) {
        if (strictMode()) {
            JPEOptimizer.LOGGER.error("Strict native density graph mode forbids Java fallback: {}", reason);
            throw new IllegalStateException("Strict native density graph mode: " + reason);
        }
        return null;
    }

    private static void recordSuccessfulEvaluation(
            DensityFunction root, RandomState randomState, DensityVolume volume
    ) {
        synchronized (LOCK) {
            StateGraphs stateGraphs = GRAPHS.get(new IdentityWeakReference<>(randomState, null));
            if (stateGraphs != null && stateGraphs.evaluated.add(root)) {
                JPEOptimizer.LOGGER.info(
                        "Native density graph evaluated for {} ({}x{}x{} volume, {} samples)",
                        root.getClass().getName(),
                        volume.sizeX(), volume.sizeY(), volume.sizeZ(), volume.size()
                );
            }
        }
    }

    private static long graphHandle(
            DensityFunction root,
            RandomState randomState,
            DensityFunction.CompileContext context,
            boolean blenderEmpty,
            boolean beardifierEmpty,
            NativeRuntime runtime
    ) {
        StateGraphs stateGraphs;
        CompletableFuture<GraphHandle> pending;
        GraphHandle staleHandle = null;
        boolean compileOwner = false;
        synchronized (LOCK) {
            drainQueues();
            IdentityWeakReference<RandomState> lookup = new IdentityWeakReference<>(randomState, null);
            stateGraphs = GRAPHS.get(lookup);
            if (stateGraphs == null) {
                stateGraphs = new StateGraphs();
                GRAPHS.put(new IdentityWeakReference<>(randomState, RANDOM_STATE_QUEUE), stateGraphs);
            }
            if (stateGraphs.disabled.contains(root)) {
                if (strictMode()) {
                    throw new IllegalStateException("Density graph was previously rejected/disabled: "
                            + root.getClass().getName());
                }
                return 0L;
            }

            GraphHandle existing = stateGraphs.handles.get(root);
            if (existing != null && existing.runtime() == runtime) return existing.handle();
            if (existing != null) {
                stateGraphs.handles.remove(root);
                staleHandle = existing;
            }

            pending = stateGraphs.compiling.get(root);
            if (pending == null) {
                pending = new CompletableFuture<>();
                stateGraphs.compiling.put(root, pending);
                compileOwner = true;
            }
        }

        // Native handle release may enter the runtime lifecycle lock. Keep it outside
        // the compiler-wide monitor so unrelated graph lookups never wait on native work.
        if (staleHandle != null) {
            staleHandle.runtime().releaseDensityGraph(staleHandle.handle());
        }

        if (!compileOwner) {
            try {
                GraphHandle result = pending.get();
                return result.runtime() == runtime ? result.handle() : 0L;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for density graph compilation", interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                if (cause instanceof LinkageError linkageFailure) throw linkageFailure;
                throw new IllegalStateException("Concurrent density graph compilation failed", cause);
            }
        }

        try {
            // Reflection-heavy graph lowering and native compilation deliberately run
            // outside LOCK. Other RandomState instances can prepare in parallel.
            PersistentCacheKey cacheKey = persistentCacheKey(root, randomState, stateGraphs);
            if (cacheKey != null) {
                Optional<PortableDensityGraphCache.Graph> cachedGraph = readPersistentGraph(cacheKey);
                if (cachedGraph.isPresent()) {
                    try {
                        long cachedHandle = compilePortableGraph(runtime, cachedGraph.orElseThrow());
                        if (cachedHandle != 0L) {
                            JPEOptimizer.LOGGER.info(
                                    "Persistent native density graph cache hit for {} ({} nodes)",
                                    root.getClass().getName(), cachedGraph.orElseThrow().nodes().size());
                            GraphHandle compiled = new GraphHandle(runtime, cachedHandle);
                            boolean disabledDuringCompile;
                            synchronized (LOCK) {
                                IdentityWeakReference<RandomState> lookup = new IdentityWeakReference<>(randomState, null);
                                StateGraphs current = GRAPHS.get(lookup);
                                if (current == null) {
                                    current = stateGraphs;
                                    GRAPHS.put(new IdentityWeakReference<>(randomState, RANDOM_STATE_QUEUE), current);
                                }
                                disabledDuringCompile = current.disabled.contains(root);
                                if (!disabledDuringCompile) {
                                    current.handles.put(root, compiled);
                                    current.compiling.remove(root, pending);
                                }
                            }
                            if (disabledDuringCompile) {
                                runtime.releaseDensityGraph(cachedHandle);
                                throw new IllegalStateException(
                                        "Density graph was disabled while cached native compilation was in progress");
                            }
                            pending.complete(compiled);
                            return cachedHandle;
                        }
                    } catch (RuntimeException | LinkageError staleCache) {
                        JPEOptimizer.LOGGER.warn(
                                "Persistent native density graph cache entry rejected for {}; rebuilding from Minecraft graph: {}",
                                root.getClass().getName(), describe(staleCache));
                    }
                }
            }

            Builder builder = new Builder(context, blenderEmpty, beardifierEmpty, cacheKey != null);
            int rootIndex = builder.lower(root);
            if (rootIndex < 0 || builder.nodes.isEmpty()) {
                throw new UnsupportedOperationException("Density graph lowered to an empty graph");
            }
            if (cacheKey != null && builder.portableGraphSupported) {
                try {
                    PortableDensityGraphCache.Graph portableGraph = builder.portableGraph(rootIndex);
                    PortableDensityGraphCache.write(
                            cacheKey.path(), cacheKey.fingerprint(), CONFIGURATION_FINGERPRINT, portableGraph);
                } catch (IOException | RuntimeException cacheWriteFailure) {
                    // Cache construction is optional. A valid live Minecraft graph
                    // must still compile through the established native path.
                    JPEOptimizer.LOGGER.warn(
                            "Unable to write persistent density graph cache for {}: {}",
                            root.getClass().getName(), describe(cacheWriteFailure));
                }
            }
            long handle = runtime.compileDensityGraph(builder.nodes, rootIndex, CONFIGURATION_FINGERPRINT);
            if (handle == 0L) {
                throw new IllegalStateException("Native density graph compiler returned a null handle");
            }

            GraphHandle compiled = new GraphHandle(runtime, handle);
            boolean disabledDuringCompile;
            synchronized (LOCK) {
                IdentityWeakReference<RandomState> lookup = new IdentityWeakReference<>(randomState, null);
                StateGraphs current = GRAPHS.get(lookup);
                if (current == null) {
                    current = stateGraphs;
                    GRAPHS.put(new IdentityWeakReference<>(randomState, RANDOM_STATE_QUEUE), current);
                }
                disabledDuringCompile = current.disabled.contains(root);
                if (!disabledDuringCompile) {
                    current.handles.put(root, compiled);
                    current.compiling.remove(root, pending);
                }
            }
            if (disabledDuringCompile) {
                runtime.releaseDensityGraph(handle);
                throw new IllegalStateException("Density graph was disabled while native compilation was in progress");
            }
            pending.complete(compiled);
            JPEOptimizer.LOGGER.info(
                    "Native density graph compiled for {} ({} nodes)",
                    root.getClass().getName(), builder.nodes.size()
            );
            return handle;
        } catch (RuntimeException | LinkageError failure) {
            synchronized (LOCK) {
                stateGraphs.compiling.remove(root, pending);
                stateGraphs.disabled.add(root);
            }
            pending.completeExceptionally(failure);
            JPEOptimizer.LOGGER.error(
                    "Native density graph rejected/compilation failed for {}: {}",
                    root.getClass().getName(), describe(failure)
            );
            if (strictMode()) {
                throw new IllegalStateException(
                        "Strict native density graph compilation failed for " + root.getClass().getName()
                                + ": " + describe(failure), failure
                );
            }
            return 0L;
        }
    }

    private static PersistentCacheKey persistentCacheKey(
            DensityFunction root, RandomState randomState, StateGraphs stateGraphs
    ) {
        try {
            RandomStateAccessor access = (RandomStateAccessor) (Object) randomState;
            NoiseRouter router = access.jpe$getRouter();
            String rootKey = routerRootKey(router, root);
            if (rootKey == null) return null;

            // NoiseRouter codecs can contain Holder references. JsonOps alone has
            // no registry context and may reject an otherwise valid resolved router.
            // A worldgen call made before the server is published simply skips disk
            // caching and can retry on a later root without poisoning this state.
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return null;

            PersistentCacheEnvironment environment;
            synchronized (stateGraphs) {
                if (!stateGraphs.persistentCacheEnvironmentResolved) {
                    // Mark the attempt before doing I/O. Unsupported or unreadable
                    // environments must not trigger the same full mod scan per root.
                    stateGraphs.persistentCacheEnvironmentResolved = true;
                    long started = System.nanoTime();
                    try {
                        stateGraphs.persistentCacheEnvironment =
                                createPersistentCacheEnvironment(router, access.jpe$getSeed(), server);
                        if (stateGraphs.persistentCacheEnvironment != null) {
                            JPEOptimizer.LOGGER.info(
                                    "Persistent density graph cache environment prepared in {} ms ({} loaded mods)",
                                    (System.nanoTime() - started) / 1_000_000.0,
                                    stateGraphs.persistentCacheEnvironment.mods().size());
                        }
                    } catch (IOException | RuntimeException | LinkageError unavailable) {
                        JPEOptimizer.LOGGER.debug(
                                "Persistent density graph cache environment unavailable: {}",
                                describe(unavailable));
                    }
                }
                environment = stateGraphs.persistentCacheEnvironment;
            }
            if (environment == null) return null;

            byte[] fingerprint = DensityGraphWorldFingerprint.create(
                    environment.worldSeed(), "26.3", "neoforge", environment.loaderVersion(),
                    "noise-router:" + rootKey, NativeRuntime.EXPECTED_ABI_VERSION,
                    CONFIGURATION_FINGERPRINT, environment.routerDigest(), environment.mods());

            return new PersistentCacheKey(
                    environment.directory().resolve(HexFormat.of().formatHex(fingerprint) + ".dgraph"),
                    fingerprint);
        } catch (RuntimeException | LinkageError unavailable) {
            JPEOptimizer.LOGGER.debug(
                    "Persistent density graph cache key unavailable: {}", describe(unavailable));
            return null;
        }
    }

    private static PersistentCacheEnvironment createPersistentCacheEnvironment(
            NoiseRouter router, long worldSeed, MinecraftServer server
    ) throws IOException {
        RegistryOps<JsonElement> registryOps =
                RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Optional<byte[]> routerDigest = EffectiveNoiseRouterDigest.tryCreate(router, registryOps);
        if (routerDigest.isEmpty()) {
            JPEOptimizer.LOGGER.debug(
                    "Persistent density graph cache disabled: resolved NoiseRouter did not encode with server registries");
            return null;
        }

        List<DensityGraphWorldFingerprint.ModVersion> mods = new ArrayList<>();
        Map<Path, String> artifactDigests = new HashMap<>();
        String loaderVersion = null;
        for (var mod : ModList.get().getMods()) {
            String id = mod.getModId();
            String version = mod.getVersion().toString();
            var owningFile = mod.getOwningFile();
            if (owningFile == null || owningFile.getFile() == null) return null;
            Path artifactPath = owningFile.getFile().getFilePath();
            if (artifactPath == null) return null;
            artifactPath = artifactPath.toAbsolutePath().normalize();
            String artifactDigest = artifactDigests.get(artifactPath);
            if (artifactDigest == null) {
                artifactDigest = sha256ModArtifact(artifactPath);
                artifactDigests.put(artifactPath, artifactDigest);
            }
            mods.add(new DensityGraphWorldFingerprint.ModVersion(id, version, artifactDigest));
            if (id.equals("neoforge")) loaderVersion = version;
        }
        if (loaderVersion == null) return null;

        String configuredDirectory = System.getProperty("jpe.nativeDensityGraphCacheDir");
        Path directory = configuredDirectory == null || configuredDirectory.isBlank()
                ? FMLPaths.GAMEDIR.get().resolve("jpe-optimizer").resolve("density-graphs")
                : Path.of(configuredDirectory);
        return new PersistentCacheEnvironment(
                worldSeed, routerDigest.orElseThrow(), loaderVersion, mods, directory);
    }

    /**
     * Hashes the actual loaded mod artifact, not merely its declared version.
     * Directory-backed development mods are hashed deterministically by relative
     * path and file contents. Symlinks and unreadable/unknown artifact types
     * disable persistent reuse rather than weakening the cache identity.
     */
    static String sha256ModArtifact(Path artifact) throws IOException {
        if (Files.isSymbolicLink(artifact)) {
            throw new IOException("Mod artifact is a symbolic link: " + artifact);
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }

        if (Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            digest.update((byte) 'F');
            updateDigestFromFile(digest, artifact);
            return HexFormat.of().formatHex(digest.digest());
        }
        if (!Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsupported mod artifact path: " + artifact);
        }

        digest.update((byte) 'D');
        try (Stream<Path> walk = Files.walk(artifact)) {
            List<Path> files = walk.sorted(Comparator.comparing(
                    path -> artifact.relativize(path).toString())).toList();
            for (Path file : files) {
                if (file.equals(artifact)) continue;
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("Mod artifact contains a symbolic link: " + file);
                }
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unsupported entry in mod artifact: " + file);
                }
                byte[] relative = artifact.relativize(file).toString()
                        .replace(file.getFileSystem().getSeparator(), "/")
                        .getBytes(StandardCharsets.UTF_8);
                long expectedSize = Files.size(file);
                // Length-prefix both the path and payload. Delimiter-only framing
                // permits ambiguous directory layouts to produce identical input
                // streams without requiring a SHA-256 collision.
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(relative.length).array());
                digest.update(relative);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(expectedSize).array());
                long actualSize = updateDigestFromFile(digest, file);
                if (actualSize != expectedSize) {
                    throw new IOException("Mod artifact changed while hashing: " + file);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long updateDigestFromFile(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static String routerRootKey(NoiseRouter router, DensityFunction root) {
        if (router == null || root == null || !router.getClass().isRecord()) return null;
        List<String> names = new ArrayList<>();
        try {
            for (RecordComponent component : router.getClass().getRecordComponents()) {
                if (DensityFunction.class.isAssignableFrom(component.getType())
                        && component.getAccessor().invoke(router) == root) {
                    names.add(component.getName());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
        if (names.isEmpty()) return null;
        Collections.sort(names);
        return String.join("+", names);
    }

    private static Optional<PortableDensityGraphCache.Graph> readPersistentGraph(PersistentCacheKey key) {
        try {
            return PortableDensityGraphCache.read(
                    key.path(), key.fingerprint(), CONFIGURATION_FINGERPRINT);
        } catch (IOException | RuntimeException cacheReadFailure) {
            JPEOptimizer.LOGGER.warn(
                    "Unable to read persistent density graph cache {}: {}",
                    key.path(), describe(cacheReadFailure));
            return Optional.empty();
        }
    }

    private static long compilePortableGraph(
            NativeRuntime runtime, PortableDensityGraphCache.Graph graph
    ) {
        List<NativeRuntime.DensityNode> materialized = graph.materialize(runtime);
        Set<Long> temporaryHandles = new HashSet<>();
        for (NativeRuntime.DensityNode node : materialized) {
            if (node.opcode() == 7 || node.opcode() == 31 || node.opcode() == 33) {
                temporaryHandles.add((long) node.immediate());
            }
        }
        try {
            return runtime.compileDensityGraph(
                    materialized, graph.rootNode(), CONFIGURATION_FINGERPRINT);
        } finally {
            // The C++ graph retains its own prepared-stack references.
            for (long handle : temporaryHandles) {
                try {
                    runtime.releasePreparedPerlinStack(handle);
                } catch (RuntimeException | LinkageError releaseFailure) {
                    JPEOptimizer.LOGGER.error(
                            "Unable to release temporary prepared Perlin stack {}", handle, releaseFailure);
                }
            }
        }
    }

    private static void disableGraph(DensityFunction root, RandomState randomState, NativeRuntime runtime) {
        GraphHandle existing;
        synchronized (LOCK) {
            StateGraphs stateGraphs = GRAPHS.get(new IdentityWeakReference<>(randomState, null));
            if (stateGraphs == null) return;
            existing = stateGraphs.handles.remove(root);
            stateGraphs.disabled.add(root);
        }
        if (existing != null) {
            existing.runtime().releaseDensityGraph(existing.handle());
        }
    }

    @SuppressWarnings("unchecked")
    private static void drainQueues() {
        IdentityWeakReference<RandomState> expired;
        while ((expired = (IdentityWeakReference<RandomState>) RANDOM_STATE_QUEUE.poll()) != null) {
            StateGraphs stateGraphs = GRAPHS.remove(expired);
            if (stateGraphs != null) {
                for (GraphHandle graph : stateGraphs.handles.values()) {
                    graph.runtime().releaseDensityGraph(graph.handle());
                }
                stateGraphs.handles.clear();
                stateGraphs.disabled.clear();
            }
        }
    }

    private static Method accessor(Class<?> type, String name, boolean recordOnly) {
        return ACCESSORS.get(type).computeIfAbsent(name, key -> {
            try {
                if (type.isRecord()) {
                    for (RecordComponent component : type.getRecordComponents()) {
                        if (component.getName().equals(key)) return component.getAccessor();
                    }
                }
                if (!recordOnly) return type.getMethod(key);
            } catch (ReflectiveOperationException failure) {
                throw new AccessorLookupFailure(failure);
            }
            throw new AccessorLookupFailure(new NoSuchMethodException(type.getName() + "." + key));
        });
    }

    private static final class AccessorLookupFailure extends RuntimeException {
        private AccessorLookupFailure(Throwable cause) { super(cause); }
    }

    private static Object component(Object value, String name) {
        if (value == null || !value.getClass().isRecord()) {
            throw new UnsupportedOperationException("Expected record component " + name);
        }
        try {
            return accessor(value.getClass(), name, true).invoke(value);
        } catch (ReflectiveOperationException | AccessorLookupFailure failure) {
            throw new UnsupportedOperationException("Unable to read density component " + name, failure);
        }
    }

    private static Object member(Object value, String name) {
        if (value == null) throw new UnsupportedOperationException("Missing spline member " + name);
        try {
            return accessor(value.getClass(), name, false).invoke(value);
        } catch (ReflectiveOperationException | AccessorLookupFailure failure) {
            throw new UnsupportedOperationException("Unable to read density member " + name, failure);
        }
    }

    private static final class Builder {
        private final DensityFunction.CompileContext context;
        private final boolean blenderEmpty;
        private final boolean beardifierEmpty;
        private final boolean capturePortable;
        private boolean portableGraphSupported = true;
        private final List<NativeRuntime.DensityNode> nodes = new ArrayList<>();
        private final IdentityHashMap<DensityFunction, Integer> indices = new IdentityHashMap<>();
        private final IdentityHashMap<DensityFunction, Boolean> active = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Integer> splineIndices = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Boolean> activeSplines = new IdentityHashMap<>();
        private final IdentityHashMap<Noise, Integer> perlinStackSlots = new IdentityHashMap<>();
        private final Map<Long, Integer> preparedHandleSlots = new HashMap<>();
        private final List<PortableDensityGraphCache.PerlinStack> perlinStacks = new ArrayList<>();
        private final Map<Integer, Integer> constantNodes = new HashMap<>();

        private Builder(DensityFunction.CompileContext context, boolean blenderEmpty,
                        boolean beardifierEmpty, boolean capturePortable) {
            this.context = context;
            this.blenderEmpty = blenderEmpty;
            this.beardifierEmpty = beardifierEmpty;
            this.capturePortable = capturePortable;
        }

        private PortableDensityGraphCache.Graph portableGraph(int rootIndex) {
            if (!capturePortable || !portableGraphSupported) {
                throw new IllegalStateException("Portable density graph snapshot is unavailable");
            }
            List<NativeRuntime.DensityNode> portableNodes = new ArrayList<>(nodes.size());
            for (NativeRuntime.DensityNode node : nodes) {
                if (node.opcode() == 7 || node.opcode() == 31 || node.opcode() == 33) {
                    Integer slot = preparedHandleSlots.get((long) node.immediate());
                    if (slot == null) {
                        throw new IllegalStateException("Native Perlin handle has no portable descriptor slot");
                    }
                    portableNodes.add(new NativeRuntime.DensityNode(
                            node.opcode(), node.inputA(), node.inputB(), node.reserved(),
                            slot.doubleValue(), node.auxiliary(), node.tertiary()));
                } else {
                    portableNodes.add(node);
                }
            }
            return new PortableDensityGraphCache.Graph(portableNodes, rootIndex, perlinStacks);
        }

        private int constant(float value) {
            int bits = Float.floatToRawIntBits(value);
            Integer existing = constantNodes.get(bits);
            if (existing != null) return existing;
            int index = append(NativeRuntime.DensityNode.constant(value));
            constantNodes.put(bits, index);
            return index;
        }

        private int add(int a, int b) {
            return append(NativeRuntime.DensityNode.add(a, b));
        }

        private int subtract(int a, int b) {
            return append(NativeRuntime.DensityNode.subtract(a, b));
        }

        private int multiply(int a, int b) {
            return append(NativeRuntime.DensityNode.multiply(a, b));
        }

        private int divide(int a, int b) {
            return append(NativeRuntime.DensityNode.divide(a, b));
        }

        private int linearExtend(int coordinate, int value, float location, float derivative) {
            int delta = subtract(coordinate, constant(location));
            int slope = constant(derivative);
            return add(value, multiply(delta, slope));
        }

        private int hermiteSegment(
                int coordinate, int firstValue, int secondValue,
                float firstLocation, float secondLocation,
                float firstDerivative, float secondDerivative
        ) {
            float spanValue = secondLocation - firstLocation;
            if (!(spanValue > 0.0F) || !Float.isFinite(spanValue)) {
                throw new UnsupportedOperationException("Invalid cubic spline interval");
            }
            int t = divide(subtract(coordinate, constant(firstLocation)), constant(spanValue));
            int t2 = multiply(t, t);
            int t3 = multiply(t2, t);

            int h00 = add(subtract(multiply(constant(2.0F), t3),
                    multiply(constant(3.0F), t2)), constant(1.0F));
            int h10 = add(subtract(t3, multiply(constant(2.0F), t2)), t);
            int h01 = add(multiply(constant(-2.0F), t3), multiply(constant(3.0F), t2));
            int h11 = subtract(t3, t2);

            int firstTangent = multiply(constant(firstDerivative * spanValue), h10);
            int secondTangent = multiply(constant(secondDerivative * spanValue), h11);
            int firstTerm = multiply(h00, firstValue);
            int secondTerm = multiply(h01, secondValue);
            return add(add(firstTerm, firstTangent), add(secondTerm, secondTangent));
        }

        private int lowerSpline(Object spline) {
            Integer existing = splineIndices.get(spline);
            if (existing != null) return existing;
            if (activeSplines.put(spline, Boolean.TRUE) != null) {
                throw new UnsupportedOperationException("Cyclic cubic spline");
            }
            try {
                String kind = spline.getClass().getSimpleName();
                if (kind.equals("Constant")) {
                    int result = constant(((Number) member(spline, "value")).floatValue());
                    splineIndices.put(spline, result);
                    return result;
                }
                if (!kind.equals("Multipoint")) {
                    throw new UnsupportedOperationException("Unsupported cubic spline " + kind);
                }

                Object coordinate = member(spline, "coordinate");
                int coordinateIndex = lower((DensityFunction) member(coordinate, "function"));
                float[] locations = (float[]) member(spline, "locations");
                float[] derivatives = (float[]) member(spline, "derivatives");
                List<?> values = (List<?>) member(spline, "values");
                if (locations.length == 0 || locations.length != derivatives.length
                        || locations.length != values.size()) {
                    throw new UnsupportedOperationException("Malformed cubic spline point arrays");
                }
                for (int i = 0; i < locations.length; i++) {
                    if (!Float.isFinite(locations[i]) || !Float.isFinite(derivatives[i])
                            || (i > 0 && !(locations[i] > locations[i - 1]))) {
                        throw new UnsupportedOperationException("Invalid cubic spline points");
                    }
                }

                int[] valueIndices = new int[values.size()];
                for (int i = 0; i < values.size(); i++) valueIndices[i] = lowerSpline(values.get(i));

                int result;
                if (locations.length == 1) {
                    result = linearExtend(coordinateIndex, valueIndices[0], locations[0], derivatives[0]);
                } else {
                    int last = locations.length - 1;
                    result = linearExtend(coordinateIndex, valueIndices[last], locations[last], derivatives[last]);
                    for (int i = last - 1; i >= 0; i--) {
                        int segment = hermiteSegment(
                                coordinateIndex, valueIndices[i], valueIndices[i + 1],
                                locations[i], locations[i + 1], derivatives[i], derivatives[i + 1]);
                        result = append(NativeRuntime.DensityNode.rangeChoice(
                                coordinateIndex, locations[i], locations[i + 1], segment, result));
                    }
                    int below = linearExtend(coordinateIndex, valueIndices[0], locations[0], derivatives[0]);
                    result = append(NativeRuntime.DensityNode.rangeChoiceBelow(
                            coordinateIndex, locations[0], below, result));
                }
                splineIndices.put(spline, result);
                return result;
            } finally {
                activeSplines.remove(spline);
            }
        }

        private int append(NativeRuntime.DensityNode node) {
            if (nodes.size() >= 65_536) throw new UnsupportedOperationException("Density graph exceeds native node limit");
            int index = nodes.size();
            nodes.add(node);
            return index;
        }

        private int lower(DensityFunction function) {
            if (function == null) throw new UnsupportedOperationException("Null density function");
            Integer existing = indices.get(function);
            if (existing != null) return existing;
            if (active.put(function, Boolean.TRUE) != null) {
                throw new UnsupportedOperationException("Cyclic density graph");
            }

            try {
                String kind = function.getClass().getSimpleName();
                int result;
                switch (kind) {
                    case "HolderHolder" -> {
                        Holder<DensityFunction> holder = (Holder<DensityFunction>) component(function, "function");
                        result = lower(holder.value());
                    }
                    case "CacheFunction" -> result = lower((DensityFunction) component(function, "input"));
                    case "BlendDensityFunction" -> {
                        if (!blenderEmpty) {
                            throw new UnsupportedOperationException("Legacy chunk blending requires a native blender node");
                        }
                        result = lower((DensityFunction) component(function, "input"));
                    }
                    case "SimpleDensityFunction" -> {
                        String simpleName = function.toString();
                        float value = switch (simpleName) {
                            case "BLEND_ALPHA" -> {
                                if (!blenderEmpty) throw new UnsupportedOperationException("Non-empty blend alpha");
                                yield 1.0F;
                            }
                            case "BLEND_OFFSET" -> {
                                if (!blenderEmpty) throw new UnsupportedOperationException("Non-empty blend offset");
                                yield 0.0F;
                            }
                            case "BEARDIFIER" -> {
                                if (!beardifierEmpty) throw new UnsupportedOperationException("Non-empty beardifier");
                                yield 0.0F;
                            }
                            default -> throw new UnsupportedOperationException("Unsupported simple density function " + simpleName);
                        };
                        result = append(NativeRuntime.DensityNode.constant(value));
                    }
                    case "ConstantFunction" -> {
                        Number value = (Number) component(function, "value");
                        result = constant(value.floatValue());
                    }
                    case "SplineFunction" -> result = lowerSpline(member(function, "spline"));
                    case "BinaryFunction" -> {
                        String operation = component(function, "type").toString();
                        int left = lower((DensityFunction) component(function, "left"));
                        int right = lower((DensityFunction) component(function, "right"));
                        NativeRuntime.DensityNode node = switch (operation) {
                            case "ADD" -> NativeRuntime.DensityNode.add(left, right);
                            case "SUB" -> NativeRuntime.DensityNode.subtract(left, right);
                            case "MUL" -> NativeRuntime.DensityNode.multiply(left, right);
                            case "DIV" -> NativeRuntime.DensityNode.divide(left, right);
                            case "MIN" -> NativeRuntime.DensityNode.min(left, right);
                            case "MAX" -> NativeRuntime.DensityNode.max(left, right);
                            default -> throw new UnsupportedOperationException("Unsupported binary density op " + operation);
                        };
                        result = append(node);
                    }
                    case "UnaryFunction" -> {
                        String operation = component(function, "type").toString();
                        int input = lower((DensityFunction) component(function, "input"));
                        NativeRuntime.DensityNode node = switch (operation) {
                            case "ABS" -> NativeRuntime.DensityNode.abs(input);
                            case "NEGATE" -> NativeRuntime.DensityNode.negate(input);
                            case "SQUARE" -> NativeRuntime.DensityNode.square(input);
                            case "CUBE" -> NativeRuntime.DensityNode.cube(input);
                            case "SQRT" -> NativeRuntime.DensityNode.sqrt(input);
                            case "HALF_NEGATIVE" -> NativeRuntime.DensityNode.halfNegative(input);
                            case "QUARTER_NEGATIVE" -> NativeRuntime.DensityNode.quarterNegative(input);
                            case "RECIPROCAL" -> NativeRuntime.DensityNode.reciprocal(input);
                            case "SIGN" -> NativeRuntime.DensityNode.sign(input);
                            case "SQUEEZE" -> NativeRuntime.DensityNode.squeeze(input);
                            case "LOG" -> NativeRuntime.DensityNode.log(input);
                            default -> throw new UnsupportedOperationException("Unsupported unary density op " + operation);
                        };
                        result = append(node);
                    }
                    case "IntervalSelectFunction" -> {
                        int input = lower((DensityFunction) component(function, "input"));
                        FloatList thresholds = (FloatList) component(function, "thresholds");
                        List<DensityFunction> functions = (List<DensityFunction>) component(function, "functions");
                        if (functions.size() != thresholds.size() + 1) {
                            throw new UnsupportedOperationException("Invalid interval-select branch count");
                        }
                        float previous = Float.NEGATIVE_INFINITY;
                        for (int i = 0; i < thresholds.size(); i++) {
                            float threshold = thresholds.getFloat(i);
                            if (!Float.isFinite(threshold) || threshold <= previous) {
                                throw new UnsupportedOperationException("Interval-select thresholds are not strictly increasing");
                            }
                            previous = threshold;
                        }
                        result = lower(functions.get(functions.size() - 1));
                        for (int i = thresholds.size() - 1; i >= 0; i--) {
                            int whenBelow = lower(functions.get(i));
                            result = append(NativeRuntime.DensityNode.rangeChoiceBelow(
                                    input, thresholds.getFloat(i), whenBelow, result));
                        }
                    }
                    case "DistanceToPointFunction" -> {
                        Vec3i point = (Vec3i) component(function, "point");
                        String metricName = component(function, "metric").toString();
                        int metric = switch (metricName) {
                            case "CHEBYSHEV" -> 0;
                            case "EUCLIDEAN" -> 1;
                            case "EUCLIDEAN_SQUARED" -> 2;
                            case "MANHATTAN" -> 3;
                            default -> throw new UnsupportedOperationException("Unknown distance metric " + metricName);
                        };
                        result = append(NativeRuntime.DensityNode.distanceToPoint(
                                point.getX(), point.getY(), point.getZ(), metric));
                    }
                    case "RangeChoiceFunction" -> {
                        int input = lower((DensityFunction) component(function, "input"));
                        int whenInRange = lower((DensityFunction) component(function, "whenInRange"));
                        int whenOutOfRange = lower((DensityFunction) component(function, "whenOutOfRange"));
                        float minimum = ((Number) component(function, "minInclusive")).floatValue();
                        float maximum = ((Number) component(function, "maxExclusive")).floatValue();
                        result = append(NativeRuntime.DensityNode.rangeChoice(
                                input, minimum, maximum, whenInRange, whenOutOfRange));
                    }
                    case "InterpolatedFunction" -> {
                        int input = lower((DensityFunction) component(function, "input"));
                        int cellSizeXz = ((Number) component(function, "cellSizeXz")).intValue();
                        int cellSizeY = ((Number) component(function, "cellSizeY")).intValue();
                        result = append(NativeRuntime.DensityNode.interpolated(input, cellSizeXz, cellSizeY));
                    }
                    case "LerpFunction" -> {
                        int alpha = lower((DensityFunction) component(function, "alpha"));
                        int first = lower((DensityFunction) component(function, "first"));
                        int second = lower((DensityFunction) component(function, "second"));
                        result = append(NativeRuntime.DensityNode.lerp(alpha, first, second));
                    }
                    case "GradientFunction" -> {
                        String axisName = component(function, "axis").toString().toUpperCase(java.util.Locale.ROOT);
                        String tilingName = component(function, "tiling").toString();
                        int tiling = switch (tilingName) {
                            case "CLAMP_TO_EDGE" -> 0;
                            case "REPEAT" -> 1;
                            case "MIRRORED_REPEAT" -> 2;
                            default -> throw new UnsupportedOperationException("Unknown gradient tiling " + tilingName);
                        };
                        int axis = switch (axisName) {
                            case "X" -> 0;
                            case "Y" -> 1;
                            case "Z" -> 2;
                            default -> throw new UnsupportedOperationException("Unknown gradient axis " + axisName);
                        };
                        int fromCoordinate = ((Number) component(function, "fromCoordinate")).intValue();
                        int toCoordinate = ((Number) component(function, "toCoordinate")).intValue();
                        float fromValue = ((Number) component(function, "fromValue")).floatValue();
                        float toValue = ((Number) component(function, "toValue")).floatValue();
                        result = append(NativeRuntime.DensityNode.gradient(
                                axis, tiling, fromCoordinate, toCoordinate, fromValue, toValue));
                    }
                    case "RoundFunction" -> {
                        String typeName = component(function, "type").toString();
                        int type = switch (typeName) {
                            case "FLOOR" -> 0;
                            case "ROUND" -> 1;
                            case "CEIL" -> 2;
                            case "TRUNCATE" -> 3;
                            default -> throw new UnsupportedOperationException("Unsupported round type " + typeName);
                        };
                        DensityFunction multipleFunction = (DensityFunction) component(function, "multiple");
                        // The native ROUND node already consumes a per-voxel multiple buffer.
                        // Lower it as a normal child graph instead of restricting the codec
                        // to constants; zero and non-finite runtime values retain IEEE semantics.
                        int input = lower((DensityFunction) component(function, "input"));
                        int multiple = lower(multipleFunction);
                        result = append(NativeRuntime.DensityNode.round(input, multiple, type));
                    }
                    case "PowFunction" -> {
                        int base = lower((DensityFunction) component(function, "base"));
                        int exponent = lower((DensityFunction) component(function, "exponent"));
                        result = append(NativeRuntime.DensityNode.pow(base, exponent));
                    }
                    case "ClampFunction" -> {
                        int input = lower((DensityFunction) component(function, "input"));
                        float minimum = ((Number) component(function, "min")).floatValue();
                        float maximum = ((Number) component(function, "max")).floatValue();
                        result = append(NativeRuntime.DensityNode.clamp(input, minimum, maximum));
                    }
                    case "FindTopSurfaceFunction" -> {
                        int density = lower((DensityFunction) component(function, "density"));
                        int upperBound = lower((DensityFunction) component(function, "upperBound"));
                        int lowerBound = ((Number) component(function, "lowerBound")).intValue();
                        int stepSize = ((Number) component(function, "stepSize")).intValue();
                        if (stepSize <= 0) {
                            throw new UnsupportedOperationException("FindTopSurface stepSize must be positive");
                        }
                        result = append(NativeRuntime.DensityNode.findTopSurface(
                                density, upperBound, lowerBound, stepSize));
                    }
                    case "SliceFunction" -> {
                        String axisName = component(function, "axis").toString().toUpperCase(java.util.Locale.ROOT);
                        int axis = switch (axisName) {
                            case "X" -> 0;
                            case "Y" -> 1;
                            case "Z" -> 2;
                            default -> throw new UnsupportedOperationException("Unknown slice axis " + axisName);
                        };
                        int coordinate = ((Number) component(function, "coordinate")).intValue();
                        int input = lower((DensityFunction) component(function, "input"));
                        result = append(NativeRuntime.DensityNode.slice(input, axis, coordinate));
                    }
                    case "ShiftA", "ShiftB", "Shift" -> {
                        @SuppressWarnings("unchecked")
                        Holder<NormalNoise> noiseHolder =
                                (Holder<NormalNoise>) component(function, "offsetNoise");
                        Noise noise = context.createNoiseSampler(noiseHolder);
                        long preparedHandle = NativePerlinNoiseBackend.preparedStackHandleForDensityGraph(noise);
                        if (preparedHandle == 0L) {
                            throw new UnsupportedOperationException(
                                    "Shift noise is not backed by a supported prepared Perlin stack");
                        }
                        if (capturePortable && portableGraphSupported) {
                            Integer stackSlot = perlinStackSlots.get(noise);
                            if (stackSlot == null) {
                                PortableDensityGraphCache.PerlinStack descriptor =
                                        NativePerlinNoiseBackend.portableStackDescriptor(noise);
                                if (descriptor == null) {
                                    portableGraphSupported = false;
                                } else {
                                    stackSlot = perlinStacks.size();
                                    perlinStacks.add(descriptor);
                                    perlinStackSlots.put(noise, stackSlot);
                                }
                            }
                            if (stackSlot != null) preparedHandleSlots.putIfAbsent(preparedHandle, stackSlot);
                        }
                        result = switch (kind) {
                            case "ShiftA" -> append(NativeRuntime.DensityNode.perlinStack(
                                    preparedHandle, 0.25, 0.0, 4.0F));
                            case "Shift" -> append(NativeRuntime.DensityNode.perlinStack(
                                    preparedHandle, 0.25, 0.25, 4.0F));
                            case "ShiftB" -> append(NativeRuntime.DensityNode.shiftBPerlinStack(preparedHandle));
                            default -> throw new AssertionError(kind);
                        };
                    }
                    case "NoiseFunction" -> {
                        Noise noise = resolveCapturedNoise((NoiseFunction) function, context);
                        long preparedHandle = NativePerlinNoiseBackend.preparedStackHandleForDensityGraph(noise);
                        if (preparedHandle == 0L) {
                            throw new UnsupportedOperationException("Noise sampler is not a supported Perlin stack");
                        }
                        if (capturePortable && portableGraphSupported) {
                            Integer stackSlot = perlinStackSlots.get(noise);
                            if (stackSlot == null) {
                                PortableDensityGraphCache.PerlinStack descriptor =
                                        NativePerlinNoiseBackend.portableStackDescriptor(noise);
                                if (descriptor == null) {
                                    portableGraphSupported = false;
                                } else {
                                    stackSlot = perlinStacks.size();
                                    perlinStacks.add(descriptor);
                                    perlinStackSlots.put(noise, stackSlot);
                                }
                            }
                            if (stackSlot != null) preparedHandleSlots.putIfAbsent(preparedHandle, stackSlot);
                        }
                        double xzScale = ((Number) component(function, "xzScale")).doubleValue();
                        double yScale = ((Number) component(function, "yScale")).doubleValue();
                        DensityFunction shiftX = (DensityFunction) component(function, "shiftX");
                        DensityFunction shiftY = (DensityFunction) component(function, "shiftY");
                        DensityFunction shiftZ = (DensityFunction) component(function, "shiftZ");
                        if (DensityFunctions.zero().equals(shiftX)
                                && DensityFunctions.zero().equals(shiftY)
                                && DensityFunctions.zero().equals(shiftZ)) {
                            result = append(NativeRuntime.DensityNode.perlinStack(
                                    preparedHandle, xzScale, yScale, 1.0F));
                        } else {
                            int shiftXIndex = lower(shiftX);
                            int shiftYIndex = lower(shiftY);
                            int shiftZIndex = lower(shiftZ);
                            result = append(NativeRuntime.DensityNode.perlinStackWarped(
                                    preparedHandle, xzScale, yScale,
                                    shiftXIndex, shiftYIndex, shiftZIndex));
                        }
                    }
                    default -> throw new UnsupportedOperationException("Unsupported density node " + kind);
                }
                indices.put(function, result);
                return result;
            } finally {
                active.remove(function);
            }
        }
    }
}
