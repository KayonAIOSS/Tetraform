package dev.tetraform.mixin;

import dev.tetraform.Tetraform;
import dev.tetraform.world.Access4;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** In a 4D world, existing nether portals are found from the 4D portal index (vanilla's POI data is 3D). */
@Mixin(PortalForcer.class)
public abstract class PortalForcer4Mixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "findClosestPortalPosition", at = @At("HEAD"), cancellable = true)
    private void tetraform$find4(BlockPos approx, boolean toNether, WorldBorder border, CallbackInfoReturnable<Optional<BlockPos>> cir) {
        if (!Tetraform.is4D(level)) return;
        Access4.Mapper m = Access4.serverContext(level);
        if (m == null) return;
        cir.setReturnValue(m.world().nearestNetherPortal(approx, toNether ? 16 : 128, (int) Math.floor(m.w())));
    }
}
