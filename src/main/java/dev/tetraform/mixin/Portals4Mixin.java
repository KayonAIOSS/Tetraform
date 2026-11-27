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
                double scale = net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(current.dimensionType(), next.dimensionType());
                // W is a horizontal axis like x and z, so in a 4D Nether it is scaled 1:8 as well
                double wc = Tetraform.is4D(current) ? wOf(entity) * scale : wOf(entity);
                Vec4 from = entity instanceof ServerPlayer sp ? ServerWorld4.state(sp).pos : new Vec4(entity.getX(), entity.getY(), entity.getZ(), wc);
                int x = (int) Math.floor(from.x() * scale), z = (int) Math.floor(from.z() * scale), cw = (int) Math.floor(wc) >> 4;
                // the exit is found or built around here: make sure that terrain exists first
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dw = -1; dw <= 1; dw++) w.loadNow((x >> 4) + dx, (z >> 4) + dz, cw + dw);
                // like vanilla, link to an existing portal nearby (here: nearby in W as well) instead of building one
                boolean toNether = newDim == Level.NETHER;
                var found = w.nearestNetherPortal4(x, (int) from.y(), z, (int) Math.floor(wc), toNether ? 16 : 128);
                if (found != null) wc = found.w() + 0.5;
                m = Access4.server(w, wc);
                // vanilla works out the exit from the entity's vanilla x and z: give it the true 4D ones meanwhile
                // (a player's vanilla position is in their own, possibly turned, slice frame)
                if (entity instanceof ServerPlayer) {
                    SAVED.set(new double[]{entity.getX(), entity.getY(), entity.getZ()});
                    entity.setPosRaw(from.x(), from.y(), from.z());
                }
            }
        }
        Access4.enterScope(m);
    }

    private static final ThreadLocal<double[]> SAVED = new ThreadLocal<>();

    @Inject(method = "getPortalDestination", at = @At("RETURN"))
    private void tetraform$exit(ServerLevel current, Entity entity, BlockPos entry, CallbackInfoReturnable<TeleportTransition> cir) {
        double[] saved = SAVED.get();
        if (saved != null) {
            SAVED.remove();
            entity.setPosRaw(saved[0], saved[1], saved[2]);
        }
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
