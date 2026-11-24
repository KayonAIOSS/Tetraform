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
        event.addRenderer(ctx -> {
            try {
                build(ctx, origin, params, level);
            } catch (Throwable t) {
                dev.tetraform.Tetraform.LOGGER.error("4D slice meshing failed for section {}", origin, t);
            }
        });
    }

    private static final class Sink implements Slicer.FaceSink {
        AddSectionGeometryEvent.SectionRenderingContext ctx;
        final EnumMap<ChunkSectionLayer, VertexConsumer> buffers = new EnumMap<>(ChunkSectionLayer.class);
        BlockState state;
        SpriteCache.Faces faces;
        ClientLevel4 level;
        int vx, vy, vz, vw; // voxel
        int oy;
        long[][] emitters;

        VertexConsumer buffer(ChunkSectionLayer l) {
            VertexConsumer c = buffers.get(l);
            if (c == null) {
                c = ctx.getOrCreateChunkBuffer(l);
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

        private int light(Direction4 dir, int emission) {
            int x = vx + dir.dx, y = vy + dir.dy, z = vz + dir.dz, w = vw + dir.dw;
            return LightCoordsUtil.pack(blockLight(emitters, x, y, z, w, emission), skyLight(level, x, y, z, w));
        }
    }

    /** 4D sky light: full above the (x, z, w) column's surface, fading with depth below it. */
    public static int skyLight(ClientLevel4 level, int x, int y, int z, int w) {
        int h = level.heightAt(x, z, w);
        return y >= h ? 15 : Math.max(0, 15 - 3 * (h - y));
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

    private static float clamp01(float f) {
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    /** Vanilla-like directional shade, generalised to arbitrary in-slice normals (W faces included). */
    private static float shade(float nx, float ny, float nz) {
        float up = ny > 0 ? ny * ny : 0, down = ny < 0 ? ny * ny : 0;
        return Math.min(1f, nx * nx * 0.6f + nz * nz * 0.8f + up * 1.0f + down * 0.5f);
    }

    private static void build(AddSectionGeometryEvent.SectionRenderingContext ctx, BlockPos origin, ClientState4.MeshParams params, ClientLevel4 level) {
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

        // gather light emitters from nearby columns
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
        sink.emitters = em.isEmpty() ? null : em.toArray(new long[0][]);

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
    }

    private static void meshColumnRun(Slicer slicer, Sink sink, ClientLevel4 level, Column4 col, int x, int z, int w, int y0, int y1) {
        int lx = x & 15, lz = z & 15, lw = w & 15;
        for (int y = y0; y < y1; y++) {
            BlockState s = col.get(lx, y, lz, lw);
            if (s.isAir()) continue;
            double[] box = BlockShapes4.render(s);
            if (box == null) continue;
            boolean fullOpaque = BlockShapes4.fullOpaque(s);
            int mask = 0;
            for (Direction4 d : Direction4.VALUES) {
                // only faces that touch the voxel boundary can be hidden by a neighbour
                boolean onBoundary = d.sign > 0 ? box[4 + d.axis] >= 1 : box[d.axis] <= 0;
                if (onBoundary) {
                    BlockState ns;
                    int nlx = lx + d.dx, nlz = lz + d.dz, nlw = lw + d.dw, ny = y + d.dy;
                    if (nlx >= 0 && nlx < 16 && nlz >= 0 && nlz < 16 && nlw >= 0 && nlw < 16) ns = col.get(nlx, ny, nlz, nlw);
                    else ns = level.getBlock(x + d.dx, ny, z + d.dz, w + d.dw);
                    if (BlockShapes4.fullOpaque(ns)) continue;
                    if (!fullOpaque && ns.getBlock() == s.getBlock() && BlockShapes4.render(ns) == box && !s.getFluidState().isEmpty()) continue;
                    if (!fullOpaque && ns == s && box == BlockShapes4.render(s) && isFullBox(box)) continue; // glass next to glass
                }
                mask |= 1 << d.ordinal();
            }
            if (mask == 0) continue;
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
