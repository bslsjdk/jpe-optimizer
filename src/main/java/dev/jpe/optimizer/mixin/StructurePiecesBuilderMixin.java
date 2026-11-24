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

    @Unique
    private long jpe$indexEpoch = Long.MIN_VALUE;

    @Inject(method = "findCollisionPiece", at = @At("HEAD"), cancellable = true)
    private void jpe$findCollisionPiece(BoundingBox query,
                                        CallbackInfoReturnable<StructurePiece> callback) {
        if (!Boolean.parseBoolean(System.getProperty("jpe.structureIndexEnabled", "true"))
                || this.pieces.size() < StructureSpatialIndex.MIN_INDEXED_PIECES) {
            StructureSpatialIndex.recordVanillaLinearQuery(this.pieces.size());
            return;
        }

        long currentEpoch = StructureSpatialIndex.movementEpoch();
        StructureSpatialIndex index = this.jpe$spatialIndex;
        if (index != null && this.jpe$indexEpoch != currentEpoch) {
            index = null;
            this.jpe$spatialIndex = null;
        }
        if (index == null) {
            long buildEpoch = currentEpoch;
            StructureSpatialIndex.Box[] initialBoxes = new StructureSpatialIndex.Box[this.pieces.size()];
            for (int i = 0; i < initialBoxes.length; ++i) {
                StructurePiece piece = this.pieces.get(i);
                initialBoxes[i] = piece == null ? null : jpe$copyBox(piece.getBoundingBox());
            }
            index = new StructureSpatialIndex(initialBoxes);
            // If another thread moved a box during the snapshot, use vanilla for this query.
            if (StructureSpatialIndex.movementEpoch() != buildEpoch) {
                return;
            }
            this.jpe$spatialIndex = index;
            this.jpe$indexEpoch = buildEpoch;
        }

        int collisionIndex = index.findFirst(jpe$copyBox(query));
        // Do not return a result from a snapshot invalidated while the query was running.
        if (StructureSpatialIndex.movementEpoch() != this.jpe$indexEpoch) {
            this.jpe$spatialIndex = null;
            return;
        }
        callback.setReturnValue(collisionIndex < 0 ? null : this.pieces.get(collisionIndex));
    }

    @Inject(method = "addPiece", at = @At("TAIL"))
    private void jpe$addPiece(StructurePiece piece, CallbackInfo callback) {
        if (this.jpe$spatialIndex != null) {
            this.jpe$spatialIndex.add(piece == null ? null : jpe$copyBox(piece.getBoundingBox()));
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

    @Unique
    private static StructureSpatialIndex.Box jpe$copyBox(BoundingBox box) {
        return box == null ? null : new StructureSpatialIndex.Box(
                box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()
        );
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void jpe$afterClear(CallbackInfo callback) {
        this.jpe$spatialIndex = null;
    }
}
