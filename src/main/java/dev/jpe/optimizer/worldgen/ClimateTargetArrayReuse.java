package dev.jpe.optimizer.worldgen;

import net.minecraft.world.level.biome.Climate;

/**
 * Optional, thread-confined scratch storage for the short-lived target vector
 * consumed synchronously by Climate.RTree.search.
 *
 * Vanilla's search never retains the array after returning. A depth guard
 * allocates a fallback array for reentrant searches so an outer traversal's
 * coordinates cannot be overwritten by a nested call.
 */
public final class ClimateTargetArrayReuse {
    private static final boolean ENABLED = Boolean.getBoolean("jpe.climateTargetArrayReuse");
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    static {
        System.out.println("JPE_CLIMATE_TARGET_ARRAY_REUSE enabled=" + ENABLED);
    }

    private ClimateTargetArrayReuse() {}

    public static void enterSearch() {
        if (ENABLED) {
            STATE.get().depth++;
        }
    }

    public static void exitSearch() {
        if (ENABLED) {
            State state = STATE.get();
            if (state.depth > 0) {
                state.depth--;
            }
        }
    }

    public static long[] toParameterArray(Climate.TargetPoint target) {
        if (!ENABLED) {
            return allocate(target);
        }

        State state = STATE.get();
        if (state.depth != 1) {
            return allocate(target);
        }
        return fill(state.values, target);
    }

    static long[] toParameterArray(
            Climate.TargetPoint target,
            boolean reuse,
            boolean nested,
            long[] scratch
    ) {
        if (!reuse || nested) {
            return allocate(target);
        }
        return fill(scratch, target);
    }

    private static long[] fill(long[] values, Climate.TargetPoint target) {
        values[0] = target.temperature();
        values[1] = target.humidity();
        values[2] = target.continentalness();
        values[3] = target.erosion();
        values[4] = target.depth();
        values[5] = target.weirdness();
        values[6] = 0L;
        return values;
    }

    private static long[] allocate(Climate.TargetPoint target) {
        return new long[] {
                target.temperature(),
                target.humidity(),
                target.continentalness(),
                target.erosion(),
                target.depth(),
                target.weirdness(),
                0L
        };
    }

    private static final class State {
        private final long[] values = new long[7];
        private int depth;
    }
}
