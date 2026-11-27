package dev.tetraform.world;

import dev.tetraform.math.Noise4;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The 4D Nether: netherrack caverns carved by 4D noise between a bedrock floor and roof (y 0 to 127), a lava sea up
 * to y 31, glowstone hanging from the ceilings, quartz, nether gold and ancient debris, and the five Nether biomes
 * as regions of the (x, z, w) space: nether wastes, crimson and warped forests (nylium, fungi, roots and huge
 * fungi), soul sand valleys and basalt deltas. Nether fortresses come from {@link Fortress4}.
 */
final class NetherTerrain4 {
    static final int LAVA_LEVEL = 31;
    static final int ROOF = 127;

    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState GLOWSTONE = Blocks.GLOWSTONE.defaultBlockState();
    private static final BlockState QUARTZ = Blocks.NETHER_QUARTZ_ORE.defaultBlockState();
    private static final BlockState NETHER_GOLD = Blocks.NETHER_GOLD_ORE.defaultBlockState();
    private static final BlockState DEBRIS = Blocks.ANCIENT_DEBRIS.defaultBlockState();
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();
    private static final BlockState SOUL_SAND = Blocks.SOUL_SAND.defaultBlockState();
    private static final BlockState SOUL_SOIL = Blocks.SOUL_SOIL.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState BASALT = Blocks.BASALT.defaultBlockState();
    private static final BlockState BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();

    enum Biome { WASTES, CRIMSON, WARPED, SOUL_VALLEY, DELTAS }

    private final long seed;
    private final Noise4 caves, caves2, temp, humid, detail;

    NetherTerrain4(long seed) {
        this.seed = seed;
        this.caves = new Noise4(seed * 13 + 101);
        this.caves2 = new Noise4(seed * 29 + 77);
        this.temp = new Noise4(seed * 41 + 5);
        this.humid = new Noise4(seed * 43 + 9);
        this.detail = new Noise4(seed ^ 0x6E65746865724CL);
    }

    Biome biome(int x, int z, int w) {
        double t = temp.fbm3(x / 260.0, z / 260.0, w / 260.0, 2), h = humid.fbm3(x / 260.0, z / 260.0, w / 260.0, 2);
        if (t > 0.32) return Biome.CRIMSON;
        if (t < -0.32) return Biome.WARPED;
        if (h > 0.34) return Biome.SOUL_VALLEY;
        if (h < -0.38) return Biome.DELTAS;
        return Biome.WASTES;
    }

