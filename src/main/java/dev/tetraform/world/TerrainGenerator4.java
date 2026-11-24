package dev.tetraform.world;

import dev.tetraform.math.Noise4;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Generates genuinely four-dimensional terrain: the surface height is a function of (x, z, w). Caves are carved
 * by 4D noise in (x, y, z, w), and trees grow as 4D hyperspherical canopies. Generation is pure and stateless per
 * column, so it runs in parallel on worker threads.
 */
public final class TerrainGenerator4 {
    public static final int SEA_LEVEL = 62;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
    private static final BlockState SNOW = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState COAL = Blocks.COAL_ORE.defaultBlockState();
    private static final BlockState IRON = Blocks.IRON_ORE.defaultBlockState();
    private static final BlockState GOLD = Blocks.GOLD_ORE.defaultBlockState();
    private static final BlockState REDSTONE = Blocks.REDSTONE_ORE.defaultBlockState();
    private static final BlockState DIAMOND = Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
    private static final BlockState SHORT_GRASS = Blocks.SHORT_GRASS.defaultBlockState();
    private static final BlockState POPPY = Blocks.POPPY.defaultBlockState();
    private static final BlockState DANDELION = Blocks.DANDELION.defaultBlockState();

    private final long seed;
    private final Noise4 height;
    private final Noise4 mountains;
    private final Noise4 caves;
    private final Noise4 detail;

    /** Set by the server once structure templates are available. */
    public volatile Structures4 structures;

    public TerrainGenerator4(long seed) {
        this.seed = seed;
        this.height = new Noise4(seed);
        this.mountains = new Noise4(seed * 31 + 7);
        this.caves = new Noise4(seed * 17 + 3);
        this.detail = new Noise4(seed ^ 0x5DEECE66DL);
    }

    public int surfaceHeight(int x, int z, int w) {
        double base = height.fbm3(x / 160.0, z / 160.0, w / 160.0, 4);
        double m = mountains.fbm3(x / 300.0, z / 300.0, w / 300.0, 3);
        double ridge = 1.0 - Math.abs(mountains.sample(x / 90.0, z / 90.0, w / 90.0, 3.3));
        double mount = Math.max(0, m) * ridge * ridge * 55;
        return (int) Math.round(66 + base * 18 + mount + detail.sample(x / 24.0, z / 24.0, w / 24.0, 0.5) * 2);
    }

