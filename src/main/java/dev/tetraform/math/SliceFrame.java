package dev.tetraform.math;

/**
 * The orientation of a viewer's 3D slice ("hyperplane") through 4D space.
 * <p>
 * Tetraform's look rotation is the composition the player experiences as: <b>XZ (yaw)</b>, then
 * <b>forward&harr;W</b>, then <b>forward&harr;Y (pitch)</b>. Because both yaw and the forward/W rotation never touch the
 * Y axis, all accumulated horizontal rotation lives in a rotation matrix over the three horizontal axes
 * (x, z, w). This class stores that matrix. Its columns are the world-space directions of the viewer's local
 * x', z' and w' axes. The local x'/y/z' axes span the visible hyperplane, and w' is its normal.
 * <p>
 * Yaw and pitch then stay ordinary vanilla angles measured inside the slice. Turning with the mouse rotates
 * inside the hyperplane and never changes which 3D slice is visible, so mouse look costs nothing. Rotating
 * forward into W ({@link #rotateForwardIntoW}) is the only thing that tilts the hyperplane.
 */
public final class SliceFrame {
    /** Row-major 3x3 over (x, z, w). m[r*3+c]: row r = world axis (0=x,1=z,2=w), column c = local axis. */
    private final double[] m = new double[9];

    public SliceFrame() {
        setIdentity();
    }

    public SliceFrame(double[] values) {
        System.arraycopy(values, 0, m, 0, 9);
    }

    public SliceFrame copy() {
        return new SliceFrame(m);
    }

    public void set(SliceFrame o) {
        System.arraycopy(o.m, 0, m, 0, 9);
    }

    public void setIdentity() {
        java.util.Arrays.fill(m, 0);
        m[0] = m[4] = m[8] = 1;
    }

    public double[] values() {
        return m.clone();
    }

    public double get(int row, int col) {
        return m[row * 3 + col];
    }

    public boolean isAxisAligned() {
        return Math.abs(Math.abs(m[8]) - 1.0) < 1.0E-9;
    }

    /** World-space 4D unit normal of the hyperplane (its Y component is always 0). */
    public Vec4 normal() {
        return new Vec4(m[2], 0, m[5], m[8]);
    }

    public double normalX() { return m[2]; }
    public double normalZ() { return m[5]; }
    public double normalW() { return m[8]; }

    /** Signed offset of a world point along the hyperplane normal. */
    public double depthOf(Vec4 p) {
        return m[2] * p.x() + m[5] * p.z() + m[8] * p.w();
    }

    /** Converts a world point into slice-local coordinates (x', y, z', w'). w' is the depth off the slice. */
    public Vec4 toLocal(Vec4 p) {
        double x = p.x(), z = p.z(), w = p.w();
        return new Vec4(m[0] * x + m[3] * z + m[6] * w, p.y(), m[1] * x + m[4] * z + m[7] * w, m[2] * x + m[5] * z + m[8] * w);
    }

    public Vec4 toWorld(Vec4 l) {
        return toWorld(l.x(), l.y(), l.z(), l.w());
    }

    public Vec4 toWorld(double lx, double ly, double lz, double lw) {
        return new Vec4(m[0] * lx + m[1] * lz + m[2] * lw, ly, m[3] * lx + m[4] * lz + m[5] * lw, m[6] * lx + m[7] * lz + m[8] * lw);
    }

    /** Local-x' of a world horizontal (x,z,w) point; fast path for meshing. */
    public double localX(double x, double z, double w) { return m[0] * x + m[3] * z + m[6] * w; }
    public double localZ(double x, double z, double w) { return m[1] * x + m[4] * z + m[7] * w; }

    /**
     * Rotates the frame in the plane spanned by the viewer's current horizontal forward (given by the in-slice
     * vanilla yaw) and the slice normal: "forward into W". Positive angles tilt forward toward +w'.
     */
    public void rotateForwardIntoW(float yawDegrees, double angleRadians) {
        double yaw = Math.toRadians(yawDegrees);
        // vanilla: forward = (-sin(yaw), 0, cos(yaw)) in (x', y, z').
        double ax = -Math.sin(yaw), az = Math.cos(yaw);
        double c = Math.cos(angleRadians), s = Math.sin(angleRadians);
        // G in local (x', z', w') coordinates: rotation in plane {a = (ax, az, 0), b = (0, 0, 1)}.
        // G = I + s (b a^T - a b^T) + (c - 1)(a a^T + b b^T)
        double[] g = new double[9];
        double[] a = {ax, az, 0};
        double[] b = {0, 0, 1};
        for (int r = 0; r < 3; r++) {
            for (int col = 0; col < 3; col++) {
                double v = (r == col ? 1 : 0)
                        + s * (b[r] * a[col] - a[r] * b[col])
                        + (c - 1) * (a[r] * a[col] + b[r] * b[col]);
                g[r * 3 + col] = v;
            }
        }
        multiplyRight(g);
        orthonormalize();
        snap();
    }

