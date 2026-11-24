package dev.tetraform.math;

/**
 * Immutable 4D vector (x, y, z, w). Y is "up", exactly as in vanilla; W is the fourth spatial axis.
 */
public record Vec4(double x, double y, double z, double w) {
    public static final Vec4 ZERO = new Vec4(0, 0, 0, 0);
    public static final Vec4 UNIT_X = new Vec4(1, 0, 0, 0);
    public static final Vec4 UNIT_Y = new Vec4(0, 1, 0, 0);
    public static final Vec4 UNIT_Z = new Vec4(0, 0, 1, 0);
    public static final Vec4 UNIT_W = new Vec4(0, 0, 0, 1);

    public Vec4 add(Vec4 o) { return new Vec4(x + o.x, y + o.y, z + o.z, w + o.w); }
    public Vec4 add(double dx, double dy, double dz, double dw) { return new Vec4(x + dx, y + dy, z + dz, w + dw); }
    public Vec4 sub(Vec4 o) { return new Vec4(x - o.x, y - o.y, z - o.z, w - o.w); }
    public Vec4 scale(double s) { return new Vec4(x * s, y * s, z * s, w * s); }
    public Vec4 multiply(double sx, double sy, double sz, double sw) { return new Vec4(x * sx, y * sy, z * sz, w * sw); }
    public double dot(Vec4 o) { return x * o.x + y * o.y + z * o.z + w * o.w; }
    public double lengthSqr() { return dot(this); }
    public double length() { return Math.sqrt(lengthSqr()); }
    public double horizontalLengthSqr() { return x * x + z * z + w * w; }
    public double distanceSqr(Vec4 o) { return sub(o).lengthSqr(); }
    public double distance(Vec4 o) { return Math.sqrt(distanceSqr(o)); }

    public Vec4 normalize() {
        double l = length();
        return l < 1.0E-12 ? ZERO : scale(1.0 / l);
    }

    public Vec4 withY(double ny) { return new Vec4(x, ny, z, w); }

    public double get(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            case 3 -> w;
            default -> throw new IndexOutOfBoundsException(axis);
        };
    }

    public Vec4 with(int axis, double v) {
        return switch (axis) {
            case 0 -> new Vec4(v, y, z, w);
            case 1 -> new Vec4(x, v, z, w);
            case 2 -> new Vec4(x, y, v, w);
            case 3 -> new Vec4(x, y, z, v);
            default -> throw new IndexOutOfBoundsException(axis);
        };
    }

    public BlockPos4 toBlockPos() {
        return new BlockPos4((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), (int) Math.floor(w));
    }

    public static Vec4 lerp(Vec4 a, Vec4 b, double t) {
        return new Vec4(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f, %.2f)", x, y, z, w);
    }
}
