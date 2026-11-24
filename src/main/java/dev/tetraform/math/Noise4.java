package dev.tetraform.math;

/**
 * Seeded 4D simplex noise (after Stefan Gustavson's public-domain reference), used for 4D terrain.
 */
public final class Noise4 {
    private static final int[][] GRAD4 = {
            {0, 1, 1, 1}, {0, 1, 1, -1}, {0, 1, -1, 1}, {0, 1, -1, -1},
            {0, -1, 1, 1}, {0, -1, 1, -1}, {0, -1, -1, 1}, {0, -1, -1, -1},
            {1, 0, 1, 1}, {1, 0, 1, -1}, {1, 0, -1, 1}, {1, 0, -1, -1},
            {-1, 0, 1, 1}, {-1, 0, 1, -1}, {-1, 0, -1, 1}, {-1, 0, -1, -1},
            {1, 1, 0, 1}, {1, 1, 0, -1}, {1, -1, 0, 1}, {1, -1, 0, -1},
            {-1, 1, 0, 1}, {-1, 1, 0, -1}, {-1, -1, 0, 1}, {-1, -1, 0, -1},
            {1, 1, 1, 0}, {1, 1, -1, 0}, {1, -1, 1, 0}, {1, -1, -1, 0},
            {-1, 1, 1, 0}, {-1, 1, -1, 0}, {-1, -1, 1, 0}, {-1, -1, -1, 0}};
    private static final double F4 = (Math.sqrt(5.0) - 1.0) / 4.0;
    private static final double G4 = (5.0 - Math.sqrt(5.0)) / 20.0;

    private final short[] perm = new short[512];

    public Noise4(long seed) {
        short[] p = new short[256];
        for (short i = 0; i < 256; i++) p[i] = i;
        long s = seed;
        for (int i = 255; i >= 0; i--) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int r = (int) ((s + 31) % (i + 1));
            if (r < 0) r += i + 1;
            short t = p[i];
            p[i] = p[r];
            p[r] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    private static double dot(int[] g, double x, double y, double z, double w) {
        return g[0] * x + g[1] * y + g[2] * z + g[3] * w;
    }

    public double sample(double x, double y, double z, double w) {
        double s = (x + y + z + w) * F4;
        int i = fastFloor(x + s), j = fastFloor(y + s), k = fastFloor(z + s), l = fastFloor(w + s);
        double t = (i + j + k + l) * G4;
        double x0 = x - (i - t), y0 = y - (j - t), z0 = z - (k - t), w0 = w - (l - t);
        int rankx = 0, ranky = 0, rankz = 0, rankw = 0;
        if (x0 > y0) rankx++; else ranky++;
        if (x0 > z0) rankx++; else rankz++;
        if (x0 > w0) rankx++; else rankw++;
        if (y0 > z0) ranky++; else rankz++;
        if (y0 > w0) ranky++; else rankw++;
        if (z0 > w0) rankz++; else rankw++;
        int i1 = rankx >= 3 ? 1 : 0, j1 = ranky >= 3 ? 1 : 0, k1 = rankz >= 3 ? 1 : 0, l1 = rankw >= 3 ? 1 : 0;
        int i2 = rankx >= 2 ? 1 : 0, j2 = ranky >= 2 ? 1 : 0, k2 = rankz >= 2 ? 1 : 0, l2 = rankw >= 2 ? 1 : 0;
        int i3 = rankx >= 1 ? 1 : 0, j3 = ranky >= 1 ? 1 : 0, k3 = rankz >= 1 ? 1 : 0, l3 = rankw >= 1 ? 1 : 0;
        double x1 = x0 - i1 + G4, y1 = y0 - j1 + G4, z1 = z0 - k1 + G4, w1 = w0 - l1 + G4;
        double x2 = x0 - i2 + 2 * G4, y2 = y0 - j2 + 2 * G4, z2 = z0 - k2 + 2 * G4, w2 = w0 - l2 + 2 * G4;
        double x3 = x0 - i3 + 3 * G4, y3 = y0 - j3 + 3 * G4, z3 = z0 - k3 + 3 * G4, w3 = w0 - l3 + 3 * G4;
        double x4 = x0 - 1 + 4 * G4, y4 = y0 - 1 + 4 * G4, z4 = z0 - 1 + 4 * G4, w4 = w0 - 1 + 4 * G4;
        int ii = i & 255, jj = j & 255, kk = k & 255, ll = l & 255;
        double n = 0;
        double t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0 - w0 * w0;
        if (t0 > 0) { t0 *= t0; n += t0 * t0 * dot(GRAD4[perm[ii + perm[jj + perm[kk + perm[ll]]]] & 31], x0, y0, z0, w0); }
        double t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1 - w1 * w1;
        if (t1 > 0) { t1 *= t1; n += t1 * t1 * dot(GRAD4[perm[ii + i1 + perm[jj + j1 + perm[kk + k1 + perm[ll + l1]]]] & 31], x1, y1, z1, w1); }
        double t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2 - w2 * w2;
        if (t2 > 0) { t2 *= t2; n += t2 * t2 * dot(GRAD4[perm[ii + i2 + perm[jj + j2 + perm[kk + k2 + perm[ll + l2]]]] & 31], x2, y2, z2, w2); }
        double t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3 - w3 * w3;
        if (t3 > 0) { t3 *= t3; n += t3 * t3 * dot(GRAD4[perm[ii + i3 + perm[jj + j3 + perm[kk + k3 + perm[ll + l3]]]] & 31], x3, y3, z3, w3); }
        double t4 = 0.6 - x4 * x4 - y4 * y4 - z4 * z4 - w4 * w4;
        if (t4 > 0) { t4 *= t4; n += t4 * t4 * dot(GRAD4[perm[ii + 1 + perm[jj + 1 + perm[kk + 1 + perm[ll + 1]]]] & 31], x4, y4, z4, w4); }
        return 27.0 * n;
    }

    /** 3D-in-4D sample used for heightmaps: (x, z, w) with a fixed fourth coordinate. */
    public double sample3(double x, double z, double w) {
        return sample(x, z, w, 17.31);
    }

    public double fbm3(double x, double z, double w, int octaves) {
        double sum = 0, amp = 1, freq = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * sample(x * freq, z * freq, w * freq, o * 31.7);
            norm += amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        return sum / norm;
    }
}
