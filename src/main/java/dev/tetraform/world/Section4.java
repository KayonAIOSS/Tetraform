package dev.tetraform.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * A 16x16x16x16 tesseract of blocks (65,536 voxels). Storage is palette-compressed and bit-packed, the same way
 * vanilla stores 3D sections, so a typical terrain section costs 16-48 KiB.
 * <p>
 * Thread-safety: readers on mesh worker threads may race with the main thread writing; the palette+data pair is
 * swapped atomically through a single volatile reference so a reader never sees a palette/data mismatch.
 */
public final class Section4 {
    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE * SIZE;

    private static final class Storage {
        final BlockState[] palette;
        final int paletteSize;
        final int bits; // 0 = single value
        final long[] data;

        Storage(BlockState[] palette, int paletteSize, int bits, long[] data) {
            this.palette = palette;
            this.paletteSize = paletteSize;
            this.bits = bits;
            this.data = data;
        }
    }

    private volatile Storage storage;
    private int nonAir;
    private int opaque;
    private int emitters;

    public Section4() {
        this(Blocks.AIR.defaultBlockState());
    }

    public Section4(BlockState fill) {
        storage = new Storage(new BlockState[]{fill}, 1, 0, null);
        nonAir = fill.isAir() ? 0 : VOLUME;
        opaque = fill.canOcclude() ? VOLUME : 0;
        emitters = fill.getLightEmission() > 0 ? VOLUME : 0;
    }

    public static int index(int x, int y, int z, int w) {
        return ((w & 15) << 12) | ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public BlockState get(int x, int y, int z, int w) {
        return get(index(x, y, z, w));
    }

    public BlockState get(int idx) {
        Storage s = storage;
        if (s.bits == 0) return s.palette[0];
        int perLong = 64 / s.bits;
        long word = s.data[idx / perLong];
        int id = (int) ((word >>> ((idx % perLong) * s.bits)) & ((1L << s.bits) - 1));
        return id < s.paletteSize ? s.palette[id] : s.palette[0];
    }

    public boolean isEmpty() { return nonAir == 0; }

    /** Cheap palette check: could any block in this section match? */
    public boolean paletteAny(java.util.function.Predicate<BlockState> test) {
        Storage st = storage;
        for (int i = 0; i < st.paletteSize; i++) if (test.test(st.palette[i])) return true;
        return false;
    }
    public boolean isFullyOpaque() { return opaque == VOLUME; }
    public int emitterCount() { return emitters; }
    public boolean isUniform() { return storage.bits == 0; }

    public BlockState set(int x, int y, int z, int w, BlockState state) {
        return set(index(x, y, z, w), state);
    }

    public synchronized BlockState set(int idx, BlockState state) {
        Storage s = storage;
        BlockState old = get(idx);
        if (old == state) return old;
        int id = -1;
        for (int i = 0; i < s.paletteSize; i++) {
            if (s.palette[i] == state) { id = i; break; }
        }
        if (id < 0) {
            if (s.paletteSize >= (s.bits == 0 ? 1 : 1 << s.bits)) {
                s = grow(s);
            }
            BlockState[] pal = s.palette.length > s.paletteSize ? s.palette : Arrays.copyOf(s.palette, Math.max(4, s.palette.length * 2));
            pal[s.paletteSize] = state;
            id = s.paletteSize;
            s = new Storage(pal, s.paletteSize + 1, s.bits, s.data);
            storage = s;
        }
        int perLong = 64 / s.bits;
        int li = idx / perLong;
        int shift = (idx % perLong) * s.bits;
        long mask = ((1L << s.bits) - 1) << shift;
        s.data[li] = (s.data[li] & ~mask) | ((long) id << shift);
        if (old.isAir() != state.isAir()) nonAir += state.isAir() ? -1 : 1;
        if (old.canOcclude() != state.canOcclude()) opaque += state.canOcclude() ? 1 : -1;
        if ((old.getLightEmission() > 0) != (state.getLightEmission() > 0)) emitters += state.getLightEmission() > 0 ? 1 : -1;
        return old;
    }

    private Storage grow(Storage s) {
        int newBits = s.bits == 0 ? 2 : s.bits * 2 > 16 ? 16 : (s.bits < 4 ? 4 : s.bits * 2);
        if (newBits <= s.bits) newBits = s.bits + 1;
        int perLong = 64 / newBits;
        long[] data = new long[(VOLUME + perLong - 1) / perLong];
        if (s.bits != 0) {
            int oldPer = 64 / s.bits;
            long oldMask = (1L << s.bits) - 1;
            for (int i = 0; i < VOLUME; i++) {
                long id = (s.data[i / oldPer] >>> ((i % oldPer) * s.bits)) & oldMask;
                data[i / perLong] |= id << ((i % perLong) * newBits);
            }
        }
        Storage ns = new Storage(s.palette, s.paletteSize, newBits, data);
        storage = ns;
        return ns;
    }

    /** Fills a whole section quickly (used by worldgen). */
    public void fillUniform(BlockState state) {
        storage = new Storage(new BlockState[]{state}, 1, 0, null);
        nonAir = state.isAir() ? 0 : VOLUME;
        opaque = state.canOcclude() ? VOLUME : 0;
        emitters = state.getLightEmission() > 0 ? VOLUME : 0;
    }

    // ---- serialization: palette as global state ids, then packed data ----

    public void write(java.io.DataOutput out) throws java.io.IOException {
        Storage s = storage;
        out.writeShort(s.paletteSize);
        for (int i = 0; i < s.paletteSize; i++) out.writeInt(Block.getId(s.palette[i]));
        out.writeByte(s.bits);
        if (s.bits != 0) {
            out.writeInt(s.data.length);
            for (long l : s.data) out.writeLong(l);
        }
    }

    public static Section4 read(java.io.DataInput in) throws java.io.IOException {
        int n = in.readShort();
        BlockState[] pal = new BlockState[Math.max(1, n)];
        for (int i = 0; i < n; i++) pal[i] = Block.stateById(in.readInt());
        int bits = in.readByte();
        Section4 sec = new Section4(pal[0]);
        if (bits != 0) {
            int len = in.readInt();
            long[] data = new long[len];
            for (int i = 0; i < len; i++) data[i] = in.readLong();
            sec.storage = new Storage(pal, n, bits, data);
            sec.recount();
        }
        return sec;
    }

    public void recount() {
        int na = 0, op = 0, em = 0;
        Storage s = storage;
        // count per palette entry, then multiply
        int[] counts = new int[s.paletteSize];
        if (s.bits == 0) {
            counts[0] = VOLUME;
        } else {
            int per = 64 / s.bits;
            long mask = (1L << s.bits) - 1;
            for (int i = 0; i < VOLUME; i++) {
                int id = (int) ((s.data[i / per] >>> ((i % per) * s.bits)) & mask);
                if (id < counts.length) counts[id]++;
            }
        }
        for (int i = 0; i < s.paletteSize; i++) {
            BlockState st = s.palette[i];
            if (!st.isAir()) na += counts[i];
            if (st.canOcclude()) op += counts[i];
            if (st.getLightEmission() > 0) em += counts[i];
        }
        nonAir = na;
        opaque = op;
        emitters = em;
    }
}
