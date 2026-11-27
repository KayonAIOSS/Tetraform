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

    /** The extra four-dimensional dimension (reachable with /tetra enter from any world). */
    public static final ResourceKey<Level> TESSERACT = ResourceKey.create(Registries.DIMENSION, id("tesseract"));
    /**
     * Every level whose dimension type is this one is four-dimensional. The mod's world preset gives the default
     * overworld this type, so new worlds are 4D out of the box.
     */
    public static final ResourceKey<net.minecraft.world.level.dimension.DimensionType> FOUR_D_TYPE =
            ResourceKey.create(Registries.DIMENSION_TYPE, id("tesseract"));

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

    /** Fourth coordinate of any entity in a 4D level; synced to clients so they can place and slice it. */
    public static final Supplier<AttachmentType<Double>> ENTITY_W = ATTACHMENTS.register("w",
            () -> AttachmentType.builder(() -> Double.NaN)
                    .serialize(com.mojang.serialization.Codec.DOUBLE.fieldOf("w"))
                    .sync(net.minecraft.network.codec.ByteBufCodecs.DOUBLE)
                    .build());

    public static final Supplier<AttachmentType<PlayerState4>> PLAYER4 = ATTACHMENTS.register("player4",
            () -> AttachmentType.serializable(PlayerState4::new).copyOnDeath().build());

    public static final DeferredHolder<ArgumentTypeInfo<?, ?>, SingletonArgumentInfo<Pos4Argument>> POS4_ARG = ARGUMENTS.register("pos4",
            () -> ArgumentTypeInfos.registerByClass(Pos4Argument.class, SingletonArgumentInfo.contextFree(Pos4Argument::pos4)));

    public static final DeferredHolder<ArgumentTypeInfo<?, ?>, SingletonArgumentInfo<dev.tetraform.command.Rot3Argument>> ROT3_ARG = ARGUMENTS.register("rot3",
            () -> ArgumentTypeInfos.registerByClass(dev.tetraform.command.Rot3Argument.class, SingletonArgumentInfo.contextFree(dev.tetraform.command.Rot3Argument::rot3)));

    public static final DeferredRegister<net.minecraft.world.level.gamerules.GameRule<?>> GAME_RULES = DeferredRegister.create(Registries.GAME_RULE, MODID);

    /** {@code /gamerule tetraform:creepers_spare_terrain}: creeper explosions don't break blocks (on by default). */
    public static final DeferredHolder<net.minecraft.world.level.gamerules.GameRule<?>, net.minecraft.world.level.gamerules.GameRule<Boolean>> CREEPERS_SPARE_TERRAIN =
            GAME_RULES.register("creepers_spare_terrain", () -> new net.minecraft.world.level.gamerules.GameRule<>(
                    net.minecraft.world.level.gamerules.GameRuleCategory.MOBS, net.minecraft.world.level.gamerules.GameRuleType.BOOL,
                    com.mojang.brigadier.arguments.BoolArgumentType.bool(), net.minecraft.world.level.gamerules.GameRuleTypeVisitor::visitBoolean,
                    com.mojang.serialization.Codec.BOOL, b -> b ? 1 : 0, true, net.minecraft.world.flag.FeatureFlagSet.of()));

    public Tetraform(IEventBus modBus, ModContainer container) {
        GAME_RULES.register(modBus);
        BLOCKS.register(modBus);
        ATTACHMENTS.register(modBus);
        ARGUMENTS.register(modBus);
        container.registerConfig(ModConfig.Type.SYNCED, Config4.SPEC);
        modBus.addListener(Net4::register);
        NeoForge.EVENT_BUS.register(ServerEvents4.class);
        NeoForge.EVENT_BUS.register(dev.tetraform.world.Mobs4.class);
        NeoForge.EVENT_BUS.register(dev.tetraform.world.Dimensions4.class);
        NeoForge.EVENT_BUS.addListener(Commands4::register);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    /**
     * Is this level four-dimensional? True for every level whose dimension type is one of this mod's: the 4D
     * overworld and the tesseract dimension ({@code tetraform:tesseract}), the 4D Nether ({@code tetraform:nether})
     * and the 4D End ({@code tetraform:end}).
     */
    public static boolean is4D(Level level) {
        return level != null && level.dimensionTypeRegistration().unwrapKey().map(k -> k.identifier().getNamespace().equals(MODID)).orElse(false);
    }

    /** Which kind of 4D terrain a level has. */
    public enum Kind { OVERWORLD, NETHER, END }

    public static Kind kind(Level level) {
        if (level.dimension() == Level.NETHER) return Kind.NETHER;
        if (level.dimension() == Level.END) return Kind.END;
        return Kind.OVERWORLD;
    }
}
