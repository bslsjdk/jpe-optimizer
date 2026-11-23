package dev.jpe.optimizer;

import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
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

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> TERRAIN_GENERATION_SMOKE =
            TEST_FUNCTIONS.register("terrain_generation_smoke", () -> GameTests::terrainGenerationSmoke);

    public static void registerFunctions(net.neoforged.bus.api.IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
    }

    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.parse("jpe_optimizer:default"),
                new TestEnvironmentDefinition.AllOf()
        );

        // Allow the terrain smoke test to finish a genuinely fresh chunk.
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                Identifier.parse("minecraft:empty"),
                1200,
                0,
                true,
                Rotation.NONE
        );

        event.registerTest(
                Identifier.parse("jpe_optimizer:math_smoke"),
                new FunctionGameTestInstance(FLOAT_MATH_SMOKE.getKey(), data)
        );
        event.registerTest(
                Identifier.parse("jpe_optimizer:terrain_generation_smoke"),
                new FunctionGameTestInstance(TERRAIN_GENERATION_SMOKE.getKey(), data)
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
        helper.succeed();
    }

    /**
     * Forces generation of a remote overworld chunk instead of merely starting
     * GameTestServer. This exercises the NoiseBasedChunkGenerator mixin on the
     * normal terrain-generation path.
     */
    private static void terrainGenerationSmoke(GameTestHelper helper) {
        int chunkX = 4096;
        int chunkZ = -4096;
        var chunk = helper.getLevel().getChunk(chunkX, chunkZ);

        if (chunk.getPos().x != chunkX || chunk.getPos().z != chunkZ) {
            throw new AssertionError("Server returned the wrong chunk: " + chunk.getPos());
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean foundTerrain = false;
        int minY = helper.getLevel().getMinBuildHeight();
        int maxY = helper.getLevel().getMaxBuildHeight();
        for (int x = 0; x < 16 && !foundTerrain; ++x) {
            for (int z = 0; z < 16 && !foundTerrain; ++z) {
                for (int y = minY; y < maxY; y += 8) {
                    pos.set(chunkX * 16 + x, y, chunkZ * 16 + z);
                    if (!chunk.getBlockState(pos).isAir()) {
                        foundTerrain = true;
                        break;
                    }
                }
            }
        }

        if (!foundTerrain) {
            throw new AssertionError("Fresh overworld chunk contains no generated terrain blocks");
        }
        helper.succeed();
    }
}
