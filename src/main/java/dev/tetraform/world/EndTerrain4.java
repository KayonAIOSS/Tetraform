package dev.tetraform.world;

import dev.tetraform.math.Noise4;
import dev.tetraform.math.Vec4;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The 4D End: the main island is a 4D lens of end stone around the origin, with vanilla's ten obsidian spikes (same
 * positions, heights and cages as vanilla for this seed) standing as 4D pillars around W = 0 and an end crystal on
 * each. The dragon fight runs in the W = 0 layer. Past a void gap, outer islands float everywhere beyond 1000 blocks
 * (in 4D distance) with chorus plants, and end cities (see {@link Structures4}).
 */
final class EndTerrain4 {
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();
    private static final BlockState OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();
    static final int MAIN_RADIUS = 88;
    static final int OUTER_START = 1000;

    /** A vanilla spike: centre (x, z) at W = 0, radius, height, iron bar cage. */
    record Spike(int x, int z, int radius, int height, boolean guarded) {}

    private final long seed;
    private final Noise4 islands, detail;
    private final List<Spike> spikes;

    EndTerrain4(long seed, List<Spike> spikes) {
        this.seed = seed;
        this.islands = new Noise4(seed * 53 + 11);
        this.detail = new Noise4(seed * 59 + 13);
        this.spikes = spikes;
    }

