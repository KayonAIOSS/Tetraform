package dev.tetraform.mixin;

import dev.tetraform.PlayerState4;
import dev.tetraform.Tetraform;
import dev.tetraform.world.Access4;
import dev.tetraform.world.ServerWorld4;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's respawn lookup (is the bed or respawn anchor still there? where do I stand up?) runs in the W slice
 * of the player's 4D respawn point, so beds and anchors in 4D work exactly like vanilla ones.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRespawn4Mixin {
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"))
    private void tetraform$enter(boolean consume, TeleportTransition.PostTeleportTransition post, CallbackInfoReturnable<TeleportTransition> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        Access4.Mapper m = null;
        var config = self.getRespawnConfig();
        PlayerState4 st = ServerWorld4.state(self);
        if (config != null && !Double.isNaN(st.spawnW)) {
            ServerLevel level = self.level().getServer().getLevel(ServerPlayer.RespawnConfig.getDimensionOrDefault(config));
            if (level != null && Tetraform.is4D(level)) {
                ServerWorld4 w = ServerWorld4.get(level);
                if (w != null) {
                    var p = config.respawnData().pos();
                    w.loadNow(p.getX() >> 4, p.getZ() >> 4, (int) Math.floor(st.spawnW) >> 4);
                    m = Access4.server(w, st.spawnW);
                }
            }
        }
        Access4.enterScope(m);
    }

    /**
     * Where the player comes back in 4D (after death, or from the End): at their bed or anchor with its W if that
     * was used, otherwise on the surface at the world spawn.
     */
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"))
    private void tetraform$exit(boolean consume, TeleportTransition.PostTeleportTransition post, CallbackInfoReturnable<TeleportTransition> cir) {
        Access4.Mapper m = Access4.serverContext();
        double spawnW = m != null ? m.w() : Double.NaN;
        Access4.exitScope();
        TeleportTransition t = cir.getReturnValue();
        if (t == null || !Tetraform.is4D(t.newLevel())) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        PlayerState4 st = ServerWorld4.state(self);
        ServerWorld4 w = ServerWorld4.get(t.newLevel());
        if (w == null) return;
        var config = self.getRespawnConfig();
        boolean usedSpawnPoint = !Double.isNaN(spawnW) && config != null
                && config.respawnData().pos().distToCenterSqr(t.position()) < 16;
        st.pos = usedSpawnPoint ? new dev.tetraform.math.Vec4(t.position().x, t.position().y, t.position().z, spawnW)
                : w.findSurface(t.position().x, t.position().z, 0.5);
        st.dimension = t.newLevel().dimension().identifier().toString();
        st.initialized = true;
        st.keep4DUntil = t.newLevel().getGameTime() + 5;
    }
}
