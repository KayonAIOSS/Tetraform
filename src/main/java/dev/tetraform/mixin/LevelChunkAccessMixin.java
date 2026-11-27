package dev.tetraform.mixin;

import dev.tetraform.world.Access4;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Collision and pathfinding read blocks straight from chunks, so chunks also answer from the 4D world while an
 * entity context is active. With no entity ticking (lighting, chunk saving, meshing), chunks are untouched.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkAccessMixin {
    @Shadow
    @Final
    Level level;

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void tetraform$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        Access4.Mapper m = Access4.entityContextOnly(); if (m != null && m.world() != null && m.world().serverLevel() != level) m = null;
        if (m != null) cir.setReturnValue(m.get(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Inject(method = "getFluidState(III)Lnet/minecraft/world/level/material/FluidState;", at = @At("HEAD"), cancellable = true)
    private void tetraform$getFluidState(int x, int y, int z, CallbackInfoReturnable<FluidState> cir) {
        Access4.Mapper m = Access4.entityContextOnly(); if (m != null && m.world() != null && m.world().serverLevel() != level) m = null;
        if (m != null) cir.setReturnValue(m.get(x, y, z).getFluidState());
    }
}
