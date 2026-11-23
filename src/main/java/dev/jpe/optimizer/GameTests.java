package dev.jpe.optimizer;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

public final class GameTests {
    private GameTests() {}

    public static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition> environment = event.registerEnvironment(
                Identifier.parse("jpe_optimizer:default"),
                new TestEnvironmentDefinition.AllOf()
        );

        TestData<Holder<TestEnvironmentDefinition>> data = new TestData<>(
                environment,
                Identifier.parse("minecraft:empty"),
                40,
                0,
                true
        );

        event.registerTest(
                Identifier.parse("jpe_optimizer:math_smoke"),
                (GameTestHelper helper) -> {
                    float x = 0.25f;
                    float y = -0.5f;
                    float z = 0.75f;
                    float reference = dev.jpe.optimizer.math.DensityMath.syntheticGraph(x, y, z);
                    float candidate = dev.jpe.optimizer.math.DensityMath.syntheticGraphShared(x, y, z);
                    if (Float.floatToIntBits(reference) != Float.floatToIntBits(candidate)) {
                        throw new AssertionError("Float32 CSE candidate mismatch: " + reference + " vs " + candidate);
                    }
                    helper.succeed();
                },
                data
        );
    }
}
