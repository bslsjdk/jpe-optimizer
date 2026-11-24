import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * GC stress harness modelling Minecraft's allocation shape: a flood of tiny
 * short-lived objects (positions, vectors, temp holders) plus occasional
 * medium bursts, with a small live set. Run under different HotSpot GC flags;
 * prints one GCRESULT line. The CI workflow runs each config several times and
 * takes the minimum so JVM warmup / ergonomics noise is filtered out.
 *
 * NOTE: this runs on x86 CI, not a phone. It validates which GC flags HotSpot
 * 25 accepts and gives a rough trend only. Final choice must be A/B tested on
 * the actual device (few cores, small heap, thermal throttling).
 *
 * Usage: java [GC flags] GcStress [objectsPerRound] [rounds]
 */
public final class GcStress {
    private static final int LIVE_SLOTS = 1024;
    private static volatile Object sink;

    public static void main(String[] args) {
        int perRound = args.length > 0 ? Integer.parseInt(args[0]) : 400_000;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 12;

        Holder[] live = new Holder[LIVE_SLOTS];
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();

        for (int r = 0; r < 3; r++) allocateRound(perRound, live);

        long gcTime0 = totalGcTime(beans);
        long gcCount0 = totalGcCount(beans);
        long t0 = System.nanoTime();
        for (int r = 0; r < rounds; r++) allocateRound(perRound, live);
        long elapsedNs = System.nanoTime() - t0;

        long gcMs = totalGcTime(beans) - gcTime0;
        long gcCount = totalGcCount(beans) - gcCount0;
        long allocatedBytes = (long) perRound * rounds * 48L;
        double allocMbPerSec = allocatedBytes / 1048576.0 * 1_000_000_000.0 / elapsedNs;

        System.out.printf("GCRESULT gcTotalMs=%d gcCount=%d allocMbPerSec=%.0f elapsedMs=%d sink=%d%n",
                gcMs, gcCount, allocMbPerSec, elapsedNs / 1_000_000, sink == null ? 0 : 1);
    }

    private static void allocateRound(int n, Holder[] live) {
        for (int i = 0; i < n; i++) {
            Holder h = new Holder(i);
            h.a = i + 1;
            h.b = (float) i * 0.5f;
            if ((i & 1023) == 0) live[i >>> 10 & (live.length - 1)] = h;
            sink = h;
        }
        if ((n & 7) == 0) {
            byte[] burst = new byte[256 * 1024];
            burst[0] = (byte) n;
            sink = burst;
        }
    }

    private static long totalGcTime(List<GarbageCollectorMXBean> beans) {
        long s = 0;
        for (GarbageCollectorMXBean b : beans) s += b.getCollectionTime();
        return s;
    }

    private static long totalGcCount(List<GarbageCollectorMXBean> beans) {
        long s = 0;
        for (GarbageCollectorMXBean b : beans) s += b.getCollectionCount();
        return s;
    }

    private static final class Holder {
        int a;
        float b;
        long c;
        double d;
        Object next;
        Holder(int v) { c = v; d = v * 0.25; }
    }
}