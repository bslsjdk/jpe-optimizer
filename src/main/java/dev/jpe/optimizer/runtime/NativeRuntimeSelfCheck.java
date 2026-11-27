package dev.jpe.optimizer.runtime;

import java.nio.file.Path;

/**
 * Loads the bundled native runtime once at startup and reports what it found.
 *
 * <p>On a dedicated server this is the only place that proves the jar's own
 * {@code META-INF/natives} payload can be unpacked and linked: world generation
 * itself only reaches the library when the final-density hook is switched on,
 * and that path refuses graphs it cannot lower completely. The check is
 * diagnostic: a missing library logs a warning and leaves the game on Java.
 */
public final class NativeRuntimeSelfCheck {
    private static final System.Logger LOGGER =
            System.getLogger(NativeRuntimeSelfCheck.class.getName());

    private NativeRuntimeSelfCheck() {}

    /** @return true when the bundled runtime loaded and answered the ABI probe. */
    public static boolean logBundledRuntime() {
        Path library = NativeLibraryLoader.resolve();
        if (library == null) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Native runtime: not found (no bundled resource, no override, no local build)");
            return false;
        }
        try (NativeRuntime runtime = NativeRuntime.load(library)) {
            LOGGER.log(System.Logger.Level.INFO,
                    "Native runtime ready: path={0} platform={1} perlinStack={2} blender={3} aquiferXoroshiro={4}",
                    library,
                    NativeLibraryLoader.platformDirectory(),
                    runtime.supportsPerlinStack(),
                    runtime.supportsBlenderDensityVolume(),
                    runtime.supportsAquiferXoroshiroOffsets());
            return true;
        } catch (Throwable failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Native runtime failed to load from " + library, failure);
            return false;
        }
    }
}
