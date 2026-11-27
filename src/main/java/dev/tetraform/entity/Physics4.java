package dev.tetraform.entity;

import dev.tetraform.math.AABB4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Level4;

import java.util.List;

/** Swept axis-separated collision against 4D block hyperboxes, with vanilla-style step-up. */
public final class Physics4 {
    private Physics4() {}

    public record Result(Vec4 delta, boolean onGround, boolean horizontalCollision, boolean verticalCollision) {}

    public static Result move(Level4 level, AABB4 box, Vec4 delta, double stepHeight, boolean wasOnGround) {
        return move(level, box, delta, stepHeight, wasOnGround, null);
    }

    /**
     * Movement for a viewer looking through {@code view}: only blocks that are drawn in that slice count, so you
     * never stand on, bump into or walk along a block you can't see. In an axis-aligned view the body is flattened
     * onto the slice along the hidden axis (so stepping along it stops exactly at the face of the next visible
     * layer); in a tilted view blocks the hyperplane doesn't pass through are ignored.
     */
    public static Result move(Level4 level, AABB4 box, Vec4 delta, double stepHeight, boolean wasOnGround, SliceFrame view) {
        if (view != null && view.isAxisAligned()) {
            int axis = Math.abs(view.normalX()) > 0.5 ? 0 : Math.abs(view.normalZ()) > 0.5 ? 2 : 3;
            double c = (box.min(axis) + box.max(axis)) * 0.5;
            box = switch (axis) {
                case 0 -> new AABB4(c, box.minY(), box.minZ(), box.minW(), c + 1.0E-5, box.maxY(), box.maxZ(), box.maxW());
                case 2 -> new AABB4(box.minX(), box.minY(), c, box.minW(), box.maxX(), box.maxY(), c + 1.0E-5, box.maxW());
                default -> new AABB4(box.minX(), box.minY(), box.minZ(), c, box.maxX(), box.maxY(), box.maxZ(), c + 1.0E-5);
            };
            view = null;
        }
        VIEW.set(view);
        try {
            return moveStep(level, box, delta, stepHeight, wasOnGround);
        } finally {
            VIEW.remove();
        }
    }

    private static final ThreadLocal<SliceFrame> VIEW = new ThreadLocal<>();

    /** Does the hyperplane of {@code f} pass through {@code b} anywhere in the depth range [d0, d1]? */
    public static boolean onSlice(SliceFrame f, AABB4 b, double d0, double d1) {
        double lo = 0, hi = 0;
        double[] n = {f.normalX(), f.normalZ(), f.normalW()};
        double[] mn = {b.minX(), b.minZ(), b.minW()}, mx = {b.maxX(), b.maxZ(), b.maxW()};
        for (int i = 0; i < 3; i++) {
            lo += n[i] * (n[i] > 0 ? mn[i] : mx[i]);
            hi += n[i] * (n[i] > 0 ? mx[i] : mn[i]);
        }
        return hi > Math.min(d0, d1) + 1.0E-6 && lo < Math.max(d0, d1) - 1.0E-6 || lo <= d0 && d0 < hi;
    }

    private static Result moveStep(Level4 level, AABB4 box, Vec4 delta, double stepHeight, boolean wasOnGround) {
        Result r = moveRaw(level, box, delta);
        if (stepHeight > 0 && r.horizontalCollision && (wasOnGround || r.onGround)) {
            // try stepping: up, horizontal, down
            Vec4 up = new Vec4(0, stepHeight, 0, 0);
            Result a = moveRaw(level, box, up);
            AABB4 b1 = box.move(0, a.delta.y(), 0, 0);
            Result h = moveRaw(level, b1, new Vec4(delta.x(), 0, delta.z(), delta.w()));
            AABB4 b2 = b1.move(h.delta.x(), 0, h.delta.z(), h.delta.w());
            Result d = moveRaw(level, b2, new Vec4(0, -a.delta.y() + Math.min(0, delta.y()), 0, 0));
            double moved = h.delta.horizontalLengthSqr();
            if (moved > r.delta.horizontalLengthSqr() + 1.0E-7) {
                Vec4 total = new Vec4(h.delta.x(), a.delta.y() + d.delta.y(), h.delta.z(), h.delta.w());
                return new Result(total, true, h.horizontalCollision, d.verticalCollision);
            }
        }
        return r;
    }

    private static Result moveRaw(Level4 level, AABB4 box, Vec4 delta) {
        List<AABB4> obstacles = level.collisions(box.expandTowards(delta.x(), delta.y(), delta.z(), delta.w()).inflate(1.0E-3));
        SliceFrame view = VIEW.get();
        if (view != null) {
            Vec4 c = box.center();
            double d0 = view.depthOf(c), d1 = d0 + view.depthOf(delta);
            // unloaded terrain (a solid placeholder reaching below the world) always blocks
            obstacles.removeIf(o -> o.minY() > -1 && !onSlice(view, o, d0, d1));
        }
        double dy = delta.y();
        for (AABB4 o : obstacles) dy = box.clipAxis(o, 1, dy);
        box = box.move(0, dy, 0, 0);
        // order horizontal axes by magnitude (like vanilla, the larger first)
        int[] order = {0, 2, 3};
        double[] d = {delta.x(), 0, delta.z(), delta.w()};
        for (int i = 0; i < 3; i++) for (int j = i + 1; j < 3; j++) {
            if (Math.abs(d[order[j]]) > Math.abs(d[order[i]])) { int t = order[i]; order[i] = order[j]; order[j] = t; }
        }
        double[] out = {0, dy, 0, 0};
        boolean hc = false;
        for (int axis : order) {
            double v = d[axis];
            if (v == 0) continue;
            double c = v;
            for (AABB4 o : obstacles) c = box.clipAxis(o, axis, c);
            if (c != v) hc = true;
            box = box.move(axis, c);
            out[axis] = c;
        }
        boolean vc = dy != delta.y();
        return new Result(new Vec4(out[0], out[1], out[2], out[3]), vc && delta.y() < 0, hc, vc);
    }

    /** Ray vs hyperbox (slab test). Returns entry distance or NaN. */
    public static double rayBox(Vec4 o, Vec4 d, AABB4 b) {
        double tmin = 0, tmax = Double.POSITIVE_INFINITY;
        for (int a = 0; a < 4; a++) {
            double oa = o.get(a), da = d.get(a), lo = b.min(a), hi = b.max(a);
            if (Math.abs(da) < 1.0E-12) {
                if (oa < lo || oa > hi) return Double.NaN;
            } else {
                double t1 = (lo - oa) / da, t2 = (hi - oa) / da;
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return Double.NaN;
            }
        }
        return tmin;
    }
}
