package dev.jpe.optimizer.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeDensityGraphCompilerArtifactHashTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void directoryHashUsesUnambiguousLengthPrefixedEntries() throws Exception {
        Path singleFile = Files.createDirectory(temporaryDirectory.resolve("single"));
        Path twoFiles = Files.createDirectory(temporaryDirectory.resolve("two-files"));

        // This was ambiguous under delimiter-only framing:
        // one file "x" with these bytes versus files "x" and "y".
        Files.write(singleFile.resolve("x"), new byte[] {
                'A', (byte) 0xFF, 'y', 0, 'B'
        });
        Files.write(twoFiles.resolve("x"), new byte[] {'A'});
        Files.write(twoFiles.resolve("y"), new byte[] {'B'});

        assertNotEquals(
                NativeDensityGraphCompiler.sha256ModArtifact(singleFile),
                NativeDensityGraphCompiler.sha256ModArtifact(twoFiles));
    }

    @Test
    void directoryHashDoesNotDependOnFileCreationOrder() throws Exception {
        Path first = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("second"));

        Files.write(first.resolve("a.dat"), new byte[] {1, 2, 3});
        Files.write(first.resolve("z.dat"), new byte[] {4, 5, 6});
        Files.write(second.resolve("z.dat"), new byte[] {4, 5, 6});
        Files.write(second.resolve("a.dat"), new byte[] {1, 2, 3});

        assertEquals(
                NativeDensityGraphCompiler.sha256ModArtifact(first),
                NativeDensityGraphCompiler.sha256ModArtifact(second));
    }
}
