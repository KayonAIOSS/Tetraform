package dev.tetraform.entity;

import dev.tetraform.math.AABB4;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Level4;

import java.util.List;

/** Swept axis-separated collision against 4D block hyperboxes, with vanilla-style step-up. */
public final class Physics4 {
    private Physics4() {}

    public record Result(Vec4 delta, boolean onGround, boolean horizontalCollision, boolean verticalCollision) {}

    public static Result move(Level4 level, AABB4 box, Vec4 delta, double stepHeight, boolean wasOnGround) {
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