    /** Rotates the frame inside the slice (about the normal); used to fold yaw into the frame. */
    public void rotateInSlice(double angleRadians) {
        double c = Math.cos(angleRadians), s = Math.sin(angleRadians);
        multiplyRight(new double[]{c, -s, 0, s, c, 0, 0, 0, 1});
        orthonormalize();
        snap();
    }

    /** World-space horizontal direction (x, z, w) of the local forward axis z'. */
    public double[] forward() {
        return new double[]{m[1], m[4], m[7]};
    }

    /**
     * Turns the local forward axis toward the world horizontal direction (tx, tz, tw) by at most
     * {@code maxAngle} radians: a rotation in the 3D horizontal space (x, z, w). Mobs use this to turn freely
     * through the fourth dimension.
     */
    public void turnForwardTowards(double tx, double tz, double tw, double maxAngle) {
        double tl = Math.sqrt(tx * tx + tz * tz + tw * tw);
        if (tl < 1.0E-9) return;
        tx /= tl; tz /= tl; tw /= tl;
        double fx = m[1], fz = m[4], fw = m[7];
        double dot = Math.max(-1, Math.min(1, fx * tx + fz * tz + fw * tw));
        double angle = Math.acos(dot);
        if (angle < 1.0E-6) return;
        // axis = f x t in (x, z, w) space
        double ax = fz * tw - fw * tz, az = fw * tx - fx * tw, aw = fx * tz - fz * tx;
        double al = Math.sqrt(ax * ax + az * az + aw * aw);
        if (al < 1.0E-9) {
            // opposite directions: rotate about the local x' axis
            ax = m[0]; az = m[3]; aw = m[6];
            al = 1;
        }
        ax /= al; az /= al; aw /= al;
        double th = Math.min(angle, maxAngle);
        double c = Math.cos(th), s = Math.sin(th), t = 1 - c;
        double[] r = {
                t * ax * ax + c, t * ax * az - s * aw, t * ax * aw + s * az,
                t * ax * az + s * aw, t * az * az + c, t * az * aw - s * ax,
                t * ax * aw - s * az, t * az * aw + s * ax, t * aw * aw + c};
        double[] out = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double v = 0;
            for (int k = 0; k < 3; k++) v += r[i * 3 + k] * m[k * 3 + j];
            out[i * 3 + j] = v;
        }
        System.arraycopy(out, 0, m, 0, 9);
        orthonormalize();
    }

    private void multiplyRight(double[] g) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double v = 0;
                for (int k = 0; k < 3; k++) v += m[i * 3 + k] * g[k * 3 + j];
                r[i * 3 + j] = v;
            }
        }
        System.arraycopy(r, 0, m, 0, 9);
    }

    /** Gram-Schmidt on the columns to stop floating point drift. */
    public void orthonormalize() {
        for (int c = 0; c < 3; c++) {
            for (int p = 0; p < c; p++) {
                double d = 0;
                for (int r = 0; r < 3; r++) d += m[r * 3 + c] * m[r * 3 + p];
                for (int r = 0; r < 3; r++) m[r * 3 + c] -= d * m[r * 3 + p];
            }
            double l = 0;
            for (int r = 0; r < 3; r++) l += m[r * 3 + c] * m[r * 3 + c];
            l = Math.sqrt(l);
            if (l < 1.0E-12) { setIdentity(); return; }
            for (int r = 0; r < 3; r++) m[r * 3 + c] /= l;
        }
    }

    /** Snaps entries that are numerically 0/±1 so that 90° rotations give exact axis-aligned frames. */
    public void snap() {
        boolean all = true;
        for (double v : m) {
            double a = Math.abs(v);
            if (!(a < 1.0E-6 || Math.abs(a - 1) < 1.0E-6)) { all = false; break; }
        }
        if (all) {
            for (int i = 0; i < 9; i++) m[i] = Math.abs(m[i]) < 0.5 ? 0 : Math.signum(m[i]);
        }
    }

    /** Angle (degrees) between the slice normal and the W axis; 0 means the usual xyz slice. */
    public double tiltDegrees() {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, m[8]))));
    }

    public boolean approxEquals(SliceFrame o, double eps) {
        for (int i = 0; i < 9; i++) if (Math.abs(m[i] - o.m[i]) > eps) return false;
        return true;
    }

    /**
     * World-space 4D look direction for a vanilla yaw/pitch measured inside this slice.
     * Composition order: yaw (xz) &rarr; frame (forward&harr;w) &rarr; pitch (forward&harr;y).
     */
    public Vec4 lookDirection(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        double lx = -Math.sin(yaw) * cp, ly = -Math.sin(pitch), lz = Math.cos(yaw) * cp;
        return toWorld(lx, ly, lz, 0);
    }
}
