package dev.tetraform.mixin;

import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandBlockEditScreen.class)
public interface CommandBlockEditScreenAccessor {
    @Accessor("mode")
    CommandBlockEntity.Mode tetraform$mode();

    @Accessor("conditional")
    boolean tetraform$conditional();

    @Accessor("autoexec")
    boolean tetraform$autoexec();
}
