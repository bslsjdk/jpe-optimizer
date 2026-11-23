package dev.jpe.optimizer;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import dev.jpe.optimizer.alloc.AllocationSampler;

@Mod(JPEOptimizer.MOD_ID)
public final class JPEOptimizer {
    public static final String MOD_ID = "jpe_optimizer";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final AllocationSampler SAMPLER =
            new AllocationSampler(FMLPaths.GAMEDIR.get());

    public JPEOptimizer(IEventBus modEventBus) {
        GameTests.registerFunctions(modEventBus);
        modEventBus.addListener(GameTests::register);
        // Verify the Mth.smoothstep Mixin actually applied: compare the live
        // Mth.smoothstep(0.37f) against the reference polynomial. If the mixin
        // is active, they differ by ~1e-7 (table approximation); if not, identical.
        float live = Mth.smoothstep(0.37f);
        float reference = dev.jpe.optimizer.math.FastMath.smoothstepExact(0.37f);
        LOGGER.info("Lean-Heap: Mth.smoothstep(0.37)={} ref={} mixinApplied={}",
                live, reference, live != reference);
        LOGGER.info("Lean-Heap: allocation-sampling={}, output={}",
                SAMPLER.allocationSupported(), SAMPLER.outputFile());
        Runtime.getRuntime().addShutdownHook(
                new Thread(SAMPLER::close, "jpe-optimizer-shutdown"));
    }
}