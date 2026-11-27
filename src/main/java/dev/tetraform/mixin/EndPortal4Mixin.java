package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Access4;
import dev.tetraform.world.ServerWorld4;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Going through an end portal into a 4D End: vanilla builds its obsidian platform at (100, 48, 0) in the W layer
 * of the dragon fight (W = 0), and you arrive there.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortal4Mixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"))
    private void tetraform$enter(ServerLevel current, Entity entity, BlockPos entry, CallbackInfoReturnable<TeleportTransition> cir) {
        Access4.Mapper m = null;
        if (current.dimension() != Level.END) {
            ServerLevel end = current.getServer().getLevel(Level.END);
            ServerWorld4 w = end != null && Tetraform.is4D(end) ? ServerWorld4.get(end) : null;
            if (w != null) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dw = -1; dw <= 0; dw++) w.loadNow((100 >> 4) + dx, dz, dw);
                m = Access4.server(w, ServerWorld4.END_FIGHT_W);
            }
        }
        Access4.enterScope(m);
    }

    @Inject(method = "getPortalDestination", at = @At("RETURN"))
    private void tetraform$exit(ServerLevel current, Entity entity, BlockPos entry, CallbackInfoReturnable<TeleportTransition> cir) {
        boolean ours = Access4.serverContext() != null && current.dimension() != Level.END;
        Access4.exitScope();
        TeleportTransition t = cir.getReturnValue();
        if (!ours || t == null || !Tetraform.is4D(t.newLevel())) return;
        if (entity instanceof ServerPlayer sp) {
            var st = ServerWorld4.state(sp);
            st.pos = new Vec4(t.position().x, t.position().y, t.position().z, ServerWorld4.END_FIGHT_W);
            st.frame.setIdentity();
            st.dimension = t.newLevel().dimension().identifier().toString();
            st.initialized = true;
            st.keep4DUntil = t.newLevel().getGameTime() + 5;
        } else {
            dev.tetraform.world.W4.set(entity, ServerWorld4.END_FIGHT_W);
        }
    }
}
