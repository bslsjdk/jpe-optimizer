package dev.jpe.optimizer.mixin;

import dev.jpe.optimizer.worldgen.StructureSpatialIndex;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the builder's O(N) collision scan with an XZ broadphase once a
 * structure has enough pieces to amortize index construction.
 */
@Mixin(StructurePiecesBuilder.class)
public abstract class StructurePiecesBuilderMixin {
    @Shadow @Final private List<StructurePiece> pieces;

    @Unique
    private StructureSpatialIndex jpe$spatialIndex;

    @Inject(method = "findCollisionPiece", at = @At("HEAD"), cancellable = true)
    private void jpe$findCollisionPiece(BoundingBox query,
                                        CallbackInfoReturnable<StructurePiece> callback) {
        if (this.pieces.size() < StructureSpatialIndex.MIN_INDEXED_PIECES) {
            return;
        }

        StructureSpatialIndex index = this.jpe$spatialIndex;
        if (index == null) {
            BoundingBox[] initialBoxes = new BoundingBox[this.pieces.size()];
            for (int i = 0; i < initialBoxes.length; ++i) {
                StructurePiece piece = this.pieces.get(i);
                initialBoxes[i] = piece == null ? null : piece.getBoundingBox();
            }
            index = new StructureSpatialIndex(initialBoxes);
            this.jpe$spatialIndex = index;
        }

        int collisionIndex = index.findFirst(query);
        callback.setReturnValue(collisionIndex < 0 ? null : this.pieces.get(collisionIndex));
    }

    @Inject(method = "addPiece", at = @At("TAIL"))
    private void jpe$addPiece(StructurePiece piece, CallbackInfo callback) {
        if (this.jpe$spatialIndex != null) {
            this.jpe$spatialIndex.add(piece == null ? null : piece.getBoundingBox());
        }
    }

    @Inject(method = "moveInsideHeights", at = @At("TAIL"))
    private void jpe$afterMoveInsideHeights(RandomSource random, int lowestAllowed,
                                             int highestAllowed, CallbackInfo callback) {
        this.jpe$spatialIndex = null;
    }

    @Inject(method = "offsetPiecesVertically", at = @At("TAIL"))
    private void jpe$afterOffsetPiecesVertically(int dy, CallbackInfo callback) {
        this.jpe$spatialIndex = null;
    }

    @Inject(method = "moveBelowSeaLevel", at = @At("TAIL"))
    private void jpe$afterMoveBelowSeaLevel(int seaLevel, int minY, RandomSource random,
                                             int offset, CallbackInfoReturnable<Integer> callback) {
        this.jpe$spatialIndex = null;
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void jpe$afterClear(CallbackInfo callback) {
        this.jpe$spatialIndex = null;
    }
}
