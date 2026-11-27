package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.world.Access4;
import dev.tetraform.world.ServerWorld4;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The dragon fight of a 4D End happens in the W layer of the main island's centre (W = 0): the exit portal, the
 * obsidian spikes and their crystals, the dragon egg and the end gateways are read and built there, by vanilla's own
 * fight logic.
 */
@Mixin(EnderDragonFight.class)
public abstract class DragonFight4Mixin {
    @Shadow private ServerLevel level;

    private Access4.Mapper tetraform$slice() {
        if (level == null || !Tetraform.is4D(level)) return null;
        ServerWorld4 w = ServerWorld4.get(level);
        return w == null ? null : Access4.server(w, ServerWorld4.END_FIGHT_W);
    }

    @Inject(method = {"tick", "setDragonKilled", "tryRespawn", "onCrystalDestroyed", "resetSpikeCrystals"}, at = @At("HEAD"))
    private void tetraform$enter(CallbackInfo ci) {
        Access4.enterScope(tetraform$slice());
    }

    @Inject(method = {"tick", "setDragonKilled", "tryRespawn", "onCrystalDestroyed", "resetSpikeCrystals"}, at = @At("RETURN"))
    private void tetraform$exit(CallbackInfo ci) {
        Access4.exitScope();
    }
}
