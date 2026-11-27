package dev.tetraform.client.render;

import dev.tetraform.client.ClientState4;
import dev.tetraform.math.SliceFrame;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background slicing, so turning or moving through W never waits on (or freezes) the chunk builders.
 * <p>
 * Three kinds of work, nearest cell first within each:
 * <ol>
 *   <li><b>demand</b>: cells a render section needs right now (it shows empty space until they are ready);</li>
 *   <li><b>view</b>: the rest of the current slice within render distance, including what is behind you, so
 *   looking around after a turn replays finished geometry like vanilla;</li>
 *   <li><b>ahead</b>: the slices you are about to see (the next rotation steps, the next depth along W), so a turn
 *   or a step through W usually finds its geometry already built.</li>
 * </ol>
 * Work for a slice you have left is dropped before it starts. Finished cells go into {@link MeshCache}; cells of
 * the slice on screen also mark their render sections for a rebuild, which then just copies them in.
 */
public final class MeshWorkers {
    private MeshWorkers() {}

    public static final int DEMAND = 0, VIEW = 1, AHEAD = 2;

    private static final int THREADS = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 3));
    private static final AtomicLong SEQ = new AtomicLong();
    /** Key -> best (lowest) priority class it is queued with. */
    private static final ConcurrentHashMap<MeshCache.Key, Integer> PENDING = new ConcurrentHashMap<>();
    /** Cells of the slice on screen that finished; the main thread marks their sections dirty. */
    private static final ConcurrentLinkedQueue<MeshCache.Key> DONE = new ConcurrentLinkedQueue<>();

    /** The slices worth building for: index 0 is the one on screen, the rest are predictions. */
    private static volatile List<Target> wanted = List.of();

    public record Target(SliceFrame frame, double depth) {
        boolean matches(SliceFrame f, double d) {
            return Math.abs(depth - d) < 1.0E-9 && frame.approxEquals(f, 1.0E-9);
        }
    }

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new PriorityBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "Tetraform slicer");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    /** A cell of a slice, without the content stamp (the worker works that out when it runs). */
    static MeshCache.Key id(int i, int sy, int j, SliceFrame f, double depth) {
        return new MeshCache.Key(i, sy, j, MeshCache.Key.q(f.get(1, 1)), MeshCache.Key.q(f.get(2, 1)), MeshCache.Key.q(depth), 0);
    }

    private static final class Task implements Runnable, Comparable<Task> {
        final MeshCache.Key key; // id (stamp 0)
        final SliceFrame frame;
        final double depth;
        final int cls;
        final double dist;
        final long seq = SEQ.incrementAndGet();

        Task(MeshCache.Key key, SliceFrame frame, double depth, int cls, double dist) {
            this.key = key;
            this.frame = frame;
            this.depth = depth;
            this.cls = cls;
            this.dist = dist;
        }

        @Override
        public int compareTo(Task o) {
            if (cls != o.cls) return Integer.compare(cls, o.cls);
            int c = Double.compare(dist, o.dist);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }

        boolean stillWanted() {
            for (Target t : wanted) if (t.matches(frame, depth)) return true;
            return false;
        }

        @Override
        public void run() {
            try {
                if (!stillWanted() || !ClientState4.active) return;
                MeshCache.Key full = SliceMesher.cellKey(key.cx(), key.sy(), key.cz(), frame, depth, ClientState4.LEVEL);
                if (!MeshCache.contains(full)) SliceMesher.buildCell(full, frame, depth, ClientState4.LEVEL);
                else if (cls != DEMAND) return; // already there: nothing new to show
                List<Target> w = wanted;
                if (!w.isEmpty() && w.get(0).matches(frame, depth)) DONE.add(key);
            } catch (Throwable t) {
                dev.tetraform.Tetraform.LOGGER.error("4D background slicing failed for cell {}", key, t);
            } finally {
                PENDING.remove(key, cls);
            }
        }
    }

    private static double distance(MeshCache.Key k, SliceFrame f) {
        var p = ClientState4.pos;
        double lx = f.localX(p.x(), p.z(), p.w()), lz = f.localZ(p.x(), p.z(), p.w());
        double dx = k.cx() * 16 + 8 - lx, dy = k.sy() * 16 + 8 - p.y(), dz = k.cz() * 16 + 8 - lz;
        return dx * dx + dy * dy * 0.5 + dz * dz;
    }

    /** Queues a cell (or raises its priority); does nothing if it is already queued at least as urgently. */
    public static void submit(int i, int sy, int j, SliceFrame frame, double depth, int cls) {
        MeshCache.Key key = id(i, sy, j, frame, depth);
        boolean[] queued = new boolean[1];
        PENDING.compute(key, (k, cur) -> {
            if (cur != null && cur <= cls) return cur;
            queued[0] = true;
            return cls;
        });
        if (!queued[0]) return;
        POOL.execute(new Task(key, frame, depth, cls, distance(key, frame)));
    }

    public static void demand(MeshCache.Key key, SliceFrame frame, double depth) {
        submit(key.cx(), key.sy(), key.cz(), frame, depth, DEMAND);
    }

    /** Sets the slices worth building for and drops queued work for any other slice. */
    public static void setWanted(List<Target> targets) {
        wanted = List.copyOf(targets);
        POOL.getQueue().removeIf(r -> {
            Task t = (Task) r;
            if (t.stillWanted()) return false;
            PENDING.remove(t.key, t.cls);
            return true;
        });
    }

    public static boolean idle() {
        return POOL.getQueue().size() < THREADS * 4;
    }

    public static int queued() {
        return POOL.getQueue().size();
    }

    /** Cells of the slice on screen finished since the last call. */
    public static MeshCache.Key pollDone() {
        return DONE.poll();
    }

    public static void reset() {
        wanted = List.of();
        POOL.getQueue().clear();
        PENDING.clear();
        DONE.clear();
    }
}
