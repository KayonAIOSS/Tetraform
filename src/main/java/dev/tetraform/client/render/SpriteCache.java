package dev.tetraform.client.render;

import dev.tetraform.math.Direction4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves, per block state and per 4D face direction, which atlas sprites (base plus overlays such as the grass
 * side tint), chunk layer and tint colour to use. It reads the real baked block models, so resource packs work.
 * Lookups after the first are a lock-free array read.
 */
public final class SpriteCache {
    public record Layer(TextureAtlasSprite sprite, ChunkSectionLayer layer, int tint, int emission) {}

    /** layers[dir.ordinal()] = sprite layers for that face. */
    public record Faces(Layer[][] layers) {}

    private static volatile Faces[] cache = new Faces[0];
    private static volatile BlockStateModelSet cachedFor;

    private SpriteCache() {}

    public static Faces get(BlockState state) {
        BlockStateModelSet set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (set != cachedFor) {
            synchronized (SpriteCache.class) {
                if (set != cachedFor) {
                    cache = new Faces[Block.BLOCK_STATE_REGISTRY.size()];
                    cachedFor = set;
                }
            }
        }
        int id = Block.getId(state);
        Faces[] c = cache;
        if (id < c.length) {
            Faces f = c[id];
            if (f != null) return f;
        }
        Faces f = compute(set, state);
        if (id < c.length) c[id] = f;
        return f;
    }

    private static Direction to3D(Direction4 d) {
        return switch (d) {
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            // The W-facing cells reuse the block's side textures (north for kata, south for ana).
            case KATA -> Direction.NORTH;
            case ANA -> Direction.SOUTH;
        };
    }

    @SuppressWarnings("deprecation")
    private static Faces compute(BlockStateModelSet set, BlockState state) {
        Layer[][] layers = new Layer[8][];
        int emission = state.getLightEmission();
        if (state.getBlock() instanceof LiquidBlock) {
            TextureAtlasSprite sprite = set.getParticleMaterial(state).sprite();
            boolean lava = state.getFluidState().is(Fluids.LAVA) || state.getFluidState().is(Fluids.FLOWING_LAVA);
            Layer l = new Layer(sprite, lava ? ChunkSectionLayer.SOLID : ChunkSectionLayer.TRANSLUCENT, lava ? -1 : 0xFF3F76E4, emission);
            for (int i = 0; i < 8; i++) layers[i] = new Layer[]{l};
            return new Faces(layers);
        }
        BlockStateModel model = set.get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            model.collectParts(RandomSource.create(42L), parts);
        } catch (Throwable ignored) {
        }
        TextureAtlasSprite particle = set.getParticleMaterial(state).sprite();
        for (Direction4 d : Direction4.VALUES) {
            Direction d3 = to3D(d);
            List<Layer> out = new ArrayList<>(2);
            for (BlockStateModelPart part : parts) {
                for (BakedQuad q : part.getQuads(d3)) addQuad(out, q, state, emission);
            }
            if (out.isEmpty()) {
                // unculled geometry (crosses, torches, slab tops...): prefer a quad facing this way
                BakedQuad best = null;
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad q : part.getQuads(null)) {
                        if (best == null || (q.direction() == d3 && best.direction() != d3)) best = q;
                    }
                }
                if (best != null) addQuad(out, best, state, emission);
            }
            if (out.isEmpty()) out.add(new Layer(particle, ChunkSectionLayer.byTransparency(particle.transparency()), -1, emission));
            layers[d.ordinal()] = out.toArray(new Layer[0]);
        }
        return new Faces(layers);
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> TINTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** ARGB tint for a model tint index (default biome colours; there are no 3D biomes in a 4D world). */
    public static int tint(BlockState state, int tintIndex) {
        long key = ((long) Block.getId(state) << 8) | (tintIndex & 0xFF);
        Integer c = TINTS.get(key);
        if (c != null) return c;
        int v = -1;
        BlockTintSource src = Minecraft.getInstance().getBlockColors().getTintSource(state, tintIndex);
        if (src != null) {
            try {
                v = 0xFF000000 | src.color(state);
            } catch (Throwable t) {
                v = 0xFF7FB238;
            }
        }
        TINTS.put(key, v);
        return v;
    }

    private static void addQuad(List<Layer> out, BakedQuad q, BlockState state, int emission) {
        if (out.size() >= 3) return;
        BakedQuad.MaterialInfo mi = q.materialInfo();
        for (Layer l : out) if (l.sprite == mi.sprite()) return;
        int tint = -1;
        if (mi.tintIndex() >= 0) {
            BlockTintSource src = Minecraft.getInstance().getBlockColors().getTintSource(state, mi.tintIndex());
            if (src != null) {
                try {
                    tint = 0xFF000000 | src.color(state);
                } catch (Throwable t) {
                    tint = 0xFF7FB238;
                }
            }
        }
        out.add(new Layer(mi.sprite(), mi.layer(), tint, Math.max(emission, mi.lightEmission())));
    }
}
