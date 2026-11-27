package dev.jpe.optimizer;

import com.mojang.logging.LogUtils;
import dev.jpe.optimizer.benchmark.ContinuousWorldgenBenchmark;
import dev.jpe.optimizer.benchmark.ServerWorldgenBenchmark;
import dev.jpe.optimizer.runtime.JavaRuntimeDiagnostics;
import dev.jpe.optimizer.runtime.NativeRuntimeSelfCheck;
import dev.jpe.optimizer.vector.DensityVectorRuntime;
import dev.jpe.optimizer.world.WorldEngineHost;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(JPEOptimizer.MOD_ID)
public final class JPEOptimizer {
    public static final String MOD_ID = "jpe_optimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** The continuously-running client world host, or {@code null} off-client / disabled. */
    public static WorldEngineHost worldHost;

    public JPEOptimizer(IEventBus modEventBus) {
        GameTests.registerFunctions(modEventBus);
        modEventBus.addListener(GameTests::register);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, ServerWorldgenBenchmark::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, ContinuousWorldgenBenchmark::onServerTick);
        // Client-only: the guard keeps WorldEngineHost (and the Minecraft client classes
        // it imports) from ever being class-loaded on a dedicated server, where they are
        // absent. The reference is inside a branch the server never executes.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            worldHost = WorldEngineHost.initOnClient();
        }
        LOGGER.info("JPE Optimizer initialized for Minecraft 26.3");
        JavaRuntimeDiagnostics.logRuntimeCapabilities();
        // Proves the native payload shipped inside this jar can be unpacked and
        // linked on a dedicated server, independent of whether any worldgen path
        // has asked for it yet.
        NativeRuntimeSelfCheck.logBundledRuntime();
        LOGGER.info("JPE density kernels: {}", DensityVectorRuntime.initialize());
    }
}
