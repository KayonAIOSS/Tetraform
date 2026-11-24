package dev.tetraform.client;

import dev.tetraform.math.BlockPos4;
import dev.tetraform.world.Column4;
import dev.tetraform.world.Level4;
import dev.tetraform.world.Section4;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Client copy of the 4D world, plus a per-column index of light emitters used by the mesher. */
public final class ClientLevel4 extends Level4 {
    /** Column key -> packed emitter list: {pos long, emission} pairs. */
    private final ConcurrentHashMap<Long, long[]> emitters = new ConcurrentHashMap<>();
    private volatile int maxY = 0;
    /** Bumped whenever any block or column changes; part of the mesh-cache key. */
    private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong();

    public long epoch() {
        return epoch.get();
    }
    /**
     * Cached per-section face-exposure masks (bit i = face Direction4.VALUES[i] is visible), keyed by
     * column key * 16 + section y. The mesher reads these instead of doing 8 neighbour lookups per voxel; most
     * voxels are buried (mask 0) and get skipped at once. Entries are dropped when nearby blocks change.
     */
    private final ConcurrentHashMap<Long, byte[]> exposure = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ sky light

    /** How far sky light spreads sideways under overhangs and canopies (4D Manhattan distance). */
    public static final int SKY_SPREAD = 4;
    /** Neighbour offsets {dx, dz, dw} grouped by Manhattan ring 1..SKY_SPREAD. */
    private static final int[][][] RINGS = new int[SKY_SPREAD + 1][][];

    static {
        for (int d = 1; d <= SKY_SPREAD; d++) {
            List<int[]> ring = new ArrayList<>();
            for (int dx = -d; dx <= d; dx++) for (int dz = -d; dz <= d; dz++) for (int dw = -d; dw <= d; dw++) {
                if (Math.abs(dx) + Math.abs(dz) + Math.abs(dw) == d) ring.add(new int[]{dx, dz, dw});
            }
            RINGS[d] = ring.toArray(new int[0][]);
        }
    }

    /**
     * Per (x, z, w) column: [0] = full surface height (leaves included), [1 + d] = the lowest sky-blocking
     * height among columns at Manhattan distance d (leaves, glass, plants and water let sky light through).
     */
    private final ConcurrentHashMap<Long, short[]> sky = new ConcurrentHashMap<>();

