package dev.tetraform.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.tetraform.client.ClientState4;
import dev.tetraform.entity.Entity4Type;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Direction4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Slicer;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import dev.tetraform.world.BlockShapes4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders dynamic 4D objects (mobs, items, other players, the block selection and crack overlay) each frame.
 * Each one is sliced by the viewer's hyperplane, and the faces go through standard vanilla render types, which
 * shader packs pick up as entity geometry. Rotated bodies are sliced exactly: the hyperplane is transformed into
 * each body's local frame instead of approximating the body with an axis-aligned box.
 */
public final class EntityRenderer4 {
    private EntityRenderer4() {}

    private static final Slicer SLICER = new Slicer();

    private record Poly(TextureAtlasSprite sprite, int n, float[] pos, float[] uv, float nx, float ny, float nz, int color, int light) {}

    public static void onSubmit(SubmitCustomGeometryEvent event) {
        if (!ClientState4.active || !ClientState4.hasPos) return;
        Minecraft mc = Minecraft.getInstance();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        ClientState4.MeshParams mp = ClientState4.mesh;
        SliceFrame m = mp.frame();
        Vec4 eye = Vec4.lerp(ClientState4.prevPos, ClientState4.pos, pt);
        double depth = m.depthOf(eye);

        List<Poly> solid = new ArrayList<>();
        for (ClientState4.EntityState es : ClientState4.ENTITIES.values()) {
            Net4.EntityView now = es.now, prev = es.prev == null ? now : es.prev;
            Entity4Type type = Entity4Type.VALUES[Math.floorMod(now.type(), Entity4Type.VALUES.length)];
            Vec4 p = new Vec4(prev.x() + (now.x() - prev.x()) * pt, prev.y() + (now.y() - prev.y()) * pt,
                    prev.z() + (now.z() - prev.z()) * pt, prev.w() + (now.w() - prev.w()) * pt);
            if (Math.abs(m.depthOf(p) - depth) > 3) continue;
            BlockState itemTex = null;
            if (type == Entity4Type.ITEM) {
                Item item = BuiltInRegistries.ITEM.byId(now.extra());
                itemTex = item instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : null;
            }
            SliceFrame body = composite(now.frame(), m);
            double ccx = m.localX(p.x(), p.z(), p.w()), ccz = m.localZ(p.x(), p.z(), p.w());
            Vec4 n = m.normal();
            double localDepth = depth - (n.x() * p.x() + n.z() * p.z() + n.w() * p.w());
            SLICER.setPlane(body, localDepth, cam.x - ccx, cam.z - ccz);
            int color = now.hurt() > 0 ? 0xFFFF7070 : 0xFFFFFFFF;
            int sky = p.y() + 1 >= ClientState4.LEVEL.heightAt((int) Math.floor(p.x()), (int) Math.floor(p.z()), (int) Math.floor(p.w())) ? 15 : 5;
            int light = LightCoordsUtil.pack(type == Entity4Type.STALKER ? 7 : 0, sky);
            double dy = p.y() - cam.y;
            for (Entity4Type.Part part : type.parts(itemTex)) {
                double[] b = part.box();
                SpriteCache.Faces faces = SpriteCache.get(part.texture());
                SLICER.slice(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], 0xFF, 0, 0, 0, 0,
                        (dir, cnt, pos, uv, nx, ny, nz) -> {
                            float[] pp = new float[cnt * 3];
                            for (int i = 0; i < cnt; i++) {
                                pp[i * 3] = pos[i * 3];
                                pp[i * 3 + 1] = (float) (pos[i * 3 + 1] + dy);
                                pp[i * 3 + 2] = pos[i * 3 + 2];
                            }
                            float[] uu = normalizeUv(uv, cnt);
                            SpriteCache.Layer l = faces.layers()[dir.ordinal()][0];
                            int c = l.tint() == -1 ? color : mul(color, l.tint());
                            solid.add(new Poly(l.sprite(), cnt, pp, uu, nx, ny, nz, c, light));
                        });
            }
        }

