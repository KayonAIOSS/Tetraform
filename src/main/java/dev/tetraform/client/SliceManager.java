package dev.tetraform.client;

import dev.tetraform.client.render.MeshCache;
import dev.tetraform.client.render.MeshWorkers;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Column4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the terrain meshes in step with the viewer's hyperplane. A remesh happens only when the hyperplane
 * changes: the zw angle changes, or the viewer crosses a step along the normal. Walking within the slice and
 * mouse look never trigger one. Block and column changes dirty only the sections their projection touches.
 * <p>
 * Geometry comes from {@link MeshCache} whenever possible. After a turn (or teleport) anything not cached yet is
 * built in the background and shows as empty space until it is ready, nearest first, the way vanilla chunks
 * stream in after a teleport; the previous slice is never left on screen. While the view holds still, the
 * workers fill in the rest of the slice within render distance and then the slices you are likely to see next.
 */
public final class SliceManager {
    private SliceManager() {}

    private static long dirtyMinX = Long.MAX_VALUE, dirtyMinZ, dirtyMaxX, dirtyMaxZ;
    private static boolean dirtyAll;
    /** Render sections dirtied by world edits: these build missing cells immediately so edits never flicker. */
    private static final Set<Long> CONTENT_DIRTY = ConcurrentHashMap.newKeySet();

    /** Depth step for slices that aren't axis-aligned: finer looks smoother when walking, coarser rebuilds less. */
    public static final double OBLIQUE_DEPTH_STEP = 0.25;

    /** Depth used for meshing. Axis-aligned slices only change when crossing a whole block. */
    public static double quantizeDepth(SliceFrame f, double depth) {
        if (f.isAxisAligned()) return Math.floor(depth) + 0.5;
        return Math.round(depth / OBLIQUE_DEPTH_STEP) * OBLIQUE_DEPTH_STEP + 1.0E-4;
    }

    private static int stableTicks;
    private static int lastRotateDir;

    /** Call each tick; schedules a full remesh if the hyperplane moved. */
    public static void update() {
        ClientState4.MeshParams m = ClientState4.mesh;
        SliceFrame f = ClientState4.FRAME;
        Vec4 p = ClientState4.pos;
        double depth = quantizeDepth(f, f.depthOf(p));
        boolean frameChanged = !m.frame().approxEquals(f, 1.0E-12);
        if (frameChanged || Math.abs(depth - m.depth()) > 1.0E-9) {
            double offX = m.offX(), offZ = m.offZ();
            if (frameChanged) {
                // keep the player's render-space position fixed so rotating never moves the camera
                offX = m.renderX(p.x(), p.z(), p.w()) - f.localX(p.x(), p.z(), p.w());
                offZ = m.renderZ(p.x(), p.z(), p.w()) - f.localZ(p.x(), p.z(), p.w());
                // back at zw = 0, render space is world space again (vanilla systems line up exactly)
                if (Math.abs(f.zwAngle()) < 1.0E-9) { offX = 0; offZ = 0; }
                double a = f.zwAngle() - m.frame().zwAngle();
                a = Math.atan2(Math.sin(a), Math.cos(a));
                lastRotateDir = a > 0 ? 1 : -1;
            }
            publish(new ClientState4.MeshParams(f.copy(), depth, offX, offZ, m.version() + 1, frameChanged));
        } else {
            stableTicks++;
        }
        flush();
        drainFinished();
        prefetch();
    }

    /** Fresh mapping after a teleport: offsets reset to zero (matches the server's view of the player). */
    public static void forceRemesh() {
        ClientState4.MeshParams m = ClientState4.mesh;
        SliceFrame f = ClientState4.FRAME;
        publish(new ClientState4.MeshParams(f.copy(), quantizeDepth(f, f.depthOf(ClientState4.pos)), 0, 0, m.version() + 1, true));
    }

    private static void publish(ClientState4.MeshParams next) {
        // the workers must want the new slice before any section asks for it
        MeshWorkers.setWanted(targets(next));
        ClientState4.mesh = next;
        dirtyAll = true;
        stableTicks = 0;
        viewCursor = null;
    }

