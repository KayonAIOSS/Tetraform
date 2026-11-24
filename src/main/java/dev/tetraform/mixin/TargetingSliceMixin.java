package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.world.W4;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A creature only perceives entities in its own W slice: step out of its slice and you vanish from its view. */
@Mixin(TargetingConditions.class)
public abstract class TargetingSliceMixin {
    @Inject(method = "test", at = @At("RETURN"), cancellable = true)
    private void tetraform$slice(ServerLevel level, LivingEntity targeter, LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && targeter != null && Tetraform.is4D(level) && !W4.sameSlice(targeter, target)) cir.setReturnValue(false);
    }
}
