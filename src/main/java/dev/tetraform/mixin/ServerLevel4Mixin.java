package dev.tetraform.mixin;

import dev.tetraform.world.Access4;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While vanilla code runs inside a 4D slice (a block interaction, a block or block-entity tick, a mob's tick):
 * <ul>
 *   <li>scheduled block and fluid ticks go to the 4D world's scheduler at the right W;</li>
 *   <li>vanilla's own 3D neighbour updates are skipped: every 4D block change already notifies all eight 4D
 *   neighbours, which then run vanilla's neighbour logic in their own slice.</li>
 * </ul>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevel4Mixin {
    @Inject(method = "getBlockTicks", at = @At("HEAD"), cancellable = true)
    private void tetraform$blockTicks(CallbackInfoReturnable<LevelTicks<Block>> cir) {
        Access4.Mapper m = Access4.serverContext((ServerLevel) (Object) this);
        if (m != null) cir.setReturnValue(m.world().ticks4(m.w()));
    }

    @Inject(method = "getFluidTicks", at = @At("HEAD"), cancellable = true)
    private void tetraform$fluidTicks(CallbackInfoReturnable<LevelTicks<Fluid>> cir) {
        Access4.Mapper m = Access4.serverContext((ServerLevel) (Object) this);
        if (m != null) cir.setReturnValue(m.world().fluidTicks4(m.w()));
    }

    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"), cancellable = true)
    private void tetraform$n1(BlockPos pos, Block block, CallbackInfo ci) {
        if (Access4.serverContext((ServerLevel) (Object) this) != null) ci.cancel();
    }

    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V", at = @At("HEAD"), cancellable = true)
    private void tetraform$n2(BlockPos pos, Block block, Orientation o, CallbackInfo ci) {
        if (Access4.serverContext((ServerLevel) (Object) this) != null) ci.cancel();
    }

    @Inject(method = "updateNeighborsAtExceptFromFacing", at = @At("HEAD"), cancellable = true)
    private void tetraform$n3(BlockPos pos, Block block, Direction skip, Orientation o, CallbackInfo ci) {
        if (Access4.serverContext((ServerLevel) (Object) this) != null) ci.cancel();
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V", at = @At("HEAD"), cancellable = true)
    private void tetraform$n4(BlockPos pos, Block block, Orientation o, CallbackInfo ci) {
        if (Access4.serverContext((ServerLevel) (Object) this) != null) ci.cancel();
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V", at = @At("HEAD"), cancellable = true)
    private void tetraform$n5(BlockState state, BlockPos pos, Block block, Orientation o, boolean piston, CallbackInfo ci) {
        if (Access4.serverContext((ServerLevel) (Object) this) != null) ci.cancel();
    }
}