    private static long skyKey(int x, int z, int w) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (z & 0x1FFFFF) << 21) | (w & 0x1FFFFF);
    }

    private static boolean blocksSky(BlockState s) {
        return !s.isAir() && s.canOcclude() && !(s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock);
    }

    /** Height of the highest sky-blocking block + 1 in a column (looking down through canopies). */
    private int opaqueHeight(int x, int z, int w) {
        Column4 c = getColumnAt(x, z, w);
        if (c == null) return 0;
        int lx = x & 15, lz = z & 15, lw = w & 15;
        int h = c.height(lx, lz, lw);
        for (int y = h - 1, n = 0; y >= 0 && n < 48; y--, n++) {
            if (blocksSky(c.get(lx, y, lz, lw))) return y + 1;
        }
        return Math.max(0, h - 48);
    }

    private short[] skyInfo(int x, int z, int w) {
        long k = skyKey(x, z, w);
        short[] v = sky.get(k);
        if (v != null) return v;
        v = new short[SKY_SPREAD + 2];
        v[0] = (short) heightAt(x, z, w);
        v[1] = (short) opaqueHeight(x, z, w);
        for (int d = 1; d <= SKY_SPREAD; d++) {
            int m = Integer.MAX_VALUE;
            for (int[] o : RINGS[d]) m = Math.min(m, opaqueHeight(x + o[0], z + o[1], w + o[2]));
            v[1 + d] = (short) m;
        }
        sky.put(k, v);
        return v;
    }

    /**
     * 4D sky light. Full sky above the surface; under leaves it drops by about one level per leaf layer; under
     * solid cover it spreads in sideways from nearby open columns (one level per block, like vanilla), and fades
     * quickly with depth beneath that.
     */
    public int skyLight(int x, int y, int z, int w) {
        short[] v = skyInfo(x, z, w);
        if (y >= v[0]) return 15;
        int best = 0;
        if (y >= v[1]) best = 15 - Math.min(3, (v[0] - y + 1) / 2);
        for (int d = 1; d <= SKY_SPREAD && best < 15 - d; d++) {
            int m = v[1 + d];
            int l = 15 - d - 3 * Math.max(0, m - y);
            if (l > best) best = l;
        }
        return Math.max(0, best);
    }

    private void invalidateSky(int x0, int z0, int w0, int x1, int z1, int w1) {
        if (sky.isEmpty()) return;
        for (int w = w0 - SKY_SPREAD; w <= w1 + SKY_SPREAD; w++)
            for (int z = z0 - SKY_SPREAD; z <= z1 + SKY_SPREAD; z++)
                for (int x = x0 - SKY_SPREAD; x <= x1 + SKY_SPREAD; x++) sky.remove(skyKey(x, z, w));
    }

    public void put(Column4 c) {
        columns.put(c.key(), c);
        epoch.incrementAndGet();
        invalidateSky(c.cx * 16, c.cz * 16, c.cw * 16, c.cx * 16 + 15, c.cz * 16 + 15, c.cw * 16 + 15);
        invalidateColumnAndNeighbours(c.cx, c.cz, c.cw);
        reindexEmitters(c);
        for (int sy = Column4.SECTIONS - 1; sy >= 0; sy--) {
            Section4 s = c.section(sy);
            if (s != null && !s.isEmpty()) {
                if ((sy + 1) * 16 > maxY) maxY = (sy + 1) * 16;
                break;
            }
        }
    }

    public void remove(long key) {
        columns.remove(key);
        emitters.remove(key);
        int kx = Column4.keyX(key) * 16, kz = Column4.keyZ(key) * 16, kw = Column4.keyW(key) * 16;
        invalidateSky(kx, kz, kw, kx + 15, kz + 15, kw + 15);
        epoch.incrementAndGet();
        invalidateColumnAndNeighbours(Column4.keyX(key), Column4.keyZ(key), Column4.keyW(key));
    }

    public void clear() {
        columns.clear();
        emitters.clear();
        exposure.clear();
        sky.clear();
        epoch.incrementAndGet();
        dev.tetraform.client.render.MeshCache.clear();
        maxY = 0;
    }

    public int maxY() {
        return maxY;
    }

    public BlockState setClient(BlockPos4 p, BlockState s) {
        BlockState old = setBlockRaw(p, s);
        epoch.incrementAndGet();
        if (blocksSky(old) != blocksSky(s) || old.isAir() != s.isAir()) invalidateSky(p.x(), p.z(), p.w(), p.x(), p.z(), p.w());
        // the block itself and its eight neighbours may change exposure
        invalidateSection(p.x(), p.y(), p.z(), p.w());
        for (dev.tetraform.math.Direction4 d : dev.tetraform.math.Direction4.VALUES) {
            invalidateSection(p.x() + d.dx, p.y() + d.dy, p.z() + d.dz, p.w() + d.dw);
        }
        if (old.getLightEmission() != s.getLightEmission()) {
            Column4 c = getColumnAt(p.x(), p.z(), p.w());
            if (c != null) reindexEmitters(c);
        }
        if (!s.isAir() && p.y() + 1 > maxY) maxY = Math.min(Column4.HEIGHT, (p.y() + 16) & ~15);
        return old;
    }

    @SuppressWarnings("deprecation")
    private void reindexEmitters(Column4 c) {
        if (!c.hasEmitters()) {
            emitters.remove(c.key());
            return;
        }
        List<Long> out = new ArrayList<>();
        int bx = c.cx << 4, bz = c.cz << 4, bw = c.cw << 4;
        for (int sy = 0; sy < Column4.SECTIONS; sy++) {
            Section4 s = c.section(sy);
            if (s == null || s.emitterCount() == 0) continue;
            for (int i = 0; i < Section4.VOLUME; i++) {
                BlockState st = s.get(i);
                int e = st.getLightEmission();
                if (e <= 0) continue;
                int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15, w = (i >> 12) & 15;
                out.add(new BlockPos4(bx + x, sy * 16 + y, bz + z, bw + w).asLong());
                out.add((long) e);
            }
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        emitters.put(c.key(), a);
    }

    private static long exposureKey(long columnKey, int sy) {
        return columnKey * 16 + sy;
    }

    private void invalidateSection(int x, int y, int z, int w) {
        if (y < 0 || y >= Column4.HEIGHT) return;
        exposure.remove(exposureKey(Column4.key(x >> 4, z >> 4, w >> 4), y >> 4));
    }

    private void invalidateColumnAndNeighbours(int cx, int cz, int cw) {
        int[][] offs = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offs) {
            long k = Column4.key(cx + o[0], cz + o[1], cw + o[2]);
            for (int sy = 0; sy < Column4.SECTIONS; sy++) exposure.remove(exposureKey(k, sy));
        }
    }

    /** Exposure masks for section {@code sy} of {@code col}, computed on first use (thread-safe, lock-free). */
    public byte[] exposure(Column4 col, int sy) {
        long key = exposureKey(col.key(), sy);
        byte[] m = exposure.get(key);
        if (m != null) return m;
        m = Exposure4.compute(this, col, sy);
        if (columns.get(col.key()) == col) exposure.put(key, m);
        return m;
    }

    public long[] emitters(long columnKey) {
        return emitters.get(columnKey);
    }
}
