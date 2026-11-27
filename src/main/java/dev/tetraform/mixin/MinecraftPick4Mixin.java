package dev.tetraform.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After vanilla picks its crosshair target, replace it with the 4D target (see {@link dev.tetraform.client.Pick4}). */
@Mixin(Minecraft.class)
public abstract class MinecraftPick4Mixin {
    @Inject(method = "pick", at = @At("TAIL"))
    private void tetraform$pick4(float partialTicks, CallbackInfo ci) {
        dev.tetraform.client.Pick4.afterVanillaPick((Minecraft) (Object) this);
    }
}
