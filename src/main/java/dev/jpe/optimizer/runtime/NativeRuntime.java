package dev.jpe.optimizer.runtime;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Optional Java 25 FFM bridge to the Rust native runtime.
 * Not wired into world generation: vanilla remains the active implementation.
 */
public final class NativeRuntime implements AutoCloseable {
    public static final int EXPECTED_ABI_VERSION = 1;

    private final Arena libraryArena;
    private final MethodHandle addF32;

    private NativeRuntime(Arena libraryArena, MethodHandle addF32) {
        this.libraryArena = libraryArena;
        this.addF32 = addF32;
    }

    public static NativeRuntime load(Path libraryPath) throws IOException {
        Objects.requireNonNull(libraryPath, "libraryPath");
        Path absolutePath = libraryPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolutePath)) {
            throw new IOException("Native runtime library does not exist: " + absolutePath);
        }

        Arena arena = Arena.ofShared();
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup(absolutePath, arena);
            MethodHandle version = linker.downcallHandle(
                    lookup.find("jpe_runtime_abi_version")
                            .orElseThrow(() -> new IllegalStateException("Missing ABI version symbol")),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            MethodHandle add = linker.downcallHandle(
                    lookup.find("jpe_runtime_add_f32")
                            .orElseThrow(() -> new IllegalStateException("Missing bulk kernel symbol")),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT
                    )
            );

            int actualVersion = (int) version.invokeExact();
            if (actualVersion != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException(
                        "Native ABI mismatch: expected " + EXPECTED_ABI_VERSION + ", got " + actualVersion
                );
            }
            return new NativeRuntime(arena, add);
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Unable to load JPE native runtime from " + absolutePath, failure);
        }
    }

    /** ABI smoke-test operation. It is not used for game data. */
    public float[] addEachValueToItself(float[] input) {
        Objects.requireNonNull(input, "input");
        try (Arena callArena = Arena.ofConfined()) {
            long bufferBytes = Math.multiplyExact((long) input.length, ValueLayout.JAVA_FLOAT.byteSize());
            MemorySegment nativeInput = callArena.allocate(bufferBytes, ValueLayout.JAVA_FLOAT.byteAlignment());
            MemorySegment nativeOutput = callArena.allocate(bufferBytes, ValueLayout.JAVA_FLOAT.byteAlignment());
            for (int i = 0; i < input.length; i++) {
                nativeInput.setAtIndex(ValueLayout.JAVA_FLOAT, i, input[i]);
            }

            int status = (int) addF32.invokeExact(nativeInput, nativeOutput, input.length);
            if (status != 0) {
                throw new IllegalStateException("Native bulk kernel failed with status " + status);
            }

            float[] result = new float[input.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = nativeOutput.getAtIndex(ValueLayout.JAVA_FLOAT, i);
            }
            return result;
        } catch (Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Native bulk call failed", failure);
        }
    }

    @Override
    public void close() {
        libraryArena.close();
    }
}
