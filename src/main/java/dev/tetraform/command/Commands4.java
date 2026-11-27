package dev.tetraform.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import dev.tetraform.PlayerState4;
import dev.tetraform.Tetraform;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.ServerWorld4;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Predicate;

/**
 * Four-dimensional versions of the vanilla world commands. They are merged into the vanilla command roots, so
 * {@code /setblock x y z w block}, {@code /fill x1 y1 z1 w1 x2 y2 z2 w2 block} (8 coordinates instead of 6),
 * {@code /clone}, {@code /tp x y z w} and {@code /summon <entity> x y z w} all work. Three-coordinate forms
 * still reach vanilla.
 */
public final class Commands4 {
    private static final int MAX_FILL = 1 << 20;

    private Commands4() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        CommandBuildContext ctx = event.getBuildContext();

        d.register(Commands.literal("setblock").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("pos4", Pos4Argument.pos4())
                        .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                .executes(c -> setblock(c, BlockStateArgument.getBlock(c, "block"), Mode.REPLACE))
                                .then(Commands.literal("replace").executes(c -> setblock(c, BlockStateArgument.getBlock(c, "block"), Mode.REPLACE)))
                                .then(Commands.literal("keep").executes(c -> setblock(c, BlockStateArgument.getBlock(c, "block"), Mode.KEEP)))
                                .then(Commands.literal("destroy").executes(c -> setblock(c, BlockStateArgument.getBlock(c, "block"), Mode.DESTROY))))));

        d.register(Commands.literal("fill").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("from4", Pos4Argument.pos4())
                        .then(Commands.argument("to4", Pos4Argument.pos4())
                                .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                        .executes(c -> fill(c, Mode.REPLACE, null))
                                        .then(Commands.literal("replace").executes(c -> fill(c, Mode.REPLACE, null))
                                                .then(Commands.argument("filter", BlockPredicateArgument.blockPredicate(ctx))
                                                        .executes(c -> fill(c, Mode.REPLACE, BlockPredicateArgument.getBlockPredicate(c, "filter")))))
                                        .then(Commands.literal("keep").executes(c -> fill(c, Mode.KEEP, null)))
                                        .then(Commands.literal("hollow").executes(c -> fill(c, Mode.HOLLOW, null)))
                                        .then(Commands.literal("outline").executes(c -> fill(c, Mode.OUTLINE, null)))
                                        .then(Commands.literal("destroy").executes(c -> fill(c, Mode.DESTROY, null)))))));

        // /clone begin end destination [replace|masked|filtered <filter>] [force|move|normal], all with 4 coordinates
        var cloneDest = Commands.argument("destination4", Pos4Argument.pos4()).executes(c -> clone(c, null, false, false));
        for (String mask : new String[]{"replace", "masked"}) {
            boolean masked = mask.equals("masked");
            var m = Commands.literal(mask).executes(c -> clone(c, null, masked, false));
            for (String mode : new String[]{"force", "move", "normal"}) {
                boolean move = mode.equals("move");
                m.then(Commands.literal(mode).executes(c -> clone(c, null, masked, move)));
            }
            cloneDest.then(m);
        }
        var filtered = Commands.argument("filter", BlockPredicateArgument.blockPredicate(ctx))
                .executes(c -> clone(c, BlockPredicateArgument.getBlockPredicate(c, "filter"), false, false));
        for (String mode : new String[]{"force", "move", "normal"}) {
            boolean move = mode.equals("move");
            filtered.then(Commands.literal(mode).executes(c -> clone(c, BlockPredicateArgument.getBlockPredicate(c, "filter"), false, move)));
        }
        cloneDest.then(Commands.literal("filtered").then(filtered));
        d.register(Commands.literal("clone").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("begin4", Pos4Argument.pos4())
                        .then(Commands.argument("end4", Pos4Argument.pos4()).then(cloneDest))));

        // "targets" takes any entities like vanilla (it used to be players only, so /execute as <mob> run tp @s ... failed)
        for (String name : new String[]{"tp", "teleport"}) {
            d.register(Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("location4", Pos4Argument.pos4())
                            .executes(c -> tp(c, List.of(c.getSource().getEntityOrException()), Pos4Argument.getVec(c, "location4"), null))
                            .then(Commands.argument("rotation4", Rot3Argument.rot3())
                                    .executes(c -> tp(c, List.of(c.getSource().getEntityOrException()), Pos4Argument.getVec(c, "location4"), Rot3Argument.get(c, "rotation4")))))
                    .then(Commands.argument("targets", EntityArgument.entities())
                            .then(Commands.argument("location4", Pos4Argument.pos4())
                                    .executes(c -> tp(c, EntityArgument.getEntities(c, "targets"), Pos4Argument.getVec(c, "location4"), null))
                                    .then(Commands.argument("rotation4", Rot3Argument.rot3())
                                            .executes(c -> tp(c, EntityArgument.getEntities(c, "targets"), Pos4Argument.getVec(c, "location4"), Rot3Argument.get(c, "rotation4")))))));
        }

        // merged into vanilla's "/summon <entity>" node: a 4-number position spawns any vanilla entity in 4D
        d.register(Commands.literal("summon").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("entity", net.minecraft.commands.arguments.ResourceArgument.resource(ctx, net.minecraft.core.registries.Registries.ENTITY_TYPE))
                        .then(Commands.argument("pos4", Pos4Argument.pos4())
                                .executes(c -> summon(c, net.minecraft.commands.arguments.ResourceArgument.getSummonableEntityType(c, "entity").value(),
                                        Pos4Argument.getVec(c, "pos4"), null))
                                .then(Commands.argument("nbt", net.minecraft.commands.arguments.CompoundTagArgument.compoundTag())
                                        .executes(c -> summon(c, net.minecraft.commands.arguments.ResourceArgument.getSummonableEntityType(c, "entity").value(),
                                                Pos4Argument.getVec(c, "pos4"), net.minecraft.commands.arguments.CompoundTagArgument.getCompoundTag(c, "nbt")))))));

        d.register(Commands.literal("tetra")
                .then(Commands.literal("enter").executes(Commands4::enter))
                .then(Commands.literal("leave").executes(Commands4::leave))
                .then(Commands.literal("where").executes(Commands4::where))
                .then(Commands.literal("locate").executes(c -> locate(c, null))
                        .then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"village", "ruined_portal", "igloo", "shipwreck"}, b))
                                .executes(c -> locate(c, com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind")))))
                .then(Commands.literal("reset").executes(c -> rotate(c, 0, true)))
                .then(Commands.literal("rotate").then(Commands.argument("degrees", DoubleArgumentType.doubleArg(-360, 360))
                        .executes(c -> rotate(c, DoubleArgumentType.getDouble(c, "degrees"), false))))
                .then(Commands.literal("explode").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("pos4", Pos4Argument.pos4())
                                .executes(c -> explode(c, 4f))
                                .then(Commands.argument("power", FloatArgumentType.floatArg(0.5f, 12f))
                                        .executes(c -> explode(c, FloatArgumentType.getFloat(c, "power"))))))
                .then(Commands.literal("probe").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("pos4", Pos4Argument.pos4()).executes(Commands4::probe)))
                .then(Commands.literal("trace").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("pos4", Pos4Argument.pos4()).executes(Commands4::trace)))
                .then(Commands.literal("fluids").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(Commands4::fluids))
                );

        registerExecuteIfBlock(d, ctx);

        // Make the 4D branches win over vanilla's when both parse the same input (e.g. "tp 1 2 3 4").
        for (String root : new String[]{"setblock", "fill", "clone", "tp", "teleport", "summon"}) {
            CommandNode<CommandSourceStack> node = d.getRoot().getChild(root);
            if (node != null) prioritize(node);
        }

        // Vanilla forms that take no 4D coordinates still have to keep the W in a 4D world.
        wrapVanilla(d, Commands4::tpToEntity, "teleport", "destination");
        wrapVanilla(d, Commands4::tpToEntity, "teleport", "targets", "destination");
        wrapVanilla(d, c -> tp3(c, false, false), "teleport", "location");
        wrapVanilla(d, c -> tp3(c, true, false), "teleport", "targets", "location");
        wrapVanilla(d, c -> tp3(c, true, true), "teleport", "targets", "location", "rotation");
        wrapVanilla(d, c -> summonHere(c, false, false), "summon", "entity");
        wrapVanilla(d, c -> summonHere(c, true, false), "summon", "entity", "pos");
        wrapVanilla(d, c -> summonHere(c, true, true), "summon", "entity", "pos", "nbt");
        wrapVanilla(d, Commands4::locateVanilla, "locate", "structure", "structure");
    }

    // ------------------------------------------------------------------ vanilla integration

    /**
     * Replaces the command on an existing vanilla node: inside a 4D world {@code fourD} runs, anywhere else the
     * original vanilla command does. The node, its argument type and its suggestions stay vanilla's.
     */
    private static void wrapVanilla(CommandDispatcher<CommandSourceStack> d, com.mojang.brigadier.Command<CommandSourceStack> fourD, String... path) {
        CommandNode<CommandSourceStack> node = d.getRoot();
        for (String p : path) {
            node = node == null ? null : node.getChild(p);
        }
        if (node == null || node.getCommand() == null) {
            Tetraform.LOGGER.warn("Vanilla command node /{} not found; its 4D form is unavailable", String.join(" ", path));
            return;
        }
        com.mojang.brigadier.Command<CommandSourceStack> vanilla = node.getCommand();
        com.mojang.brigadier.Command<CommandSourceStack> wrapped = c -> Tetraform.is4D(c.getSource().getLevel()) ? fourD.run(c) : vanilla.run(c);
        try {
            Field f = CommandNode.class.getDeclaredField("command");
            f.setAccessible(true);
            f.set(node, wrapped);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Tetraform.LOGGER.warn("Could not add the 4D form of /{}: {}", String.join(" ", path), e.toString());
        }
    }

    private static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType CONDITION_FAILED =
            new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.translatable("commands.execute.conditional.fail"));

    /** {@code /execute if|unless block x y z w <block>}: tests the 4D world (vanilla would read the empty 3D chunks). */
    private static void registerExecuteIfBlock(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx) {
        CommandNode<CommandSourceStack> execute = d.getRoot().getChild("execute");
        if (execute == null) return;
        for (String word : new String[]{"if", "unless"}) {
            boolean expected = word.equals("if");
            CommandNode<CommandSourceStack> blockNode = execute.getChild(word) == null ? null : execute.getChild(word).getChild("block");
            if (blockNode == null) continue;
            var arg = Commands.argument("pos4", Pos4Argument.pos4())
                    .then(Commands.argument("block", BlockPredicateArgument.blockPredicate(ctx))
                            .fork(execute, c -> testBlock(c) == expected ? List.of(c.getSource()) : List.of())
                            .executes(c -> {
                                if (testBlock(c) != expected) throw CONDITION_FAILED.create();
                                c.getSource().sendSuccess(() -> Component.translatable("commands.execute.conditional.pass"), false);
                                return 1;
                            }));
            blockNode.addChild(arg.build());
            prioritize(blockNode);
        }
    }

    private static boolean testBlock(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        BlockPos4 p = Pos4Argument.getVec(c, "pos4").toBlockPos();
        w.loadNow(p.x() >> 4, p.z() >> 4, p.w() >> 4);
        BlockState s = w.getBlock(p);
        return BlockPredicateArgument.getBlockPredicate(c, "block").test(inWorld(c, s));
    }

    private static BlockInWorld inWorld(CommandContext<CommandSourceStack> c, BlockState s) {
        return new BlockInWorld(c.getSource().getLevel(), net.minecraft.core.BlockPos.ZERO, false) {
            @Override public BlockState getState() { return s; }
        };
    }


    @SuppressWarnings("unchecked")
    private static void prioritize(CommandNode<CommandSourceStack> node) {
        try {
            Field children = CommandNode.class.getDeclaredField("children");
            Field arguments = CommandNode.class.getDeclaredField("arguments");
            children.setAccessible(true);
            arguments.setAccessible(true);
            reorder((Map<String, Object>) children.get(node));
            reorder((Map<String, Object>) arguments.get(node));
            CommandNode<CommandSourceStack> targets = node.getChild("targets");
            if (targets == null) targets = node.getChild("entity");
            if (targets != null) {
                reorder((Map<String, Object>) children.get(targets));
                reorder((Map<String, Object>) arguments.get(targets));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Tetraform.LOGGER.warn("Could not prioritise 4D command branches for /{}: {}", node.getName(), e.toString());
        }
    }

    private static void reorder(Map<String, Object> map) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (String k : new String[]{"pos4", "from4", "location4", "begin4", "destination4"}) if (map.containsKey(k)) copy.put(k, map.get(k));
        for (Map.Entry<String, Object> e : map.entrySet()) copy.putIfAbsent(e.getKey(), e.getValue());
        map.clear();
        map.putAll(copy);
    }

    // ------------------------------------------------------------------ implementations

    private static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType NO_4D =
            new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("No four-dimensional world is loaded"));

    private static ServerWorld4 world(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerWorld4 w = ServerWorld4.defaultFor(c.getSource().getServer(), c.getSource().getLevel());
        if (w == null) throw NO_4D.create();
        return w;
    }

    /** Vanilla's "destroy": drop what the old block would drop (in the block's own W slice), then replace it. */
    private static void destroyDrops(ServerWorld4 w, BlockPos4 p) {
        BlockState old = w.getBlock(p);
        if (old.isAir() || !old.getFluidState().isEmpty()) return;
        var prev = dev.tetraform.world.Access4.push(dev.tetraform.world.Access4.server(w, p.w() + 0.5));
        try {
            for (var stack : net.minecraft.world.level.block.Block.getDrops(old, w.serverLevel(), new net.minecraft.core.BlockPos(p.x(), p.y(), p.z()), null)) {
                w.dropItem(p.center(), stack);
            }
        } finally {
            dev.tetraform.world.Access4.pop(prev);
        }
    }

    private static int setblock(CommandContext<CommandSourceStack> c, BlockInput block, Mode mode) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        BlockPos4 p = Pos4Argument.getVec(c, "pos4").toBlockPos();
        w.loadNow(p.x() >> 4, p.z() >> 4, p.w() >> 4);
        if (mode == Mode.KEEP && !w.getBlock(p).isAir()) {
            c.getSource().sendFailure(Component.translatable("commands.setblock.failed"));
            return 0;
        }
        if (mode == Mode.DESTROY) destroyDrops(w, p);
        // vanilla's own placement in the block's slice, so block entity data works: setblock ... spawner{...}, chest{...}
        boolean placed = dev.tetraform.world.Access4.inSlice(w, p.w() + 0.5,
                () -> block.place(w.serverLevel(), new net.minecraft.core.BlockPos(p.x(), p.y(), p.z()), net.minecraft.world.level.block.Block.UPDATE_ALL));
        if (!placed) {
            c.getSource().sendFailure(Component.literal("Could not set the block (unchanged or out of bounds)"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Changed the block at " + p), true);
        return 1;
    }

    private enum Mode { REPLACE, KEEP, HOLLOW, OUTLINE, DESTROY }

    private static int fill(CommandContext<CommandSourceStack> c, Mode mode, Predicate<BlockInWorld> filter) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        BlockPos4 a = Pos4Argument.getVec(c, "from4").toBlockPos();
        BlockPos4 b = Pos4Argument.getVec(c, "to4").toBlockPos();
        BlockState state = BlockStateArgument.getBlock(c, "block").getState();
        int x0 = Math.min(a.x(), b.x()), x1 = Math.max(a.x(), b.x());
        int y0 = Math.max(0, Math.min(a.y(), b.y())), y1 = Math.min(255, Math.max(a.y(), b.y()));
        int z0 = Math.min(a.z(), b.z()), z1 = Math.max(a.z(), b.z());
        int w0 = Math.min(a.w(), b.w()), w1 = Math.max(a.w(), b.w());
        long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) * (w1 - w0 + 1);
        if (volume > MAX_FILL) {
            c.getSource().sendFailure(Component.literal("Too many blocks in the specified hypervolume (maximum " + MAX_FILL + ", specified " + volume + ")"));
            return 0;
        }
        boolean notify = volume <= 4096;
        for (int cw = w0 >> 4; cw <= w1 >> 4; cw++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) w.loadNow(cx, cz, cw);
        int changed = 0;
        for (int pw = w0; pw <= w1; pw++) for (int pz = z0; pz <= z1; pz++) for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
            boolean border = px == x0 || px == x1 || py == y0 || py == y1 || pz == z0 || pz == z1 || pw == w0 || pw == w1;
            BlockPos4 p = new BlockPos4(px, py, pz, pw);
            BlockState target = state;
            switch (mode) {
                case KEEP -> { if (!w.getBlock(p).isAir()) continue; }
                case HOLLOW -> { if (!border) target = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(); }
                case OUTLINE -> { if (!border) continue; }
                case DESTROY -> destroyDrops(w, p);
                case REPLACE -> {
                    if (filter != null && !filter.test(inWorld(c, w.getBlock(p)))) continue;
                }
            }
            if (w.setBlock(p, target, notify)) changed++;
        }
        int n = changed;
        c.getSource().sendSuccess(() -> Component.literal("Successfully filled " + n + " block(s) across 4 dimensions"), true);
        return n;
    }

    private static int clone(CommandContext<CommandSourceStack> c, Predicate<BlockInWorld> filter, boolean masked, boolean move) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        BlockPos4 a = Pos4Argument.getVec(c, "begin4").toBlockPos();
        BlockPos4 b = Pos4Argument.getVec(c, "end4").toBlockPos();
        BlockPos4 dst = Pos4Argument.getVec(c, "destination4").toBlockPos();
        int x0 = Math.min(a.x(), b.x()), y0 = Math.min(a.y(), b.y()), z0 = Math.min(a.z(), b.z()), w0 = Math.min(a.w(), b.w());
        int sx = Math.abs(a.x() - b.x()) + 1, sy = Math.abs(a.y() - b.y()) + 1, sz = Math.abs(a.z() - b.z()) + 1, sw = Math.abs(a.w() - b.w()) + 1;
        long volume = (long) sx * sy * sz * sw;
        if (volume > MAX_FILL) {
            c.getSource().sendFailure(Component.literal("Too many blocks (" + volume + ")"));
            return 0;
        }
        BlockState[] buf = new BlockState[(int) volume];
        int i = 0;
        for (int dw = 0; dw < sw; dw++) for (int dz = 0; dz < sz; dz++) for (int dy = 0; dy < sy; dy++) for (int dx = 0; dx < sx; dx++) {
            BlockPos4 p = new BlockPos4(x0 + dx, y0 + dy, z0 + dz, w0 + dw);
            w.loadNow(p.x() >> 4, p.z() >> 4, p.w() >> 4);
            buf[i++] = w.getBlock(p);
        }
        boolean[] copied = new boolean[(int) volume];
        i = 0;
        for (int k = 0; k < buf.length; k++) {
            boolean take = !masked || !buf[k].isAir();
            if (take && filter != null && !filter.test(inWorld(c, buf[k]))) take = false;
            copied[k] = take;
        }
        // "move": the source becomes air where something was copied (cleared first, so overlapping moves work)
        if (move) {
            for (int dw = 0; dw < sw; dw++) for (int dz = 0; dz < sz; dz++) for (int dy = 0; dy < sy; dy++) for (int dx = 0; dx < sx; dx++) {
                if (!copied[i++]) continue;
                w.setBlock(new BlockPos4(x0 + dx, y0 + dy, z0 + dz, w0 + dw), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), volume <= 4096);
            }
        }
        i = 0;
        int changed = 0;
        for (int dw = 0; dw < sw; dw++) for (int dz = 0; dz < sz; dz++) for (int dy = 0; dy < sy; dy++) for (int dx = 0; dx < sx; dx++) {
            int k = i++;
            if (!copied[k]) continue;
            BlockPos4 p = new BlockPos4(dst.x() + dx, dst.y() + dy, dst.z() + dz, dst.w() + dw);
            w.loadNow(p.x() >> 4, p.z() >> 4, p.w() >> 4);
            if (w.setBlock(p, buf[k], volume <= 4096)) changed++;
        }
        int n = changed;
        c.getSource().sendSuccess(() -> Component.literal("Successfully cloned " + n + " block(s)"), true);
        return n;
    }

    /** 4D teleport of any entities: players through their 4D state, other entities by vanilla position plus W. */
    private static int tp(CommandContext<CommandSourceStack> c, Collection<? extends net.minecraft.world.entity.Entity> targets, Vec4 to,
                          Rot3Argument.Rot rot) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        for (net.minecraft.world.entity.Entity e : targets) {
            float yaw = rot == null ? e.getYRot() : (float) rot.yaw(e.getYRot());
            float pitch = rot == null ? e.getXRot() : (float) Math.max(-90, Math.min(90, rot.pitch(e.getXRot())));
            if (e instanceof ServerPlayer p) {
                if (rot == null) {
                    w.teleport(p, to, null);
                } else {
                    PlayerState4 st = ServerWorld4.state(p);
                    double zw = Math.toRadians(rot.zw(Math.toDegrees(st.frame.zwAngle())));
                    w.teleport(p, to, SliceFrame.zw(zw), yaw, pitch);
                }
            } else {
                w.loadNow((int) Math.floor(to.x()) >> 4, (int) Math.floor(to.z()) >> 4, (int) Math.floor(to.w()) >> 4);
                dev.tetraform.world.W4.set(e, to.w());
                e.teleportTo(w.serverLevel(), to.x(), to.y(), to.z(), Set.of(), yaw, pitch, true);
                // teleportTo across dimensions makes a new entity; give it the W as well
                for (var moved : w.serverLevel().getEntities((net.minecraft.world.entity.Entity) null,
                        net.minecraft.world.phys.AABB.ofSize(new net.minecraft.world.phys.Vec3(to.x(), to.y(), to.z()), 0.01, 0.01, 0.01).inflate(1),
                        x -> x.getUUID().equals(e.getUUID()))) dev.tetraform.world.W4.set(moved, to.w());
            }
        }
        if (targets.size() == 1) {
            var only = targets.iterator().next();
            c.getSource().sendSuccess(() -> Component.literal("Teleported ").append(only.getDisplayName()).append(" to " + to
                    + (rot == null ? "" : " facing (yaw / pitch / zw)")), true);
        } else {
            c.getSource().sendSuccess(() -> Component.literal("Teleported " + targets.size() + " entities to " + to
                    + (rot == null ? "" : " facing (yaw / pitch / zw)")), true);
        }
        return targets.size();
    }

    /** {@code /tp <destination>} and {@code /tp <targets> <destination>}: to the destination's full 4D position. */
    private static int tpToEntity(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        net.minecraft.world.entity.Entity dest = EntityArgument.getEntity(c, "destination");
        Collection<? extends net.minecraft.world.entity.Entity> targets = hasArg(c, "targets")
                ? EntityArgument.getEntities(c, "targets") : List.of(c.getSource().getEntityOrException());
        Vec4 to = Pos4Argument.pos4(dest);
        if (!Tetraform.is4D(dest.level())) to = new Vec4(dest.getX(), dest.getY(), dest.getZ(), 0.5);
        return tp(c, targets, to, null);
    }

    /** The 3-coordinate vanilla forms in a 4D world: x, y and z as vanilla parses them, W stays the source's. */
    private static int tp3(CommandContext<CommandSourceStack> c, boolean withTargets, boolean withRotation) throws CommandSyntaxException {
        Collection<? extends net.minecraft.world.entity.Entity> targets = withTargets
                ? EntityArgument.getEntities(c, "targets") : List.of(c.getSource().getEntityOrException());
        var v = net.minecraft.commands.arguments.coordinates.Vec3Argument.getCoordinates(c, "location").getPosition(c.getSource());
        Vec4 to = new Vec4(v.x, v.y, v.z, Pos4Argument.origin(c.getSource()).w());
        Rot3Argument.Rot rot = null;
        if (withRotation) {
            var r = net.minecraft.commands.arguments.coordinates.RotationArgument.getRotation(c, "rotation").getRotation(c.getSource());
            rot = new Rot3Argument.Rot(new double[]{r.y, r.x, 0}, new boolean[]{false, false, true});
        }
        return tp(c, targets, to, rot);
    }

    /** {@code /summon <entity>} and {@code /summon <entity> x y z [nbt]} in a 4D world: spawned in the source's W. */
    private static int summonHere(CommandContext<CommandSourceStack> c, boolean withPos, boolean withNbt) throws CommandSyntaxException {
        var type = net.minecraft.commands.arguments.ResourceArgument.getSummonableEntityType(c, "entity").value();
        Vec4 o = Pos4Argument.origin(c.getSource());
        Vec4 pos = o;
        if (withPos) {
            var v = net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(c, "pos");
            pos = new Vec4(v.x, v.y, v.z, o.w());
        }
        return summon(c, type, pos, withNbt ? net.minecraft.commands.arguments.CompoundTagArgument.getCompoundTag(c, "nbt") : null);
    }

    /** Vanilla {@code /locate structure <id>} finds the 4D structures (vanilla's 3D ones don't generate there). */
    private static int locateVanilla(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String id = c.getArgument("structure", net.minecraft.commands.arguments.ResourceOrTagKeyArgument.Result.class).asPrintable();
        if (id.contains("stronghold")) {
            ServerWorld4 w = world(c);
            Vec4 from = Pos4Argument.origin(c.getSource());
            Vec4 s = dev.tetraform.world.Rooms4.nearestStronghold(w.generator().seed(), from);
            if (s == null) {
                c.getSource().sendFailure(Component.literal("No 4D stronghold found nearby"));
                return 0;
            }
            c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Nearest stronghold portal room at %d %d %d %d (%d blocks away in 4D)",
                    (int) Math.floor(s.x()), (int) Math.floor(s.y()), (int) Math.floor(s.z()), (int) Math.floor(s.w()), (int) Math.round(s.distance(from)))), false);
            return 1;
        }
        String kind = id.contains("village") ? "village" : id.contains("ruined_portal") ? "ruined_portal"
                : id.contains("igloo") ? "igloo" : id.contains("shipwreck") ? "shipwreck" : null;
        if (kind == null) {
            c.getSource().sendFailure(Component.literal(id + " does not generate in the 4D world (villages, ruined portals, igloos and shipwrecks do)"));
            return 0;
        }
        return locate(c, kind);
    }

    private static boolean hasArg(CommandContext<CommandSourceStack> c, String name) {
        try {
            c.getArgument(name, Object.class);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int summon(CommandContext<CommandSourceStack> c, net.minecraft.world.entity.EntityType<?> type, Vec4 pos,
                              net.minecraft.nbt.CompoundTag nbt) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        w.loadNow((int) Math.floor(pos.x()) >> 4, (int) Math.floor(pos.z()) >> 4, (int) Math.floor(pos.w()) >> 4);
        var e = w.spawn(type, pos, net.minecraft.world.entity.EntitySpawnReason.COMMAND, nbt);
        if (e == null) {
            c.getSource().sendFailure(Component.literal("Unable to summon entity"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("commands.summon.success", e.getDisplayName()), true);
        return 1;
    }

    private static int locate(CommandContext<CommandSourceStack> c, String kind) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        var st = w.generator().structures;
        Vec4 from = c.getSource().getEntity() instanceof ServerPlayer p ? w.pos4(p)
                : new Vec4(c.getSource().getPosition().x, c.getSource().getPosition().y, c.getSource().getPosition().z, 0.5);
        String r = st == null ? null : st.locate(from, kind);
        if (r == null) {
            c.getSource().sendFailure(Component.literal("No 4D structure found nearby"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Nearest " + r), false);
        return 1;
    }

    private static int enter(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel tl = c.getSource().getServer().getLevel(Tetraform.TESSERACT);
        ServerWorld4 w = tl == null ? null : ServerWorld4.get(tl);
        if (w == null) {
            c.getSource().sendFailure(Component.literal("The tesseract dimension is not available"));
            return 0;
        }
        PlayerState4 st = ServerWorld4.state(p);
        boolean same = st.initialized && st.dimension.equals(Tetraform.TESSERACT.identifier().toString());
        Vec4 to = same ? st.pos : w.findSurface(0.5, 0.5, 0.5);
        w.teleport(p, to, null);
        c.getSource().sendSuccess(() -> Component.literal("Entered the tesseract. Hold Left Alt and scroll (or use mouse buttons 4/5) to rotate into the fourth dimension."), false);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel overworld = c.getSource().getServer().overworld();
        var spawn = overworld.getRespawnData().globalPos().pos();
        p.teleportTo(overworld, spawn.getX() + 0.5, overworld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()), spawn.getZ() + 0.5, Set.of(), p.getYRot(), p.getXRot(), true);
        return 1;
    }

    private static int where(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        PlayerState4 st = ServerWorld4.state(p);
        Vec4 n = st.frame.normal();
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "4D position %s, slice normal (%.3f, %.3f, %.3f, %.3f), zw %.1f°",
                st.pos, n.x(), n.y(), n.z(), n.w(), Math.toDegrees(st.frame.zwAngle()))), false);
        return 1;
    }

    private static int rotate(CommandContext<CommandSourceStack> c, double degrees, boolean reset) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerWorld4 w = world(c);
        PlayerState4 st = ServerWorld4.state(p);
        SliceFrame f = reset ? new SliceFrame() : SliceFrame.zw(st.frame.zwAngle() + Math.toRadians(degrees));
        st.frame.set(f);
        w.sendTeleport(p, false);
        return 1;
    }

    private static int explode(CommandContext<CommandSourceStack> c, float power) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        Vec4 pos = Pos4Argument.getVec(c, "pos4");
        w.explodeAt(pos, power);
        return 1;
    }

    /** Debug: counts fluid blocks by level within 24 blocks of the player (4D box). */
    private static int fluids(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerWorld4 w = world(c);
        BlockPos4 o = ServerWorld4.state(p).pos.toBlockPos();
        int[] water = new int[16], lava = new int[16];
        int r = 24;
        for (int dw = -r; dw <= r; dw++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) for (int y = Math.max(0, o.y() - 12); y <= o.y() + 12; y++) {
            BlockState s = w.getBlock(new BlockPos4(o.x() + dx, y, o.z() + dz, o.w() + dw));
            if (s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                int lvl = s.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL);
                if (s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) lava[lvl]++; else water[lvl]++;
            }
        }
        c.getSource().sendSuccess(() -> Component.literal("water by level " + Arrays.toString(water) + " lava " + Arrays.toString(lava)), false);
        // describe one level-1 cell and its neighbourhood (debug aid)
        outer:
        for (int dw = -r; dw <= r; dw++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) for (int y = Math.max(0, o.y() - 12); y <= o.y() + 12; y++) {
            BlockPos4 bp = new BlockPos4(o.x() + dx, y, o.z() + dz, o.w() + dw);
            BlockState s = w.getBlock(bp);
            if (s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && s.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) == 1) {
                StringBuilder sb = new StringBuilder("level-1 at " + bp + ":");
                for (dev.tetraform.math.Direction4 d : dev.tetraform.math.Direction4.VALUES) {
                    BlockState n = w.getBlock(bp.relative(d));
                    String v = n.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock ? "w" + n.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) : n.getBlock().getName().getString();
                    sb.append(" ").append(d.name()).append("=").append(v);
                }
                String msg = sb.toString();
                c.getSource().sendSuccess(() -> Component.literal(msg), false);
                break outer;
            }
        }
        return 1;
    }

    private static String describe(BlockState n) {
        return n.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                ? "w" + n.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) : n.getBlock().getName().getString();
    }

    /** Debug: a block and its eight 4D neighbours. */
    private static int probe(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        BlockPos4 bp = Pos4Argument.getVec(c, "pos4").toBlockPos();
        for (int dw = -1; dw <= 1; dw++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            w.loadNow((bp.x() + dx) >> 4, (bp.z() + dz) >> 4, (bp.w() + dw) >> 4);
        StringBuilder sb = new StringBuilder(bp + " = " + describe(w.getBlock(bp)) + ":");
        for (dev.tetraform.math.Direction4 d : dev.tetraform.math.Direction4.VALUES) sb.append(" ").append(d.name()).append("=").append(describe(w.getBlock(bp.relative(d))));
        String msg = sb.toString();
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** Debug: follows the strongest horizontal water neighbour until a source/falling block. */
    private static int trace(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerWorld4 w = world(c);
        BlockPos4 bp = Pos4Argument.getVec(c, "pos4").toBlockPos();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            for (int dw = -1; dw <= 1; dw++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                w.loadNow((bp.x() + dx) >> 4, (bp.z() + dz) >> 4, (bp.w() + dw) >> 4);
            BlockState s = w.getBlock(bp);
            sb.append(bp).append("=").append(describe(s)).append(" above=").append(describe(w.getBlock(bp.above()))).append(" | ");
            if (!(s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) break;
            int lvl = s.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL);
            if (lvl == 0 || lvl >= 8) break;
            BlockPos4 best = null;
            int bestEff = 99;
            for (dev.tetraform.math.Direction4 d : dev.tetraform.math.Direction4.HORIZONTAL) {
                BlockState n = w.getBlock(bp.relative(d));
                if (n.getBlock() != s.getBlock()) continue;
                int nl = n.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL);
                int eff = nl >= 8 ? 0 : nl;
                if (eff < bestEff) { bestEff = eff; best = bp.relative(d); }
            }
            if (best == null) break;
            bp = best;
        }
        String msg = sb.toString();
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

}
