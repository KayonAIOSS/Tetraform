package dev.tetraform.mixin;

import dev.tetraform.client.Debug4;
import net.minecraft.client.gui.components.debug.DebugEntryLookingAt;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows the 4D version of this F3 entry while in a four-dimensional level (see {@link Debug4}). */
@Mixin(DebugEntryLookingAt.class)
public abstract class DebugLookingAtMixin {
    @Inject(method = "display", at = @At("HEAD"), cancellable = true)
    private void tetraform$display(DebugScreenDisplayer d, Level l, LevelChunk c, LevelChunk s, CallbackInfo ci) {
        if (Debug4.active()) {
            Debug4.lookingAt(d, (DebugEntryLookingAt) (Object) this);
            ci.cancel();
        }
    }
}
