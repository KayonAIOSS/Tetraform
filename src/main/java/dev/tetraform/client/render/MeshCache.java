package dev.tetraform.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memory-for-compute trade: finished section meshes are kept (bounded to a quarter of the heap, at most 3 GiB),
 * keyed by section, slice orientation, depth, render offset and world-content epoch. Snapping back to an angle
 * you have used, or strafing back along W, replays the stored vertices instead of re-slicing the 4D world. Any
 * block or column change bumps the epoch, so stale geometry is never shown.
 */
public final class MeshCache {
    private MeshCache() {}

    public record Key(long origin, long c, long s, long depth, long offX, long offZ, long epoch) {
        static long q(double v) {
            return Math.round(v * 1.0E6);
        }
    }

    static final class Entry {
        final EnumMap<ChunkSectionLayer, float[]> layers = new EnumMap<>(ChunkSectionLayer.class);
        final EnumMap<ChunkSectionLayer, Integer> counts = new EnumMap<>(ChunkSectionLayer.class);
        long bytes;
    }

    private static final long BUDGET = Math.min(3L << 30, Runtime.getRuntime().maxMemory() / 4);
    private static long used;
    private static final LinkedHashMap<Key, Entry> MAP = new LinkedHashMap<>(4096, 0.75f, true);

    public static synchronized Entry get(Key k) {
        return MAP.get(k);
    }

    static synchronized void put(Key k, Entry e) {
        Entry old = MAP.put(k, e);
        if (old != null) used -= old.bytes;
        used += e.bytes;
        var it = MAP.entrySet().iterator();
        while (used > BUDGET && it.hasNext()) {
            Map.Entry<Key, Entry> x = it.next();
            used -= x.getValue().bytes;
            it.remove();
        }
    }

    public static synchronized void clear() {
        MAP.clear();
        used = 0;
    }

    public static synchronized long usedBytes() {
        return used;
    }

    public static synchronized int size() {
        return MAP.size();
    }

    private static final int STRIDE = 11;

    /** Writes a cached mesh into the section's buffers. */
    static void replay(Entry e, AddSectionGeometryEvent.SectionRenderingContext ctx) {
        for (var le : e.layers.entrySet()) {
            float[] d = le.getValue();
            int n = e.counts.get(le.getKey());
            VertexConsumer vc = ctx.getOrCreateChunkBuffer(le.getKey());
            for (int i = 0; i < n; i += STRIDE) {
                vc.addVertex(d[i], d[i + 1], d[i + 2], Float.floatToRawIntBits(d[i + 3]), d[i + 4], d[i + 5],
                        Float.floatToRawIntBits(d[i + 6]), Float.floatToRawIntBits(d[i + 7]), d[i + 8], d[i + 9], d[i + 10]);
            }
        }
    }

    /** Pass-through consumer that also records every vertex for the cache. */
    static final class Recorder implements VertexConsumer {
        final VertexConsumer out;
        float[] data = new float[STRIDE * 256];
        int n;

        Recorder(VertexConsumer out) {
            this.out = out;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            out.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
            if (n + STRIDE > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[n++] = x; data[n++] = y; data[n++] = z;
            data[n++] = Float.intBitsToFloat(color);
            data[n++] = u; data[n++] = v;
            data[n++] = Float.intBitsToFloat(overlay);
            data[n++] = Float.intBitsToFloat(light);
            data[n++] = nx; data[n++] = ny; data[n++] = nz;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { return out.addVertex(x, y, z); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return out.setColor(r, g, b, a); }
        @Override public VertexConsumer setColor(int color) { return out.setColor(color); }
        @Override public VertexConsumer setUv(float u, float v) { return out.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return out.setUv1(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return out.setUv2(u, v); }
        @Override public VertexConsumer setUv3(float u, float v) { return out.setUv3(u, v); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return out.setNormal(x, y, z); }
        @Override public VertexConsumer setLineWidth(float width) { return out.setLineWidth(width); }
    }

    static Entry entryFrom(EnumMap<ChunkSectionLayer, Recorder> recorders) {
        Entry e = new Entry();
        for (var r : recorders.entrySet()) {
            Recorder rec = r.getValue();
            if (rec.n == 0) continue;
            e.layers.put(r.getKey(), java.util.Arrays.copyOf(rec.data, rec.n));
            e.counts.put(r.getKey(), rec.n);
            e.bytes += 16L + rec.n * 4L;
        }
        e.bytes += 64;
        return e;
    }
}
