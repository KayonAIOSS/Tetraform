package dev.tetraform.world;

import dev.tetraform.math.Vec4;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 4D nether fortresses: a nether brick hall with bridges running out of it along all six horizontal directions
 * (&plusmn;x, &plusmn;z and &plusmn;w), each ending in a room. Bridges are five blocks wide in both directions across
 * them, so they look like vanilla bridges from every slice. Every fortress has at least two blaze spawner rooms (blaze
 * rods for the eyes of ender), a nether wart room and loot chests with vanilla's {@code chests/nether_bridge} table,
 * and fortress mobs (blazes, wither skeletons) spawn inside it.
 */
public final class Fortress4 {
    private Fortress4() {}

    static final int CELL = 224;

    enum Kind { HALL, BRIDGE, BLAZE_ROOM, WART_ROOM, CHEST_ROOM, ROOM }

    /** An axis-aligned 4D box (inclusive) with its kind; {@code axis} is the bridge direction (0 = x, 1 = z, 2 = w). */
    record Part(Kind kind, int x0, int y0, int z0, int w0, int x1, int y1, int z1, int w1, int axis, int dir, int cx, int cz, int cw) {
        boolean contains(int x, int y, int z, int w) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1 && w >= w0 && w <= w1;
        }
    }

    record Plan(int cx, int cy, int cz, int cw, List<Part> parts, long seed) {}

    private static long hash(long seed, int a, int b, int c, long salt) {
        long h = seed ^ salt;
        h ^= a * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 21) ^ b * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 21) ^ c * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 29);
    }

    private static final java.util.Map<Long, Plan> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Plan NONE = new Plan(0, 0, 0, 0, List.of(), 0);

    static Plan plan(long seed, int gx, int gz, int gw) {
        long key = hash(seed, gx, gz, gw, 0xF0F0L);
        Plan p = CACHE.get(key);
        if (p != null) return p;
        if (CACHE.size() > 512) CACHE.clear();
        p = makePlan(seed, gx, gz, gw);
        CACHE.put(key, p);
        return p;
    }

    private static Plan makePlan(long seed, int gx, int gz, int gw) {
        RandomSource r = RandomSource.create(hash(seed, gx, gz, gw, 0xF04L));
        if (r.nextFloat() > 0.5f) return NONE;
        int cx = gx * CELL + 72 + r.nextInt(CELL - 144), cz = gz * CELL + 72 + r.nextInt(CELL - 144), cw = gw * CELL + 72 + r.nextInt(CELL - 144);
        int cy = 50 + r.nextInt(20);
        List<Part> parts = new ArrayList<>();
        int hh = 6; // hall half-size
        parts.add(new Part(Kind.HALL, cx - hh, cy - 1, cz - hh, cw - hh, cx + hh, cy + 7, cz + hh, cw + hh, -1, 0, cx, cz, cw));
        // six bridges; the first two end in blaze rooms, the third in the wart room
        Kind[] rooms = {Kind.BLAZE_ROOM, Kind.BLAZE_ROOM, Kind.WART_ROOM, Kind.CHEST_ROOM,
                r.nextBoolean() ? Kind.BLAZE_ROOM : Kind.CHEST_ROOM, r.nextBoolean() ? Kind.ROOM : Kind.WART_ROOM};
        int[] order = {0, 1, 2, 3, 4, 5};
        for (int i = 5; i > 0; i--) { int j = r.nextInt(i + 1); int t = order[i]; order[i] = order[j]; order[j] = t; }
        for (int b = 0; b < 6; b++) {
            int axis = order[b] >> 1, dir = (order[b] & 1) == 0 ? 1 : -1;
            int len = 18 + r.nextInt(26);
            int start = hh + 1, end = hh + len;
            int[] lo = {cx, cz, cw}, hi = {cx, cz, cw};
            for (int a = 0; a < 3; a++) {
                if (a == axis) continue;
                lo[a] -= 2;
                hi[a] += 2;
            }
            int[] c = {cx, cz, cw};
            lo[axis] = dir > 0 ? c[axis] + start : c[axis] - end;
            hi[axis] = dir > 0 ? c[axis] + end : c[axis] - start;
            parts.add(new Part(Kind.BRIDGE, lo[0], cy - 1, lo[1], lo[2], hi[0], cy + 4, hi[1], hi[2], axis, dir, cx, cz, cw));
            // the room at the end
            int[] rc = {cx, cz, cw};
            rc[axis] += dir * (end + 5);
            parts.add(new Part(rooms[b], rc[0] - 4, cy - 1, rc[1] - 4, rc[2] - 4, rc[0] + 4, cy + 6, rc[1] + 4, rc[2] + 4, axis, dir, rc[0], rc[1], rc[2]));
        }
        return new Plan(cx, cy, cz, cw, parts, hash(seed, gx, gz, gw, 0xF05L));
    }

    /** Is (x, y, z, w) inside a fortress (for fortress mob spawning)? */
    public static boolean inside(long seed, int x, int y, int z, int w) {
        int gx = Math.floorDiv(x, CELL), gz = Math.floorDiv(z, CELL), gw = Math.floorDiv(w, CELL);
        Plan p = plan(seed, gx, gz, gw);
        for (Part part : p.parts) if (part.contains(x, y, z, w)) return true;
        return false;
    }

    /** Nearest fortress hall centre within a few cells of {@code p}, or null. */
    public static Vec4 nearest(long seed, Vec4 p) {
        int gx = Math.floorDiv((int) Math.floor(p.x()), CELL), gz = Math.floorDiv((int) Math.floor(p.z()), CELL), gw = Math.floorDiv((int) Math.floor(p.w()), CELL);
        Vec4 best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dw = -2; dw <= 2; dw++) {
            Plan pl = plan(seed, gx + dx, gz + dz, gw + dw);
            if (pl.parts.isEmpty()) continue;
            Vec4 c = new Vec4(pl.cx + 0.5, pl.cy + 1, pl.cz + 0.5, pl.cw + 0.5);
            double d = c.distanceSqr(p);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    /** The blaze spawner of the fortress nearest {@code p}, or null. */
    public static dev.tetraform.math.BlockPos4 nearestBlazeSpawner(long seed, Vec4 p) {
        Vec4 hall = nearest(seed, p);
        if (hall == null) return null;
        Plan pl = plan(seed, Math.floorDiv((int) Math.floor(hall.x()), CELL), Math.floorDiv((int) Math.floor(hall.z()), CELL), Math.floorDiv((int) Math.floor(hall.w()), CELL));
        for (Part part : pl.parts) if (part.kind == Kind.BLAZE_ROOM) return new dev.tetraform.math.BlockPos4(part.cx, pl.cy + 2, part.cz, part.cw);
        return null;
    }

    // ------------------------------------------------------------------ writing into columns

    private static final BlockState BRICKS = Blocks.NETHER_BRICKS.defaultBlockState();
    private static final BlockState FENCE = Blocks.NETHER_BRICK_FENCE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    static void decorate(Column4 col, long seed, NetherTerrain4 terrain) {
        int bx = col.cx << 4, bz = col.cz << 4, bw = col.cw << 4;
        for (int gx = Math.floorDiv(bx - 80, CELL); gx <= Math.floorDiv(bx + 95, CELL); gx++)
            for (int gz = Math.floorDiv(bz - 80, CELL); gz <= Math.floorDiv(bz + 95, CELL); gz++)
                for (int gw = Math.floorDiv(bw - 80, CELL); gw <= Math.floorDiv(bw + 95, CELL); gw++) {
                    Plan p = plan(seed, gx, gz, gw);
                    for (Part part : p.parts) {
                        // bridges also have support pillars below their box
                        if (part.x1 < bx || part.x0 > bx + 15 || part.z1 < bz || part.z0 > bz + 15 || part.w1 < bw || part.w0 > bw + 15) continue;
                        write(col, p, part);
                    }
                }
    }

    private static void put(Column4 col, int x, int y, int z, int w, BlockState s) {
        int lx = x - (col.cx << 4), lz = z - (col.cz << 4), lw = w - (col.cw << 4);
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || lw < 0 || lw > 15 || y < 1 || y >= NetherTerrain4.ROOF) return;
        col.setRaw(lx, y, lz, lw, s);
    }

    private static void blockEntity(Column4 col, int x, int y, int z, int w, CompoundTag tag) {
        int lx = x - (col.cx << 4), lz = z - (col.cz << 4), lw = w - (col.cw << 4);
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || lw < 0 || lw > 15) return;
        if (col.pendingBlockEntities == null) col.pendingBlockEntities = new java.util.HashMap<>();
        col.pendingBlockEntities.put(new dev.tetraform.math.BlockPos4(x, y, z, w).asLong(), tag);
    }

    private static int[] axes(int x, int z, int w) {
        return new int[]{x, z, w};
    }

    private static void write(Column4 col, Plan plan, Part p) {
        int cy = plan.cy;
        int bx = col.cx << 4, bz = col.cz << 4, bw = col.cw << 4;
        int x0 = Math.max(p.x0, bx), x1 = Math.min(p.x1, bx + 15), z0 = Math.max(p.z0, bz), z1 = Math.min(p.z1, bz + 15),
                w0 = Math.max(p.w0, bw), w1 = Math.min(p.w1, bw + 15);
        switch (p.kind) {
            case BRIDGE -> {
                for (int w = w0; w <= w1; w++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                    int[] q = axes(x - p.cx, z - p.cz, w - p.cw);
                    int along = Math.abs(q[p.axis]);
                    int a1 = -1, a2 = -1;
                    for (int a = 0; a < 3; a++) if (a != p.axis) { if (a1 < 0) a1 = a; else a2 = a; }
                    boolean edge = Math.abs(q[a1]) == 2 || Math.abs(q[a2]) == 2;
                    put(col, x, cy, z, w, BRICKS);
                    put(col, x, cy - 1, z, w, BRICKS);
                    for (int y = cy + 1; y <= cy + 4; y++) put(col, x, y, z, w, y == cy + 1 && edge ? FENCE : AIR);
                    // pillars every 8 blocks down into the lava sea
                    if (along % 8 == 4 && Math.abs(q[a1]) <= 1 && Math.abs(q[a2]) <= 1) {
                        for (int y = cy - 2; y > Math.max(2, cy - 60); y--) put(col, x, y, z, w, BRICKS);
                    }
                }
            }
            case HALL -> {
                for (int w = w0; w <= w1; w++) for (int y = p.y0; y <= p.y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                    int dx = x - p.cx, dz = z - p.cz, dw = w - p.cw;
                    int m = Math.max(Math.abs(dx), Math.max(Math.abs(dz), Math.abs(dw)));
                    boolean shell = m == 6 || y <= cy || y == p.y1;
                    // doorways where the six bridges come in
                    boolean door = m == 6 && y >= cy + 1 && y <= cy + 4 && countSmall(dx, dz, dw) >= 2;
                    BlockState s = door ? AIR : shell ? BRICKS : AIR;
                    if (shell && !door && y >= cy + 2 && y <= cy + 3 && m == 6 && countSmall(dx, dz, dw) >= 1 && (dx + dz + dw & 1) == 0) s = FENCE;
                    put(col, x, y, z, w, s);
                }
                // supports under the hall, and two chests
                for (int w = w0; w <= w1; w++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                    if (Math.abs(x - p.cx) == 5 && Math.abs(z - p.cz) == 5 && Math.abs(w - p.cw) == 5) {
                        for (int y = cy - 2; y > Math.max(2, cy - 60); y--) put(col, x, y, z, w, BRICKS);
                    }
                }
                chest(col, p.cx + 4, cy + 1, p.cz - 4, p.cw, Direction.SOUTH, plan.seed);
                chest(col, p.cx - 4, cy + 1, p.cz + 4, p.cw + 1, Direction.NORTH, plan.seed + 1);
            }
            default -> {
                for (int w = w0; w <= w1; w++) for (int y = p.y0; y <= p.y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                    int dx = x - p.cx, dz = z - p.cz, dw = w - p.cw;
                    int m = Math.max(Math.abs(dx), Math.max(Math.abs(dz), Math.abs(dw)));
                    int[] q = axes(dx, dz, dw);
                    boolean toBridge = q[p.axis] == -p.dir * 4;
                    int a1 = -1, a2 = -1;
                    for (int a = 0; a < 3; a++) if (a != p.axis) { if (a1 < 0) a1 = a; else a2 = a; }
                    boolean door = toBridge && Math.abs(q[a1]) <= 2 && Math.abs(q[a2]) <= 2 && y >= cy + 1 && y <= cy + 4;
                    boolean shell = m == 4 || y <= cy || y == p.y1;
                    put(col, x, y, z, w, door ? AIR : shell ? BRICKS : AIR);
                }
                for (int w = w0; w <= w1; w++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                    int dx = x - p.cx, dz = z - p.cz, dw = w - p.cw;
                    if (Math.abs(dx) == 3 && Math.abs(dz) == 3 && Math.abs(dw) == 3) {
                        for (int y = cy - 2; y > Math.max(2, cy - 60); y--) put(col, x, y, z, w, BRICKS);
                    }
                }
                contents(col, plan, p);
            }
        }
    }

    private static int countSmall(int dx, int dz, int dw) {
        return (Math.abs(dx) <= 2 ? 1 : 0) + (Math.abs(dz) <= 2 ? 1 : 0) + (Math.abs(dw) <= 2 ? 1 : 0);
    }

    private static void contents(Column4 col, Plan plan, Part p) {
        int cy = plan.cy;
        switch (p.kind) {
            case BLAZE_ROOM -> {
                put(col, p.cx, cy + 1, p.cz, p.cw, BRICKS);
                put(col, p.cx, cy + 2, p.cz, p.cw, Blocks.SPAWNER.defaultBlockState());
                CompoundTag t = new CompoundTag();
                CompoundTag data = new CompoundTag();
                CompoundTag e = new CompoundTag();
                e.putString("id", "minecraft:blaze");
                data.put("entity", e);
                t.put("SpawnData", data);
                blockEntity(col, p.cx, cy + 2, p.cz, p.cw, t);
            }
            case WART_ROOM -> {
                for (int dw = -3; dw <= 3; dw++) for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
                    int[] q = {dx, dz, dw};
                    if (Math.abs(q[p.axis]) < 1) continue; // a walkway through the middle
                    long h = hash(plan.seed, p.cx + dx, p.cz + dz, p.cw + dw, 0x77L);
                    put(col, p.cx + dx, cy, p.cz + dz, p.cw + dw, Blocks.SOUL_SAND.defaultBlockState());
                    put(col, p.cx + dx, cy + 1, p.cz + dz, p.cw + dw, NetherTerrain4.wart((int) Math.floorMod(h, 4)));
                }
                chest(col, p.cx + 3, cy + 1, p.cz + 3, p.cw + 3, Direction.WEST, plan.seed + p.cx);
            }
            case CHEST_ROOM -> {
                chest(col, p.cx + 3, cy + 1, p.cz, p.cw, Direction.WEST, plan.seed + p.cx * 31L);
                chest(col, p.cx - 3, cy + 1, p.cz, p.cw + 1, Direction.EAST, plan.seed + p.cz * 17L);
            }
            default -> {
            }
        }
    }

    private static void chest(Column4 col, int x, int y, int z, int w, Direction facing, long seed) {
        put(col, x, y, z, w, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
        CompoundTag t = new CompoundTag();
        t.putString("LootTable", "minecraft:chests/nether_bridge");
        t.putLong("LootTableSeed", seed);
        blockEntity(col, x, y, z, w, t);
    }
}
