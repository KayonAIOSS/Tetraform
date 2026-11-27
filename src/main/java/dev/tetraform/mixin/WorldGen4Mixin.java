package dev.tetraform.mixin;

import com.mojang.serialization.Dynamic;
import dev.tetraform.world.Dimensions4;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Loading a 4D world made before the 4D Nether and End existed upgrades them (see {@link Dimensions4}). */
@Mixin(LevelStorageSource.class)
public abstract class WorldGen4Mixin {
    private static final ThreadLocal<HolderLookup.Provider> REGISTRIES = new ThreadLocal<>();

    @Inject(method = "getLevelDataAndDimensions", at = @At("HEAD"))
    private static void tetraform$registries(LevelStorageSource.LevelStorageAccess access, Dynamic<?> tag, WorldDataConfiguration config,
                                             Registry<LevelStem> datapackDimensions, HolderLookup.Provider registries,
                                             CallbackInfoReturnable<LevelDataAndDimensions> cir) {
        REGISTRIES.set(registries);
    }

    @ModifyVariable(method = "getLevelDataAndDimensions", at = @At("STORE"), ordinal = 0)
    private static WorldGenSettings tetraform$fourD(WorldGenSettings settings) {
        WorldGenSettings out = Dimensions4.upgrade(settings, REGISTRIES.get());
        REGISTRIES.remove();
        return out;
    }
}
