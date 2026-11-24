package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.StructureSpatialIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks mutations of mutable Minecraft bounding boxes so cached structure
 * broadphases cannot retain stale coordinates after a piece moves directly.
 */
@Mixin(BoundingBox.class)
public abstract class BoundingBoxMixin {
    @Inject(method = "move(III)", at = @At("TAIL"))
    private void jpe$afterMove(int dx, int dy, int dz,
                               CallbackInfoReturnable<BoundingBox> callback) {
        StructureSpatialIndex.noteBoundsMutation();
    }

    @Inject(method = "move(Lnet/minecraft/core/Vec3i;)", at = @At("TAIL"))
    private void jpe$afterMoveVector(Vec3i amount,
                                     CallbackInfoReturnable<BoundingBox> callback) {
        StructureSpatialIndex.noteBoundsMutation();
    }

    @Inject(method = "encapsulate(Lnet/minecraft/core/BlockPos;)", at = @At("TAIL"))
    private void jpe$afterEncapsulatePosition(BlockPos position,
                                              CallbackInfoReturnable<BoundingBox> callback) {
        StructureSpatialIndex.noteBoundsMutation();
    }

    @Inject(method = "encapsulate(Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)",
            at = @At("TAIL"))
    private void jpe$afterEncapsulateBox(BoundingBox other,
                                         CallbackInfoReturnable<BoundingBox> callback) {
        StructureSpatialIndex.noteBoundsMutation();
    }
}
