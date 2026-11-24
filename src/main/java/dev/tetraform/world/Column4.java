package dev.tetraform.world;

import net.minecraft.world.level.block.state.BlockState;

/**
 * A vertical stack of {@link Section4}s at a (cx, cz, cw) column position: a 16x16x16 horizontal hyper-area
 * that is {@value #HEIGHT} blocks tall. It also keeps a 3D heightmap (one value per x,z,w), which drives sky light.
 */
public final class Column4 {
    public static final int SECTIONS = 16;
    public static final int HEIGHT = SECTIONS * Section4.SIZE;

    public final int cx, cz, cw;
    private final Section4[] sections = new Section4[SECTIONS];
    /** Index (w<<8 | z<<4 | x): y of the topmost opaque block + 1 (0 if none). */
    private final short[] heightmap = new short[4096];
    private volatile boolean dirty;
    public volatile long lastAccess;

    public Column4(int cx, int cz, int cw) {
        this.cx = cx;
        this.cz = cz;
        this.cw = cw;
    }

    public static long key(int cx, int cz, int cw) {
        return ((long) (cx & 0x1FFFFF) << 42) | ((long) (cz & 0x1FFFFF) << 21) | (cw & 0x1FFFFF);
    }

    public long key() {
        return key(cx, cz, cw);
    }

    public static int keyX(long k) { return (int) (k >> 42) << 11 >> 11; }
    public static int keyZ(long k) { return (int) ((k >> 21) & 0x1FFFFF) << 11 >> 11; }
    public static int keyW(long k) { return (int) (k & 0x1FFFFF) << 11 >> 11; }

    public Section4 section(int sy) {
        return sections[sy];
    }

    public Section4 sectionOrCreate(int sy) {
        Section4 s = sections[sy];
        if (s == null) {
            s = new Section4();
            sections[sy] = s;
        }
        return s;
    }

    public void setSection(int sy, Section4 s) {
        sections[sy] = s;
    }

    public BlockState get(int lx, int y, int lz, int lw) {
        if (y < 0 || y >= HEIGHT) return AIR;
        Section4 s = sections[y >> 4];
        return s == null ? AIR : s.get(lx, y & 15, lz, lw);
    }

    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

    public BlockState set(int lx, int y, int lz, int lw, BlockState state) {
        if (y < 0 || y >= HEIGHT) return AIR;
        BlockState old = sectionOrCreate(y >> 4).set(lx, y & 15, lz, lw, state);
        if (old != state) {
            dirty = true;
            int hi = (lw << 8) | (lz << 4) | lx;
            int h = heightmap[hi];
            if (BlockShapes4.blocksSky(state)) {
                if (y + 1 > h) heightmap[hi] = (short) (y + 1);
            } else if (y + 1 == h) {
                int ny = y - 1;
                while (ny >= 0 && !BlockShapes4.blocksSky(get(lx, ny, lz, lw))) ny--;
                heightmap[hi] = (short) (ny + 1);
            }
        }
        return old;
    }

    /** Sets a block without heightmap maintenance (world generation; call {@link #recomputeHeightmap()} after). */
    public void setRaw(int lx, int y, int lz, int lw, BlockState state) {
        if (y < 0 || y >= HEIGHT) return;
        sectionOrCreate(y >> 4).set(lx, y & 15, lz, lw, state);
    }

    public int height(int lx, int lz, int lw) {
        return heightmap[(lw << 8) | (lz << 4) | lx];
    }

    public void recomputeHeightmap() {
        for (int lw = 0; lw < 16; lw++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int y = HEIGHT - 1;
            while (y >= 0) {
                Section4 s = sections[y >> 4];
                if (s == null || s.isEmpty()) { y = (y & ~15) - 1; continue; }
                if (BlockShapes4.blocksSky(s.get(lx, y & 15, lz, lw))) break;
                y--;
            }
            heightmap[(lw << 8) | (lz << 4) | lx] = (short) (y + 1);
        }
    }

    public boolean isDirty() { return dirty; }
    public void setDirty(boolean d) { dirty = d; }

    public boolean hasEmitters() {
        for (Section4 s : sections) if (s != null && s.emitterCount() > 0) return true;
        return false;
    }

    // ---- serialization ----

    public byte[] toBytes() {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(16384);
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.util.zip.DeflaterOutputStream(bos, new java.util.zip.Deflater(1), 8192))) {
                out.writeInt(cx);
                out.writeInt(cz);
                out.writeInt(cw);
                int mask = 0;
                for (int i = 0; i < SECTIONS; i++) if (sections[i] != null && !sections[i].isEmpty()) mask |= 1 << i;
                out.writeShort(mask);
                for (int i = 0; i < SECTIONS; i++) if ((mask & (1 << i)) != 0) sections[i].write(out);
            }
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static Column4 fromBytes(byte[] bytes) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(bytes)))) {
            Column4 c = new Column4(in.readInt(), in.readInt(), in.readInt());
            int mask = in.readShort() & 0xFFFF;
            for (int i = 0; i < SECTIONS; i++) if ((mask & (1 << i)) != 0) c.sections[i] = Section4.read(in);
            c.recomputeHeightmap();
            return c;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
