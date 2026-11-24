package dev.tetraform.math;

/**
 * Intersects axis-aligned 4D hyperboxes with a viewer's hyperplane and emits the resulting <b>faces</b>
 * (convex polygons), already mapped into the viewer's 3D render space.
 * <p>
 * A hyperbox has eight bounding cells (3-cubes), one per {@link Direction4}. A hyperplane cuts each cell in a
 * convex polygon. The visible 3D object is the polyhedron bounded by those polygons. Since the hyperplane
 * normal never has a Y component, the cuts come in two kinds:
 * <ul>
 *     <li>cells normal to X, Z or W become vertical rectangles (a clipped line segment swept along Y);</li>
 *     <li>cells normal to Y (top and bottom) become convex polygons with 3 to 6 vertices.</li>
 * </ul>
 * Instances keep scratch buffers, so each thread should use its own. The slicer does no allocation per face.
 */
public final class Slicer {
    private static final double EPS = 1.0E-9;

    /** Receives one convex face. Arrays are reused between calls; copy what you need. */
    public interface FaceSink {
        /**
         * @param dir    the cell (face direction) of the hyperbox this polygon came from
         * @param count  vertex count (3..6), counter-clockwise seen from outside
         * @param pos    render-space positions, xyz interleaved
         * @param uv     texture coordinates in block units, uv interleaved
         * @param nx     render-space face normal
         */
        void face(Direction4 dir, int count, float[] pos, float[] uv, float nx, float ny, float nz);
    }

    private final float[] pos = new float[18];
    private final float[] uv = new float[12];
    private final double[] px = new double[12], pz = new double[12], pw = new double[12];
    private final double[] ang = new double[12];
    private final int[] order = new int[12];

    private SliceFrame frame;
    private double nX, nZ, nW, depth;
    private double offX, offZ;

    /**
     * @param frame the viewer's slice frame
     * @param depth hyperplane offset along the normal (n &middot; p = depth)
     * @param offX  subtracted from render-space x' (lets callers emit section-relative coordinates)
     * @param offZ  subtracted from render-space z'
     */
    public void setPlane(SliceFrame frame, double depth, double offX, double offZ) {
        this.frame = frame;
        this.nX = frame.normalX();
        this.nZ = frame.normalZ();
        this.nW = frame.normalW();
        this.depth = depth;
        this.offX = offX;
        this.offZ = offZ;
    }

    public double depth() { return depth; }

    /** Quick test whether a box straddles the hyperplane. */
    public boolean intersects(double x0, double z0, double w0, double x1, double z1, double w1) {
        double cx = (x0 + x1) * 0.5, cz = (z0 + z1) * 0.5, cw = (w0 + w1) * 0.5;
        double h = 0.5 * (Math.abs(nX) * (x1 - x0) + Math.abs(nZ) * (z1 - z0) + Math.abs(nW) * (w1 - w0));
        double s = nX * cx + nZ * cz + nW * cw - depth;
        return s > -h && s < h;
    }

    /**
     * Slices one hyperbox and emits its faces.
     *
     * @param faceMask bit i set means emit the face for Direction4.VALUES[i]
     * @param ox       block origin used as the texture reference (u/v are measured from the block corner)
     * @param oy       block origin y
     * @param oz       block origin z
     * @param ow       block origin w
     */
    public void slice(double x0, double y0, double z0, double w0, double x1, double y1, double z1, double w1,
                      int faceMask, int ox, int oy, int oz, int ow, FaceSink sink) {
        if (!intersects(x0, z0, w0, x1, z1, w1)) return;
        // Vertical cells: X (axis 0), Z (axis 2), W (axis 3).
        if ((faceMask & 0b11) != 0) {
            if ((faceMask & 1) != 0) wall(Direction4.WEST, x0, z0, z1, w0, w1, y0, y1, ox, oy, oz, ow, sink);
            if ((faceMask & 2) != 0) wall(Direction4.EAST, x1, z0, z1, w0, w1, y0, y1, ox, oy, oz, ow, sink);
        }
        if ((faceMask & 0b110000) != 0) {
            if ((faceMask & 16) != 0) wall(Direction4.NORTH, z0, x0, x1, w0, w1, y0, y1, ox, oy, oz, ow, sink);
            if ((faceMask & 32) != 0) wall(Direction4.SOUTH, z1, x0, x1, w0, w1, y0, y1, ox, oy, oz, ow, sink);
        }
        if ((faceMask & 0b11000000) != 0) {
            if ((faceMask & 64) != 0) wall(Direction4.KATA, w0, x0, x1, z0, z1, y0, y1, ox, oy, oz, ow, sink);
            if ((faceMask & 128) != 0) wall(Direction4.ANA, w1, x0, x1, z0, z1, y0, y1, ox, oy, oz, ow, sink);
        }
        if ((faceMask & 0b1100) != 0) {
            int n = capPolygon(x0, z0, w0, x1, z1, w1);
            if (n >= 3) {
                if ((faceMask & 4) != 0) emitCap(Direction4.DOWN, n, y0, ox, oz, ow, sink);
                if ((faceMask & 8) != 0) emitCap(Direction4.UP, n, y1, ox, oz, ow, sink);
            }
        }
    }

