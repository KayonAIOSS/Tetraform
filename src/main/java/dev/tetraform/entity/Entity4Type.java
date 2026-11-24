package dev.tetraform.entity;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 4D entity types. Bodies are built from hyperbox parts expressed in the entity's own local frame
 * (x' = right, y = up, z' = forward, w' = ana), so a creature that turns toward W visibly rotates through the
 * fourth dimension. For example, the Hyperhog has 2x2x2 = 8 legs, one per (x', z', w') corner.
 */
public enum Entity4Type {
    HYPERHOG(0.45, 0.95, 10, false, 0.13),
    STALKER(0.3, 2.1, 20, true, 0.17),
    ITEM(0.125, 0.25, 1, false, 0),
    PLAYER(0.3, 1.8, 20, false, 0);

    public static final Entity4Type[] VALUES = values();

    public final double halfWidth;
    public final double height;
    public final float maxHealth;
    public final boolean hostile;
    public final double speed;

    Entity4Type(double halfWidth, double height, float maxHealth, boolean hostile, double speed) {
        this.halfWidth = halfWidth;
        this.height = height;
        this.maxHealth = maxHealth;
        this.hostile = hostile;
        this.speed = speed;
    }

    /** A hyperbox in entity-local coordinates: {x0, y0, z0, w0, x1, y1, z1, w1}. */
    public record Part(double[] box, BlockState texture) {}

    private static Part part(double x0, double y0, double z0, double w0, double x1, double y1, double z1, double w1, BlockState t) {
        return new Part(new double[]{x0, y0, z0, w0, x1, y1, z1, w1}, t);
    }

    private static final List<Part> HOG_PARTS;
    private static final List<Part> STALKER_PARTS;
    private static final List<Part> PLAYER_PARTS;

    static {
        java.util.ArrayList<Part> hog = new java.util.ArrayList<>();
        hog.add(part(-0.42, 0.3, -0.6, -0.42, 0.42, 0.9, 0.55, 0.42, Blocks.WOOL.pick(DyeColor.PINK).defaultBlockState()));
        hog.add(part(-0.3, 0.45, 0.55, -0.3, 0.3, 0.95, 0.95, 0.3, Blocks.DYED_TERRACOTTA.pick(DyeColor.PINK).defaultBlockState()));
        hog.add(part(-0.12, 0.55, 0.95, -0.12, 0.12, 0.72, 1.05, 0.12, Blocks.CONCRETE.pick(DyeColor.PINK).defaultBlockState()));
        // eight legs: every (x', z', w') corner of the body
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) for (int sw = -1; sw <= 1; sw += 2) {
            double cx = sx * 0.27, cz = sz * 0.38, cw = sw * 0.27;
            hog.add(part(cx - 0.1, 0, cz - 0.1, cw - 0.1, cx + 0.1, 0.3, cz + 0.1, cw + 0.1, Blocks.DYED_TERRACOTTA.pick(DyeColor.PINK).defaultBlockState()));
        }
        HOG_PARTS = List.copyOf(hog);

        java.util.ArrayList<Part> st = new java.util.ArrayList<>();
        st.add(part(-0.28, 0.85, -0.18, -0.28, 0.28, 1.6, 0.18, 0.28, Blocks.CRYING_OBSIDIAN.defaultBlockState()));
        st.add(part(-0.25, 1.6, -0.25, -0.25, 0.25, 2.1, 0.25, 0.25, Blocks.CARVED_PUMPKIN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CarvedPumpkinBlock.FACING, net.minecraft.core.Direction.SOUTH)));
        for (int sx = -1; sx <= 1; sx += 2) for (int sw = -1; sw <= 1; sw += 2) {
            double cx = sx * 0.15, cw = sw * 0.15;
            st.add(part(cx - 0.09, 0, -0.09, cw - 0.09, cx + 0.09, 0.85, 0.09, cw + 0.09, Blocks.OBSIDIAN.defaultBlockState()));
        }
        // arms reach forward in z'
        st.add(part(-0.4, 1.3, 0.0, -0.08, -0.28, 1.45, 0.6, 0.08, Blocks.OBSIDIAN.defaultBlockState()));
        st.add(part(0.28, 1.3, 0.0, -0.08, 0.4, 1.45, 0.6, 0.08, Blocks.OBSIDIAN.defaultBlockState()));
        STALKER_PARTS = List.copyOf(st);

        java.util.ArrayList<Part> pl = new java.util.ArrayList<>();
        pl.add(part(-0.25, 0.75, -0.13, -0.13, 0.25, 1.45, 0.13, 0.13, Blocks.WOOL.pick(DyeColor.LIGHT_BLUE).defaultBlockState()));
        pl.add(part(-0.22, 1.45, -0.22, -0.22, 0.22, 1.85, 0.22, 0.22, Blocks.BIRCH_PLANKS.defaultBlockState()));
        pl.add(part(-0.24, 0, -0.12, -0.12, -0.01, 0.75, 0.12, 0.12, Blocks.WOOL.pick(DyeColor.BLUE).defaultBlockState()));
        pl.add(part(0.01, 0, -0.12, -0.12, 0.24, 0.75, 0.12, 0.12, Blocks.WOOL.pick(DyeColor.BLUE).defaultBlockState()));
        pl.add(part(-0.38, 0.75, -0.1, -0.1, -0.26, 1.45, 0.1, 0.1, Blocks.BIRCH_PLANKS.defaultBlockState()));
        pl.add(part(0.26, 0.75, -0.1, -0.1, 0.38, 1.45, 0.1, 0.1, Blocks.BIRCH_PLANKS.defaultBlockState()));
        PLAYER_PARTS = List.copyOf(pl);
    }

    public List<Part> parts(BlockState itemTexture) {
        return switch (this) {
            case HYPERHOG -> HOG_PARTS;
            case STALKER -> STALKER_PARTS;
            case PLAYER -> PLAYER_PARTS;
            case ITEM -> List.of(part(-0.125, 0, -0.125, -0.125, 0.125, 0.25, 0.125, 0.125,
                    itemTexture == null ? Blocks.GOLD_BLOCK.defaultBlockState() : itemTexture));
        };
    }
}
