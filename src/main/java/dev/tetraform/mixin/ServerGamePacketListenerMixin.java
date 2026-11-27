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
        if (Tetraform.is4D(this.player.level())) cir.setReturnValue(false);
    }

    /**
     * Using an item in the air (eating, bows, boats, buckets, ender pearls, eyes of ender, modded items) runs in
     * the player's own W slice, so its ray traces and block changes see the 4D world.
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "handleUseItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private net.minecraft.world.InteractionResult tetraform$useItemInSlice(net.minecraft.server.level.ServerPlayerGameMode gameMode, ServerPlayer p,
                                                                           net.minecraft.world.level.Level level, net.minecraft.world.item.ItemStack stack,
                                                                           net.minecraft.world.InteractionHand hand) {
        dev.tetraform.world.Access4.Mapper m = dev.tetraform.world.Access4.forEntity(level, p);
        if (m == null) return gameMode.useItem(p, level, stack, hand);
        dev.tetraform.world.Access4.Mapper prev = dev.tetraform.world.Access4.push(m);
        try {
            return gameMode.useItem(p, level, stack, hand);
        } finally {
            dev.tetraform.world.Access4.pop(prev);
        }
    }

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("RETURN"))
    private void tetraform$teleport4(net.minecraft.world.entity.PositionMoveRotation dest, java.util.Set<net.minecraft.world.entity.Relative> rel, CallbackInfo ci) {
        dev.tetraform.world.ServerWorld4.afterVanillaTeleport(this.player);
    }

    @Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
    private void tetraform$noSupportSpam(CallbackInfo ci) {
        if (Tetraform.is4D(this.player.level())) ci.cancel();
    }
}