    /**
     * A cell normal to horizontal axis k at coordinate c. (a0..a1, b0..b1) are the remaining two horizontal axes,
     * always in (x, z, w) order with k removed.
     */
    private void wall(Direction4 dir, double c, double a0, double a1, double b0, double b1, double y0, double y1,
                      int ox, int oy, int oz, int ow, FaceSink sink) {
        double nk, na, nb;
        int ka = dir.axis; // 0, 2 or 3
        switch (ka) {
            case 0 -> { nk = nX; na = nZ; nb = nW; }
            case 2 -> { nk = nZ; na = nX; nb = nW; }
            default -> { nk = nW; na = nX; nb = nZ; }
        }
        // Line inside the (a,b) rectangle: na*a + nb*b = r
        double r = depth - nk * c;
        int found = 0;
        if (Math.abs(na) > EPS) {
            double a = (r - nb * b0) / na;
            if (a >= a0 - EPS && a <= a1 + EPS) found = addSeg(found, clamp(a, a0, a1), b0);
            a = (r - nb * b1) / na;
            if (a >= a0 - EPS && a <= a1 + EPS) found = addSeg(found, clamp(a, a0, a1), b1);
        }
        if (Math.abs(nb) > EPS) {
            double b = (r - na * a0) / nb;
            if (b >= b0 - EPS && b <= b1 + EPS) found = addSeg(found, a0, clamp(b, b0, b1));
            b = (r - na * a1) / nb;
            if (b >= b0 - EPS && b <= b1 + EPS) found = addSeg(found, a1, clamp(b, b0, b1));
        }
        if (found < 2) return;
        // pick the two most distant points (handles corner duplicates)
        int bi = 0, bj = 1;
        double best = -1;
        for (int i = 0; i < found; i++) for (int j = i + 1; j < found; j++) {
            double d = Math.abs(segA[i] - segA[j]) + Math.abs(segB[i] - segB[j]);
            if (d > best) { best = d; bi = i; bj = j; }
        }
        if (best < 1.0E-6) return;
        double sa0 = segA[bi], sb0 = segB[bi], sa1 = segA[bj], sb1 = segB[bj];

        double x0, z0, w0, x1, z1, w1;
        switch (ka) {
            case 0 -> { x0 = c; z0 = sa0; w0 = sb0; x1 = c; z1 = sa1; w1 = sb1; }
            case 2 -> { z0 = c; x0 = sa0; w0 = sb0; z1 = c; x1 = sa1; w1 = sb1; }
            default -> { w0 = c; x0 = sa0; z0 = sb0; w1 = c; x1 = sa1; z1 = sb1; }
        }
        double rx0 = frame.localX(x0, z0, w0) - offX, rz0 = frame.localZ(x0, z0, w0) - offZ;
        double rx1 = frame.localX(x1, z1, w1) - offX, rz1 = frame.localZ(x1, z1, w1) - offZ;

        // Outward normal: projection of the cell normal (sign * e_k) onto the slice.
        int row = ka == 0 ? 0 : ka == 2 ? 1 : 2;
        double nrx = frame.get(row, 0) * dir.sign, nrz = frame.get(row, 1) * dir.sign;
        double nl = Math.sqrt(nrx * nrx + nrz * nrz);
        if (nl < 1.0E-6) return;
        nrx /= nl;
        nrz /= nl;
        // Quad A(P,y0) B(Q,y0) C(Q,y1) D(P,y1) has normal (Q-P) x up = (-dz, 0, dx); flip to match outward.
        double dx = rx1 - rx0, dz = rz1 - rz0;
        if (-dz * nrx + dx * nrz < 0) {
            double t;
            t = rx0; rx0 = rx1; rx1 = t;
            t = rz0; rz0 = rz1; rz1 = t;
            t = sa0; sa0 = sa1; sa1 = t;
            t = sb0; sb0 = sb1; sb1 = t;
        }
        double oa, ob;
        switch (ka) {
            case 0 -> { oa = oz; ob = ow; }
            case 2 -> { oa = ox; ob = ow; }
            default -> { oa = ox; ob = oz; }
        }
        boolean useA = Math.abs(sa1 - sa0) >= Math.abs(sb1 - sb0);
        double u0 = useA ? sa0 - oa : sb0 - ob;
        double u1 = useA ? sa1 - oa : sb1 - ob;
        double v0 = 1.0 - (y0 - oy), v1 = 1.0 - (y1 - oy);

        float[] p = pos;
        p[0] = (float) rx0; p[1] = (float) y0; p[2] = (float) rz0;
        p[3] = (float) rx1; p[4] = (float) y0; p[5] = (float) rz1;
        p[6] = (float) rx1; p[7] = (float) y1; p[8] = (float) rz1;
        p[9] = (float) rx0; p[10] = (float) y1; p[11] = (float) rz0;
        float[] t = uv;
        t[0] = (float) u0; t[1] = (float) v0;
        t[2] = (float) u1; t[3] = (float) v0;
        t[4] = (float) u1; t[5] = (float) v1;
        t[6] = (float) u0; t[7] = (float) v1;
        sink.face(dir, 4, p, t, (float) nrx, 0f, (float) nrz);
    }

