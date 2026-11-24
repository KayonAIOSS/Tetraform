package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.client.ClientChunks4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In 4D levels the client manages its own render chunks around the camera (see {@link ClientChunks4}). */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListener4Mixin {
    private static boolean tetraform$in4D() {
        return Minecraft.getInstance().isSameThread() && Tetraform.is4D(Minecraft.getInstance().level);
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void tetraform$chunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (tetraform$in4D()) ClientChunks4.onChunkPacket(packet);
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), cancellable = true)
    private void tetraform$forget(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (tetraform$in4D() && !ClientChunks4.allowForget) ci.cancel();
    }

    @Inject(method = "handleSetChunkCacheCenter", at = @At("HEAD"), cancellable = true)
    private void tetraform$center(ClientboundSetChunkCacheCenterPacket packet, CallbackInfo ci) {
        if (tetraform$in4D()) ci.cancel();
    }
}
