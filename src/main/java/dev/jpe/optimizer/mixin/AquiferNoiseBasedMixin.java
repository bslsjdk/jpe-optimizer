package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.runtime.NativePerlinNoiseBackend;
import dev.jpe.optimizer.runtime.NativeRuntime;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in replacement for vanilla's per-cell positional RNG cache population.
 * FluidStatus, pressure, scheduling, and final block-state decisions remain vanilla.
 */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class AquiferNoiseBasedMixin {
    private static final AtomicBoolean REPORTED_FAILURE = new AtomicBoolean();
    private static final AtomicBoolean REPORTED_SUCCESS = new AtomicBoolean();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jpe$populateAquiferLocationCache(CallbackInfo ci) {
        if (!Boolean.getBoolean("jpe.nativeAquiferGridCentersEnabled")) return;

        AquiferNoiseBasedAccessor aquifer = (AquiferNoiseBasedAccessor) this;
        long[] vanillaCache = aquifer.jpe$getAquiferLocationCache();
        if (vanillaCache == null || vanillaCache.length == 0 || vanillaCache.length > 1_048_576) return;

        // Populate only a pristine vanilla cache. Never overwrite another integration's data.
        for (long location : vanillaCache) {
            if (location != Long.MAX_VALUE) return;
        }

        PositionalRandomFactory factory = aquifer.jpe$getPositionalRandomFactory();
        final int randomMode;
        final long seedLo;
        final long seedHi;
        if (factory instanceof XoroshiroPositionalRandomFactoryAccessor xoroshiro) {
            randomMode = 0;
            seedLo = xoroshiro.jpe$seedLo();
            seedHi = xoroshiro.jpe$seedHi();
        } else if (factory instanceof LegacyPositionalRandomFactoryAccessor legacy) {
            randomMode = 1;
            seedLo = legacy.jpe$seed();
            seedHi = 0L;
        } else {
            return;
        }

        NativeRuntime runtime = NativePerlinNoiseBackend.runtimeForAquifer();
        if (runtime == null) return;

        int sizeX = aquifer.jpe$getGridSizeX();
        int sizeZ = aquifer.jpe$getGridSizeZ();
        long layerSize = (long) sizeX * sizeZ;
        if (sizeX <= 0 || sizeZ <= 0 || layerSize > vanillaCache.length
                || vanillaCache.length % layerSize != 0) return;

        long started = System.nanoTime();
        try {
            // Generate off to the side; commit only after the entire native call succeeds.
            long[] generated = new long[vanillaCache.length];
            runtime.aquiferGridCenters(randomMode, seedLo, seedHi,
                    aquifer.jpe$getMinGridX(), aquifer.jpe$getMinGridY(), aquifer.jpe$getMinGridZ(),
                    sizeX, sizeZ, generated);
            System.arraycopy(generated, 0, vanillaCache, 0, generated.length);
            if (REPORTED_SUCCESS.compareAndSet(false, true)) {
                JPEOptimizer.LOGGER.info(
                        "JPE_NATIVE_AQUIFER_CACHE_PREFILL mode={} cells={} elapsedUs={}",
                        randomMode, generated.length, (System.nanoTime() - started) / 1_000.0);
            }
        } catch (RuntimeException | LinkageError failure) {
            if (REPORTED_FAILURE.compareAndSet(false, true)) {
                JPEOptimizer.LOGGER.warn(
                        "Experimental native Aquifer cache prefill failed; vanilla cache was retained: {}",
                        failure.toString());
            }
        }
    }
}
