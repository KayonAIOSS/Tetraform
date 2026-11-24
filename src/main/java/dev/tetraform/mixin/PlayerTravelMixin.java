package dev.tetraform.mixin;

import dev.tetraform.Hooks4;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the tesseract the local player is moved by the 4D physics engine, so vanilla 3D travel is skipped. */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void tetraform$travel(Vec3 input, CallbackInfo ci) {
        if (Hooks4.skipVanillaTravel((Player) (Object) this)) ci.cancel();
    }
}
