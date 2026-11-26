package dev.jpe.optimizer.world;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Copies real Minecraft {@link LevelChunkSection} block states into the block-index
 * layout the Rust world engine consumes: one index per block in
 * {@code (y << 8) | (z << 4) | x} order with {@code 0} meaning air.
 *
 * <p><b>Transitional Stage 6 design.</b> The indices are assigned by this reader in
 * order of first appearance, so they identify "the section-local state", not the
 * global block ids the C++ terrain engine will eventually produce. Terrain
 * generation stays in Minecraft; everything after this hand-off — meshing, GPU
 * residency, visibility, culling, per-frame scheduling — runs in Rust.
 *
 * <p>Every read is cross-checked against Minecraft's own palette bookkeeping: the
 * non-air blocks counted by the per-block scan must equal the sum of the palette
 * entry counts reported by {@link net.minecraft.world.level.chunk.PalettedContainer#count}.
 * A mismatch means the hand-off silently dropped or duplicated blocks, so it throws
 * instead of logging.
 */
public final class MinecraftChunkReader {

    /** Blocks in one section, matching {@code ChunkSection::VOLUME} in Rust. */
    public static final int SECTION_VOLUME = 16 * 16 * 16;
    /** Air is index 0 in the engine layout; real blocks start at 1. */
    public static final int FIRST_BLOCK_ID = 1;
    /** The hand-off type is u16, so ids wrap into a shared fallback above this. */
    public static final int MAX_BLOCK_ID = 65_535;

    /** Identity is correct because {@link BlockState} instances are singletons. */
    private final Map<BlockState, Integer> ids = new IdentityHashMap<>();
    private int nextId = FIRST_BLOCK_ID;

    private long sectionsRead;
    private long sectionsEmpty;
    private long sectionsMissing;
    private long sectionsOutOfRange;
    private long blocksScanned;
    private long nonAirBlocks;
    private long paletteBlocks;
    private long idOverflows;

    /** Air is always 0; the first unseen state takes the next free id. */
    public int idOf(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        Integer existing = ids.get(state);
        if (existing != null) {
            return existing;
        }
        int id = nextId;
        if (id > MAX_BLOCK_ID) {
            // u16 hand-off cannot represent more distinct states; reuse the top id
            // rather than dropping blocks. Recorded in idOverflows for the report.
            id = MAX_BLOCK_ID;
            idOverflows++;
        } else {
            nextId++;
        }
        ids.put(state, id);
        return id;
    }

    /** Distinct non-air block states seen so far. */
    public int distinctStates() {
        return ids.size();
    }

    /**
     * The first {@code limit} assigned ids with their registry names, for logs and
     * failure messages. Names come from Minecraft's own block registry, so a line
     * like {@code minecraft:grass_block=3} is directly checkable in game.
     */
    public String stateNames(int limit) {
        StringBuilder out = new StringBuilder();
        java.util.List<Map.Entry<BlockState, Integer>> sorted = new java.util.ArrayList<>(ids.entrySet());
        sorted.sort(java.util.Map.Entry.comparingByValue());
        for (int i = 0; i < Math.min(limit, sorted.size()); ++i) {
            if (i > 0) {
                out.append(',');
            }
            out.append(BuiltInRegistries.BLOCK.getKey(sorted.get(i).getKey().getBlock()))
                    .append('=')
                    .append(sorted.get(i).getValue());
        }
        return out.toString();
    }

    /**
     * Read {@code sectionCount} sections of {@code chunk} starting at the section
     * Y coordinate {@code baseSectionY} (world section coordinates, i.e. -4..19
     * for a standard overworld).
     *
     * @return {@code sectionCount * 4096} indices, ready for
     *         {@code jpe_pipeline_submit_chunk}
     */
    public short[] read(ChunkAccess chunk, int baseSectionY, int sectionCount) {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk is null");
        }
        if (sectionCount <= 0) {
            throw new IllegalArgumentException("sectionCount must be positive");
        }
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = SectionPos.blockToSectionCoord(chunk.getMinY());
        short[] out = new short[sectionCount * SECTION_VOLUME];

        for (int i = 0; i < sectionCount; ++i) {
            int slot = baseSectionY + i - minSection;
            if (slot < 0 || slot >= sections.length) {
                sectionsOutOfRange++;
                continue;
            }
            LevelChunkSection section = sections[slot];
            sectionsRead++;
            if (section == null) {
                sectionsMissing++;
                continue;
            }
            int palette = paletteNonAir(section);
            paletteBlocks += palette;
            if (section.hasOnlyAir()) {
                sectionsEmpty++;
                if (palette != 0) {
                    throw new IllegalStateException("Minecraft reports an air-only section with "
                            + palette + " non-air palette entries at section y="
                            + (baseSectionY + i));
                }
                continue;
            }
            int offset = i * SECTION_VOLUME;
            int local = 0;
            for (int y = 0; y < 16; ++y) {
                for (int z = 0; z < 16; ++z) {
                    for (int x = 0; x < 16; ++x) {
                        int id = idOf(section.getBlockState(x, y, z));
                        if (id != 0) {
                            nonAirBlocks++;
                        }
                        blocksScanned++;
                        out[offset + local++] = (short) id;
                    }
                }
            }
            int sectionNonAir = 0;
            for (int j = 0; j < SECTION_VOLUME; ++j) {
                if (out[offset + j] != 0) {
                    sectionNonAir++;
                }
            }
            if (sectionNonAir != palette) {
                throw new IllegalStateException("block hand-off mismatch in section y="
                        + (baseSectionY + i) + ": scanned=" + sectionNonAir + ", palette=" + palette);
            }
        }
        return out;
    }

    private int paletteNonAir(LevelChunkSection section) {
        int[] total = new int[1];
        section.getStates().count((state, count) -> {
            // Mirrors idOf(): air is index 0 and must not reach the engine as geometry.
            if (!state.isAir()) {
                total[0] += count;
            }
        });
        return total[0];
    }

    /** Cumulative counters for the reader's whole lifetime. */
    public String describe() {
        return "sections=" + sectionsRead
                + " empty=" + sectionsEmpty
                + " missing=" + sectionsMissing
                + " outOfRange=" + sectionsOutOfRange
                + " blocksScanned=" + blocksScanned
                + " nonAir=" + nonAirBlocks
                + " paletteNonAir=" + paletteBlocks
                + " distinctStates=" + ids.size()
                + " idOverflows=" + idOverflows;
    }

    public long sectionsRead() { return sectionsRead; }
    public long sectionsEmpty() { return sectionsEmpty; }
    public long nonAirBlocks() { return nonAirBlocks; }
    public long paletteBlocks() { return paletteBlocks; }
    public long idOverflows() { return idOverflows; }
    public long blocksScanned() { return blocksScanned; }

    /** True when the per-block scan and Minecraft's palette counts agree entirely. */
    public boolean paletteParity() {
        return nonAirBlocks == paletteBlocks;
    }
}
