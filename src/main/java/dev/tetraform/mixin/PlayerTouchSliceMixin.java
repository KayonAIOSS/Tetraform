package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.world.W4;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Players only touch (pick up items, XP, ...) entities in their own W slice. */
@Mixin(Player.class)
public abstract class PlayerTouchSliceMixin {
    @Inject(method = "touch", at = @At("HEAD"), cancellable = true)
    private void tetraform$slice(Entity entity, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (Tetraform.is4D(self.level()) && !W4.sameSlice(self, entity)) ci.cancel();
    }
}
