package dev.tetraform.client;

import dev.tetraform.math.Direction4;
import dev.tetraform.world.BlockShapes4;
import dev.tetraform.world.Column4;
import dev.tetraform.world.Section4;
import net.minecraft.world.level.block.state.BlockState;

/** Computes which of a voxel's eight tesseract faces can be seen (are not hidden by a neighbour). */
public final class Exposure4 {
    private Exposure4() {}

    private static final byte[] EMPTY = new byte[0];

    /** Returns a mask per voxel of the section, or an empty array for an all-air section. */
    public static byte[] compute(ClientLevel4 level, Column4 col, int sy) {
        Section4 sec = col.section(sy);
        if (sec == null || sec.isEmpty()) return EMPTY;
        byte[] out = new byte[Section4.VOLUME];
        int bx = col.cx << 4, bz = col.cz << 4, bw = col.cw << 4;
        for (int i = 0; i < Section4.VOLUME; i++) {
            BlockState s = sec.get(i);
            if (s.isAir()) continue;
            int lx = i & 15, lz = (i >> 4) & 15, ly = (i >> 8) & 15, lw = (i >> 12) & 15;
            out[i] = (byte) mask(level, col, bx + lx, sy * 16 + ly, bz + lz, bw + lw, s);
        }
        return out;
    }

    public static int mask(ClientLevel4 level, Column4 col, int x, int y, int z, int w, BlockState s) {
        double[] box = BlockShapes4.render(s);
        if (box == null) return 0;
        boolean fullOpaque = BlockShapes4.fullOpaque(s);
        boolean fluid = !s.getFluidState().isEmpty();
        int lx = x & 15, lz = z & 15, lw = w & 15;
        int mask = 0;
        for (Direction4 d : Direction4.VALUES) {
            boolean onBoundary = d.sign > 0 ? box[4 + d.axis] >= 1 : box[d.axis] <= 0;
            if (onBoundary || fluid) {
                BlockState ns;
                int nlx = lx + d.dx, nlz = lz + d.dz, nlw = lw + d.dw, ny = y + d.dy;
                if (nlx >= 0 && nlx < 16 && nlz >= 0 && nlz < 16 && nlw >= 0 && nlw < 16) ns = col.get(nlx, ny, nlz, nlw);
                else ns = level.getBlock(x + d.dx, ny, z + d.dz, w + d.dw);
                if (onBoundary && BlockShapes4.fullOpaque(ns)) continue;
                // fluid faces against the same fluid are hidden (the top only if fluid continues above)
                if (fluid && ns.getBlock() == s.getBlock()) continue;
                if (!fullOpaque && ns == s && isFullBox(box)) continue; // glass next to glass, leaves next to leaves
            }
            mask |= 1 << d.ordinal();
        }
        return mask;
    }

    private static boolean isFullBox(double[] b) {
        return b[0] <= 0 && b[1] <= 0 && b[2] <= 0 && b[3] <= 0 && b[4] >= 1 && b[5] >= 1 && b[6] >= 1 && b[7] >= 1;
    }
}
