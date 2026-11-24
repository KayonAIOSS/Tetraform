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

    public void put(Column4 c) {
        columns.put(c.key(), c);
        epoch.incrementAndGet();
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
        epoch.incrementAndGet();
        invalidateColumnAndNeighbours(Column4.keyX(key), Column4.keyZ(key), Column4.keyW(key));
    }

    public void clear() {
        columns.clear();
        emitters.clear();
        exposure.clear();
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
