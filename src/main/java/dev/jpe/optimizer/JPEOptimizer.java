package dev.jpe.optimizer;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
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
        LOGGER.info("JPE Optimizer lean-heap initialized. allocation-sampling={}, output={}",
                SAMPLER.allocationSupported(), SAMPLER.outputFile());
        Runtime.getRuntime().addShutdownHook(
                new Thread(SAMPLER::close, "jpe-optimizer-shutdown"));
    }
}