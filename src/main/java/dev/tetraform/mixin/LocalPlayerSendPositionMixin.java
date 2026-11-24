package dev.tetraform.mixin;

import dev.tetraform.client.ClientState4;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The local player's vanilla position is in render space (the camera), but the server must see true world
 * (x, y, z) so vanilla mobs, tracking and spawning work. Positions are swapped to world coordinates only while
 * movement packets are built.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSendPositionMixin {
    @Unique
    private double tetraform$rx, tetraform$ry, tetraform$rz;
    @Unique
    private boolean tetraform$swapped;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void tetraform$toWorld(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        tetraform$swapped = ClientState4.active && ClientState4.hasPos;
        if (!tetraform$swapped) return;
        tetraform$rx = self.getX();
        tetraform$ry = self.getY();
        tetraform$rz = self.getZ();
        self.setPosRaw(ClientState4.pos.x(), ClientState4.pos.y(), ClientState4.pos.z());
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void tetraform$toRender(CallbackInfo ci) {
        if (!tetraform$swapped) return;
        ((LocalPlayer) (Object) this).setPosRaw(tetraform$rx, tetraform$ry, tetraform$rz);
        tetraform$swapped = false;
    }
}
