package dev.jpe.optimizer.worldgen;

/** Runtime marker for the opt-in 3-bit/4-bit palette factory experiment. */
public final class PaletteHashExperiment {
    private static volatile boolean applied;

    private PaletteHashExperiment() {}

    public static boolean applied() {
        return applied;
    }

    public static void markApplied() {
        applied = true;
    }
}
