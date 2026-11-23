package dev.jpe.optimizer.alloc;

/**
 * Allocation-free 3-component vector scratch space.
 *
 * Hot worldgen/entity code frequently allocates short-lived Vec3-like objects.
 * HotSpot C2 scalar-replaces small non-escaping objects, but when a vector must
 * escape to a helper call the JIT cannot eliminate it. This pool provides
 * reusable mutable slots via ThreadLocal, so callers can borrow a scratch
 * vector, fill it, and pass it along without allocating per call.
 *
 * Correctness: slots are thread-confined; NEVER retain a borrowed slot beyond
 * the current operation. Values are overwritten on each borrow.
 */
public final class Vec3Pool {
    private Vec3Pool() {}

    public static final class Vec3 {
        public double x, y, z;
        public Vec3() {}
        public Vec3(double x, double y, double z) { set(x, y, z); }
        public void set(double x, double y, double z) {
            this.x = x; this.y = y; this.z = z;
        }
        public double lengthSq() { return x * x + y * y + z * z; }
        public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
        public void add(Vec3 o) { x += o.x; y += o.y; z += o.z; }
        public void scaled(double s) { x *= s; y *= s; z *= s; }
        @Override public String toString() { return "(" + x + "," + y + "," + z + ")"; }
    }

    private static final ThreadLocal<Vec3[]> SLOTS =
            ThreadLocal.withInitial(() -> new Vec3[]{
                    new Vec3(), new Vec3(), new Vec3(), new Vec3()
            });

    /** Borrow a scratch vector. index must be 0..3. Do not retain. */
    public static Vec3 borrow(int index) {
        Vec3[] slots = SLOTS.get();
        Vec3 v = slots[index & 3];
        v.set(0, 0, 0);
        return v;
    }

    /** Borrow and initialize in one call (still zero allocation). */
    public static Vec3 borrow(int index, double x, double y, double z) {
        Vec3 v = borrow(index);
        v.set(x, y, z);
        return v;
    }

    /**
     * Bit-identical reference implementation of a common worldgen distance
     * expression vs an allocation-free pooled version, for equivalence tests.
     */
    public static double distanceSqNaive(double ax, double ay, double az,
                                         double bx, double by, double bz) {
        Vec3 a = new Vec3(ax, ay, az);
        Vec3 b = new Vec3(bx, by, bz);
        a.add(b);
        return a.lengthSq();
    }

    public static double distanceSqPooled(double ax, double ay, double az,
                                          double bx, double by, double bz) {
        Vec3 a = borrow(0, ax, ay, az);
        Vec3 b = borrow(1, bx, by, bz);
        a.add(b);
        return a.lengthSq();
    }
}