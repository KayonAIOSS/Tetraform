package dev.tetraform;

import dev.tetraform.command.Commands4;
import dev.tetraform.command.Pos4Argument;
import dev.tetraform.net.Net4;
import dev.tetraform.world.ServerEvents4;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@Mod(Tetraform.MODID)
public final class Tetraform {
    public static final String MODID = "tetraform";
    public static final Logger LOGGER = LoggerFactory.getLogger("Tetraform");

    /** The four-dimensional world. */
    public static final ResourceKey<Level> TESSERACT = ResourceKey.create(Registries.DIMENSION, id("tesseract"));

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);
    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> ARGUMENTS = DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, MODID);

    /**
     * Invisible, collision-free filler for the vanilla 3D chunks of the tesseract dimension. It keeps every vanilla
     * render section non-empty, so section meshing (vanilla or Sodium) runs and calls the 4D slice mesher.
     */
    public static final DeferredBlock<SliceAnchorBlock> SLICE_ANCHOR = BLOCKS.registerBlock("slice_anchor", SliceAnchorBlock::new,
            p -> p.noCollision().noOcclusion().noLootTable().replaceable().pushReaction(PushReaction.IGNORE_ENTITY)
                    .isSuffocating((s, l, pos) -> false).isViewBlocking((s, l, pos, box) -> false)
                    .isRedstoneConductor((s, l, pos) -> false).sound(SoundType.EMPTY).noTerrainParticles());

    public static final Supplier<AttachmentType<PlayerState4>> PLAYER4 = ATTACHMENTS.register("player4",
            () -> AttachmentType.serializable(PlayerState4::new).copyOnDeath().build());

    public static final DeferredHolder<ArgumentTypeInfo<?, ?>, SingletonArgumentInfo<Pos4Argument>> POS4_ARG = ARGUMENTS.register("pos4",
            () -> ArgumentTypeInfos.registerByClass(Pos4Argument.class, SingletonArgumentInfo.contextFree(Pos4Argument::pos4)));

    public Tetraform(IEventBus modBus, ModContainer container) {
        BLOCKS.register(modBus);
        ATTACHMENTS.register(modBus);
        ARGUMENTS.register(modBus);
        container.registerConfig(ModConfig.Type.SYNCED, Config4.SPEC);
        modBus.addListener(Net4::register);
        NeoForge.EVENT_BUS.register(ServerEvents4.class);
        NeoForge.EVENT_BUS.addListener(Commands4::register);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    public static boolean isTesseract(Level level) {
        return level != null && level.dimension() == TESSERACT;
    }
}
