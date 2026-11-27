package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Access4;
import dev.tetraform.world.Rooms4;
import dev.tetraform.world.ServerWorld4;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eyes of ender in a 4D world fly toward the nearest 4D stronghold portal room. The eye itself can only fly in your
 * slice (toward its x and z), so the remaining distance along W is shown above the hotbar.
 */
@Mixin(ServerLevel.class)
public abstract class Strongholds4Mixin {
    @Inject(method = "findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;",
            at = @At("HEAD"), cancellable = true)
    private void tetraform$stronghold4(TagKey<Structure> tag, BlockPos origin, int radius, boolean createReference, CallbackInfoReturnable<BlockPos> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!Tetraform.is4D(self) || tag != StructureTags.EYE_OF_ENDER_LOCATED) return;
        ServerWorld4 w = ServerWorld4.get(self);
        if (w == null) return;
        ServerPlayer near = (ServerPlayer) self.getNearestPlayer(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 4, false);
        Access4.Mapper ctx = Access4.serverContext(self);
        double wc = ctx != null ? ctx.w() : near != null ? ServerWorld4.state(near).pos.w() : 0.5;
        Vec4 s = Rooms4.nearestStronghold(w.generator().seed(), new Vec4(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, wc));
        if (s == null) {
            cir.setReturnValue(null);
            return;
        }
        if (near != null) {
            double dw = s.w() - wc;
            String msg = Math.abs(dw) < 1 ? "The eye pulls straight ahead: the stronghold is in this W layer"
                    : String.format(java.util.Locale.ROOT, "The eye also tugs %d blocks %s along W (to W %d)",
                    (int) Math.round(Math.abs(dw)), dw > 0 ? "ana (+W)" : "kata (-W)", (int) Math.floor(s.w()));
            near.sendSystemMessage(Component.literal(msg), true);
        }
        cir.setReturnValue(BlockPos.containing(s.x(), s.y(), s.z()));
    }
}
