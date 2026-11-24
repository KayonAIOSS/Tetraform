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

    public void put(Column4 c) {
        columns.put(c.key(), c);
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
    }

    public void clear() {
        columns.clear();
        emitters.clear();
        maxY = 0;
    }

    public int maxY() {
        return maxY;
    }

    public BlockState setClient(BlockPos4 p, BlockState s) {
        BlockState old = setBlockRaw(p, s);
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

    public long[] emitters(long columnKey) {
        return emitters.get(columnKey);
    }
}
