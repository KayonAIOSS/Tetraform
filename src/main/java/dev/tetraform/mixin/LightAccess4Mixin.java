package dev.tetraform.mixin;

import dev.tetraform.world.Access4;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Light inside a 4D slice comes from the 4D world: block light from 4D emitters, sky light from the 4D surface.
 * Vanilla's own light engine only sees the empty 3D chunks (full daylight everywhere, even underground), which
 * broke everything that asks for light: monster spawners and spawning rules, crop growth, mob AI that avoids
 * light. These override BlockAndLightGetter's default methods on the server level (outside a 4D slice they do
 * exactly what the defaults do).
 */
@Mixin(ServerLevel.class)
public abstract class LightAccess4Mixin {
    public int getBrightness(LightLayer layer, BlockPos pos) {
        ServerLevel self = (ServerLevel) (Object) this;
        Access4.Mapper m = Access4.serverContext(self);
        if (m == null) return self.getLightEngine().getLayerListener(layer).getLightValue(pos);
        int w = (int) Math.floor(m.w());
        return layer == LightLayer.BLOCK ? m.world().blockLight(pos.getX(), pos.getY(), pos.getZ(), w)
                : m.world().skyLight(pos.getX(), pos.getY(), pos.getZ(), w);
    }

    public int getRawBrightness(BlockPos pos, int darkening) {
        ServerLevel self = (ServerLevel) (Object) this;
        Access4.Mapper m = Access4.serverContext(self);
        if (m == null) return self.getLightEngine().getRawBrightness(pos, darkening);
        int w = (int) Math.floor(m.w());
        int block = m.world().blockLight(pos.getX(), pos.getY(), pos.getZ(), w);
        int sky = m.world().skyLight(pos.getX(), pos.getY(), pos.getZ(), w) - darkening;
        return Math.max(block, Math.max(0, sky));
    }
}
