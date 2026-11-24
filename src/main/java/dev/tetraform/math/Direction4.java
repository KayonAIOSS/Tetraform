package dev.tetraform.math;

/**
 * The eight face directions of a tesseract: every block has eight neighbours (two per axis), and redstone,
 * fluids, light and face culling all operate on these eight directions.
 */
public enum Direction4 {
    WEST(0, -1), EAST(0, 1),
    DOWN(1, -1), UP(1, 1),
    NORTH(2, -1), SOUTH(2, 1),
    KATA(3, -1), ANA(3, 1);

    public static final Direction4[] VALUES = values();
    /** Directions that are not vertical (6 of them in 4D). */
    public static final Direction4[] HORIZONTAL = {WEST, EAST, NORTH, SOUTH, KATA, ANA};

    public final int axis;
    public final int sign;
    public final int dx, dy, dz, dw;

    Direction4(int axis, int sign) {
        this.axis = axis;
        this.sign = sign;
        this.dx = axis == 0 ? sign : 0;
        this.dy = axis == 1 ? sign : 0;
        this.dz = axis == 2 ? sign : 0;
        this.dw = axis == 3 ? sign : 0;
    }

    public Direction4 opposite() {
        return VALUES[ordinal() ^ 1];
    }

    public static Direction4 of(int axis, int sign) {
        return VALUES[axis * 2 + (sign > 0 ? 1 : 0)];
    }

    public Vec4 normal() {
        return new Vec4(dx, dy, dz, dw);
    }
}
