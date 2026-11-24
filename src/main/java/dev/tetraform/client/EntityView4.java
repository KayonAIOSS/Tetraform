package dev.tetraform.client;

import dev.tetraform.math.SliceFrame;
import dev.tetraform.world.W4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * Client-side placement of vanilla entities in a 4D level. Entities keep their true (x, y, z) plus a synced W;
 * each viewer sees them at their position inside its own slice, and only if their 4D body crosses that slice.
 * Facing is left untouched: turning your view into W doesn't make anyone else appear to turn.
 */
public final class EntityView4 {
    private EntityView4() {}

    private static boolean applies(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        return ClientState4.active && ClientState4.hasPos && e != mc.player && e.level() == mc.level;
    }

    /** null = not a 4D case (use vanilla logic). */
    public static Boolean visible(Entity e, float pt) {
        if (!applies(e)) return null;
        ClientState4.MeshParams mp = ClientState4.mesh;
        SliceFrame f = mp.frame();
        double x = Mth.lerp(pt, e.xOld, e.getX()), z = Mth.lerp(pt, e.zOld, e.getZ()), w = W4.get(e);
        double hw = Math.max(0.2, e.getBbWidth() / 2);
        double depth = f.depthOf(ClientState4.pos);
        if (f.isAxisAligned()) depth = mp.depth();
        double h = hw * (Math.abs(f.normalX()) + Math.abs(f.normalZ()) + Math.abs(f.normalW()));
        double s = f.normalX() * x + f.normalZ() * z + f.normalW() * w - depth;
        if (Math.abs(s) > h + 1.0E-3) return false;
        // close enough to the camera (render distance), measured in render space
        double rx = mp.renderX(x, z, w) - Minecraft.getInstance().player.getX();
        double rz = mp.renderZ(x, z, w) - Minecraft.getInstance().player.getZ();
        double max = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return rx * rx + rz * rz < max * max;
    }

    public static void transform(Entity e, EntityRenderState state) {
        if (state == null || !applies(e)) return;
        ClientState4.MeshParams mp = ClientState4.mesh;
        double w = W4.get(e);
        double x = state.x, z = state.z;
        state.x = mp.renderX(x, z, w);
        state.z = mp.renderZ(x, z, w);
    }
}
