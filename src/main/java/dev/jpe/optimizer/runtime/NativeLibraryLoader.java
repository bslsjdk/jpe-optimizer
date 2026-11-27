package dev.jpe.optimizer.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Finds the native runtime for a packaged mod.
 *
 * <p>A development checkout keeps the cdylib under {@code native/rust-runtime/target}.
 * A released jar cannot: the game process starts in some other working directory,
 * so the library is bundled as {@code META-INF/natives/<platform>/<name>} and
 * unpacked once into the JVM temporary directory. The file name and size are
 * checked before reuse, which is enough to avoid a partial copy after a crash;
 * the jar's own copy is authoritative.
 *
 * <p>The explicit {@code -Djpe.nativeLibraryPath} override always wins, so a
 * locally built library can be swapped in without touching the jar.
 */
public final class NativeLibraryLoader {
    /** System property that overrides every other lookup. */
    public static final String LIBRARY_PATH_PROPERTY = "jpe.nativeLibraryPath";

    private static final String RESOURCE_ROOT = "/META-INF/natives/";
    private static final Object LOCK = new Object();
    private static volatile Path resolved;
    private static volatile boolean attempted;

    private NativeLibraryLoader() {}

    /**
     * Returns the library to load, or {@code null} when this platform has no
     * bundled native and no development build is present.
     */
    public static Path resolve() {
        Path cached = resolved;
        if (attempted) return cached;
        synchronized (LOCK) {
            if (attempted) return resolved;
            attempted = true;
            String override = System.getProperty(LIBRARY_PATH_PROPERTY);
            if (override != null && !override.isBlank()) {
                Path path = Path.of(override).toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) {
                    resolved = path;
                    return resolved;
                }
                System.getLogger(NativeLibraryLoader.class.getName()).log(System.Logger.Level.WARNING,
                        "jpe.nativeLibraryPath points at a missing file: {0}", path);
            }
            Path bundled = extractBundled();
            resolved = bundled != null ? bundled : findDevelopmentBuild();
            return resolved;
        }
    }

    /** Platform directory used inside the jar, for example {@code linux-x86_64}. */
    public static String platformDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String family = os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "macos" : "linux";
        String cpu = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
        return family + "-" + cpu;
    }

    /** File name of the cdylib on this platform. */
    public static String libraryFileName() {
        String platform = platformDirectory();
        if (platform.startsWith("windows")) return "jpe_runtime.dll";
        if (platform.startsWith("macos")) return "libjpe_runtime.dylib";
        return "libjpe_runtime.so";
    }

    private static Path extractBundled() {
        String platform = platformDirectory();
        String name = libraryFileName();
        String resource = RESOURCE_ROOT + platform + "/" + name;
        ClassLoader loader = NativeLibraryLoader.class.getClassLoader();
        try (InputStream in = loader == null
                ? NativeLibraryLoader.class.getResourceAsStream(resource)
                : loader.getResourceAsStream(resource.substring(1))) {
            if (in == null) return null;
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "jpe-optimizer-natives", platform);
            Files.createDirectories(directory);
            Path target = directory.resolve(name);
            Path temporary = Files.createTempFile(directory, name + ".", ".part");
            try {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    in.transferTo(out);
                }
                long size = Files.size(temporary);
                if (size == 0) throw new IOException("Bundled native runtime is empty: " + resource);
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
                return target;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            System.getLogger(NativeLibraryLoader.class.getName()).log(System.Logger.Level.WARNING,
                    "Unable to unpack the bundled native runtime", failure);
            return null;
        }
    }

    /**
     * Development fallback. Only consulted when the jar carries no native, so a
     * released build never picks up a stale target directory by accident.
     */
    private static Path findDevelopmentBuild() {
        String name = libraryFileName();
        String[] candidates = {
                "native/rust-runtime/target/release/" + name,
                "native/rust-runtime/target/debug/" + name,
                "../native/rust-runtime/target/release/" + name
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) return path.toAbsolutePath().normalize();
        }
        return null;
    }

    /** Test hook: forgets the memoized answer. */
    static void resetForTests() {
        synchronized (LOCK) {
            resolved = null;
            attempted = false;
        }
    }
}
