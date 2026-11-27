package dev.jpe.optimizer.world;

/**
 * Cheap, allocation-free content signature for a chunk column, used to detect when a
 * resident chunk's blocks changed and therefore needs re-submitting to the engine.
 *
 * <p>This is a 64-bit FNV-1a fold over the u16 block ids the
 * {@link MinecraftChunkReader} hands to Rust, in the exact
 * {@code (section, y, z, x)} order the reader uses. It is deliberately not a
 * cryptographic hash: collisions only cost a missed re-mesh, never wrong geometry, and
 * the function is only ever compared against itself for the same reader mapping.
 *
 * <p>Two callers must agree on the fold for equality to mean "unchanged":
 * {@link #of(short[])} folds a fully read column array, and
 * {@link MinecraftChunkReader#signature} folds the same sequence without allocating the
 * array. Both go through {@link #step} and {@link #foldAirSection} so an all-air section
 * costs one multiply instead of 4096.
 */
public final class BlockSignature {

    /** FNV-1a 64-bit offset basis. */
    public static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    /** FNV-1a 64-bit prime. */
    public static final long PRIME = 0x100000001b3L;

    /** {@code PRIME^4096 mod 2^64}: one multiply folds a whole all-air section of zeros. */
    static final long AIR_SECTION_MULTIPLIER = powPrime(MinecraftChunkReader.SECTION_VOLUME);

    private BlockSignature() {
    }

    /** One FNV-1a step over a single u16 block id. */
    public static long step(long hash, int id) {
        hash ^= id & 0xFFFFL;
        hash *= PRIME;
        return hash;
    }

    /**
     * Fold an all-air section (4096 zeros) in one multiply. Equivalent to calling
     * {@link #step} 4096 times with id {@code 0}, which is what the array form does for
     * the default-zero region of a skipped section.
     */
    public static long foldAirSection(long hash) {
        return hash * AIR_SECTION_MULTIPLIER;
    }

    /** Signature of a whole read column array. */
    public static long of(short[] ids) {
        return of(ids, ids.length);
    }

    /** Signature of the first {@code length} ids of a column array. */
    public static long of(short[] ids, int length) {
        if (length < 0 || length > ids.length) {
            throw new IllegalArgumentException("length " + length + " out of range 0.." + ids.length);
        }
        long hash = OFFSET_BASIS;
        for (int i = 0; i < length; ++i) {
            hash = step(hash, ids[i]);
        }
        return hash;
    }

    private static long powPrime(int exponent) {
        long result = 1L;
        long base = PRIME;
        int e = exponent;
        while (e > 0) {
            if ((e & 1) != 0) {
                result *= base;
            }
            base *= base;
            e >>>= 1;
        }
        return result;
    }
}