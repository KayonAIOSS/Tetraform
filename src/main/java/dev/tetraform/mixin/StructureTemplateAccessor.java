package dev.tetraform.mixin;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Raw template contents, so structures can be written straight into 4D columns. */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {
    @Accessor("palettes")
    List<StructureTemplate.Palette> tetraform$palettes();

    @Accessor("entityInfoList")
    List<StructureTemplate.StructureEntityInfo> tetraform$entities();
}