    /** The slice on screen, then the ones you are most likely to see next. */
    private static List<MeshWorkers.Target> targets(ClientState4.MeshParams m) {
        List<MeshWorkers.Target> out = new ArrayList<>();
        out.add(new MeshWorkers.Target(m.frame(), m.depth()));
        Vec4 p = ClientState4.pos;
        double step = m.frame().isAxisAligned() ? 1.0 : OBLIQUE_DEPTH_STEP;
        // one step along W either way (walking or strafing through the hidden axis)
        out.add(new MeshWorkers.Target(m.frame(), m.depth() + step));
        out.add(new MeshWorkers.Target(m.frame(), m.depth() - step));
        // the next rotation steps, in the direction of the last turn first
        double zw = ClientState4.zw;
        int dir = lastRotateDir == 0 ? 1 : lastRotateDir;
        // the quarter-turn views the side buttons snap to, then one scroll notch either way
        double[] next = {Rotation4.nextQuarter(zw, -1), Rotation4.nextQuarter(zw, 1),
                zw + Math.toRadians(Rotation4.SCROLL_STEP_DEG * dir), zw - Math.toRadians(Rotation4.SCROLL_STEP_DEG * dir)};
        for (double a0 : next) {
            double a = a0;
            SliceFrame g = SliceFrame.zw(snapAngle(a));
            out.add(new MeshWorkers.Target(g, quantizeDepth(g, g.depthOf(p))));
        }
        return out;
    }

    /** Same normalisation as {@link ClientState4#setZW}, so predicted frames equal the real ones exactly. */
    private static double snapAngle(double angle) {
        double twoPi = Math.PI * 2;
        double a = ((angle % twoPi) + twoPi) % twoPi;
        double q = a / (Math.PI / 2);
        if (Math.abs(q - Math.rint(q)) < 1.0E-9) a = Math.rint(q) * (Math.PI / 2) % twoPi;
        return a;
    }

    // ------------------------------------------------------------------ background prefetch

    /** Cells still to queue: for the slice on screen first (whole render distance), then the predicted ones. */
    private static ArrayList<int[]> viewCursor;
    private static int viewIndex;
    private static final int PREFETCH_PER_TICK = 1024;
    private static final int AHEAD_RADIUS = 5;

