package dev.tetraform.math;

/**
 * Integer 4D block position. A "block" in Tetraform is a unit tesseract [x,x+1]x[y,y+1]x[z,z+1]x[w,w+1].
 */
public record BlockPos4(int x, int y, int z, int w) {
    public static final BlockPos4 ZERO = new BlockPos4(0, 0, 0, 0);

    public BlockPos4 offset(int dx, int dy, int dz, int dw) {
        return new BlockPos4(x + dx, y + dy, z + dz, w + dw);
    }

    public BlockPos4 relative(Direction4 d) {
        return new BlockPos4(x + d.dx, y + d.dy, z + d.dz, w + d.dw);
    }

    public BlockPos4 relative(Direction4 d, int n) {
        return new BlockPos4(x + d.dx * n, y + d.dy * n, z + d.dz * n, w + d.dw * n);
    }

    public BlockPos4 above() { return new BlockPos4(x, y + 1, z, w); }
    public BlockPos4 below() { return new BlockPos4(x, y - 1, z, w); }

    public Vec4 center() { return new Vec4(x + 0.5, y + 0.5, z + 0.5, w + 0.5); }
    public Vec4 corner() { return new Vec4(x, y, z, w); }

    public int get(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            case 3 -> w;
            default -> throw new IndexOutOfBoundsException(axis);
        };
    }

    public int manhattan(BlockPos4 o) {
        return Math.abs(x - o.x) + Math.abs(y - o.y) + Math.abs(z - o.z) + Math.abs(w - o.w);
    }

    public double distSqr(BlockPos4 o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z, dw = w - o.w;
        return dx * dx + dy * dy + dz * dz + dw * dw;
    }

    /** Packs into a long: 18 bits x, 10 bits y, 18 bits z, 18 bits w (signed). */
    public long asLong() {
        return ((long) (x & 0x3FFFF) << 46) | ((long) (y & 0x3FF) << 36) | ((long) (z & 0x3FFFF) << 18) | (long) (w & 0x3FFFF);
    }

    public static BlockPos4 of(long l) {
        int x = (int) (l >> 46) << 14 >> 14;
        int y = (int) ((l >> 36) & 0x3FF) << 22 >> 22;
        int z = (int) ((l >> 18) & 0x3FFFF) << 14 >> 14;
        int w = (int) (l & 0x3FFFF) << 14 >> 14;
        return new BlockPos4(x, y, z, w);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z + " " + w;
    }
}
