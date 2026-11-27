package dev.tetraform.world;

import dev.tetraform.math.Vec4;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Underground rooms that are fully four-dimensional (they have thickness along W as well, so they look right
 * from any slice):
 * <ul>
 *   <li><b>Dungeons</b>: vanilla's mossy cobblestone room with a zombie, skeleton or spider spawner and two
 *   chests using vanilla's {@code chests/simple_dungeon} loot table.</li>
 *   <li><b>Stronghold portal rooms</b>: stone brick rooms with the twelve end portal frames around a lava pool, a
 *   silverfish spawner and the stairs up to the frames. Eyes of ender lead to the nearest one (in 4D), and the
 *   portal activates with vanilla's own frame check.</li>
 * </ul>
 * Blocks are written per column as columns generate; spawners and chests get their block entity data through
 * {@link Column4#pendingBlockEntities}.
 */
public final class Rooms4 {
    private Rooms4() {}

    static final int DUNGEON_CELL = 48;
    static final int STRONGHOLD_CELL = 640;

    record Room(int x0, int y0, int z0, int w0, int x1, int y1, int z1, int w1, RandomSource rng, boolean stronghold, int cx, int cy, int cz, int cw) {}

    private static long hash(long seed, int a, int b, int c, long salt) {
        long h = seed ^ salt;
        h ^= a * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 21) ^ b * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 21) ^ c * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 29);
    }

    static Room dungeon(long seed, int gx, int gz, int gw) {
        RandomSource r = RandomSource.create(hash(seed, gx, gz, gw, 0xD0D0L));
        if (r.nextFloat() > 0.3f) return null;
        int cx = gx * DUNGEON_CELL + 8 + r.nextInt(DUNGEON_CELL - 16);
        int cz = gz * DUNGEON_CELL + 8 + r.nextInt(DUNGEON_CELL - 16);
        int cw = gw * DUNGEON_CELL + 8 + r.nextInt(DUNGEON_CELL - 16);
        int cy = 16 + r.nextInt(32);
        int hx = 2 + r.nextInt(2), hz = 2 + r.nextInt(2), hw = 2 + r.nextInt(2);
        return new Room(cx - hx - 1, cy - 1, cz - hz - 1, cw - hw - 1, cx + hx + 1, cy + 4, cz + hz + 1, cw + hw + 1,
                RandomSource.create(hash(seed, gx, gz, gw, 0xD1D1L)), false, cx, cy, cz, cw);
    }

    /** The portal room of stronghold cell (gx, gz, gw), or null. None within 320 blocks of the origin. */
    static Room stronghold(long seed, int gx, int gz, int gw) {
        RandomSource r = RandomSource.create(hash(seed, gx, gz, gw, 0x5A5AL));
        int cx = gx * STRONGHOLD_CELL + 64 + r.nextInt(STRONGHOLD_CELL - 128);
        int cz = gz * STRONGHOLD_CELL + 64 + r.nextInt(STRONGHOLD_CELL - 128);
        int cw = gw * STRONGHOLD_CELL + 64 + r.nextInt(STRONGHOLD_CELL - 128);
        if ((double) cx * cx + (double) cz * cz + (double) cw * cw < 320.0 * 320.0) return null;
        int cy = 24 + r.nextInt(12);
        // u along x (11 wide), v along z (16 long), 7 thick along w; contents sit in the middle W layer
        return new Room(cx - 5, cy, cz - 8, cw - 3, cx + 5, cy + 7, cz + 7, cw + 3,
                RandomSource.create(hash(seed, gx, gz, gw, 0x5B5BL)), true, cx, cy, cz, cw);
    }

    /** Nearest stronghold portal room to {@code p} in 4D (centre of its frame ring), or null. */
    public static Vec4 nearestStronghold(long seed, Vec4 p) {
        int gx = Math.floorDiv((int) Math.floor(p.x()), STRONGHOLD_CELL), gz = Math.floorDiv((int) Math.floor(p.z()), STRONGHOLD_CELL),
                gw = Math.floorDiv((int) Math.floor(p.w()), STRONGHOLD_CELL);
        Vec4 best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dw = -2; dw <= 2; dw++) {
            Room room = stronghold(seed, gx + dx, gz + dz, gw + dw);
            if (room == null) continue;
            Vec4 c = new Vec4(room.x0 + 5.5, room.y0 + 2, room.z0 + 11.5, room.cw + 0.5);
            double d = c.distanceSqr(p);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ writing into columns

    static void decorate(Column4 col, long seed) {
        int bx = col.cx << 4, bz = col.cz << 4, bw = col.cw << 4;
        // dungeons are at most 9 wide: check the cells this column touches (plus a margin)
        for (int gx = Math.floorDiv(bx - 8, DUNGEON_CELL); gx <= Math.floorDiv(bx + 23, DUNGEON_CELL); gx++)
            for (int gz = Math.floorDiv(bz - 8, DUNGEON_CELL); gz <= Math.floorDiv(bz + 23, DUNGEON_CELL); gz++)
                for (int gw = Math.floorDiv(bw - 8, DUNGEON_CELL); gw <= Math.floorDiv(bw + 23, DUNGEON_CELL); gw++) {
                    Room room = dungeon(seed, gx, gz, gw);
                    if (room != null && intersects(room, bx, bz, bw)) writeDungeon(col, room);
                }
        for (int gx = Math.floorDiv(bx - 16, STRONGHOLD_CELL); gx <= Math.floorDiv(bx + 31, STRONGHOLD_CELL); gx++)
            for (int gz = Math.floorDiv(bz - 16, STRONGHOLD_CELL); gz <= Math.floorDiv(bz + 31, STRONGHOLD_CELL); gz++)
                for (int gw = Math.floorDiv(bw - 16, STRONGHOLD_CELL); gw <= Math.floorDiv(bw + 31, STRONGHOLD_CELL); gw++) {
                    Room room = stronghold(seed, gx, gz, gw);
                    if (room != null && intersects(room, bx, bz, bw)) writeStronghold(col, room);
                }
    }

    private static boolean intersects(Room r, int bx, int bz, int bw) {
        return r.x1 >= bx && r.x0 <= bx + 15 && r.z1 >= bz && r.z0 <= bz + 15 && r.w1 >= bw && r.w0 <= bw + 15;
    }

    private static void put(Column4 col, int x, int y, int z, int w, BlockState s) {
        int lx = x - (col.cx << 4), lz = z - (col.cz << 4), lw = w - (col.cw << 4);
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || lw < 0 || lw > 15 || y < 1 || y >= Column4.HEIGHT) return;
        col.setRaw(lx, y, lz, lw, s);
    }

    private static void blockEntity(Column4 col, int x, int y, int z, int w, CompoundTag tag) {
        int lx = x - (col.cx << 4), lz = z - (col.cz << 4), lw = w - (col.cw << 4);
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || lw < 0 || lw > 15) return;
        if (col.pendingBlockEntities == null) col.pendingBlockEntities = new java.util.HashMap<>();
        col.pendingBlockEntities.put(new dev.tetraform.math.BlockPos4(x, y, z, w).asLong(), tag);
    }

    private static CompoundTag spawner(String entity) {
        CompoundTag t = new CompoundTag();
        CompoundTag data = new CompoundTag();
        CompoundTag e = new CompoundTag();
        e.putString("id", entity);
        data.put("entity", e);
        t.put("SpawnData", data);
        return t;
    }

    private static CompoundTag lootChest(String table, long seed) {
        CompoundTag t = new CompoundTag();
        t.putString("LootTable", table);
        t.putLong("LootTableSeed", seed);
        return t;
    }

    private static void writeDungeon(Column4 col, Room r) {
        for (int w = r.w0; w <= r.w1; w++) for (int y = r.y0; y <= r.y1; y++) for (int z = r.z0; z <= r.z1; z++) for (int x = r.x0; x <= r.x1; x++) {
            boolean shell = x == r.x0 || x == r.x1 || y == r.y0 || y == r.y1 || z == r.z0 || z == r.z1 || w == r.w0 || w == r.w1;
            long h = hash(0, x, y * 31 + z, w, 0x99L);
            BlockState s = !shell ? Blocks.AIR.defaultBlockState()
                    : y == r.y0 && (h & 3) != 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            put(col, x, y, z, w, s);
        }
        String[] mobs = {"minecraft:zombie", "minecraft:zombie", "minecraft:skeleton", "minecraft:spider"};
        String mob = mobs[(int) Math.floorMod(hash(r.cx, r.cy, r.cz, r.cw, 0x31L), mobs.length)];
        put(col, r.cx, r.cy, r.cz, r.cw, Blocks.SPAWNER.defaultBlockState());
        blockEntity(col, r.cx, r.cy, r.cz, r.cw, spawner(mob));
        // two chests against the walls
        for (int i = 0; i < 2; i++) {
            long h = hash(r.cx, r.cy, r.cz, r.cw, 0x40L + i);
            boolean alongX = (h & 1) == 0;
            int x = alongX ? r.x0 + 1 + (int) Math.floorMod(h >> 3, r.x1 - r.x0 - 1) : ((h & 2) == 0 ? r.x0 + 1 : r.x1 - 1);
            int z = alongX ? ((h & 2) == 0 ? r.z0 + 1 : r.z1 - 1) : r.z0 + 1 + (int) Math.floorMod(h >> 3, r.z1 - r.z0 - 1);
            int w = r.cw + (int) Math.floorMod(h >> 17, 3) - 1;
            if (x == r.cx && z == r.cz && w == r.cw) continue;
            Direction facing = alongX ? ((h & 2) == 0 ? Direction.SOUTH : Direction.NORTH) : ((h & 2) == 0 ? Direction.EAST : Direction.WEST);
            put(col, x, r.cy, z, w, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
            blockEntity(col, x, r.cy, z, w, lootChest("minecraft:chests/simple_dungeon", h));
        }
    }

    private static void writeStronghold(Column4 col, Room r) {
        BlockState[] bricks = {Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(),
                Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState()};
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int w = r.w0; w <= r.w1; w++) for (int y = r.y0; y <= r.y1; y++) for (int z = r.z0; z <= r.z1; z++) for (int x = r.x0; x <= r.x1; x++) {
            boolean shell = x == r.x0 || x == r.x1 || y == r.y0 || y == r.y1 || z == r.z0 || z == r.z1 || w == r.w0 || w == r.w1;
            put(col, x, y, z, w, shell ? bricks[(int) Math.floorMod(hash(0, x, y * 31 + z, w, 0x55L), bricks.length)] : air);
        }
        int w = r.cw, y = r.y0 + 1, ux = r.x0, vz = r.z0; // u = x - x0, v = z - z0
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        // raised platform under the frame ring, lava under the portal
        for (int u = 2; u <= 8; u++) for (int v = 8; v <= 14; v++) {
            boolean pool = u >= 4 && u <= 6 && v >= 10 && v <= 12;
            put(col, ux + u, y, vz + v, w, pool ? Blocks.LAVA.defaultBlockState() : brick);
        }
        // stairs up to the platform
        for (int u = 4; u <= 6; u++) {
            put(col, ux + u, y, vz + 7, w, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        }
        // the twelve frames, facing the middle; each has an eye with vanilla's 10% chance
        RandomSource eyes = RandomSource.create(hash(r.cx, r.cy, r.cz, r.cw, 0xE7EL));
        int fy = y + 1;
        for (int i = 4; i <= 6; i++) {
            frame(col, ux + i, fy, vz + 9, w, Direction.SOUTH, eyes);
            frame(col, ux + i, fy, vz + 13, w, Direction.NORTH, eyes);
            frame(col, ux + 3, fy, vz + 6 + i, w, Direction.EAST, eyes);
            frame(col, ux + 7, fy, vz + 6 + i, w, Direction.WEST, eyes);
        }
        // silverfish spawner near the entrance, as in vanilla
        put(col, ux + 5, y, vz + 3, w, Blocks.SPAWNER.defaultBlockState());
        blockEntity(col, ux + 5, y, vz + 3, w, spawner("minecraft:silverfish"));
        // a little light by the door so the room can be found from inside the wall
        put(col, ux + 1, y, vz + 1, w, Blocks.TORCH.defaultBlockState());
    }

    private static void frame(Column4 col, int x, int y, int z, int w, Direction facing, RandomSource eyes) {
        put(col, x, y, z, w, Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, facing)
                .setValue(EndPortalFrameBlock.HAS_EYE, eyes.nextFloat() < 0.1f));
    }
}
