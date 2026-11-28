package dev.jpe.optimizer.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Finds a native library for a packaged mod.
 *
 * <p>A development checkout keeps the cdylibs under {@code native/<crate>/target}.
 * A released jar cannot: the game process starts in some other working directory,
 * so each library is bundled as {@code META-INF/natives/<platform>/<name>} and
 * unpacked once into the JVM temporary directory. The file name and size are
 * checked before reuse, which is enough to avoid a partial copy after a crash;
 * the jar's own copy is authoritative.
 *
 * <h2>Platforms</h2>
 *
 * <p>{@link #platformDirectory()} names a platform {@code <family>-<cpu>}.
 * Android is its own family even though {@code os.name} says {@code Linux}:
 * Android's JVM links against bionic, and a glibc build for the same CPU cannot
 * be loaded into it. The two are therefore never interchangeable, and the
 * directory name has to distinguish them or a phone would pick up the desktop
 * Linux library and fail to load it at runtime.
 */
public final class NativeLibraryLoader {
    /** System property that overrides the runtime-kernel lookup. */
    public static final String LIBRARY_PATH_PROPERTY = "jpe.nativeLibraryPath";

    /** System property that overrides the world-engine lookup. */
    public static final String WORLD_PATH_PROPERTY = "jpe.worldLibrary";

    /** System property that forces a platform directory, mainly for tests. */
    public static final String PLATFORM_PROPERTY = "jpe.nativePlatform";

    /** Crate name of the density/perlin kernels. */
    public static final String RUNTIME_LIBRARY = "jpe_runtime";

    /** Crate name of the Rust world engine. */
    public static final String WORLD_LIBRARY = "jpe_world";

    private static final String RESOURCE_ROOT = "/META-INF/natives/";
    private static final Object LOCK = new Object();
    private static final Map<String, Path> RESOLVED = new HashMap<>();
    private static final Map<String, Boolean> ATTEMPTED = new HashMap<>();

    private NativeLibraryLoader() {}

    /**
     * Returns the runtime library to load, or {@code null} when this platform has
     * no bundled native and no development build is present.
     */
    public static Path resolve() {
        return resolve(RUNTIME_LIBRARY);
    }

    /**
     * Returns the library called {@code baseName}, or {@code null}.
     *
     * @param baseName crate name without prefix or suffix, for example
     *                 {@link #RUNTIME_LIBRARY} or {@link #WORLD_LIBRARY}
     */
    public static Path resolve(String baseName) {
        synchronized (LOCK) {
            if (Boolean.TRUE.equals(ATTEMPTED.get(baseName))) return RESOLVED.get(baseName);
            ATTEMPTED.put(baseName, Boolean.TRUE);

            String override = System.getProperty(overrideProperty(baseName));
            if (override != null && !override.isBlank()) {
                Path path = Path.of(override).toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) {
                    RESOLVED.put(baseName, path);
                    return path;
                }
                System.getLogger(NativeLibraryLoader.class.getName()).log(System.Logger.Level.WARNING,
                        "{0} points at a missing file: {1}", overrideProperty(baseName), path);
            }

            Path bundled = extractBundled(baseName);
            Path resolved = bundled != null ? bundled : findDevelopmentBuild(baseName);
            RESOLVED.put(baseName, resolved);
            return resolved;
        }
    }

    /**
     * Property that overrides the lookup for a given library. The two keep the
     * names they already had, so existing launch scripts and CI rigs keep working.
     */
    public static String overrideProperty(String baseName) {
        return WORLD_LIBRARY.equals(baseName) ? WORLD_PATH_PROPERTY : LIBRARY_PATH_PROPERTY;
    }

    /** Platform directory used inside the jar, for example {@code linux-x86_64}. */
    public static String platformDirectory() {
        String forced = System.getProperty(PLATFORM_PROPERTY);
        if (forced != null && !forced.isBlank()) return forced.trim().toLowerCase(Locale.ROOT);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String family;
        if (os.contains("win")) {
            family = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            family = "macos";
        } else if (isAndroid()) {
            family = "android";
        } else {
            family = "linux";
        }
        return family + "-" + cpuName(arch);
    }

    /**
     * True when this JVM runs on Android. Android reports {@code os.name=Linux}, so
     * the check looks for files that only exist on Android rather than trusting the
     * OS name. A Pojav-style launcher runs a real HotSpot JVM, so the JVM vendor is
     * not a reliable signal either.
     */
    public static boolean isAndroid() {
        if (Files.exists(Path.of("/system/build.prop")) || Files.isDirectory(Path.of("/system/app"))) {
            return true;
        }
        // Fallback for restricted file systems: bionic's linker is present on every
        // Android release, and some launchers set the vendor string.
        return Files.exists(Path.of("/system/bin/linker64"))
                || Files.exists(Path.of("/system/bin/linker"))
                || System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT).contains("android");
    }

    private static String cpuName(String arch) {
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        if (arch.startsWith("arm")) return "arm32";
        return "x86_64";
    }

    /** File name of the runtime cdylib on this platform. */
    public static String libraryFileName() {
        return fileName(RUNTIME_LIBRARY, platformDirectory());
    }

    /** File name of the world engine cdylib on this platform. */
    public static String worldFileName() {
        return fileName(WORLD_LIBRARY, platformDirectory());
    }

    private static String fileName(String baseName, String platform) {
        if (platform.startsWith("windows")) return baseName + ".dll";
        if (platform.startsWith("macos")) return "lib" + baseName + ".dylib";
        return "lib" + baseName + ".so";
    }

    private static Path extractBundled(String baseName) {
        String platform = platformDirectory();
        String name = fileName(baseName, platform);
        String resource = RESOURCE_ROOT + platform + "/" + name;
        ClassLoader loader = NativeLibraryLoader.class.getClassLoader();
        try (InputStream in = loader == null
                ? NativeLibraryLoader.class.getResourceAsStream(resource)
                : loader.getResourceAsStream(resource.substring(1))) {
            if (in == null) return null;
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "jpe-optimizer-natives", platform);
            try {
                Files.createDirectories(directory);
            } catch (IOException unwritable) {
                // Some launchers point java.io.tmpdir at a read-only location; the game
                // directory is the next best writable spot.
                directory = Path.of(System.getProperty("user.dir", "."), "jpe-optimizer-natives", platform);
                Files.createDirectories(directory);
            }
            Path target = directory.resolve(name);
            Path temporary = Files.createTempFile(directory, name + ".", ".part");
            try {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    in.transferTo(out);
                }
                long size = Files.size(temporary);
                if (size == 0) throw new IOException("Bundled native is empty: " + resource);
                if (Files.isRegularFile(target) && Files.size(target) == size) {
                    Files.deleteIfExists(temporary);
                    return target;
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                target.toFile().setReadable(true, false);
                // Android's linker refuses to map a library that is not executable.
                target.toFile().setExecutable(true, false);
                return target;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            System.getLogger(NativeLibraryLoader.class.getName()).log(System.Logger.Level.WARNING,
                    "Unable to unpack the bundled native " + baseName, failure);
            return null;
        }
    }

    /**
     * Development fallback. Only consulted when the jar carries no native, so a
     * released build never picks up a stale target directory by accident.
     */
    private static Path findDevelopmentBuild(String baseName) {
        String name = fileName(baseName, platformDirectory());
        String crate = baseName.replace('_', '-');
        String[] candidates = {
                "native/" + crate + "/target/release/" + name,
                "native/" + crate + "/target/debug/" + name,
                "../native/" + crate + "/target/release/" + name,
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) return path.toAbsolutePath().normalize();
        }
        return null;
    }

    /** Test hook: forgets every memoized answer. */
    static void resetForTests() {
        synchronized (LOCK) {
            RESOLVED.clear();
            ATTEMPTED.clear();
        }
    }
}
