package dev.tetraform.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.tetraform.client.ClientLevel4;
import dev.tetraform.client.ClientState4;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Direction4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Slicer;
import dev.tetraform.world.BlockShapes4;
import dev.tetraform.world.Column4;
import dev.tetraform.world.Section4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;

import java.util.EnumMap;

/**
 * Turns the viewer's 3D cross-section of the 4D world into ordinary chunk-section geometry.
 * <p>
 * For a vanilla render section (16^3 in render space), the mesher enumerates exactly the 4D voxels whose
 * cross-section lands in that section. It does this by iterating two horizontal axes and solving the plane
 * equation for the third. It then slices each voxel's hyperbox with the hyperplane and emits the resulting
 * <b>faces</b> (convex polygons, never lines) through the normal section buffers. Vanilla, Sodium and Iris all
 * treat this geometry as regular terrain: it gets frustum and occlusion culling, translucency sorting and
 * shader-pack shading (shadow pass included).
 * <p>
 * Faces between two opaque full tesseracts are culled across all eight 4D directions. The per-thread slicer
 * does no allocation per face.
 */
public final class SliceMesher {
    private SliceMesher() {}

    private static final ThreadLocal<Slicer> SLICER = ThreadLocal.withInitial(Slicer::new);

    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        if (!ClientState4.active || !ClientState4.hasPos) return;
        Minecraft mc = Minecraft.getInstance();
        if (event.getLevel() != mc.level) return;
        BlockPos origin = event.getSectionOrigin().immutable();
        ClientLevel4 level = ClientState4.LEVEL;
        if (origin.getY() >= level.maxY() || origin.getY() < 0) return;
        ClientState4.MeshParams params = ClientState4.mesh;
        SliceFrame f = params.frame();
        MeshCache.Key key = new MeshCache.Key(origin.asLong(), MeshCache.Key.q(f.get(1, 1)), MeshCache.Key.q(f.get(2, 1)),
                MeshCache.Key.q(params.depth()), MeshCache.Key.q(params.offX()), MeshCache.Key.q(params.offZ()), level.epoch());
        MeshCache.Entry cached = MeshCache.get(key);
        if (cached != null) {
            if (!cached.layers.isEmpty()) event.addRenderer(ctx -> MeshCache.replay(cached, ctx));
            return;
        }
        event.addRenderer(ctx -> {
            try {
                Sink sink = build(ctx, origin, params, level);
                if (sink != null && level.epoch() == key.epoch()) MeshCache.put(key, MeshCache.entryFrom(sink.recorders));
            } catch (Throwable t) {
                dev.tetraform.Tetraform.LOGGER.error("4D slice meshing failed for section {}", origin, t);
            }
        });
    }

    private static final class Sink implements Slicer.FaceSink {
        AddSectionGeometryEvent.SectionRenderingContext ctx;
        final EnumMap<ChunkSectionLayer, VertexConsumer> buffers = new EnumMap<>(ChunkSectionLayer.class);
        final EnumMap<ChunkSectionLayer, MeshCache.Recorder> recorders = new EnumMap<>(ChunkSectionLayer.class);
        BlockState state;
        SpriteCache.Faces faces;
        ClientLevel4 level;
        int vx, vy, vz, vw; // voxel
        int oy;
        int[] emitters;
        int emitterCount;
        boolean modelPath;
        // mapping for model-rendered blocks (zw frames): render x = x + mox - ox, render z' = c*z + s*w + moz - oz
        double zc, zs, depth, relX, relZ;

        VertexConsumer buffer(ChunkSectionLayer l) {
            VertexConsumer c = buffers.get(l);
            if (c == null) {
                MeshCache.Recorder r = new MeshCache.Recorder(ctx.getOrCreateChunkBuffer(l));
                recorders.put(l, r);
                c = r;
                buffers.put(l, c);
            }
            return c;
        }

        @Override
        public void face(Direction4 dir, int n, float[] pos, float[] uv, float nx, float ny, float nz) {
            SpriteCache.Layer[] layers = faces.layers()[dir.ordinal()];
            int light = light(dir, layers[0].emission());
            float shade = shade(nx, ny, nz);
            for (SpriteCache.Layer l : layers) {
                int tint = l.tint();
                int r = (int) (((tint >> 16) & 255) * shade), g = (int) (((tint >> 8) & 255) * shade), b = (int) ((tint & 255) * shade);
                int color = 0xFF000000 | (r << 16) | (g << 8) | b;
                VertexConsumer vc = buffer(l.layer());
                var sp = l.sprite();
                // fan of quads: (0,1,2,3), (0,3,4,5); a triangle repeats its last vertex
                for (int start = 1; start < n - 1; start += 2) {
                    emit(vc, sp, pos, uv, 0, color, light, nx, ny, nz);
                    emit(vc, sp, pos, uv, start, color, light, nx, ny, nz);
                    emit(vc, sp, pos, uv, start + 1, color, light, nx, ny, nz);
                    emit(vc, sp, pos, uv, Math.min(start + 2, n - 1), color, light, nx, ny, nz);
                }
            }
        }

        private void emit(VertexConsumer vc, net.minecraft.client.renderer.texture.TextureAtlasSprite sp, float[] pos, float[] uv, int i,
                          int color, int light, float nx, float ny, float nz) {
            float u = clamp01(uv[i * 2]), v = clamp01(uv[i * 2 + 1]);
            vc.addVertex(pos[i * 3], pos[i * 3 + 1] - oy, pos[i * 3 + 2], color, sp.getU(u), sp.getV(v), 0, light, nx, ny, nz);
        }

        /**
         * Renders a non-cube block (plants, torches, slabs, stairs...) with its real vanilla model. The model is
         * placed in the block's cross-section box: x and y unchanged, model z mapped onto the depth interval
         * the slice cuts through the block's z-w square.
         */
        void model(BlockState s, int x, int y, int z, int w, ClientLevel4 level, Column4 col) {
            if (!segment(z, w)) return;
            double u0 = segU0, u1 = segU1;
            boolean flip = zc < 0; // rotate the model 180 degrees about Y (keeps winding) when z' points toward -z
            double len = u1 - u0;
            float bx = (float) (x + relX), by = y - oy;
            float bz = (float) (u0 + relZ);

            var parts = SpriteCache.parts(s);
            int light = net.minecraft.util.LightCoordsUtil.pack(blockLight(emitters, emitterCount, x, y, z, w, s.getLightEmission()), skyLight(level, x, y + 1, z, w));
            for (var part : parts) {
                for (net.minecraft.core.Direction d3 : DIRS) {
                    for (var quad : part.getQuads(d3)) {
                        if (d3 != null && culled(d3, x, y, z, w, level, col)) continue;
                        emitQuad(quad, s, bx, by, bz, len, flip, light);
                    }
                }
            }
        }

        // ---- cut of the plane (-s*z + c*w = depth) with a unit z-w square ----
        double segU0, segU1;
        double segZ0, segW0, segZ1, segW1; // 4D (z, w) of the u0 and u1 ends

        /** Interval on the render z' axis covered by the slice of the unit square at (z, w), plus its ends. */
        boolean segment(int z, int w) {
            double nz = -zs, nw = zc;
            segU0 = Double.MAX_VALUE;
            segU1 = -Double.MAX_VALUE;
            int found = 0;
            if (Math.abs(nw) > 1.0E-9) for (int k = 0; k < 2; k++) {
                double zz = z + k, ww = (depth - nz * zz) / nw;
                if (ww >= w - 1.0E-9 && ww <= w + 1 + 1.0E-9) { found++; take(zz, Math.max(w, Math.min(w + 1, ww))); }
            }
            if (Math.abs(nz) > 1.0E-9) for (int k = 0; k < 2; k++) {
                double ww = w + k, zz = (depth - nw * ww) / nz;
                if (zz >= z - 1.0E-9 && zz <= z + 1 + 1.0E-9) { found++; take(Math.max(z, Math.min(z + 1, zz)), ww); }
            }
            return found >= 2 && segU1 - segU0 > 1.0E-5;
        }

        private void take(double zz, double ww) {
            double u = zc * zz + zs * ww;
            if (u < segU0) { segU0 = u; segZ0 = zz; segW0 = ww; }
            if (u > segU1) { segU1 = u; segZ1 = zz; segW1 = ww; }
        }

        /** The voxel (z, w) on the other side of the square's edge that contains point (pz, pw). */
        private static int[] across(int z, int w, double pz, double pw) {
            if (Math.abs(pz - z) < 1.0E-7) return new int[]{z - 1, w};
            if (Math.abs(pz - (z + 1)) < 1.0E-7) return new int[]{z + 1, w};
            if (Math.abs(pw - w) < 1.0E-7) return new int[]{z, w - 1};
            return new int[]{z, w + 1};
        }

        private static float fluidHeight(BlockState s, BlockState n, BlockState above) {
            if (n.getBlock() != s.getBlock()) return -1;
            if (above.getBlock() == s.getBlock()) return 1.0f;
            int lvl = n.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL);
            if (lvl >= 8) return 1.0f;
            return lvl == 0 ? 8f / 9f : (8 - lvl) / 9f;
        }

        /** Average height of the same fluid in the four voxels around a top corner (vanilla-style smoothing). */
        private float cornerHeight(BlockState s, ClientLevel4 level, int y, int xa, int xb, int z, int w, int[] o) {
            float sum = 0;
            int cnt = 0;
            int[][] zw = {{z, w}, {o[0], o[1]}};
            for (int xi : new int[]{xa, xb}) for (int[] q : zw) {
                BlockState n = level.getBlock(xi, y, q[0], q[1]);
                BlockState above = level.getBlock(xi, y + 1, q[0], q[1]);
                float h = fluidHeight(s, n, above);
                if (h >= 1.0f) return 1.0f;
                if (h >= 0) { sum += h; cnt++; }
            }
            return cnt == 0 ? 8f / 9f : sum / cnt;
        }

        /** Water and lava with sloped, smoothly connected surfaces (zw frames). */
        void fluid(BlockState s, int x, int y, int z, int w, ClientLevel4 level, int mask) {
            if (!segment(z, w)) return;
            int[] oA = across(z, w, segZ0, segW0), oB = across(z, w, segZ1, segW1);
            BlockState above = level.getBlock(x, y + 1, z, w);
            boolean topVisible = above.getBlock() != s.getBlock();
            float h00, h01, h10, h11; // (x0,u0) (x0,u1) (x1,u0) (x1,u1)
            if (!topVisible) {
                h00 = h01 = h10 = h11 = 1f;
            } else {
                h00 = cornerHeight(s, level, y, x - 1, x, z, w, oA);
                h01 = cornerHeight(s, level, y, x - 1, x, z, w, oB);
                h10 = cornerHeight(s, level, y, x, x + 1, z, w, oA);
                h11 = cornerHeight(s, level, y, x, x + 1, z, w, oB);
            }
            SpriteCache.Layer layer = SpriteCache.get(s).layers()[Direction4.UP.ordinal()][0];
            var sp = layer.sprite();
            int tint = layer.tint();
            VertexConsumer vc = buffer(layer.layer());
            int light = net.minecraft.util.LightCoordsUtil.pack(blockLight(emitters, emitterCount, x, y, z, w, s.getLightEmission()), skyLight(level, x, y + 1, z, w));
            float X0 = (float) (x + relX), X1 = X0 + 1, Y = y - oy;
            float U0 = (float) (segU0 + relZ), U1 = (float) (segU1 + relZ);
            if (topVisible) {
                int c = shadeColor(tint, 1f);
                fquad(vc, sp, c, light, 0, 1, 0,
                        X0, Y + h00, U0, 0, 0, X0, Y + h01, U1, 0, 1, X1, Y + h11, U1, 1, 1, X1, Y + h10, U0, 1, 0);
                // underside of the surface, seen from below the water
                fquad(vc, sp, c, light, 0, -1, 0,
                        X0, Y + h00, U0, 0, 0, X1, Y + h10, U0, 1, 0, X1, Y + h11, U1, 1, 1, X0, Y + h01, U1, 0, 1);
            }
            BlockState below = level.getBlock(x, y - 1, z, w);
            if (below.getBlock() != s.getBlock() && !BlockShapes4.fullOpaque(below)) {
                fquad(vc, sp, shadeColor(tint, 0.5f), light, 0, -1, 0,
                        X0, Y, U0, 0, 0, X1, Y, U0, 1, 0, X1, Y, U1, 1, 1, X0, Y, U1, 0, 1);
            }
            // x sides
            if (sideVisible(s, level.getBlock(x - 1, y, z, w))) fquad(vc, sp, shadeColor(tint, 0.6f), light, -1, 0, 0,
                    X0, Y, U0, 0, 1, X0, Y, U1, 1, 1, X0, Y + h01, U1, 1, 1 - h01, X0, Y + h00, U0, 0, 1 - h00);
            if (sideVisible(s, level.getBlock(x + 1, y, z, w))) fquad(vc, sp, shadeColor(tint, 0.6f), light, 1, 0, 0,
                    X1, Y, U1, 0, 1, X1, Y, U0, 1, 1, X1, Y + h10, U0, 1, 1 - h10, X1, Y + h11, U1, 0, 1 - h11);
            // z' sides
            if (sideVisible(s, level.getBlock(x, y, oA[0], oA[1]))) fquad(vc, sp, shadeColor(tint, 0.8f), light, 0, 0, -1,
                    X1, Y, U0, 0, 1, X0, Y, U0, 1, 1, X0, Y + h00, U0, 1, 1 - h00, X1, Y + h10, U0, 0, 1 - h10);
            if (sideVisible(s, level.getBlock(x, y, oB[0], oB[1]))) fquad(vc, sp, shadeColor(tint, 0.8f), light, 0, 0, 1,
                    X0, Y, U1, 0, 1, X1, Y, U1, 1, 1, X1, Y + h11, U1, 1, 1 - h11, X0, Y + h01, U1, 0, 1 - h01);
        }

        private static boolean sideVisible(BlockState s, BlockState n) {
            return n.getBlock() != s.getBlock() && !BlockShapes4.fullOpaque(n);
        }

        private static int shadeColor(int tint, float shade) {
            int r = (int) (((tint >> 16) & 255) * shade), g = (int) (((tint >> 8) & 255) * shade), b = (int) ((tint & 255) * shade);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        private static void fquad(VertexConsumer vc, net.minecraft.client.renderer.texture.TextureAtlasSprite sp, int color, int light,
                                  float nx, float ny, float nz, float... v) {
            for (int i = 0; i < 4; i++) {
                int o = i * 5;
                vc.addVertex(v[o], v[o + 1], v[o + 2], color, sp.getU(clamp01(v[o + 3])), sp.getV(clamp01(v[o + 4])), 0, light, nx, ny, nz);
            }
        }

        private static final net.minecraft.core.Direction[] DIRS = {null, net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.UP,
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST};

        /** A cull-face quad is hidden when the 4D neighbour it faces (along x, y, or the dominant of z/w) is opaque. */
        private boolean culled(net.minecraft.core.Direction d3, int x, int y, int z, int w, ClientLevel4 level, Column4 col) {
            int dx = 0, dy = 0, dz = 0, dw = 0;
            switch (d3) {
                case UP -> dy = 1;
                case DOWN -> dy = -1;
                case EAST -> dx = 1;
                case WEST -> dx = -1;
                default -> {
                    int sign = d3 == net.minecraft.core.Direction.SOUTH ? 1 : -1;
                    if (Math.abs(zc) >= Math.abs(zs)) dz = sign * (zc >= 0 ? 1 : -1);
                    else dw = sign * (zs >= 0 ? 1 : -1);
                }
            }
            return BlockShapes4.fullOpaque(level.getBlock(x + dx, y + dy, z + dz, w + dw));
        }

        private void emitQuad(net.minecraft.client.resources.model.geometry.BakedQuad q, BlockState s, float bx, float by, float bz,
                              double len, boolean flip, int light) {
            var mi = q.materialInfo();
            int tint = mi.tintIndex() >= 0 ? SpriteCache.tint(s, mi.tintIndex()) : -1;
            net.minecraft.core.Direction shadeDir = mi.shadeDirectionOverride() != null ? mi.shadeDirectionOverride() : q.direction();
            float shade = switch (shadeDir) {
                case UP -> 1.0f;
                case DOWN -> 0.5f;
                case NORTH, SOUTH -> 0.8f;
                default -> 0.6f;
            };
            int r = (int) (((tint >> 16) & 255) * shade), g = (int) (((tint >> 8) & 255) * shade), b = (int) ((tint & 255) * shade);
            int color = 0xFF000000 | (r << 16) | (g << 8) | b;
            int lightE = mi.lightEmission() > 0 ? net.minecraft.util.LightCoordsUtil.pack(Math.max(mi.lightEmission(), (light >> 4) & 15), (light >> 20) & 15) : light;
            VertexConsumer vc = buffer(mi.layer());
            var n = q.direction().getUnitVec3f();
            float nx = flip ? -n.x() : n.x(), nz = flip ? -n.z() : n.z();
            for (int i = 0; i < 4; i++) {
                var p = q.position(i);
                float mx = flip ? 1 - p.x() : p.x(), mz = flip ? 1 - p.z() : p.z();
                long uv = q.packedUV(i);
                vc.addVertex(bx + mx, by + p.y(), (float) (bz + mz * len), color,
                        net.minecraft.client.model.geom.builders.UVPair.unpackU(uv), net.minecraft.client.model.geom.builders.UVPair.unpackV(uv),
                        0, lightE, nx, n.y(), nz);
            }
        }

        private int light(Direction4 dir, int emission) {
            int x = vx + dir.dx, y = vy + dir.dy, z = vz + dir.dz, w = vw + dir.dw;
            return LightCoordsUtil.pack(blockLight(emitters, emitterCount, x, y, z, w, emission), skyLight(level, x, y, z, w));
        }
    }

    /** 4D sky light (see {@link ClientLevel4#skyLight}). */
    public static int skyLight(ClientLevel4 level, int x, int y, int z, int w) {
        return level.skyLight(x, y, z, w);
    }

    /** 4D block light from a flat {x, y, z, w, emission} list (allocation-free). */
    public static int blockLight(int[] em, int count, int x, int y, int z, int w, int base) {
        int block = base;
        for (int i = 0; i < count; i += 5) {
            int d = Math.abs(em[i] - x) + Math.abs(em[i + 1] - y) + Math.abs(em[i + 2] - z) + Math.abs(em[i + 3] - w);
            int l = em[i + 4] - d;
            if (l > block) block = l;
        }
        return Math.max(0, Math.min(15, block));
    }

    /** 4D block light: emitters attenuate by 4D Manhattan distance. */
    public static int blockLight(long[][] emitters, int x, int y, int z, int w, int base) {
        int block = base;
        if (emitters != null) {
            for (long[] list : emitters) {
                if (list == null) continue;
                for (int i = 0; i < list.length; i += 2) {
                    BlockPos4 e = BlockPos4.of(list[i]);
                    int d = Math.abs(e.x() - x) + Math.abs(e.y() - y) + Math.abs(e.z() - z) + Math.abs(e.w() - w);
                    int l = (int) list[i + 1] - d;
                    if (l > block) block = l;
                }
            }
        }
        return Math.max(0, Math.min(15, block));
    }

    /**
     * Interval [u0, u1] on the local z' axis that the slice cuts through the unit z-w square at (z, w), for a
     * zw frame whose local z' axis is (zc, zs) in (z, w). Returns null if the slice misses the square.
     */
    public static double[] cut(double zc, double zs, double depth, int z, int w) {
        double nz = -zs, nw = zc;
        double u0 = Double.MAX_VALUE, u1 = -Double.MAX_VALUE;
        int found = 0;
        if (Math.abs(nw) > 1.0E-9) for (int k = 0; k < 2; k++) {
            double zz = z + k, ww = (depth - nz * zz) / nw;
            if (ww >= w - 1.0E-9 && ww <= w + 1 + 1.0E-9) { double u = zc * zz + zs * ww; u0 = Math.min(u0, u); u1 = Math.max(u1, u); found++; }
        }
        if (Math.abs(nz) > 1.0E-9) for (int k = 0; k < 2; k++) {
            double ww = w + k, zz = (depth - nw * ww) / nz;
            if (zz >= z - 1.0E-9 && zz <= z + 1 + 1.0E-9) { double u = zc * zz + zs * ww; u0 = Math.min(u0, u); u1 = Math.max(u1, u); found++; }
        }
        return found >= 2 && u1 - u0 > 1.0E-5 ? new double[]{u0, u1} : null;
    }

    private static float clamp01(float f) {
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    /** Vanilla-like directional shade, generalised to arbitrary in-slice normals (W faces included). */
    private static float shade(float nx, float ny, float nz) {
        float up = ny > 0 ? ny * ny : 0, down = ny < 0 ? ny * ny : 0;
        return Math.min(1f, nx * nx * 0.6f + nz * nz * 0.8f + up * 1.0f + down * 0.5f);
    }

    private static Sink build(AddSectionGeometryEvent.SectionRenderingContext ctx, BlockPos origin, ClientState4.MeshParams params, ClientLevel4 level) {
        SliceFrame f = params.frame();
        double depth = params.depth();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        double mox = params.offX(), moz = params.offZ();
        Slicer slicer = SLICER.get();
        slicer.setPlane(f, depth, ox - mox, oz - moz);

        double nx = f.normalX(), nz = f.normalZ(), nw = f.normalW();
        double h = 0.5 * (Math.abs(nx) + Math.abs(nz) + Math.abs(nw));
        // dominant axis m (0=x, 1=z, 2=w in horizontal index space)
        double[] n = {nx, nz, nw};
        int m = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(n[i]) > Math.abs(n[m])) m = i;
        int a = m == 0 ? 1 : 0, b = m == 2 ? 1 : 2;

        // Preimage of the section's render square in 4D, padded by one voxel.
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int c = 0; c < 4; c++) {
            double lx = ox - mox + ((c & 1) != 0 ? 16 : 0), lz = oz - moz + ((c & 2) != 0 ? 16 : 0);
            var p = f.toWorld(lx, 0, lz, depth);
            double[] q = {p.x(), p.z(), p.w()};
            for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], q[i]); hi[i] = Math.max(hi[i], q[i]); }
        }
        int a0 = (int) Math.floor(lo[a]) - 1, a1 = (int) Math.floor(hi[a]) + 1;
        int b0 = (int) Math.floor(lo[b]) - 1, b1 = (int) Math.floor(hi[b]) + 1;

        Sink sink = new Sink();
        sink.ctx = ctx;
        sink.level = level;
        sink.oy = oy;
        sink.zc = f.get(1, 1);
        sink.zs = f.get(2, 1);
        sink.depth = depth;
        sink.relX = mox - ox;
        sink.relZ = moz - oz;
        boolean zwFrame = Math.abs(f.get(0, 0) - 1) < 1.0E-9 && Math.abs(f.normalX()) < 1.0E-9;
        sink.modelPath = zwFrame;

        // gather light emitters that can reach this section (flat int list, filtered by distance)
        java.util.ArrayList<long[]> em = new java.util.ArrayList<>();
        int cxa = Math.floorDiv((int) Math.floor(Math.min(lo[0], hi[0])) - 15, 16), cxb = Math.floorDiv((int) Math.floor(hi[0]) + 15, 16);
        int cza = Math.floorDiv((int) Math.floor(lo[1]) - 15, 16), czb = Math.floorDiv((int) Math.floor(hi[1]) + 15, 16);
        int cwa = Math.floorDiv((int) Math.floor(lo[2]) - 15, 16), cwb = Math.floorDiv((int) Math.floor(hi[2]) + 15, 16);
        if ((long) (cxb - cxa + 1) * (czb - cza + 1) * (cwb - cwa + 1) <= 512) {
            for (int cw = cwa; cw <= cwb; cw++) for (int cz = cza; cz <= czb; cz++) for (int cx = cxa; cx <= cxb; cx++) {
                long[] list = level.emitters(Column4.key(cx, cz, cw));
                if (list != null) em.add(list);
            }
        }
        int[] flat = new int[0];
        int fn = 0;
        for (long[] list : em) {
            for (int i = 0; i < list.length; i += 2) {
                BlockPos4 e = BlockPos4.of(list[i]);
                if (e.x() < lo[0] - 16 || e.x() > hi[0] + 16 || e.z() < lo[1] - 16 || e.z() > hi[1] + 16
                        || e.w() < lo[2] - 16 || e.w() > hi[2] + 16 || e.y() < oy - 16 || e.y() > oy + 32) continue;
                if (fn + 5 > flat.length) flat = java.util.Arrays.copyOf(flat, Math.max(20, flat.length * 2));
                flat[fn++] = e.x(); flat[fn++] = e.y(); flat[fn++] = e.z(); flat[fn++] = e.w(); flat[fn++] = (int) list[i + 1];
            }
        }
        sink.emitters = flat;
        sink.emitterCount = fn;

        int[] idx = new int[3];
        int yTop = Math.min(oy + 16, Column4.HEIGHT);
        Column4 cached = null;
        for (int ia = a0; ia <= a1; ia++) {
            for (int ib = b0; ib <= b1; ib++) {
                double ca = ia + 0.5, cb = ib + 0.5;
                double rest = depth - n[a] * ca - n[b] * cb;
                // centre of voxel along m: |n_m * c_m - rest| < h
                double t0 = (rest - h) / n[m], t1 = (rest + h) / n[m];
                if (t0 > t1) { double t = t0; t0 = t1; t1 = t; }
                int k0 = (int) Math.ceil(t0 - 0.5 - 1.0E-9), k1 = (int) Math.floor(t1 - 0.5 + 1.0E-9);
                for (int km = k0; km <= k1; km++) {
                    idx[a] = ia;
                    idx[b] = ib;
                    idx[m] = km;
                    int x = idx[0], z = idx[1], w = idx[2];
                    // assign each voxel to exactly one section by its projected centre
                    double cx = x + 0.5, cz = z + 0.5, cw = w + 0.5;
                    double rx = f.localX(cx, cz, cw) + mox, rz = f.localZ(cx, cz, cw) + moz;
                    if (rx < ox || rx >= ox + 16 || rz < oz || rz >= oz + 16) continue;
                    if (cached == null || cached.cx != (x >> 4) || cached.cz != (z >> 4) || cached.cw != (w >> 4)) {
                        cached = level.getColumnAt(x, z, w);
                        if (cached == null) continue;
                    }
                    meshColumnRun(slicer, sink, level, cached, x, z, w, oy, yTop);
                }
            }
        }
        return sink;
    }

    private static void meshColumnRun(Slicer slicer, Sink sink, ClientLevel4 level, Column4 col, int x, int z, int w, int y0, int y1) {
        int lx = x & 15, lz = z & 15, lw = w & 15;
        byte[] exposure = null;
        int exposureSy = -1;
        for (int y = y0; y < y1; y++) {
            int sy = y >> 4;
            if (sy != exposureSy) {
                exposure = level.exposure(col, sy);
                exposureSy = sy;
            }
            if (exposure.length == 0) { y = (sy << 4) + 15; continue; }
            int mask = exposure[Section4.index(lx, y & 15, lz, lw)] & 0xFF;
            if (mask == 0) continue;
            BlockState s = col.get(lx, y, lz, lw);
            if (s.isAir()) continue;
            double[] box = BlockShapes4.render(s);
            if (box == null) continue;
            if (sink.modelPath && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                sink.fluid(s, x, y, z, w, level, mask);
                continue;
            }
            if (sink.modelPath && !BlockShapes4.fullShape(s) && s.getFluidState().isEmpty()
                    && s.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                sink.vx = x; sink.vy = y; sink.vz = z; sink.vw = w;
                sink.model(s, x, y, z, w, level, col);
                continue;
            }
            sink.state = s;
            sink.faces = SpriteCache.get(s);
            sink.vx = x;
            sink.vy = y;
            sink.vz = z;
            sink.vw = w;
            slicer.slice(x + box[0], y + box[1], z + box[2], w + box[3], x + box[4], y + box[5], z + box[6], w + box[7],
                    mask, x, y, z, w, sink);
        }
    }

    private static boolean isFullBox(double[] b) {
        return b[0] <= 0 && b[1] <= 0 && b[2] <= 0 && b[3] <= 0 && b[4] >= 1 && b[5] >= 1 && b[6] >= 1 && b[7] >= 1;
    }
}
