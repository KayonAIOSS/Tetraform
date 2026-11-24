package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.world.W4;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Melee attacks can't reach across W. */
@Mixin(Mob.class)
public abstract class MobAttackSliceMixin {
    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void tetraform$slice(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (Tetraform.is4D(level) && !W4.sameSlice((Mob) (Object) this, target)) cir.setReturnValue(false);
    }
}
