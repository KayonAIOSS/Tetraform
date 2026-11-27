package dev.tetraform.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finished slice geometry, kept like vanilla keeps compiled chunk meshes so the view never has to be sliced twice.
 * <p>
 * Entries are <b>cells</b>: 16x16x16 boxes of the slice's own coordinates (x', y, z' with no render offset),
 * keyed by the slice orientation, the slice depth and a content stamp of the 4D columns that can affect them.
 * The render offset that keeps the camera still while you rotate is applied when a cell is copied into a vanilla
 * render section, so the same cell is reused wherever it ends up on screen: rotating back to an angle, snapping
 * to it, or walking back to a depth replays stored vertices. Editing blocks changes the stamp of the nearby cells
 * only, so the rest of the cache survives.
 * <p>
 * Bounded to a quarter of the heap (at most 3 GB), least recently used first out.
 */
public final class MeshCache {
    private MeshCache() {}

    public record Key(int cx, int sy, int cz, long c, long s, long depth, long stamp) {
        static long q(double v) {
            return Math.round(v * 1.0E6);
        }
    }

    static final class Layer {
        final float[] v;     // STRIDE floats per vertex, positions relative to the cell origin
        final float[] owner; // per quad: x', z' of the owning voxel's projected centre, relative to the cell origin
        final int quads;

        Layer(float[] v, float[] owner, int quads) {
            this.v = v;
            this.owner = owner;
            this.quads = quads;
        }
    }

    static final class Entry {
        final EnumMap<ChunkSectionLayer, Layer> layers = new EnumMap<>(ChunkSectionLayer.class);
        long bytes;
    }

    private static final long BUDGET = Math.min(3L << 30, Runtime.getRuntime().maxMemory() / 4);
    private static long used;
    private static final LinkedHashMap<Key, Entry> MAP = new LinkedHashMap<>(4096, 0.75f, true);

    public static synchronized Entry get(Key k) {
        return MAP.get(k);
    }

    public static synchronized boolean contains(Key k) {
        return MAP.containsKey(k);
    }

    /** Cell + slice (stamp 0) -> the newest stamped key, so a rebuilt cell replaces its outdated copy. */
    private static final java.util.HashMap<Key, Key> NEWEST = new java.util.HashMap<>();

    static synchronized void put(Key k, Entry e) {
        Key id = new Key(k.cx(), k.sy(), k.cz(), k.c(), k.s(), k.depth(), 0);
        Key prev = NEWEST.put(id, k);
        if (prev != null && !prev.equals(k)) {
            Entry stale = MAP.remove(prev);
            if (stale != null) used -= stale.bytes;
        }
        Entry old = MAP.put(k, e);
        if (old != null) used -= old.bytes;
        used += e.bytes;
        var it = MAP.entrySet().iterator();
        while (used > BUDGET && it.hasNext()) {
            Map.Entry<Key, Entry> x = it.next();
            used -= x.getValue().bytes;
            Key ek = x.getKey();
            NEWEST.remove(new Key(ek.cx(), ek.sy(), ek.cz(), ek.c(), ek.s(), ek.depth(), 0), ek);
            it.remove();
        }
    }

    public static synchronized void clear() {
        NEWEST.clear();
        MAP.clear();
        used = 0;
    }

    public static synchronized long usedBytes() {
        return used;
    }

    public static synchronized int size() {
        return MAP.size();
    }

    static final int STRIDE = 11;

    /**
     * Copies the quads of a cached cell whose owning voxel lands in the render section into that section's
     * buffers, moved by (dx, dz) from cell coordinates to section coordinates.
     */
    static void replay(Entry e, AddSectionGeometryEvent.SectionRenderingContext ctx, float dx, float dz) {
        for (var le : e.layers.entrySet()) {
            Layer l = le.getValue();
            float[] d = l.v;
            VertexConsumer vc = null;
            for (int q = 0; q < l.quads; q++) {
                float ox = l.owner[q * 2] + dx, oz = l.owner[q * 2 + 1] + dz;
                if (ox < 0 || ox >= 16 || oz < 0 || oz >= 16) continue;
                if (vc == null) vc = ctx.getOrCreateChunkBuffer(le.getKey());
                for (int i = q * 4 * STRIDE, end = i + 4 * STRIDE; i < end; i += STRIDE) {
                    vc.addVertex(d[i] + dx, d[i + 1], d[i + 2] + dz, Float.floatToRawIntBits(d[i + 3]), d[i + 4], d[i + 5],
                            Float.floatToRawIntBits(d[i + 6]), Float.floatToRawIntBits(d[i + 7]), d[i + 8], d[i + 9], d[i + 10]);
                }
            }
        }
    }

    /** Records vertices (always whole quads) and the owning voxel of each quad. */
    static final class Recorder implements VertexConsumer {
        float[] data = new float[STRIDE * 256];
        float[] owner = new float[128];
        int n, vertices;
        /** Owner {x', z'} of the quads being emitted, shared by all layers and set by the mesher per voxel column. */
        final float[] cur;

        Recorder(float[] cur) {
            this.cur = cur;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            if ((vertices & 3) == 0) {
                int q = vertices >> 2;
                if (q * 2 + 2 > owner.length) owner = java.util.Arrays.copyOf(owner, owner.length * 2);
                owner[q * 2] = cur[0];
                owner[q * 2 + 1] = cur[1];
            }
            vertices++;
            if (n + STRIDE > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[n++] = x; data[n++] = y; data[n++] = z;
            data[n++] = Float.intBitsToFloat(color);
            data[n++] = u; data[n++] = v;
            data[n++] = Float.intBitsToFloat(overlay);
            data[n++] = Float.intBitsToFloat(light);
            data[n++] = nx; data[n++] = ny; data[n++] = nz;
        }

        // The mesher only uses the full-vertex call above.
        @Override public VertexConsumer addVertex(float x, float y, float z) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setColor(int color) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv(float u, float v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv1(int u, int v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv2(int u, int v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv3(float u, float v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setLineWidth(float width) { throw new UnsupportedOperationException(); }
    }

    static Entry entryFrom(EnumMap<ChunkSectionLayer, Recorder> recorders) {
        Entry e = new Entry();
        for (var r : recorders.entrySet()) {
            Recorder rec = r.getValue();
            int quads = rec.vertices >> 2;
            if (quads == 0) continue;
            e.layers.put(r.getKey(), new Layer(java.util.Arrays.copyOf(rec.data, quads * 4 * STRIDE),
                    java.util.Arrays.copyOf(rec.owner, quads * 2), quads));
            e.bytes += 48L + quads * (4L * STRIDE * 4 + 8);
        }
        e.bytes += 96;
        return e;
    }
}
