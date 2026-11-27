package dev.tetraform.mixin;

import dev.tetraform.world.Access4;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/** Routes vanilla block access to the 4D world in the slice of whichever entity is ticking (see {@link Access4}). */
@Mixin(Level.class)
public abstract class LevelAccessMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void tetraform$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        Access4.Mapper m = Access4.current((Level) (Object) this);
        if (m != null) cir.setReturnValue(m.get(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void tetraform$getFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        Access4.Mapper m = Access4.current((Level) (Object) this);
        if (m != null) cir.setReturnValue(m.get(pos.getX(), pos.getY(), pos.getZ()).getFluidState());
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void tetraform$setBlock(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
        Access4.Mapper m = Access4.current((Level) (Object) this);
        if (m != null) cir.setReturnValue(m.set(pos.getX(), pos.getY(), pos.getZ(), state, (flags & 1) != 0));
    }

    /** Block entities of 4D blocks (chests, furnaces, spawners, modded machines) answer in the active slice. */
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void tetraform$getBlockEntity(BlockPos pos, CallbackInfoReturnable<net.minecraft.world.level.block.entity.BlockEntity> cir) {
        Access4.Mapper m = Access4.current((Level) (Object) this);
        if (m != null) cir.setReturnValue(m.getBlockEntity(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void tetraform$setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity be, CallbackInfo ci) {
        Access4.Mapper m = Access4.serverContext((Level) (Object) this);
        if (m == null) return;
        ci.cancel();
        BlockPos p = be.getBlockPos();
        m.world().putVanillaBlockEntity(new dev.tetraform.math.BlockPos4(p.getX(), p.getY(), p.getZ(), (int) Math.floor(m.w())), be);
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void tetraform$removeBlockEntity(BlockPos p, CallbackInfo ci) {
        Access4.Mapper m = Access4.serverContext((Level) (Object) this);
        if (m == null) return;
        ci.cancel();
        m.world().removeVanillaBlockEntity(new dev.tetraform.math.BlockPos4(p.getX(), p.getY(), p.getZ(), (int) Math.floor(m.w())));
    }

    /** Heightmaps (portal placement, rain, mob AI, features) follow the 4D surface of the slice. */
    @Inject(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", at = @At("HEAD"), cancellable = true)
    private void tetraform$getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z, CallbackInfoReturnable<Integer> cir) {
        Access4.Mapper m = Access4.current((Level) (Object) this);
        if (m != null) cir.setReturnValue(m.surface(x, z));
    }

    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"), cancellable = true)
    private void tetraform$outputSignal(BlockPos pos, net.minecraft.world.level.block.Block block, CallbackInfo ci) {
        if (Access4.serverContext((Level) (Object) this) != null) ci.cancel();
    }

    @Inject(method = "guardEntityTick", at = @At("HEAD"))
    private <T extends Entity> void tetraform$enter(Consumer<T> tick, T entity, CallbackInfo ci) {
        Access4.Mapper m = Access4.forEntity((Level) (Object) this, entity);
        ContextStack.push(Access4.push(m));
    }

    @Inject(method = "guardEntityTick", at = @At("RETURN"))
    private <T extends Entity> void tetraform$exit(Consumer<T> tick, T entity, CallbackInfo ci) {
        Access4.pop(ContextStack.pop());
    }

    /** Remembers the previous context across the HEAD/RETURN pair (entity ticks nest for passengers). */
    public static final class ContextStack {
        private static final ThreadLocal<java.util.ArrayDeque<Object>> STACK = ThreadLocal.withInitial(java.util.ArrayDeque::new);
        private static final Object NONE = new Object();

        static void push(Access4.Mapper prev) {
            STACK.get().push(prev == null ? NONE : prev);
        }

        static Access4.Mapper pop() {
            Object o = STACK.get().poll();
            return o == null || o == NONE ? null : (Access4.Mapper) o;
        }
    }
}
