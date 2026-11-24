package dev.tetraform.mixin;

import dev.tetraform.client.EntityView4;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Places every vanilla entity at its position inside the viewer's slice (vanilla models, skins and all). */
@Mixin(EntityRenderer.class)
public abstract class EntityRenderTransformMixin<T extends Entity, S extends EntityRenderState> {
    @Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
    private void tetraform$toSlice(T entity, float partialTicks, CallbackInfoReturnable<S> cir) {
        EntityView4.transform(entity, cir.getReturnValue());
    }
}