    private long hash(int x, int y, int z, int w) {
        long h = seed ^ 0x454E44L;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 17) ^ y * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 23) ^ z * 0x165667B19E3779F9L;
        h = Long.rotateLeft(h, 29) ^ w * 0x27D4EB2F165667C5L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return h;
    }

    private double rand(int x, int y, int z, int w) {
        return (hash(x, y, z, w) >>> 11) * 0x1.0p-53;
    }

    /** Island top and bottom at (x, z, w), or null over the void. */
    private int[] island(int x, int z, int w) {
        double r = Math.sqrt((double) x * x + (double) z * z + (double) w * w);
        if (r < MAIN_RADIUS) {
            double f = 1 - (r / MAIN_RADIUS) * (r / MAIN_RADIUS);
            if (f < 0.015) return null;
            int top = (int) Math.round(56 + 7 * Math.sqrt(f) + detail.sample3(x / 20.0, z / 20.0, w / 20.0) * 1.5);
            int bottom = (int) Math.round(top - 3 - 52 * Math.pow(f, 1.4) - detail.sample3(x / 13.0 + 50, z / 13.0, w / 13.0) * 4);
            return new int[]{Math.max(1, bottom), top};
        }
        if (r < OUTER_START) return null;
        double n = islands.fbm3(x / 85.0, z / 85.0, w / 85.0, 3) - Math.max(0, (OUTER_START + 120 - r) / 120.0) * 0.5;
        double t = 0.22;
        if (n <= t) return null;
        double m = n - t;
        int top = (int) Math.round(54 + m * 34 + detail.sample3(x / 16.0, z / 16.0, w / 16.0) * 2);
        int bottom = (int) Math.round(top - 2 - m * 110);
        return new int[]{Math.max(1, bottom), Math.min(120, top)};
    }

    int surfaceHeight(int x, int z, int w) {
        int[] i = island(x, z, w);
        return i == null ? 0 : i[1];
    }

    Column4 generate(int cx, int cz, int cw) {
        Column4 col = new Column4(cx, cz, cw);
        int bx = cx << 4, bz = cz << 4, bw = cw << 4;
        // quick reject: chunks wholly inside the void ring
        double nearX = Math.max(Math.abs(bx + 8) - 12, 0), nearZ = Math.max(Math.abs(bz + 8) - 12, 0), nearW = Math.max(Math.abs(bw + 8) - 12, 0);
        double rNear = Math.sqrt(nearX * nearX + nearZ * nearZ + nearW * nearW);
        double fx = Math.abs(bx + 8) + 12, fz = Math.abs(bz + 8) + 12, fw = Math.abs(bw + 8) + 12;
        double rFar = Math.sqrt(fx * fx + fz * fz + fw * fw);
        boolean voidRing = rNear > MAIN_RADIUS + 2 && rFar < OUTER_START - 2;
        if (!voidRing) {
            for (int lw = 0; lw < 16; lw++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int x = bx + lx, z = bz + lz, w = bw + lw;
                int[] i = island(x, z, w);
                if (i == null) continue;
                for (int y = i[0]; y <= i[1]; y++) col.setRaw(lx, y, lz, lw, END_STONE);
                // chorus plants on the outer islands
                double r = rand(x, i[1], z, w);
                if (Math.abs(x) + Math.abs(z) + Math.abs(w) > OUTER_START && r < 0.004) chorus(col, lx, i[1] + 1, lz, lw, x, z, w);
            }
        }
        for (Spike s : spikes) spike(col, s);
        col.recomputeHeightmap();
        col.setDirty(true);
        return col;
    }

    private void chorus(Column4 col, int lx, int y, int lz, int lw, int x, int z, int w) {
        int h = 2 + (int) (rand(x, y + 1, z, w) * 5);
        if (y + h >= 250) return;
        for (int i = 0; i < h; i++) {
            BlockState s = Blocks.CHORUS_PLANT.defaultBlockState().setValue(PipeBlock.DOWN, true).setValue(PipeBlock.UP, true);
            if (i == 0) s = s.setValue(PipeBlock.DOWN, true);
            col.setRaw(lx, y + i, lz, lw, s);
        }
        col.setRaw(lx, y + h, lz, lw, Blocks.CHORUS_FLOWER.defaultBlockState());
    }

    private void spike(Column4 col, Spike s) {
        int bx = col.cx << 4, bz = col.cz << 4, bw = col.cw << 4;
        int r = s.radius + 3;
        if (s.x + r < bx || s.x - r > bx + 15 || s.z + r < bz || s.z - r > bz + 15 || r < bw || -r > bw + 15) return;
        int rr = s.radius * s.radius + 1;
        for (int lw = 0; lw < 16; lw++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int dx = bx + lx - s.x, dz = bz + lz - s.z, dw = bw + lw;
            if (dx * dx + dz * dz + dw * dw <= rr) {
                for (int y = 1; y < s.height; y++) col.setRaw(lx, y, lz, lw, OBSIDIAN);
            }
            if (s.guarded && Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && Math.abs(dw) <= 2) {
                for (int dy = 0; dy <= 3; dy++) {
                    boolean xs = Math.abs(dx) == 2, zs = Math.abs(dz) == 2, top = dy == 3 || Math.abs(dw) == 2;
                    if (!(xs || zs || top)) continue;
                    boolean xEdge = dx == -2 || dx == 2 || top, zEdge = dz == -2 || dz == 2 || top;
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState()
                            .setValue(IronBarsBlock.NORTH, xEdge && dz != -2).setValue(IronBarsBlock.SOUTH, xEdge && dz != 2)
                            .setValue(IronBarsBlock.WEST, zEdge && dx != -2).setValue(IronBarsBlock.EAST, zEdge && dx != 2);
                    col.setRaw(lx, s.height + dy, lz, lw, bars);
                }
            }
        }
        // the crystal sits on bedrock (with fire) at the top, in the W = 0 layer
        int lx = s.x - bx, lz = s.z - bz, lw = -bw;
        if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16 && lw >= 0 && lw < 16) {
            col.setRaw(lx, s.height, lz, lw, Blocks.BEDROCK.defaultBlockState());
            col.setRaw(lx, s.height + 1, lz, lw, Blocks.FIRE.defaultBlockState());
            CompoundTag t = new CompoundTag();
            t.putString("id", "minecraft:end_crystal");
            t.putBoolean("ShowBottom", true);
            if (col.pendingEntities == null) col.pendingEntities = new ArrayList<>();
            col.pendingEntities.add(new Structures4.PendingEntity(new Vec4(s.x + 0.5, s.height + 1, s.z + 0.5, ServerWorld4.END_FIGHT_W), t));
        }
    }
}
