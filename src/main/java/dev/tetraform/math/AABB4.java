package dev.tetraform.math;

/** Axis-aligned 4D hyperbox. */
public record AABB4(double minX, double minY, double minZ, double minW, double maxX, double maxY, double maxZ, double maxW) {

    public static AABB4 ofCenterBottom(Vec4 p, double halfWidth, double height) {
        return new AABB4(p.x() - halfWidth, p.y(), p.z() - halfWidth, p.w() - halfWidth,
                p.x() + halfWidth, p.y() + height, p.z() + halfWidth, p.w() + halfWidth);
    }

    public static AABB4 unitBlock(BlockPos4 p) {
        return new AABB4(p.x(), p.y(), p.z(), p.w(), p.x() + 1, p.y() + 1, p.z() + 1, p.w() + 1);
    }

    public double min(int axis) {
        return switch (axis) { case 0 -> minX; case 1 -> minY; case 2 -> minZ; default -> minW; };
    }

    public double max(int axis) {
        return switch (axis) { case 0 -> maxX; case 1 -> maxY; case 2 -> maxZ; default -> maxW; };
    }

    public AABB4 move(double dx, double dy, double dz, double dw) {
        return new AABB4(minX + dx, minY + dy, minZ + dz, minW + dw, maxX + dx, maxY + dy, maxZ + dz, maxW + dw);
    }

    public AABB4 move(int axis, double d) {
        return switch (axis) {
            case 0 -> move(d, 0, 0, 0);
            case 1 -> move(0, d, 0, 0);
            case 2 -> move(0, 0, d, 0);
            default -> move(0, 0, 0, d);
        };
    }

    public AABB4 inflate(double r) {
        return new AABB4(minX - r, minY - r, minZ - r, minW - r, maxX + r, maxY + r, maxZ + r, maxW + r);
    }

    public AABB4 expandTowards(double dx, double dy, double dz, double dw) {
        return new AABB4(
                dx < 0 ? minX + dx : minX, dy < 0 ? minY + dy : minY, dz < 0 ? minZ + dz : minZ, dw < 0 ? minW + dw : minW,
                dx > 0 ? maxX + dx : maxX, dy > 0 ? maxY + dy : maxY, dz > 0 ? maxZ + dz : maxZ, dw > 0 ? maxW + dw : maxW);
    }

    public boolean intersects(AABB4 o) {
        return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY
                && minZ < o.maxZ && maxZ > o.minZ && minW < o.maxW && maxW > o.minW;
    }

    public boolean contains(Vec4 p) {
        return p.x() >= minX && p.x() < maxX && p.y() >= minY && p.y() < maxY
                && p.z() >= minZ && p.z() < maxZ && p.w() >= minW && p.w() < maxW;
    }

    public Vec4 center() {
        return new Vec4((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5, (minW + maxW) * 0.5);
    }

    /**
     * Swept collision along one axis: how far can {@code this} move by {@code delta} along {@code axis} before it
     * touches {@code obstacle}? Returns the clipped delta.
     */
    public double clipAxis(AABB4 obstacle, int axis, double delta) {
        for (int a = 0; a < 4; a++) {
            if (a == axis) continue;
            if (max(a) <= obstacle.min(a) + 1.0E-7 || min(a) >= obstacle.max(a) - 1.0E-7) return delta;
        }
        if (delta > 0 && max(axis) <= obstacle.min(axis) + 1.0E-7) {
            double gap = obstacle.min(axis) - max(axis);
            if (gap < delta) delta = Math.max(0, gap);
        } else if (delta < 0 && min(axis) >= obstacle.max(axis) - 1.0E-7) {
            double gap = obstacle.max(axis) - min(axis);
            if (gap > delta) delta = Math.min(0, gap);
        }
        return delta;
    }
}