    private final double[] segA = new double[4], segB = new double[4];

    private int addSeg(int n, double a, double b) {
        for (int i = 0; i < n; i++) if (Math.abs(segA[i] - a) + Math.abs(segB[i] - b) < 1.0E-7) return n;
        segA[n] = a;
        segB[n] = b;
        return n + 1;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Builds the convex cross-section polygon of the horizontal 3-box (x,z,w) with the plane. */
    private int capPolygon(double x0, double z0, double w0, double x1, double z1, double w1) {
        int n = 0;
        double[] xs = {x0, x1}, zs = {z0, z1}, ws = {w0, w1};
        // Edges along x
        if (Math.abs(nX) > EPS) {
            for (int j = 0; j < 2; j++) for (int k = 0; k < 2; k++) {
                double x = (depth - nZ * zs[j] - nW * ws[k]) / nX;
                if (x >= x0 - EPS && x <= x1 + EPS) n = addPoint(n, clamp(x, x0, x1), zs[j], ws[k]);
            }
        }
        if (Math.abs(nZ) > EPS) {
            for (int i = 0; i < 2; i++) for (int k = 0; k < 2; k++) {
                double z = (depth - nX * xs[i] - nW * ws[k]) / nZ;
                if (z >= z0 - EPS && z <= z1 + EPS) n = addPoint(n, xs[i], clamp(z, z0, z1), ws[k]);
            }
        }
        if (Math.abs(nW) > EPS) {
            for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
                double w = (depth - nX * xs[i] - nZ * zs[j]) / nW;
                if (w >= w0 - EPS && w <= w1 + EPS) n = addPoint(n, xs[i], zs[j], clamp(w, w0, w1));
            }
        }
        return n;
    }

    private int addPoint(int n, double x, double z, double w) {
        for (int i = 0; i < n; i++) {
            if (Math.abs(px[i] - x) + Math.abs(pz[i] - z) + Math.abs(pw[i] - w) < 1.0E-7) return n;
        }
        if (n >= 12) return n;
        px[n] = x;
        pz[n] = z;
        pw[n] = w;
        return n + 1;
    }

    private void emitCap(Direction4 dir, int n, double y, int ox, int oz, int ow, FaceSink sink) {
        double cx = 0, cz = 0;
        for (int i = 0; i < n; i++) {
            double lx = frame.localX(px[i], pz[i], pw[i]) - offX;
            double lz = frame.localZ(px[i], pz[i], pw[i]) - offZ;
            tmpX[i] = lx;
            tmpZ[i] = lz;
            order[i] = i;
            cx += lx;
            cz += lz;
        }
        cx /= n;
        cz /= n;
        for (int i = 0; i < n; i++) ang[i] = Math.atan2(tmpZ[i] - cz, tmpX[i] - cx);
        for (int i = 1; i < n; i++) {
            int v = order[i];
            int j = i - 1;
            while (j >= 0 && ang[order[j]] > ang[v]) { order[j + 1] = order[j]; j--; }
            order[j + 1] = v;
        }
        if (n > 6) n = 6; // a plane cuts a 3-box in at most a hexagon
        // UVs: project onto the two horizontal axes other than the one with the largest normal component.
        double ax = Math.abs(nX), az = Math.abs(nZ), aw = Math.abs(nW);
        int drop = (ax >= az && ax >= aw) ? 0 : (az >= aw ? 2 : 3);
        float[] p = pos;
        for (int k = 0; k < n; k++) {
            int i = order[k];
            p[k * 3] = (float) tmpX[i];
            p[k * 3 + 1] = (float) y;
            p[k * 3 + 2] = (float) tmpZ[i];
            double u, v;
            switch (drop) {
                case 0 -> { u = pz[i] - oz; v = pw[i] - ow; }
                case 2 -> { u = px[i] - ox; v = pw[i] - ow; }
                default -> { u = px[i] - ox; v = pz[i] - oz; }
            }
            uv[k * 2] = (float) u;
            uv[k * 2 + 1] = (float) v;
        }
        // Orientation: y component of (v1-v0) x (v2-v1) must match the wanted normal.
        double e1x = p[3] - p[0], e1z = p[5] - p[2];
        double e2x = p[6] - p[3], e2z = p[8] - p[5];
        double crossY = e1z * e2x - e1x * e2z;
        double want = dir == Direction4.UP ? 1 : -1;
        if (crossY * want < 0) reverse(n);
        sink.face(dir, n, p, uv, 0f, (float) want, 0f);
    }

    private final double[] tmpX = new double[12], tmpZ = new double[12];

    private void reverse(int n) {
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            for (int c = 0; c < 3; c++) {
                float t = pos[i * 3 + c];
                pos[i * 3 + c] = pos[j * 3 + c];
                pos[j * 3 + c] = t;
            }
            for (int c = 0; c < 2; c++) {
                float t = uv[i * 2 + c];
                uv[i * 2 + c] = uv[j * 2 + c];
                uv[j * 2 + c] = t;
            }
        }
    }
}
