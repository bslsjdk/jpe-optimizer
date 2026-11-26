package dev.jpe.optimizer.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.jpe.world.JpeWorldNative;

/**
 * Owns a Rust world-engine pipeline inside the Minecraft runtime.
 *
 * <p>The renderer and the pipeline are thread-affine on the Rust side (the renderer
 * holds the GPU context, and the pipeline draws through it), but Minecraft's GameTest
 * and render code does not run on one stable thread. So this bridge keeps a single
 * dedicated engine thread and marshals every native call onto it. Block data is read
 * on the caller's thread — that is the only Minecraft access here — and handed over as
 * a plain {@code short[]}.
 *
 * <p>Scope, stated honestly: this is the transitional Java &lt;-&gt; Rust integration
 * for Stage 6. Minecraft still generates terrain, the engine draws into its own
 * offscreen target rather than Minecraft's window or swapchain, and block indices come
 * from {@link MinecraftChunkReader} instead of the C++ terrain engine. Nothing here
 * takes over vanilla rendering.
 */
public final class RustWorldBridge implements AutoCloseable {

    /** Absolute path to {@code libjpe_world.so}. Unset means the bridge is disabled. */
    public static final String LIBRARY_PROPERTY = "jpe.worldLibrary";
    /** When true, a missing library or missing GPU backend is a hard failure. */
    public static final String REQUIRED_PROPERTY = "jpe.worldRequired";
    /** ABI the Java binding and {@code src/ffi.rs} agree on. */
    public static final int EXPECTED_ABI_VERSION = 2;

    public record Options(
            int backend,
            int width,
            int height,
            int terrainBudget,
            int meshBudget,
            int remeshBudget,
            int threads,
            int sectionYMin,
            int sectionYMax,
            long worldEpoch,
            int queueCapacity) {

        /** Small deterministic rig: one chunk window of 3 sections, 128x128 target. */
        public static Options testRig(int sectionYMin, int sectionYMax) {
            return new Options(JpeWorldNative.BACKEND_AUTO, 128, 128, 8, 8, 8, 2,
                    sectionYMin, sectionYMax, 1L, 256);
        }
    }

    public record Backend(int code, String name, int apiMajor, int apiMinor,
                          int maxTextureSize, boolean supportsCompute) {}

    private static final String[] BACKEND_NAMES = {"auto", "vulkan", "opengl", "opengles"};

    private final Path library;
    private final JpeWorldNative world;
    private final ExecutorService engine;
    private final Backend backend;
    private final long rendererHandle;
    private final long pipelineHandle;
    private final Options options;
    private final long engineThreadId;
    private final AtomicLong framesSubmitted = new AtomicLong();
    private volatile boolean closed;

    private RustWorldBridge(Path library, JpeWorldNative world, ExecutorService engine,
                            Backend backend, long rendererHandle, long pipelineHandle,
                            Options options, long engineThreadId) {
        this.library = library;
        this.world = world;
        this.engine = engine;
        this.backend = backend;
        this.rendererHandle = rendererHandle;
        this.pipelineHandle = pipelineHandle;
        this.options = options;
        this.engineThreadId = engineThreadId;
    }

    /** Configured library path, if any. */
    public static Optional<Path> configuredLibrary() {
        String raw = System.getProperty(LIBRARY_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(raw));
    }

    public static boolean required() {
        return Boolean.getBoolean(REQUIRED_PROPERTY);
    }

