package dev.jpe.optimizer;

import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class GameTests {
    private GameTests() {}

    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, JPEOptimizer.MOD_ID);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> FLOAT_MATH_SMOKE =
            TEST_FUNCTIONS.register("float_math_smoke", () -> GameTests::floatMathSmoke);

    public static void registerFunctions(net.neoforged.bus.api.IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
    }

    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.parse("jpe_optimizer:default"),
                new TestEnvironmentDefinition.AllOf()
        );

        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                Identifier.parse("minecraft:empty"),
                40,
                0,
                true,
                Rotation.NONE
        );

        event.registerTest(
                Identifier.parse("jpe_optimizer:math_smoke"),
                new FunctionGameTestInstance(FLOAT_MATH_SMOKE.getKey(), data)
        );
    }

    private static void floatMathSmoke(GameTestHelper helper) {
        float x = 0.25f;
        float y = -0.5f;
        float z = 0.75f;
        float reference = dev.jpe.optimizer.math.DensityMath.syntheticGraph(x, y, z);
        float candidate = dev.jpe.optimizer.math.DensityMath.syntheticGraphShared(x, y, z);
        if (Float.floatToIntBits(reference) != Float.floatToIntBits(candidate)) {
            throw new AssertionError("Float32 CSE candidate mismatch: " + reference + " vs " + candidate);
        }

        // Verify the FastMath Mth.smoothstep @Overwrite mixin actually applied.
        // Sample many points and count bit differences from the exact polynomial.
        // The LUT approximation differs on most points (~1e-6, far above one
        // float ULP); if the mixin never loaded, all bits are identical.
        int differing = 0;
        int total = 0;
        for (int i = 1; i < 100; i++) {
            float v = i / 100.0f;
            float live = net.minecraft.util.Mth.smoothstep(v);
            float exact = v * v * v * (v * (v * 6.0f - 15.0f) + 10.0f);
            total++;
            if (Float.floatToIntBits(live) != Float.floatToIntBits(exact)) differing++;
        }
        if (differing < total / 2) {
            throw new AssertionError("Mth.smoothstep mixin did not apply (differing "
                    + differing + "/" + total + ")");
        }
        helper.succeed();
    }
}