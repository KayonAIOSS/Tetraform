package dev.tetraform.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Lifts vanilla 3D block shapes into 4D hyperboxes. The W extent of a non-full shape follows its horizontal
 * footprint: a slab spans the full W range, while a torch or fence post is thin in W as well. Results are cached
 * per block state id, so lookups are a single array read.
 * <p>
 * Box layout: {minX, minY, minZ, minW, maxX, maxY, maxZ, maxW} in block-local units [0, 1].
 */
public final class BlockShapes4 {
    private static final double[] FULL = {0, 0, 0, 0, 1, 1, 1, 1};
    private static volatile Entry[] cache = new Entry[0];

    private record Entry(double[] collision, double[] render, boolean full, boolean blocksSky, boolean selectable) {}

    private BlockShapes4() {}

    private static Entry entry(BlockState state) {
        int id = Block.getId(state);
        Entry[] c = cache;
        if (id < c.length) {
            Entry e = c[id];
            if (e != null) return e;
        }
        Entry e = compute(state);
        synchronized (BlockShapes4.class) {
            c = cache;
            if (id >= c.length) {
                c = java.util.Arrays.copyOf(c, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
            }
            c[id] = e;
            cache = c;
        }
        return e;
    }

    private static Entry compute(BlockState state) {
        if (state.isAir()) return new Entry(null, null, false, false, false);
        double[] col;
        double[] ren;
        boolean full;
        try {
            VoxelShape cs = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            col = cs.isEmpty() ? null : lift(cs.bounds());
            VoxelShape rs = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            ren = rs.isEmpty() ? FULL : lift(rs.bounds());
            full = Block.isShapeFullBlock(rs);
        } catch (Throwable t) {
            col = FULL;
            ren = FULL;
            full = true;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            int level = state.getFluidState().getAmount();
            double h = Math.max(0.1, Math.min(1.0, level / 9.0));
            if (state.getFluidState().isSource()) h = 0.875;
            ren = new double[]{0, 0, 0, 0, 1, h, 1, 1};
            col = null;
            full = false;
        }
        boolean blocksSky = state.canOcclude() || !state.getFluidState().isEmpty();
        boolean selectable = !(state.getBlock() instanceof LiquidBlock);
        return new Entry(col, ren, full && state.canOcclude(), blocksSky, selectable);
    }

    private static double[] lift(AABB b) {
        if (b.minX <= 0 && b.minY <= 0 && b.minZ <= 0 && b.maxX >= 1 && b.maxY >= 1 && b.maxZ >= 1) return FULL;
        // Narrow in both x and z (torch, post, flower): also narrow in W. Otherwise (slab, door, pane): full W.
        double wx = b.maxX - b.minX, wz = b.maxZ - b.minZ;
        double w0 = 0, w1 = 1;
        if (wx < 0.99 && wz < 0.99) {
            w0 = Math.min(b.minX, b.minZ);
            w1 = Math.max(b.maxX, b.maxZ);
        }
        return new double[]{clamp(b.minX), clamp(b.minY), clamp(b.minZ), clamp(w0), clamp(b.maxX), clamp(b.maxY), clamp(b.maxZ), clamp(w1)};
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Collision hyperbox, or null if the block has no collision. */
    public static double[] collision(BlockState s) { return entry(s).collision; }

    /** Visual hyperbox (never null for non-air). */
    public static double[] render(BlockState s) { return entry(s).render; }

    /** True if this is an opaque full tesseract (used for face culling). */
    public static boolean fullOpaque(BlockState s) { return entry(s).full; }

    public static boolean blocksSky(BlockState s) { return entry(s).blocksSky; }

    public static boolean selectable(BlockState s) { return entry(s).selectable; }
}