    private static void prefetch() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || stableTicks < 2 || !MeshWorkers.idle()) return;
        ClientState4.MeshParams m = ClientState4.mesh;
        if (viewCursor == null) {
            viewCursor = new ArrayList<>();
            viewIndex = 0;
            int rd = mc.options.getEffectiveRenderDistance() + 1;
            int top = Math.min((Column4.HEIGHT >> 4) - 1, ClientState4.LEVEL.maxY() >> 4);
            List<MeshWorkers.Target> ts = targets(m);
            for (int t = 0; t < ts.size(); t++) {
                SliceFrame f = ts.get(t).frame();
                Vec4 p = ClientState4.pos;
                int ci = Math.floorDiv((int) Math.floor(f.localX(p.x(), p.z(), p.w())), 16);
                int cj = Math.floorDiv((int) Math.floor(f.localZ(p.x(), p.z(), p.w())), 16);
                int r = t == 0 ? rd : AHEAD_RADIUS;
                ArrayList<int[]> cells = new ArrayList<>();
                for (int di = -r; di <= r; di++) for (int dj = -r; dj <= r; dj++) {
                    if (di * di + dj * dj > r * r) continue;
                    for (int sy = 0; sy <= top; sy++) cells.add(new int[]{ci + di, sy, cj + dj, t, di * di + dj * dj});
                }
                cells.sort((a, b) -> Integer.compare(a[4], b[4]));
                viewCursor.addAll(cells);
            }
        }
        List<MeshWorkers.Target> ts = targets(m);
        for (int n = 0; n < PREFETCH_PER_TICK && viewIndex < viewCursor.size(); n++) {
            int[] c = viewCursor.get(viewIndex++);
            MeshWorkers.Target t = ts.get(c[3]);
            MeshWorkers.submit(c[0], c[1], c[2], t.frame(), t.depth(), c[3] == 0 ? MeshWorkers.VIEW : MeshWorkers.AHEAD);
        }
    }

    /** Marks the render sections showing freshly built cells of the current slice, so they copy them in. */
    private static void drainFinished() {
        ClientLevel level = Minecraft.getInstance().level;
        ClientState4.MeshParams m = ClientState4.mesh;
        MeshCache.Key k;
        int budget = 4096;
        while (budget-- > 0 && (k = MeshWorkers.pollDone()) != null) {
            if (level == null) continue;
            int x0 = (int) Math.floor(k.cx() * 16 + m.offX()) >> 4, x1 = (int) Math.floor(k.cx() * 16 + 16 + m.offX() - 1.0E-6) >> 4;
            int z0 = (int) Math.floor(k.cz() * 16 + m.offZ()) >> 4, z1 = (int) Math.floor(k.cz() * 16 + 16 + m.offZ() - 1.0E-6) >> 4;
            level.setSectionRangeDirty(x0, k.sy(), z0, x1, k.sy(), z1);
        }
    }

    /** Called each rendered frame, so background results appear without waiting for the next tick. */
    public static void onFrame() {
        if (ClientState4.active) drainFinished();
    }

    /** True (once) if this render section was dirtied by a world edit rather than by a new slice. */
    public static boolean takeContentDirty(BlockPos sectionOrigin) {
        return !CONTENT_DIRTY.isEmpty() && CONTENT_DIRTY.remove(SectionPos.asLong(sectionOrigin.getX() >> 4, sectionOrigin.getY() >> 4, sectionOrigin.getZ() >> 4));
    }

    public static void reset() {
        CONTENT_DIRTY.clear();
        MeshWorkers.reset();
        viewCursor = null;
    }

    // ------------------------------------------------------------------ mapping and edits

    /** Render-space position (vanilla coordinates) of a 4D point under the current mesh mapping. */
    public static double[] toRender(Vec4 p) {
        ClientState4.MeshParams m = ClientState4.mesh;
        return new double[]{m.renderX(p.x(), p.z(), p.w()), p.y(), m.renderZ(p.x(), p.z(), p.w())};
    }

    private static void markRender(double minX, double minZ, double maxX, double maxZ) {
        long x0 = (long) Math.floor(minX) >> 4, x1 = (long) Math.floor(maxX) >> 4;
        long z0 = (long) Math.floor(minZ) >> 4, z1 = (long) Math.floor(maxZ) >> 4;
        if (dirtyMinX == Long.MAX_VALUE) {
            dirtyMinX = x0; dirtyMaxX = x1; dirtyMinZ = z0; dirtyMaxZ = z1;
        } else {
            dirtyMinX = Math.min(dirtyMinX, x0); dirtyMaxX = Math.max(dirtyMaxX, x1);
            dirtyMinZ = Math.min(dirtyMinZ, z0); dirtyMaxZ = Math.max(dirtyMaxZ, z1);
        }
    }

    public static void markBlock(BlockPos4 p) {
        ClientState4.MeshParams m = ClientState4.mesh;
        double cx = p.x() + 0.5, cz = p.z() + 0.5, cw = p.w() + 0.5;
        double rx = m.renderX(cx, cz, cw), rz = m.renderZ(cx, cz, cw);
        markRender(rx - 2, rz - 2, rx + 2, rz + 2);
    }

    public static void markColumn(int cx, int cz, int cw) {
        ClientState4.MeshParams m = ClientState4.mesh;
        SliceFrame f = m.frame();
        double depth = m.depth();
        double ccx = cx * 16 + 8, ccz = cz * 16 + 8, ccw = cw * 16 + 8;
        double h = 8 * (Math.abs(f.normalX()) + Math.abs(f.normalZ()) + Math.abs(f.normalW())) + 1.5;
        if (Math.abs(f.normalX() * ccx + f.normalZ() * ccz + f.normalW() * ccw - depth) > h) return;
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double x = cx * 16 + ((i & 1) != 0 ? 17 : -1);
            double z = cz * 16 + ((i & 2) != 0 ? 17 : -1);
            double w = cw * 16 + ((i & 4) != 0 ? 17 : -1);
            double rx = m.renderX(x, z, w), rz = m.renderZ(x, z, w);
            minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
            minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
        }
        markRender(minX, minZ, maxX, maxZ);
    }

    private static void flush() {
        ClientLevel level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (level == null || player == null) return;
        int ymax = (Column4.HEIGHT >> 4) - 1;
        int rd = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
        int sx = player.blockPosition().getX() >> 4, sz = player.blockPosition().getZ() >> 4;
        int contentTop = Math.min(ymax, (ClientState4.LEVEL.maxY() >> 4));
        if (dirtyAll) {
            dirtyAll = false;
            dirtyMinX = Long.MAX_VALUE;
            CONTENT_DIRTY.clear();
            level.setSectionRangeDirty(sx - rd, 0, sz - rd, sx + rd, contentTop, sz + rd);
            return;
        }
        if (dirtyMinX != Long.MAX_VALUE) {
            long x0 = Math.max(dirtyMinX, sx - rd), x1 = Math.min(dirtyMaxX, sx + rd);
            long z0 = Math.max(dirtyMinZ, sz - rd), z1 = Math.min(dirtyMaxZ, sz + rd);
            if (x0 <= x1 && z0 <= z1) {
                for (long x = x0; x <= x1; x++) for (long z = z0; z <= z1; z++) for (int y = 0; y <= ymax; y++) {
                    CONTENT_DIRTY.add(SectionPos.asLong((int) x, y, (int) z));
                }
                level.setSectionRangeDirty((int) x0, 0, (int) z0, (int) x1, ymax, (int) z1);
            }
            dirtyMinX = Long.MAX_VALUE;
        }
    }
}
