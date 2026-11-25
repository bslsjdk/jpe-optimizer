package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.runtime.NativeBlenderDensityBackend;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Replaces Blender's per-voxel Java loop with one complete Native volume call when enabled. */
@Mixin(targets = "net.minecraft.world.level.levelgen.densityfunction.op.BlendDensityFunction$Sampler")
public abstract class BlendDensityFunctionSamplerMixin {
    @Shadow @Final private DensitySampler input;

    @Overwrite
    public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
        this.input.sampleVolume(context, outputBuffer, volume);
        if (NativeBlenderDensityBackend.tryBlend(context, volume, outputBuffer)) return;

        Blender blender = context.getField(Blender.CONTEXT_KEY);
        if (blender == null || blender.isEmpty()) return;
        int index = 0;
        for (int z = 0; z < volume.sizeZ(); ++z) {
            int blockZ = volume.blockZ(z);
            for (int x = 0; x < volume.sizeX(); ++x) {
                int blockX = volume.blockX(x);
                for (int y = 0; y < volume.sizeY(); ++y) {
                    int blockY = volume.blockY(y);
                    outputBuffer.set(index, blender.blendDensity(
                            blockX, blockY, blockZ, outputBuffer.get(index)));
                    ++index;
                }
            }
        }
    }
}
