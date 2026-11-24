package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player's vanilla position in the tesseract is their 4D position expressed in their own slice frame. Rotating
 * the slice moves that position instantly and legitimately, so the vanilla speed check is skipped there; the 4D
 * position has its own server-side sanity check instead.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
    private void tetraform$noMoveCheck(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
        if (Tetraform.isTesseract(this.player.level())) cir.setReturnValue(false);
    }

    @Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
    private void tetraform$noSupportSpam(CallbackInfo ci) {
        if (Tetraform.isTesseract(this.player.level())) ci.cancel();
    }
}
