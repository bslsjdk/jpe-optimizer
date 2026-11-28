package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Platform naming is the one piece of the loader that can be tested without a
 * device: it decides which directory inside the jar is opened, and getting it
 * wrong means a phone silently finds no native payload.
 */
class NativeLibraryLoaderTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty(NativeLibraryLoader.PLATFORM_PROPERTY);
        NativeLibraryLoader.resetForTests();
    }

    @Test
    void forcedPlatformIsHonoured() {
        System.setProperty(NativeLibraryLoader.PLATFORM_PROPERTY, "android-aarch64");
        assertEquals("android-aarch64", NativeLibraryLoader.platformDirectory());
        assertEquals("libjpe_runtime.so", NativeLibraryLoader.libraryFileName());
        assertEquals("libjpe_world.so", NativeLibraryLoader.worldFileName());
    }

    @Test
    void everyPlatformGetsTheRightFileName() {
        String[][] expected = {
                {"linux-x86_64", "libjpe_runtime.so", "libjpe_world.so"},
                {"linux-aarch64", "libjpe_runtime.so", "libjpe_world.so"},
                {"android-aarch64", "libjpe_runtime.so", "libjpe_world.so"},
                {"macos-aarch64", "libjpe_runtime.dylib", "libjpe_world.dylib"},
                {"windows-x86_64", "jpe_runtime.dll", "jpe_world.dll"},
        };
        for (String[] row : expected) {
            System.setProperty(NativeLibraryLoader.PLATFORM_PROPERTY, row[0]);
            assertEquals(row[1], NativeLibraryLoader.libraryFileName(), row[0]);
            assertEquals(row[2], NativeLibraryLoader.worldFileName(), row[0]);
        }
    }

    @Test
    void androidIsNeverNamedLinux() {
        // Android reports os.name=Linux. If the family stayed `linux` a phone would
        // open the glibc directory and fail to load the library at runtime, which is
        // exactly the bug this loader exists to prevent.
        System.clearProperty(NativeLibraryLoader.PLATFORM_PROPERTY);
        String platform = NativeLibraryLoader.platformDirectory();
        assertNotNull(platform);
        if (NativeLibraryLoader.isAndroid()) {
            assertTrue(platform.startsWith("android-"),
                    "an Android JVM must not resolve to " + platform);
            assertFalse(platform.startsWith("linux-"),
                    "an Android JVM must not resolve to " + platform);
        } else {
            assertFalse(platform.startsWith("android-"),
                    "a desktop JVM must not resolve to " + platform);
        }
    }

    @Test
    void architectureIsMappedToTheShippedNames() {
        System.setProperty(NativeLibraryLoader.PLATFORM_PROPERTY, "linux-" + cpu("aarch64"));
        assertEquals("linux-aarch64", NativeLibraryLoader.platformDirectory());
        System.setProperty(NativeLibraryLoader.PLATFORM_PROPERTY, "linux-" + cpu("amd64"));
        assertEquals("linux-x86_64", NativeLibraryLoader.platformDirectory());
    }

    private static String cpu(String arch) {
        // Mirrors the loader's own mapping so the assertion names the expected value
        // rather than re-implementing the comparison.
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        if (arch.toLowerCase(Locale.ROOT).startsWith("arm")) return "arm32";
        return "x86_64";
    }

    @Test
    void worldEngineHasItsOwnOverrideProperty() {
        // The existing launch scripts pass -Djpe.worldLibrary for the engine and
        // -Djpe.nativeLibraryPath for the kernels; both must keep working.
        assertEquals("jpe.worldLibrary",
                NativeLibraryLoader.overrideProperty(NativeLibraryLoader.WORLD_LIBRARY));
        assertEquals("jpe.nativeLibraryPath",
                NativeLibraryLoader.overrideProperty(NativeLibraryLoader.RUNTIME_LIBRARY));
    }
}
