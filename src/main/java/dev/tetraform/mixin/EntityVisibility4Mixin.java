package dev.tetraform.mixin;

import dev.tetraform.client.EntityView4;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An entity is drawn only if its 4D body crosses the viewer's slice. */
@Mixin(LevelExtractor.class)
public abstract class EntityVisibility4Mixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void tetraform$visible(Entity entity, Frustum frustum, double camX, double camY, double camZ, float pt, long fade,
                                   CallbackInfoReturnable<Boolean> cir) {
        Boolean v = EntityView4.visible(entity, pt);
        if (v != null) cir.setReturnValue(v);
    }
}
