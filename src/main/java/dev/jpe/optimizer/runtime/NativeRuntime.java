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

/** Optional Java 25 FFM bridge. Native worldgen is not loaded or enabled automatically. */
public final class NativeRuntime implements AutoCloseable {
    public static final int EXPECTED_ABI_VERSION = 2;
    private final Arena libraryArena;
    private final MethodHandle addF32;
    private final MethodHandle perlinAddVolume;

    private NativeRuntime(Arena arena, MethodHandle add, MethodHandle perlin) {
        this.libraryArena = arena;
        this.addF32 = add;
        this.perlinAddVolume = perlin;
    }

    public static NativeRuntime load(Path libraryPath) throws IOException {
        Objects.requireNonNull(libraryPath, "libraryPath");
        Path absolutePath = libraryPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolutePath)) throw new IOException("Native runtime library does not exist: " + absolutePath);
        Arena arena = Arena.ofShared();
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup(absolutePath, arena);
            MethodHandle version = linker.downcallHandle(
                lookup.find("jpe_runtime_abi_version").orElseThrow(() -> new IllegalStateException("Missing ABI version symbol")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
            MethodHandle add = linker.downcallHandle(
                lookup.find("jpe_runtime_add_f32").orElseThrow(() -> new IllegalStateException("Missing bulk kernel symbol")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle perlin = linker.downcallHandle(
                lookup.find("jpe_runtime_perlin_add_volume").orElseThrow(() -> new IllegalStateException("Missing Perlin volume symbol")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_FLOAT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            int actual = (int) version.invokeExact();
            if (actual != EXPECTED_ABI_VERSION) throw new IllegalStateException("Native ABI mismatch: expected " + EXPECTED_ABI_VERSION + ", got " + actual);
            return new NativeRuntime(arena, add, perlin);
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Unable to load JPE native runtime from " + absolutePath, failure);
        }
    }

    public float[] addEachValueToItself(float[] input) {
        Objects.requireNonNull(input, "input");
        try (Arena arena = Arena.ofConfined()) {
            long bytes = Math.multiplyExact((long) input.length, ValueLayout.JAVA_FLOAT.byteSize());
            MemorySegment in = arena.allocate(bytes, ValueLayout.JAVA_FLOAT.byteAlignment());
            MemorySegment out = arena.allocate(bytes, ValueLayout.JAVA_FLOAT.byteAlignment());
            for (int i = 0; i < input.length; i++) in.setAtIndex(ValueLayout.JAVA_FLOAT, i, input[i]);
            int status = (int) addF32.invokeExact(in, out, input.length);
            checkStatus(status, "add-f32");
            float[] result = new float[input.length];
            for (int i = 0; i < result.length; i++) result[i] = out.getAtIndex(ValueLayout.JAVA_FLOAT, i);
            return result;
        } catch (Throwable failure) { throw rethrow("Native bulk call failed", failure); }
    }

    /** Synchronous bulk Perlin entry retained for exact parity tests, not live worldgen. */
    public void perlinAddVolume(byte[] permutation, int[] blockX, int[] blockY, int[] blockZ,
        double offsetX, double offsetY, double offsetZ, double xzScale, double yScale,
        float amplitude, float[] output, boolean farLands) {
        Objects.requireNonNull(permutation, "permutation");
        Objects.requireNonNull(blockX, "blockX"); Objects.requireNonNull(blockY, "blockY");
        Objects.requireNonNull(blockZ, "blockZ"); Objects.requireNonNull(output, "output");
        if (permutation.length != 256) throw new IllegalArgumentException("Permutation table must contain 256 bytes");
        if (blockX.length == 0 || blockY.length == 0 || blockZ.length == 0) throw new IllegalArgumentException("Perlin dimensions must be non-zero");
        long expected = Math.multiplyExact(Math.multiplyExact((long) blockX.length, blockY.length), blockZ.length);
        if (expected != output.length) throw new IllegalArgumentException("Output length does not match volume dimensions");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocate(256, ValueLayout.JAVA_BYTE.byteAlignment());
            MemorySegment x = allocateInts(arena, blockX.length), y = allocateInts(arena, blockY.length);
            MemorySegment z = allocateInts(arena, blockZ.length), out = allocateFloats(arena, output.length);
            for (int i = 0; i < permutation.length; i++) p.setAtIndex(ValueLayout.JAVA_BYTE, i, permutation[i]);
            copy(blockX, x); copy(blockY, y); copy(blockZ, z);
            for (int i = 0; i < output.length; i++) out.setAtIndex(ValueLayout.JAVA_FLOAT, i, output[i]);
            int status = (int) perlinAddVolume.invokeExact(p, x, y, z, blockX.length, blockY.length, blockZ.length,
                offsetX, offsetY, offsetZ, xzScale, yScale, amplitude, out, (long) output.length, farLands ? 1 : 0);
            checkStatus(status, "perlin-add-volume");
            for (int i = 0; i < output.length; i++) output[i] = out.getAtIndex(ValueLayout.JAVA_FLOAT, i);
        } catch (Throwable failure) { throw rethrow("Native Perlin volume call failed", failure); }
    }

    private static MemorySegment allocateInts(Arena arena, int length) {
        return arena.allocate(Math.multiplyExact((long) length, ValueLayout.JAVA_INT.byteSize()), ValueLayout.JAVA_INT.byteAlignment());
    }
    private static MemorySegment allocateFloats(Arena arena, int length) {
        return arena.allocate(Math.multiplyExact((long) length, ValueLayout.JAVA_FLOAT.byteSize()), ValueLayout.JAVA_FLOAT.byteAlignment());
    }
    private static void copy(int[] source, MemorySegment target) {
        for (int i = 0; i < source.length; i++) target.setAtIndex(ValueLayout.JAVA_INT, i, source[i]);
    }
    private static void checkStatus(int status, String operation) {
        if (status != 0) throw new IllegalStateException("Native " + operation + " failed with status " + status);
    }
    private static RuntimeException rethrow(String message, Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, failure);
    }
    @Override public void close() { libraryArena.close(); }
}
