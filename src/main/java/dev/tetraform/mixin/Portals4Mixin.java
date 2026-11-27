package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Access4;
import dev.tetraform.world.ServerWorld4;
import dev.tetraform.world.W4;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nether portals into a 4D world: vanilla finds or builds the exit portal (with the usual 8:1 scaling) inside
 * the W slice you left the 4D world from, and you arrive there with that W.
 */
@Mixin(NetherPortalBlock.class)
public abstract class Portals4Mixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"))
    private void tetraform$enter(ServerLevel current, Entity entity, BlockPos entry, CallbackInfoReturnable<TeleportTransition> cir) {
        var newDim = current.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        ServerLevel next = current.getServer().getLevel(newDim);
        Access4.Mapper m = null;
        if (next != null && Tetraform.is4D(next)) {
            ServerWorld4 w = ServerWorld4.get(next);
            if (w != null) {
                double wc = wOf(entity);
                double scale = net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(current.dimensionType(), next.dimensionType());
                int x = (int) Math.floor(entity.getX() * scale), z = (int) Math.floor(entity.getZ() * scale), cw = (int) Math.floor(wc) >> 4;
                // the exit is found or built around here: make sure that terrain exists first
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) w.loadNow((x >> 4) + dx, (z >> 4) + dz, cw);
                m = Access4.server(w, wc);
            }
        }
        Access4.enterScope(m);
    }

    @Inject(method = "getPortalDestination", at = @At("RETURN"))
    private void tetraform$exit(ServerLevel current, Entity entity, BlockPos entry, CallbackInfoReturnable<TeleportTransition> cir) {
        Access4.Mapper m = Access4.serverContext();
        double wc = m != null ? m.w() : Double.NaN;
        Access4.exitScope();
        TeleportTransition t = cir.getReturnValue();
        if (t == null || Double.isNaN(wc) || !Tetraform.is4D(t.newLevel())) return;
        if (entity instanceof ServerPlayer sp) {
            var st = ServerWorld4.state(sp);
            st.pos = new Vec4(t.position().x, t.position().y, t.position().z, wc);
            st.dimension = t.newLevel().dimension().identifier().toString();
            st.initialized = true;
            st.keep4DUntil = t.newLevel().getGameTime() + 5;
        }
    }

    private static double wOf(Entity e) {
        if (e instanceof ServerPlayer sp) return ServerWorld4.state(sp).pos.w();
        return W4.get(e);
    }
}