        PoseStack ps = event.getPoseStack();
        if (!solid.isEmpty()) {
            event.getSubmitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS),
                    (pose, buf) -> { for (Poly poly : solid) emit(pose, buf, poly); });
        }
        submitSelection(event, m, depth, cam);
    }

    private static void submitSelection(SubmitCustomGeometryEvent event, SliceFrame m, double depth, Vec3 cam) {
        var hit = ClientState4.target;
        if (hit == null || ClientState4.targetEntity >= 0) return;
        BlockPos4 bp = hit.pos();
        BlockState s = ClientState4.LEVEL.getBlock(bp);
        if (s.isAir()) return;
        double[] b = BlockShapes4.render(s);
        double e = 0.004;
        TextureAtlasSprite white = SpriteCache.get(Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState()).layers()[0][0].sprite();
        List<Poly> polys = new ArrayList<>();
        List<Poly> crack = new ArrayList<>();
        SLICER.setPlane(m, depth, cam.x, cam.z);
        double dy = -cam.y;
        SLICER.slice(bp.x() + b[0] - e, bp.y() + b[1] - e, bp.z() + b[2] - e, bp.w() + b[3] - e,
                bp.x() + b[4] + e, bp.y() + b[5] + e, bp.z() + b[6] + e, bp.w() + b[7] + e, 0xFF, bp.x(), bp.y(), bp.z(), bp.w(),
                (dir, cnt, pos, uv, nx, ny, nz) -> {
                    float[] pp = pos.clone();
                    for (int i = 0; i < cnt; i++) pp[i * 3 + 1] += (float) dy;
                    float[] uu = uv.clone();
                    for (int i = 0; i < cnt * 2; i++) uu[i] = Math.max(0, Math.min(1, uu[i]));
                    polys.add(new Poly(white, cnt, pp, uu, nx, ny, nz, 0x40FFFFFF, LightCoordsUtil.pack(15, 15)));
                    crack.add(new Poly(null, cnt, pp, uu, nx, ny, nz, 0xFFFFFFFF, LightCoordsUtil.pack(15, 15)));
                });
        if (polys.isEmpty()) return;
        PoseStack ps = event.getPoseStack();
        event.getSubmitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS),
                (pose, buf) -> { for (Poly p : polys) emit(pose, buf, p); });
        if (ClientState4.breaking != null && ClientState4.breaking.equals(bp) && ClientState4.breakProgress > 0) {
            int stage = Math.min(9, (int) (ClientState4.breakProgress * 10));
            RenderType rt = RenderTypes.crumbling(Identifier.withDefaultNamespace("textures/block/destroy_stage_" + stage + ".png"));
            event.getSubmitNodeCollector().submitCustomGeometry(ps, rt, (pose, buf) -> { for (Poly p : crack) emit(pose, buf, p); });
        }
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer buf, Poly p) {
        for (int start = 1; start < p.n - 1; start += 2) {
            vertex(pose, buf, p, 0);
            vertex(pose, buf, p, start);
            vertex(pose, buf, p, start + 1);
            vertex(pose, buf, p, Math.min(start + 2, p.n - 1));
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Poly p, int i) {
        float u = p.uv[i * 2], v = p.uv[i * 2 + 1];
        if (p.sprite != null) {
            u = p.sprite.getU(u);
            v = p.sprite.getV(v);
        }
        buf.addVertex(pose, p.pos[i * 3], p.pos[i * 3 + 1], p.pos[i * 3 + 2])
                .setColor(p.color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(p.light)
                .setNormal(pose, p.nx, p.ny, p.nz);
    }

    /** Stretches a face's UVs over the whole sprite (entity parts are not block-aligned). */
    private static float[] normalizeUv(float[] uv, int n) {
        float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            u0 = Math.min(u0, uv[i * 2]); u1 = Math.max(u1, uv[i * 2]);
            v0 = Math.min(v0, uv[i * 2 + 1]); v1 = Math.max(v1, uv[i * 2 + 1]);
        }
        float du = Math.max(1.0E-4f, u1 - u0), dv = Math.max(1.0E-4f, v1 - v0);
        float[] out = new float[n * 2];
        for (int i = 0; i < n; i++) {
            out[i * 2] = (uv[i * 2] - u0) / du;
            out[i * 2 + 1] = (uv[i * 2 + 1] - v0) / dv;
        }
        return out;
    }

    private static int mul(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255;
        int g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255;
        int bl = (a & 255) * (b & 255) / 255;
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    /** M' = R^T M: the viewer frame expressed in an entity's local frame. */
    private static SliceFrame composite(float[] r, SliceFrame m) {
        double[] mv = m.values();
        double[] out = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double v = 0;
            for (int k = 0; k < 3; k++) v += r[k * 3 + i] * mv[k * 3 + j];
            out[i * 3 + j] = v;
        }
        return new SliceFrame(out);
    }
}