    private long hash(int x, int y, int z, int w) {
        long h = seed ^ 0x4E45544845524CL;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 17) ^ y * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 23) ^ z * 0x165667B19E3779F9L;
        h = Long.rotateLeft(h, 29) ^ w * 0x27D4EB2F165667C5L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return h;
    }

    double rand(int x, int y, int z, int w) {
        return (hash(x, y, z, w) >>> 11) * 0x1.0p-53;
    }

    // ------------------------------------------------------------------ density

    private static final int G = 4, GY = 8, GN = 16 / G + 1, GNY = (ROOF + 1) / GY + 1;

    /** Coarse 4D density grid (positive = solid), interpolated per block. */
    private float[] densityGrid(int bx, int bz, int bw) {
        float[] g = new float[GN * GN * GN * GNY];
        for (int iy = 0; iy < GNY; iy++) for (int iw = 0; iw < GN; iw++) for (int iz = 0; iz < GN; iz++) for (int ix = 0; ix < GN; ix++) {
            double x = bx + ix * G, y = iy * GY, z = bz + iz * G, w = bw + iw * G;
            double d = caves.sample(x / 96.0, y / 42.0, z / 96.0, w / 96.0) * 0.85 + caves2.sample(x / 38.0, y / 22.0, z / 38.0, w / 38.0) * 0.35;
            d += 0.02 + Math.max(0, (38 - y) / 11.0) + Math.max(0, (y - 98) / 9.0);
            g[((iy * GN + iw) * GN + iz) * GN + ix] = (float) d;
        }
        return g;
    }

    private static float density(float[] g, int lx, int y, int lz, int lw) {
        int ix = lx / G, iz = lz / G, iw = lw / G, iy = Math.min(y / GY, GNY - 2);
        float fx = (lx % G) / (float) G, fz = (lz % G) / (float) G, fw = (lw % G) / (float) G, fy = (y - iy * GY) / (float) GY;
        float r = 0;
        for (int c = 0; c < 16; c++) {
            int ox = c & 1, oz = (c >> 1) & 1, ow = (c >> 2) & 1, oy = (c >> 3) & 1;
            float wgt = (ox == 1 ? fx : 1 - fx) * (oz == 1 ? fz : 1 - fz) * (ow == 1 ? fw : 1 - fw) * (oy == 1 ? fy : 1 - fy);
            if (wgt == 0) continue;
            r += wgt * g[(((iy + oy) * GN + iw + ow) * GN + iz + oz) * GN + ix + ox];
        }
        return r;
    }

    int surfaceHeight(int x, int z, int w) {
        return LAVA_LEVEL + 1;
    }

    // ------------------------------------------------------------------ generation

    Column4 generate(int cx, int cz, int cw) {
        Column4 col = new Column4(cx, cz, cw);
        int bx = cx << 4, bz = cz << 4, bw = cw << 4;
        float[] g = densityGrid(bx, bz, bw);
        Biome[] biomes = new Biome[4096];
        for (int lw = 0; lw < 16; lw++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int x = bx + lx, z = bz + lz, w = bw + lw;
            Biome b = biome(x, z, w);
            biomes[(lw << 8) | (lz << 4) | lx] = b;
            BlockState stone = b == Biome.DELTAS ? (detail.sample(x / 9.0, 0, z / 9.0, w / 9.0) > 0 ? BASALT : BLACKSTONE) : NETHERRACK;
            for (int y = 0; y <= ROOF; y++) {
                BlockState s;
                if (y == 0 || y == ROOF) s = BEDROCK;
                else if (y < 5 && rand(x, y, z, w) < (5 - y) / 5.0) s = BEDROCK;
                else if (y > ROOF - 5 && rand(x, y, z, w) < (y - (ROOF - 5)) / 5.0) s = BEDROCK;
                else if (density(g, lx, y, lz, lw) > 0) s = ore(stone, x, y, z, w);
                else if (y <= LAVA_LEVEL) s = LAVA;
                else continue;
                col.setRaw(lx, y, lz, lw, s);
            }
        }
        // surfaces: biome floors and plants, glowstone under the ceilings, magma by the lava sea
        for (int lw = 0; lw < 16; lw++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int x = bx + lx, z = bz + lz, w = bw + lw;
            Biome b = biomes[(lw << 8) | (lz << 4) | lx];
            for (int y = ROOF - 1; y > 1; y--) {
                BlockState s = col.get(lx, y, lz, lw);
                if (s.isAir() || s == LAVA || s == BEDROCK) continue;
                BlockState above = col.get(lx, y + 1, lz, lw), below = col.get(lx, y - 1, lz, lw);
                if (above.isAir()) floor(col, b, lx, y, lz, lw, x, z, w);
                else if (above == LAVA && y >= LAVA_LEVEL - 3 && rand(x, y, z, w) < 0.25) col.setRaw(lx, y, lz, lw, MAGMA);
                if (below.isAir() && y > 40 && rand(x, y + 7, z, w) < 0.0035) glowstone(col, lx, y - 1, lz, lw, x, z, w);
            }
        }
        Fortress4.decorate(col, seed, this);
        col.recomputeHeightmap();
        col.setDirty(true);
        return col;
    }

    private BlockState ore(BlockState stone, int x, int y, int z, int w) {
        if (stone != NETHERRACK) return stone;
        double r = rand(x, y + 300, z, w);
        if (y >= 8 && y <= 24 && r < 0.0001) return DEBRIS;
        if (r < 0.007) return QUARTZ;
        if (r < 0.010) return NETHER_GOLD;
        return stone;
    }

    private void floor(Column4 col, Biome b, int lx, int y, int lz, int lw, int x, int z, int w) {
        double r = rand(x, y, z, w);
        switch (b) {
            case CRIMSON, WARPED -> {
                boolean crimson = b == Biome.CRIMSON;
                if (col.get(lx, y, lz, lw) != NETHERRACK) return;
                col.setRaw(lx, y, lz, lw, (crimson ? Blocks.CRIMSON_NYLIUM : Blocks.WARPED_NYLIUM).defaultBlockState());
                if (y + 1 >= ROOF - 1) return;
                if (r < 0.006 && lx >= 3 && lx <= 12 && lz >= 3 && lz <= 12 && lw >= 3 && lw <= 12) hugeFungus(col, crimson, lx, y + 1, lz, lw, x, z, w);
                else if (r < 0.03) col.setRaw(lx, y + 1, lz, lw, (crimson ? Blocks.CRIMSON_FUNGUS : Blocks.WARPED_FUNGUS).defaultBlockState());
                else if (r < 0.16) col.setRaw(lx, y + 1, lz, lw, (crimson ? Blocks.CRIMSON_ROOTS : r < 0.1 ? Blocks.WARPED_ROOTS : Blocks.NETHER_SPROUTS).defaultBlockState());
            }
            case SOUL_VALLEY -> {
                boolean soil = detail.sample(x / 7.0, y / 7.0, z / 7.0, w / 7.0) > 0;
                for (int d = 0; d < 3 && y - d > 1; d++) {
                    if (col.get(lx, y - d, lz, lw) != NETHERRACK) break;
                    col.setRaw(lx, y - d, lz, lw, soil ? SOUL_SOIL : SOUL_SAND);
                }
                if (r < 0.004) col.setRaw(lx, y + 1, lz, lw, Blocks.SOUL_FIRE.defaultBlockState());
            }
            case DELTAS -> {
                if (r < 0.04 && y < 70) col.setRaw(lx, y, lz, lw, MAGMA);
            }
            case WASTES -> {
                if (col.get(lx, y, lz, lw) != NETHERRACK) return;
                double n = detail.sample(x / 12.0, y / 6.0, z / 12.0, w / 12.0);
                if (y <= LAVA_LEVEL + 4 && n > 0.45) col.setRaw(lx, y, lz, lw, GRAVEL);
                else if (y <= LAVA_LEVEL + 6 && n < -0.5) col.setRaw(lx, y, lz, lw, SOUL_SAND);
                else if (r < 0.006 && y + 1 < ROOF - 1) col.setRaw(lx, y + 1, lz, lw, Blocks.FIRE.defaultBlockState());
                else if (r < 0.012) col.setRaw(lx, y + 1, lz, lw, (r < 0.009 ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM).defaultBlockState());
            }
        }
    }

    private void glowstone(Column4 col, int lx, int y, int lz, int lw, int x, int z, int w) {
        int len = 2 + (int) (rand(x, y, z, w + 1) * 4);
        for (int dy = 0; dy < len; dy++) for (int dw = -1; dw <= 1; dw++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int d = Math.abs(dx) + Math.abs(dz) + Math.abs(dw);
            if (d > (dy == 0 ? 2 : 1) || dy > 0 && d > 0 && rand(x + dx, y - dy, z + dz, w + dw) < 0.5) continue;
            int px = lx + dx, pz = lz + dz, pw = lw + dw, py = y - dy;
            if (px < 0 || px > 15 || pz < 0 || pz > 15 || pw < 0 || pw > 15 || py < 2) continue;
            if (col.get(px, py, pz, pw).isAir()) col.setRaw(px, py, pz, pw, GLOWSTONE);
        }
    }

    /** A huge crimson or warped fungus: a stem with a 4D cap of wart blocks dotted with shroomlights. */
    private void hugeFungus(Column4 col, boolean crimson, int lx, int y, int lz, int lw, int x, int z, int w) {
        int h = 5 + (int) (rand(x, y + 1, z, w) * 5);
        if (y + h + 2 >= ROOF - 2) return;
        BlockState stem = (crimson ? Blocks.CRIMSON_STEM : Blocks.WARPED_STEM).defaultBlockState();
        BlockState wart = (crimson ? Blocks.NETHER_WART_BLOCK : Blocks.WARPED_WART_BLOCK).defaultBlockState();
        for (int i = 0; i < h; i++) {
            if (!col.get(lx, y + i, lz, lw).isAir()) return;
        }
        for (int i = 0; i < h; i++) col.setRaw(lx, y + i, lz, lw, stem);
        int top = y + h;
        for (int dy = -3; dy <= 1; dy++) for (int dw = -3; dw <= 3; dw++) for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
            int rr = dx * dx + dz * dz + dw * dw;
            int lim = dy == 1 ? 2 : dy == 0 ? 6 : 10;
            if (rr > lim || dy < 0 && rr < 4) continue;
            int px = lx + dx, pz = lz + dz, pw = lw + dw, py = top + dy;
            if (px < 0 || px > 15 || pz < 0 || pz > 15 || pw < 0 || pw > 15) continue;
            if (!col.get(px, py, pz, pw).isAir()) continue;
            col.setRaw(px, py, pz, pw, rand(x + dx, py, z + dz, w + dw) < 0.08 ? Blocks.SHROOMLIGHT.defaultBlockState() : wart);
        }
    }

    static BlockState wart(int age) {
        return Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, Math.max(0, Math.min(3, age)));
    }
}
