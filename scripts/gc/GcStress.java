import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * GC stress harness modelling Minecraft's allocation shape: a flood of tiny
 * short-lived objects plus a small live set. Run under different HotSpot GC
 * flags; prints one GCRESULT line.
 *
 * Metrics:
 *  - gcTotalMs   : cumulative GC pause time from MXBean (lower is better)
 *  - gcCount     : number of collections
 *  - worstRoundMs: slowest allocation round, approximates the visible hitch a
 *                  stop-the-world pause causes (lower is better, most relevant
 *                  to "stutter" feel)
 *  - allocMbPerSec: throughput
 *
 * A volatile sink forces real allocation (no scalar-replacement elision).
 * Each config is run several times in CI; take the min to filter noise.
 *
 * NOTE: x86 CI is trend-only. Final selection must be A/B tested on the actual
 * phone (few cores, small heap, thermal throttling).
 *
 * Usage: java [GC flags] GcStress [objectsPerRound] [rounds]
 */
public final class GcStress {
    private static final int LIVE_SLOTS = 1024;
    private static volatile Object sink;

    public static void main(String[] args) {
        int perRound = args.length > 0 ? Integer.parseInt(args[0]) : 300_000;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 60;

        Holder[] live = new Holder[LIVE_SLOTS];
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();

        for (int r = 0; r < 4; r++) allocateRound(perRound, live);

        long gcTime0 = totalGcTime(beans);
        long gcCount0 = totalGcCount(beans);
        long allocatedBytes = 0;
        long worstRoundNs = 0;
        long totalNs = 0;
        for (int r = 0; r < rounds; r++) {
            long rs = System.nanoTime();
            allocatedBytes += allocateRound(perRound, live);
            long d = System.nanoTime() - rs;
            totalNs += d;
            if (d > worstRoundNs) worstRoundNs = d;
        }

        long gcMs = totalGcTime(beans) - gcTime0;
        long gcCount = totalGcCount(beans) - gcCount0;
        double allocMbPerSec = allocatedBytes / 1048576.0 * 1_000_000_000.0 / totalNs;

        System.out.printf("GCRESULT gcTotalMs=%d gcCount=%d worstRoundMs=%d allocMbPerSec=%.0f elapsedMs=%d sink=%d%n",
                gcMs, gcCount, worstRoundNs / 1_000_000, allocMbPerSec,
                totalNs / 1_000_000, sink == null ? 0 : 1);
    }

    private static long allocateRound(int n, Holder[] live) {
        for (int i = 0; i < n; i++) {
            Holder h = new Holder(i);
            h.a = i + 1;
            h.b = (float) i * 0.5f;
            if ((i & 1023) == 0) live[i >>> 10 & (live.length - 1)] = h;
            sink = h;
        }
        long bytes = (long) n * 48L;
        if ((n & 7) == 0) {
            byte[] burst = new byte[256 * 1024];
            burst[0] = (byte) n;
            sink = burst;
            bytes += burst.length;
        }
        return bytes;
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