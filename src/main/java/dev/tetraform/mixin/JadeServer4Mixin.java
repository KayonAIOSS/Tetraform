package dev.tetraform.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.tetraform.Tetraform;
import dev.tetraform.world.Access4;
import dev.tetraform.world.ServerWorld4;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Jade's server data (chest contents, furnace progress, spawner mob, beehive bees, modded machines...) for 4D
 * blocks. Jade's client asks the server about the block at a vanilla position: in a 4D world that is the render
 * cell the crosshair is drawn at. While Jade answers, vanilla block and block entity access is mapped to the 4D
 * world around the block the client reported looking at, so every Jade provider (Jade's own and other mods')
 * reads the real 4D block entity. Does nothing without Jade.
 */
@Pseudo
@Mixin(targets = "snownee.jade.impl.BlockAccessorImpl", remap = false)
public abstract class JadeServer4Mixin {
    private static final ThreadLocal<Access4.Mapper[]> PREV = new ThreadLocal<>();

    @WrapOperation(method = "lambda$handleRequest$0", require = 0,
            at = @At(value = "INVOKE", target = "Lsnownee/jade/Jade;isOutOfReach(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;D)Z"))
    private static boolean tetraform$enter(ServerPlayer player, BlockPos pos, double range, Operation<Boolean> original) {
        if (!Tetraform.is4D(player.level())) return original.call(player, pos, range);
        ServerWorld4 w = ServerWorld4.getIfLoaded(player.level());
        var st = w == null ? null : ServerWorld4.state(player);
        if (st == null || st.look4 == null || !pos.equals(st.lookRender)) return true;
        var c = st.look4;
        double dx = c.x() + 0.5 - st.pos.x(), dy = c.y() + 0.5 - (st.pos.y() + player.getEyeHeight()), dz = c.z() + 0.5 - st.pos.z(), dw = c.w() + 0.5 - st.pos.w();
        if (dx * dx + dy * dy + dz * dz + dw * dw > (range + 1.5) * (range + 1.5)) return true;
        PREV.set(new Access4.Mapper[]{Access4.push(Access4.relative(w, pos, c, st.frame))});
        return false;
    }

    @WrapOperation(method = "lambda$handleRequest$0", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isLoaded(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean tetraform$loaded(ServerLevel level, BlockPos pos, Operation<Boolean> original) {
        return PREV.get() != null || original.call(level, pos);
    }

    @Inject(method = "lambda$handleRequest$0", at = @At("RETURN"), require = 0)
    private static void tetraform$exit(CallbackInfo ci) {
        Access4.Mapper[] prev = PREV.get();
        if (prev == null) return;
        PREV.remove();
        Access4.pop(prev[0]);
    }
}