    /**
     * Load {@code libjpe_world}, create a renderer plus pipeline on a private engine
     * thread, and verify the Java ABI matches.
     *
     * @throws IllegalStateException when the library is unusable, the ABI disagrees, or
     *         no native render backend can be created here
     */
    public static RustWorldBridge open(Path library, Options options) {
        if (!Files.isReadable(library)) {
            throw new IllegalStateException("world engine library is not readable: " + library);
        }
        JpeWorldNative world = new JpeWorldNative(library);
        ExecutorService engine = null;
        long[] handles = null;
        try {
            int abi = world.abiVersion();
            if (abi != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException("world engine ABI mismatch: library reports " + abi
                        + ", this bridge needs " + EXPECTED_ABI_VERSION);
            }
            ThreadFactory factory = task -> {
                Thread thread = new Thread(task, "jpe-world-engine");
                thread.setDaemon(true);
                return thread;
            };
            engine = Executors.newSingleThreadExecutor(factory);
            final ExecutorService engineRef = engine;
            Future<long[]> handlesFuture = engineRef.submit(() -> {
                long renderer = world.rendererCreate(options.backend());
                if (renderer == 0) {
                    throw new IllegalStateException("no native render backend available (requested "
                            + backendName(options.backend()) + ")");
                }
                long pipeline = world.pipelineCreate(renderer, options.worldEpoch(),
                        options.queueCapacity(), new JpeWorldNative.PipelineConfig(
                                options.terrainBudget(), options.meshBudget(), options.remeshBudget(),
                                options.threads(), options.sectionYMin(), options.sectionYMax(),
                                options.width(), options.height()));
                if (pipeline == 0) {
                    world.rendererDestroy(renderer);
                    throw new IllegalStateException("jpe_pipeline_create rejected the rig");
                }
                return new long[]{renderer, pipeline};
            });
            try {
                handles = handlesFuture.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while creating the pipeline", interrupted);
            } catch (ExecutionException failed) {
                throw new IllegalStateException("pipeline creation failed: "
                        + describeCause(failed.getCause()), failed.getCause());
            }
            final long renderer = handles[0];
            Future<JpeWorldNative.RendererCaps> caps = engineRef.submit(
                    () -> world.rendererCapabilities(renderer));
            JpeWorldNative.RendererCaps capsValue;
            try {
                capsValue = caps.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while reading capabilities", interrupted);
            } catch (ExecutionException failed) {
                throw new IllegalStateException("rendererCapabilities failed", failed.getCause());
            }
            Backend backend = new Backend(capsValue.backend(), backendName(capsValue.backend()),
                    capsValue.apiMajor(), capsValue.apiMinor(), capsValue.maxTextureSize(),
                    capsValue.supportsCompute());
            // Thread id of the engine thread, read from inside a task on that thread.
            Future<Long> threadId = engineRef.submit(() -> Thread.currentThread().threadId());
            long id;
            try {
                id = threadId.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while reading the engine thread id",
                        interrupted);
            } catch (ExecutionException failed) {
                throw new IllegalStateException("could not read the engine thread id",
                        failed.getCause());
            }
            return new RustWorldBridge(library, world, engineRef, backend, handles[0], handles[1],
                    options, id);
        } catch (RuntimeException | Error failure) {
            if (engine != null) {
                // Handles exist means the engine thread already owns GPU objects; they
                // must be released on that thread, so ask for it before stopping it.
                final long[] created = handles;
                if (created != null) {
                    try {
                        engine.submit(() -> {
                            world.pipelineDestroy(created[1]);
                            return world.rendererDestroy(created[0]);
                        }).get(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {
                        // Teardown of a rig that never finished starting is best-effort.
                    }
                }
                engine.shutdownNow();
            }
            world.close();
            throw failure;
        }
    }

    public static String backendName(int code) {
        return code >= 0 && code < BACKEND_NAMES.length ? BACKEND_NAMES[code] : "code" + code;
    }

    /** Orthographic look-at matrix built by the Rust camera helper. */
    public float[] ortho(float[] eye, float[] target, float[] up, float halfWidth,
                         float halfHeight, float near, float far) {
        return call(() -> world.cameraOrtho(eye, target, up, halfWidth, halfHeight, near, far));
    }

    /** Hand one chunk's block indices to the Rust world engine. Returns a SUBMIT_* code. */
    public int submitChunk(int chunkX, int chunkZ, int baseSectionY, short[] blocks) {
        return call(() -> world.pipelineSubmitChunk(pipelineHandle, chunkX, chunkZ,
                baseSectionY, blocks));
    }

    /** Run one full engine frame (jobs, upload, cull, draw). */
    public JpeWorldNative.FrameStats frame(float[] viewProj, float[] eye) {
        framesSubmitted.incrementAndGet();
        return call(() -> world.pipelineFrame(pipelineHandle, viewProj, eye));
    }

    public long[] residentKeys(int max) {
        return call(() -> world.pipelineResidentKeys(pipelineHandle, max));
    }

    public JpeWorldNative.PipelineStats stats() {
        return call(() -> world.pipelineStats(pipelineHandle));
    }

    /** RGBA8 of the last completed engine frame, plus its dimensions. */
    public FramePixels readPixels() {
        return call(() -> new FramePixels(
                world.rendererReadPixels(rendererHandle, options.width(), options.height()),
                options.width(), options.height()));
    }

    public int unloadOutside(int centerX, int centerZ, int radius) {
        return call(() -> world.pipelineUnloadOutsideStatus(pipelineHandle, centerX, centerZ, radius));
    }

    public int setPlayer(int chunkX, int chunkZ) {
        return call(() -> world.pipelineSetPlayer(pipelineHandle, chunkX, chunkZ));
    }

    public int ticket(int chunkX, int chunkZ, boolean acquire) {
        return call(() -> world.pipelineTicket(pipelineHandle, chunkX, chunkZ, acquire));
    }

    public long reset() {
        return call(() -> world.pipelineReset(pipelineHandle));
    }

    public long engineThreadId() {
        return engineThreadId;
    }

    public Backend backend() {
        return backend;
    }

    public Options options() {
        return options;
    }

    public Path library() {
        return library;
    }

    public long framesSubmitted() {
        return framesSubmitted.get();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Tear down the pipeline and renderer on their owner thread and stop the engine
     * thread, but keep the native library mapped for the process lifetime.
     *
     * <p>Once a GPU backend has been created, unloading {@code libjpe_world} (which is
     * what {@link #close()} does via {@link JpeWorldNative#close()}) leaves Mesa/glvnd
     * teardown state referencing the module and crashes the JVM on exit. A live
     * Minecraft host therefore uses this instead of {@link #close()} so the library
     * stays resident and is only released by normal process exit.
     */
    public void releaseHandles() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            call(() -> {
                int pipelineStatus = world.pipelineDestroy(pipelineHandle);
                int rendererStatus = world.rendererDestroy(rendererHandle);
                return new int[]{pipelineStatus, rendererStatus};
            });
        } catch (RuntimeException failure) {
            // Teardown must never escape into Minecraft's shutdown path.
        } finally {
            engine.shutdown();
            try {
                if (!engine.awaitTermination(5, TimeUnit.SECONDS)) {
                    engine.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                engine.shutdownNow();
                Thread.currentThread().interrupt();
            }
            // Deliberately no world.close(): keep libjpe_world resident.
        }
    }

    /** Tear the pipeline down on its owner thread, then release the library. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            call(() -> {
                int pipelineStatus = world.pipelineDestroy(pipelineHandle);
                int rendererStatus = world.rendererDestroy(rendererHandle);
                return new int[]{pipelineStatus, rendererStatus};
            });
        } catch (RuntimeException failure) {
            // Teardown must never escape into Minecraft's shutdown path.
        } finally {
            engine.shutdown();
            try {
                if (!engine.awaitTermination(5, TimeUnit.SECONDS)) {
                    engine.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                engine.shutdownNow();
                Thread.currentThread().interrupt();
            }
            world.close();
        }
    }

    private <T> T call(Callable<T> task) {
        if (closed) {
            throw new IllegalStateException("bridge is closed");
        }
        Future<T> future = engine.submit(task);
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted in the world engine bridge", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException(describeCause(failed.getCause()), failed.getCause());
        }
    }

    private static String describeCause(Throwable cause) {
        return cause == null ? "unknown native failure" : cause.getClass().getSimpleName()
                + ": " + cause.getMessage();
    }

    /** One RGBA8 frame plus a couple of derived, human-readable measurements. */
    public record FramePixels(byte[] rgba, int width, int height) {

        /** Pixels clearly above the dark clear colour; the same rule the Rust tests use. */
        public int litPixels() {
            int lit = 0;
            for (int i = 0; i + 3 < rgba.length; i += 4) {
                if ((rgba[i] & 0xFF) > 32 || (rgba[i + 1] & 0xFF) > 32 || (rgba[i + 2] & 0xFF) > 32) {
                    lit++;
                }
            }
            return lit;
        }

        /** Distinct RGB triples seen, to prove real shading rather than one flat quad. */
        public int distinctColours() {
            java.util.TreeSet<Integer> tones = new java.util.TreeSet<>();
            for (int i = 0; i + 3 < rgba.length; i += 4) {
                tones.add(((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
            return tones.size();
        }

        public int size() {
            return width * height;
        }
    }
}
