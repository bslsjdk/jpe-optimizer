package dev.jpe.optimizer.worldgen;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns temporary pooled buffers used by material rules while one chunk's
 * surface rules are evaluated. Rule lambdas capture these buffers, so they
 * are released only after MaterialSystem.buildSurface has returned.
 */
public final class MaterialDensityBufferPoolScope implements AutoCloseable {
    private static final boolean ENABLED = Boolean.getBoolean("jpe.materialBufferPooling");
    private static final ThreadLocal<MaterialDensityBufferPoolScope> CURRENT = new ThreadLocal<>();

    private final DensityBufferPool pool;
    private final MaterialDensityBufferPoolScope parent;
    private final List<ScopedDensityBuffer> acquired = new ArrayList<>(8);
    private boolean closed;

    private MaterialDensityBufferPoolScope(DensityBufferPool pool, MaterialDensityBufferPoolScope parent) {
        this.pool = pool;
        this.parent = parent;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static MaterialDensityBufferPoolScope enter(DensityBufferPool pool) {
        MaterialDensityBufferPoolScope scope = new MaterialDensityBufferPoolScope(pool, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /** Outside a buildSurface scope, preserve vanilla's unpooled behavior. */
    public static DensityBuffer acquire(int size) {
        MaterialDensityBufferPoolScope scope = CURRENT.get();
        if (scope == null || !ENABLED) {
            return DensityBuffer.createUnpooled(size);
        }

        ScopedDensityBuffer buffer = scope.pool.acquire(size);
        scope.acquired.add(buffer);
        return buffer;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (int i = acquired.size() - 1; i >= 0; i--) {
            acquired.get(i).close();
        }
        acquired.clear();
        if (CURRENT.get() == this) {
            if (parent == null) CURRENT.remove();
            else CURRENT.set(parent);
        }
    }
}
