import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * GC stress harness modelling Minecraft's allocation shape: many short-lived
 * objects, small bursts (chunk gen / world load), tiny live set. Run with
 * different HotSpot GC flags; each run prints one GCRESULT line with total GC
 * pause time, collection count, achieved allocation rate and a rough pause
 * estimate (max per-collector time / count). Compare across configs in CI.
 *
 * Usage: java [GC flags] GcStress [mbPerRound] [rounds] [smallObjScale]
 */
public final class GcStress {
    private static final int LIVE_SLOTS = 512;

    public static void main(String[] args) {
        int mbPerRound = args.length > 0 ? Integer.parseInt(args[0]) : 48;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 24;
        int smallScale = args.length > 2 ? Integer.parseInt(args[2]) : 20000;

        Object[] live = new Object[LIVE_SLOTS];
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();

        for (int r = 0; r < 4; r++) allocateRound(mbPerRound, smallScale, live);

        long gcTime0 = totalGcTime(beans);
        long gcCount0 = totalGcCount(beans);
        long t0 = System.nanoTime();
        for (int r = 0; r < rounds; r++) allocateRound(mbPerRound, smallScale, live);
        long elapsedNs = System.nanoTime() - t0;

        long gcMs = totalGcTime(beans) - gcTime0;
        long gcCount = totalGcCount(beans) - gcCount0;
        long allocatedMb = (long) mbPerRound * rounds;
        double allocMbPerSec = allocatedMb * 1_000_000_000.0 / elapsedNs;

        long worstPauseMs = 0;
        for (GarbageCollectorMXBean b : beans) {
            long c = b.getCollectionCount();
            if (c > 0) worstPauseMs = Math.max(worstPauseMs, b.getCollectionTime() / c);
        }

        long liveBytes = 0;
        for (Object o : live) if (o instanceof byte[]) liveBytes += ((byte[]) o).length;

        System.out.printf("GCRESULT gcTotalMs=%d gcCount=%d allocMbPerSec=%.1f approxWorstPauseMs=%d liveKeptMb=%d elapsedMs=%d%n",
                gcMs, gcCount, allocMbPerSec, worstPauseMs,
                liveBytes / (1024 * 1024), elapsedNs / 1_000_000);
    }

    private static void allocateRound(int mb, int smallScale, Object[] live) {
        for (int i = 0; i < mb; i++) {
            byte[] block = new byte[1024 * 1024];
            block[0] = (byte) i;
            if (i % 7 == 0) live[i % live.length] = block;
        }
        for (int i = 0; i < smallScale; i++) {
            Node n = new Node(i);
            n.next = new Node(i + 1);
            if ((i & 31) == 0) live[i % live.length] = n;
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

    private static final class Node {
        int v;
        Node next;
        Node(int v) { this.v = v; }
    }
}