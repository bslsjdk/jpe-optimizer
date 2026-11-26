package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.JPEOptimizer;
import dev.jpe.optimizer.runtime.AquiferGridCenterMetrics;
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
 * Experimental opt-in replacement for the expensive per-cell positional RNG
 * population of vanilla's Aquifer location cache. Vanilla still performs all
 * nearest-four selection, fluid-status, pressure and block-state decisions.
 */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class AquiferNoiseBasedMixin {
    private static final AtomicBoolean REPORTED_FAILURE = new AtomicBoolean();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jpe$prefillAquiferLocationCache(CallbackInfo ci) {
        if (!Boolean.getBoolean("jpe.nativeAquiferGridCentersEnabled")) return;

        AquiferNoiseBasedAccessor aquifer = (AquiferNoiseBasedAccessor) this;
        long[] cache = aquifer.jpe$getAquiferLocationCache();
        if (cache == null || cache.length == 0 || cache.length > 1_048_576) return;

        // Vanilla initializes every cache entry to Long.MAX_VALUE. Never overwrite
        // a partially populated cache, which could belong to another integration.
        for (long location : cache) {
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

        NativeRuntime runtime = NativePerlinNoiseBackend.runtimeForDiagnostics();
        if (runtime == null || !runtime.supportsAquiferGridCenters()) return;

        int sizeX = aquifer.jpe$getGridSizeX();
        int sizeZ = aquifer.jpe$getGridSizeZ();
        if (sizeX <= 0 || sizeZ <= 0 || (long) sizeX * sizeZ > cache.length
                || cache.length % ((long) sizeX * sizeZ) != 0) return;

        long started = System.nanoTime();
        try {
            runtime.aquiferGridCenters(randomMode, seedLo, seedHi,
                    aquifer.jpe$getMinGridX(), aquifer.jpe$getMinGridY(), aquifer.jpe$getMinGridZ(),
                    sizeX, sizeZ, cache);
            long elapsed = System.nanoTime() - started;
            AquiferGridCenterMetrics.recordSuccess(cache.length, elapsed);
            JPEOptimizer.LOGGER.debug(
                    "Native Aquifer grid cache populated: mode={} cells={} elapsedUs={}",
                    randomMode, cache.length, elapsed / 1_000.0);
        } catch (RuntimeException | LinkageError failure) {
            AquiferGridCenterMetrics.recordFailure(cache.length, System.nanoTime() - started);
            if (REPORTED_FAILURE.compareAndSet(false, true)) {
                JPEOptimizer.LOGGER.warn(
                        "Experimental Native Aquifer cache prefill failed; leaving vanilla cache untouched: {}",
                        failure.toString());
            }
        }
    }
}