    private long hash(int x, int y, int z, int w) {
        long h = seed;
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

    public Column4 generate(int cx, int cz, int cw) {
        Column4 col = new Column4(cx, cz, cw);
        int bx = cx << 4, bz = cz << 4, bw = cw << 4;
        int[] heights = new int[4096];
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (int lw = 0; lw < 16; lw++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int h = surfaceHeight(bx + lx, bz + lz, bw + lw);
            h = Math.max(4, Math.min(Column4.HEIGHT - 24, h));
            heights[(lw << 8) | (lz << 4) | lx] = h;
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }
        // Fully solid sections below the lowest surface (minus cave depth band) are filled uniformly.
        int solidTop = Math.max(0, minH - 6);
        for (int sy = 0; sy < Column4.SECTIONS; sy++) {
            int y0 = sy << 4;
            if (y0 + 16 <= solidTop && y0 >= 16 && y0 + 16 < 40) {
                // deep uniform stone (caves are confined to y >= 40 band near the surface to keep gen fast)
                col.sectionOrCreate(sy).fillUniform(y0 < 32 ? DEEPSLATE : STONE);
            }
        }
        float[] caveGrid = null;
        for (int lw = 0; lw < 16; lw++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int x = bx + lx, z = bz + lz, w = bw + lw;
            int h = heights[(lw << 8) | (lz << 4) | lx];
            boolean beach = h <= SEA_LEVEL + 1 && h >= SEA_LEVEL - 3;
            boolean underwater = h < SEA_LEVEL;
            boolean snowy = h > 118;
            int top = Math.max(h, SEA_LEVEL);
            int minNH = minNeighbourHeight(heights, bx, bz, bw, lx, lz, lw);
            for (int y = 0; y <= top; y++) {
                Section4 sec = col.section(y >> 4);
                if (sec != null && sec.isUniform() && !sec.isEmpty() && y < solidTop) {
                    // inside a uniform solid section: only ores to add
                    if (y > 0) maybeOre(col, lx, y, lz, lw, x, z, w);
                    continue;
                }
                BlockState s;
                if (y == 0) s = BEDROCK;
                else if (y < h - 4) s = y < 32 ? DEEPSLATE : STONE;
                else if (y < h) s = (beach || underwater) ? (underwater && h < SEA_LEVEL - 6 ? GRAVEL : SAND) : DIRT;
                else if (y == h) s = underwater ? (h < SEA_LEVEL - 6 ? GRAVEL : SAND) : beach ? SAND : snowy ? SNOW : GRASS;
                else s = WATER; // h < y <= SEA_LEVEL
                // 4D caves
                // Caves stay at least 2 blocks below this column's surface AND every face-neighbour column's
                // surface, so they can never open into a lake or ocean (vanilla uses aquifers for this).
                if (y >= 12 && y < h - 1 && s != WATER && y < minNH - 1) {
                    if (caveGrid == null) caveGrid = buildCaveGrid(bx, bz, bw, maxH);
                    if (caveValue(caveGrid, lx, y, lz, lw) < 0.085) s = Level4.AIR;
                }
                if (s.isAir()) continue;
                col.setRaw(lx, y, lz, lw, s);
                if (s == STONE || s == DEEPSLATE) maybeOre(col, lx, y, lz, lw, x, z, w);
            }
            // vegetation
            if (!underwater && !beach && !snowy && col.get(lx, h, lz, lw) == GRASS) {
                double r = rand(x, h, z, w);
                if (r < 0.06 && structures != null && structures.reserved(x, z, w)) r = 1;
                if (r < 0.0035 && lx >= 2 && lx <= 13 && lz >= 2 && lz <= 13 && lw >= 2 && lw <= 13 && h < Column4.HEIGHT - 12) {
                    placeTree(col, lx, h + 1, lz, lw, x, z, w);
                } else if (r < 0.06) {
                    col.set(lx, h + 1, lz, lw, r < 0.008 ? POPPY : r < 0.012 ? DANDELION : SHORT_GRASS);
                }
            }
        }
        Structures4 st = structures;
        if (st != null) {
            var ents = st.decorate(col);
            if (!ents.isEmpty()) col.pendingEntities = ents;
        }
        col.recomputeHeightmap();
        col.setDirty(true);
        return col;
    }

    /** Lowest surface among a column and its six horizontal face neighbours (x, z and w). */
    private int minNeighbourHeight(int[] heights, int bx, int bz, int bw, int lx, int lz, int lw) {
        int m = heights[(lw << 8) | (lz << 4) | lx];
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            int nx = lx + o[0], nz = lz + o[1], nw = lw + o[2];
            int h;
            if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16 && nw >= 0 && nw < 16) h = heights[(nw << 8) | (nz << 4) | nx];
            else h = Math.max(4, Math.min(Column4.HEIGHT - 24, surfaceHeight(bx + nx, bz + nz, bw + nw)));
            if (h < m) m = h;
        }
        return m;
    }

    private static final int CG = 4; // cave grid spacing
    private static final int CGN = 16 / CG + 1;

    /** Coarse 4D grid of |cave noise| over the column, interpolated per voxel (about 100x fewer noise samples). */
    private float[] buildCaveGrid(int bx, int bz, int bw, int maxH) {
        int ny = (maxH + CG) / CG + 1;
        float[] g = new float[CGN * CGN * CGN * ny];
        for (int iy = 0; iy < ny; iy++) for (int iw = 0; iw < CGN; iw++) for (int iz = 0; iz < CGN; iz++) for (int ix = 0; ix < CGN; ix++) {
            double x = bx + ix * CG, y = iy * CG, z = bz + iz * CG, w = bw + iw * CG;
            double c = Math.abs(caves.sample(x / 28.0, y / 18.0, z / 28.0, w / 28.0));
            double c2 = caves.sample(x / 11.0 + 100, y / 11.0, z / 11.0, w / 11.0);
            if (c2 < -0.4) c = 1;
            g[((iy * CGN + iw) * CGN + iz) * CGN + ix] = (float) c;
        }
        return g;
    }

    private static float caveValue(float[] g, int lx, int y, int lz, int lw) {
        int ix = lx / CG, iz = lz / CG, iw = lw / CG, iy = y / CG;
        float fx = (lx % CG) / (float) CG, fz = (lz % CG) / (float) CG, fw = (lw % CG) / (float) CG, fy = (y % CG) / (float) CG;
        int maxIy = g.length / (CGN * CGN * CGN) - 1;
        if (iy >= maxIy) { iy = maxIy - 1; fy = 1; }
        float r = 0;
        for (int c = 0; c < 16; c++) {
            int ox = c & 1, oz = (c >> 1) & 1, ow = (c >> 2) & 1, oy = (c >> 3) & 1;
            float wgt = (ox == 1 ? fx : 1 - fx) * (oz == 1 ? fz : 1 - fz) * (ow == 1 ? fw : 1 - fw) * (oy == 1 ? fy : 1 - fy);
            if (wgt == 0) continue;
            r += wgt * g[(((iy + oy) * CGN + iw + ow) * CGN + iz + oz) * CGN + ix + ox];
        }
        return r;
    }

    private void maybeOre(Column4 col, int lx, int y, int lz, int lw, int x, int z, int w) {
        double r = rand(x, y, z, w);
        if (r > 0.012) return;
        BlockState ore;
        if (r < 0.0006 && y < 24) ore = DIAMOND;
        else if (r < 0.0015 && y < 40) ore = GOLD;
        else if (r < 0.003 && y < 32) ore = REDSTONE;
        else if (r < 0.006) ore = IRON;
        else ore = COAL;
        col.setRaw(lx, y, lz, lw, ore);
    }

    private void placeTree(Column4 col, int lx, int y, int lz, int lw, int x, int z, int w) {
        int height = 4 + (int) (rand(x, y + 1, z, w) * 3);
        for (int i = 0; i < height; i++) col.set(lx, y + i, lz, lw, LOG);
        int cy = y + height - 1;
        // 4D hyperspherical canopy: radius 2 in x, z, w and y.
        for (int dw = -2; dw <= 2; dw++) for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 2; dy++) {
            int d2 = dx * dx + dz * dz + dw * dw + dy * dy;
            if (d2 > 5) continue;
            if (d2 == 5 && rand(x + dx, cy + dy, z + dz, w + dw) < 0.5) continue;
            int px = lx + dx, pz = lz + dz, pw = lw + dw, py = cy + dy;
            if (px < 0 || px > 15 || pz < 0 || pz > 15 || pw < 0 || pw > 15) continue;
            if (col.get(px, py, pz, pw).isAir()) col.set(px, py, pz, pw, LEAVES);
        }
    }
}
