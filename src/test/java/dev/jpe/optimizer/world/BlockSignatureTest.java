package dev.jpe.optimizer.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure tests for {@link BlockSignature}. They pin the two properties the dirty-block
 * detector relies on: identical content folds to an identical value (so an unchanged
 * chunk is never re-submitted), and any single id edit changes it (so a real edit is
 * always caught). The all-air fast path must equal the naive per-zero fold.
 */
class BlockSignatureTest {

    @Test
    void emptyAndAirInputsFoldFromTheOffsetBasis() {
        assertEquals(BlockSignature.OFFSET_BASIS, BlockSignature.of(new short[0]));
        // An all-air column: the array path folds 4096 zeros, which must equal the
        // one-multiply fast path used by the allocation-free reader.
        long expected = BlockSignature.foldAirSection(BlockSignature.OFFSET_BASIS);
        assertEquals(expected, BlockSignature.of(new short[MinecraftChunkReader.SECTION_VOLUME]));
    }

    @Test
    void foldAirSectionEqualsPerZeroStepping() {
        long stepped = BlockSignature.OFFSET_BASIS;
        for (int i = 0; i < MinecraftChunkReader.SECTION_VOLUME; i++) {
            stepped = BlockSignature.step(stepped, 0);
        }
        assertEquals(stepped, BlockSignature.foldAirSection(BlockSignature.OFFSET_BASIS));
    }

    @Test
    void identicalContentIsStable() {
        short[] a = {0, 1, 2, 3, 0, 0, 5};
        short[] b = a.clone();
        assertEquals(BlockSignature.of(a), BlockSignature.of(b));
    }

    @Test
    void aSingleIdEditChangesTheSignature() {
        short[] a = {0, 1, 2, 3, 4};
        short[] b = {0, 1, 9, 3, 4};
        assertNotEquals(BlockSignature.of(a), BlockSignature.of(b));
    }

    @Test
    void reorderingChangesTheSignature() {
        short[] a = {1, 2, 3};
        short[] b = {1, 3, 2};
        assertNotEquals(BlockSignature.of(a), BlockSignature.of(b));
    }

    @Test
    void lengthAffectsTheSignature() {
        assertNotEquals(BlockSignature.of(new short[]{1, 2}),
                BlockSignature.of(new short[]{1, 2, 3}));
    }

    @Test
    void lengthBoundedFoldMatchesFullArray() {
        short[] ids = {1, 2, 3, 4, 5};
        // Folding only the first three must equal folding a three-element array.
        assertEquals(BlockSignature.of(new short[]{1, 2, 3}), BlockSignature.of(ids, 3));
    }

    @Test
    void idsAreTreatedAsUnsignedSixteenBit() {
        // idOf can hand back up to 65535; stored in a short that is negative above 32767.
        short high = (short) 0xFFFF;
        assertEquals(BlockSignature.step(BlockSignature.OFFSET_BASIS, 65535),
                BlockSignature.step(BlockSignature.OFFSET_BASIS, high & 0xFFFF));
        long a = BlockSignature.of(new short[]{(short) 0x8000});
        long b = BlockSignature.of(new short[]{-1});
        assertNotEquals(a, b);
    }

    @Test
    void rejectsOutOfRangeLength() {
        short[] ids = {1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> BlockSignature.of(ids, -1));
        assertThrows(IllegalArgumentException.class, () -> BlockSignature.of(ids, 4));
    }

    @Test
    void differentContentOverwhelminglyDiffers() {
        // Not a security claim, just a sanity check that distinct small edits do not
        // collapse to one value for a run of realistic ids.
        int collisions = 0;
        for (int i = 0; i < 1000; i++) {
            short[] a = new short[64];
            short[] b = a.clone();
            a[i % 64] = 1;
            b[i % 64] = (short) (2 + i);
            if (BlockSignature.of(a) == BlockSignature.of(b)) {
                collisions++;
            }
        }
        assertTrue(collisions == 0, "unexpected collisions: " + collisions);
    }
}