package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.world.W4;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Projectiles only hit entities in their own W slice. */
@Mixin(Projectile.class)
public abstract class ProjectileSliceMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void tetraform$slice(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        Projectile self = (Projectile) (Object) this;
        if (Tetraform.is4D(self.level()) && !W4.sameSlice(self, entity)) cir.setReturnValue(false);
    }
}
