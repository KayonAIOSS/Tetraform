package dev.tetraform.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.tetraform.client.ClientState4;
import dev.tetraform.entity.ProxyEntity4;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** Draws a 4D entity through the vanilla entity pipeline (so shader packs render and shadow it). */
public final class ProxyRenderer4 extends EntityRenderer<ProxyEntity4, ProxyRenderer4.State> {
    public static final class State extends EntityRenderState {
        int viewId;
        float pt;
    }

    public ProxyRenderer4(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ProxyEntity4 entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.viewId = entity.viewId;
        state.pt = partialTicks;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        ClientState4.EntityState es = ClientState4.ENTITIES.get(state.viewId);
        if (es == null) return;
        // The pose is at the proxy's position relative to this pass's camera; undo that, since the body is
        // emitted camera-relative from the exact 4D state.
        Vec3 cam = camera.pos;
        poseStack.pushPose();
        poseStack.translate(cam.x - state.x, cam.y - state.y, cam.z - state.z);
        EntityRenderer4.submitBody(es, state.pt, poseStack, collector, cam);
        poseStack.popPose();
    }
}
