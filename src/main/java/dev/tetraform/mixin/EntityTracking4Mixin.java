package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.world.W4;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla sends a player every entity within range in x and z, so in 4D it would also send everything at the
 * same x/z in every other W layer. Entities are only sent while they are within tracking range in 4D (x, z and
 * w together), which cuts network and client load a lot when many mobs live in nearby W layers.
 */
@Mixin(Entity.class)
public abstract class EntityTracking4Mixin {
    private static final double RANGE = 128;

    @Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
    private void tetraform$range4(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self == player || !Tetraform.is4D(self.level())) return;
        double dx = self.getX() - player.getX(), dz = self.getZ() - player.getZ(), dw = W4.get(self) - W4.get(player);
        if (dx * dx + dz * dz + dw * dw > RANGE * RANGE) cir.setReturnValue(false);
    }
}
