package dev.jpe.optimizer;

import com.mojang.logging.LogUtils;
import dev.jpe.optimizer.runtime.JavaRuntimeDiagnostics;
import dev.jpe.optimizer.vector.DensityVectorRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(JPEOptimizer.MOD_ID)
public final class JPEOptimizer {
    public static final String MOD_ID = "jpe_optimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JPEOptimizer(IEventBus modEventBus) {
        GameTests.registerFunctions(modEventBus);
        modEventBus.addListener(GameTests::register);
        LOGGER.info("JPE Optimizer initialized for Minecraft 26.3");
        JavaRuntimeDiagnostics.logRuntimeCapabilities();
        LOGGER.info("JPE density kernels: {}", DensityVectorRuntime.initialize());
    }
}
