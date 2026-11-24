package dev.tetraform.mixin;

import dev.tetraform.world.Access4;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Undead burn only under open 4D sky (the vanilla 3D sky check would see straight through 4D terrain). */
@Mixin(Mob.class)
public abstract class MobSunMixin {
    @Redirect(method = "isSunBurnTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;canSeeSky(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean tetraform$canSeeSky(Level level, BlockPos pos) {
        Access4.Mapper m = Access4.current(level);
        if (m == null) return level.canSeeSky(pos);
        return pos.getY() >= m.surface(pos.getX(), pos.getZ());
    }
}
