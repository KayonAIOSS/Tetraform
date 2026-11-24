package dev.tetraform.world;

import dev.tetraform.math.AABB4;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Vec4;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block storage for a whole 4D world, shared by the logical server and client implementations.
 */
public class Level4 {
    public static final BlockState AIR = Blocks.AIR.defaultBlockState();
    protected final ConcurrentHashMap<Long, Column4> columns = new ConcurrentHashMap<>();

    public Column4 getColumn(int cx, int cz, int cw) {
        return columns.get(Column4.key(cx, cz, cw));
    }

    public Column4 getColumnAt(int x, int z, int w) {
        return getColumn(x >> 4, z >> 4, w >> 4);
    }

    public boolean isLoaded(int x, int z, int w) {
        return getColumnAt(x, z, w) != null;
    }

    public java.util.Collection<Column4> columns() {
        return columns.values();
    }

    public BlockState getBlock(int x, int y, int z, int w) {
        Column4 c = getColumnAt(x, z, w);
        return c == null ? AIR : c.get(x & 15, y, z & 15, w & 15);
    }

    public BlockState getBlock(BlockPos4 p) {
        return getBlock(p.x(), p.y(), p.z(), p.w());
    }

    /** Raw set without updates. Returns the previous state. */
    public BlockState setBlockRaw(BlockPos4 p, BlockState state) {
        Column4 c = getColumnAt(p.x(), p.z(), p.w());
        if (c == null) return AIR;
        return c.set(p.x() & 15, p.y(), p.z() & 15, p.w() & 15, state);
    }

    public int heightAt(int x, int z, int w) {
        Column4 c = getColumnAt(x, z, w);
        return c == null ? 0 : c.height(x & 15, z & 15, w & 15);
    }

    /** Collects collision boxes of blocks that intersect {@code area}. */
    public List<AABB4> collisions(AABB4 area) {
        List<AABB4> out = new ArrayList<>();
        int x0 = (int) Math.floor(area.minX()), x1 = (int) Math.floor(area.maxX());
        int y0 = Math.max(0, (int) Math.floor(area.minY()) - 1), y1 = (int) Math.floor(area.maxY());
        int z0 = (int) Math.floor(area.minZ()), z1 = (int) Math.floor(area.maxZ());
        int w0 = (int) Math.floor(area.minW()), w1 = (int) Math.floor(area.maxW());
        for (int w = w0; w <= w1; w++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            Column4 c = getColumnAt(x, z, w);
            if (c == null) {
                // Unloaded terrain is solid so nothing falls out of the world while it streams in.
                out.add(new AABB4(x, -64, z, w, x + 1, Column4.HEIGHT + 64, z + 1, w + 1));
                continue;
            }
            for (int y = y0; y <= y1; y++) {
                BlockState s = c.get(x & 15, y, z & 15, w & 15);
                if (s.isAir()) continue;
                double[] b = BlockShapes4.collision(s);
                if (b == null) continue;
                AABB4 box = new AABB4(x + b[0], y + b[1], z + b[2], w + b[3], x + b[4], y + b[5], z + b[6], w + b[7]);
                if (box.intersects(area)) out.add(box);
            }
        }
        return out;
    }

    public boolean isFluidAt(Vec4 p) {
        BlockState s = getBlock(p.toBlockPos());
        return !s.getFluidState().isEmpty();
    }

    /** Result of a 4D voxel ray cast. */
    public record Hit(BlockPos4 pos, dev.tetraform.math.Direction4 face, Vec4 point) {}

    /** 4D DDA ray cast through the voxel grid. */
    public Hit raycast(Vec4 from, Vec4 dir, double maxDist, boolean hitFluids) {
        double[] p = {from.x(), from.y(), from.z(), from.w()};
        double[] d = {dir.x(), dir.y(), dir.z(), dir.w()};
        int[] cell = new int[4];
        int[] step = new int[4];
        double[] tMax = new double[4];
        double[] tDelta = new double[4];
        for (int i = 0; i < 4; i++) {
            cell[i] = (int) Math.floor(p[i]);
            if (Math.abs(d[i]) < 1.0E-12) {
                step[i] = 0;
                tMax[i] = Double.POSITIVE_INFINITY;
                tDelta[i] = Double.POSITIVE_INFINITY;
            } else {
                step[i] = d[i] > 0 ? 1 : -1;
                double next = d[i] > 0 ? cell[i] + 1 - p[i] : p[i] - cell[i];
                tDelta[i] = Math.abs(1.0 / d[i]);
                tMax[i] = next * tDelta[i];
            }
        }
        int lastAxis = -1;
        double t = 0;
        while (t <= maxDist) {
            BlockState s = getBlock(cell[0], cell[1], cell[2], cell[3]);
            if (!s.isAir() && (BlockShapes4.selectable(s) || (hitFluids && !s.getFluidState().isEmpty()))) {
                dev.tetraform.math.Direction4 face = lastAxis < 0 ? dev.tetraform.math.Direction4.UP
                        : dev.tetraform.math.Direction4.of(lastAxis, -step[lastAxis]);
                return new Hit(new BlockPos4(cell[0], cell[1], cell[2], cell[3]), face,
                        new Vec4(p[0] + d[0] * t, p[1] + d[1] * t, p[2] + d[2] * t, p[3] + d[3] * t));
            }
            int a = 0;
            for (int i = 1; i < 4; i++) if (tMax[i] < tMax[a]) a = i;
            t = tMax[a];
            tMax[a] += tDelta[a];
            cell[a] += step[a];
            lastAxis = a;
        }
        return null;
    }
}
