package dev.tetraform.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import dev.tetraform.PlayerState4;
import dev.tetraform.Tetraform;
import dev.tetraform.entity.Entity4;
import dev.tetraform.entity.Entity4Type;
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
 * {@code /clone}, {@code /tp x y z w} and {@code /summon tetraform:<mob> x y z w} all work. Three-coordinate forms
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
                                .executes(c -> setblock(c, BlockStateArgument.getBlock(c, "block"))))));

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
                                        .then(Commands.literal("outline").executes(c -> fill(c, Mode.OUTLINE, null)))))));

        d.register(Commands.literal("clone").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("begin4", Pos4Argument.pos4())
                        .then(Commands.argument("end4", Pos4Argument.pos4())
                                .then(Commands.argument("destination4", Pos4Argument.pos4()).executes(Commands4::clone)))));

        for (String name : new String[]{"tp", "teleport"}) {
            d.register(Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("location4", Pos4Argument.pos4())
                            .executes(c -> tp(c, List.of(c.getSource().getPlayerOrException()), Pos4Argument.getVec(c, "location4"))))
                    .then(Commands.argument("targets", EntityArgument.players())
                            .then(Commands.argument("location4", Pos4Argument.pos4())
                                    .executes(c -> tp(c, EntityArgument.getPlayers(c, "targets"), Pos4Argument.getVec(c, "location4"))))));
        }

        var summon = Commands.literal("summon").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (Entity4Type t : Entity4Type.VALUES) {
            if (t == Entity4Type.PLAYER || t == Entity4Type.ITEM) continue;
            String lit = "tetraform:" + t.name().toLowerCase(Locale.ROOT);
            summon.then(Commands.literal(lit)
                    .executes(c -> summon(c, t, Pos4Argument.origin(c.getSource())))
                    .then(Commands.argument("pos4", Pos4Argument.pos4()).executes(c -> summon(c, t, Pos4Argument.getVec(c, "pos4")))));
        }
        d.register(summon);

        d.register(Commands.literal("tetra")
                .then(Commands.literal("enter").executes(Commands4::enter))
                .then(Commands.literal("leave").executes(Commands4::leave))
                .then(Commands.literal("where").executes(Commands4::where))
                .then(Commands.literal("reset").executes(c -> rotate(c, 0, true)))
                .then(Commands.literal("rotate").then(Commands.argument("degrees", DoubleArgumentType.doubleArg(-360, 360))
                        .executes(c -> rotate(c, DoubleArgumentType.getDouble(c, "degrees"), false))))
                .then(Commands.literal("explode").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("pos4", Pos4Argument.pos4())
                                .executes(c -> explode(c, 4f))
                                .then(Commands.argument("power", FloatArgumentType.floatArg(0.5f, 12f))
                                        .executes(c -> explode(c, FloatArgumentType.getFloat(c, "power"))))))
                .then(Commands.literal("killmobs").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(Commands4::killMobs)));

        // Make the 4D branches win over vanilla's when both parse the same input (e.g. "tp 1 2 3 4").
        for (String root : new String[]{"setblock", "fill", "clone", "tp", "teleport"}) {
            CommandNode<CommandSourceStack> node = d.getRoot().getChild(root);
            if (node != null) prioritize(node);
        }
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

    private static ServerWorld4 world(CommandContext<CommandSourceStack> c) {
        ServerWorld4 w = ServerWorld4.defaultFor(c.getSource().getServer(), c.getSource().getLevel());
        if (w == null) throw new IllegalStateException("No four-dimensional world is loaded");
        return w;
    }

    private static int setblock(CommandContext<CommandSourceStack> c, BlockInput block) {
        ServerWorld4 w = world(c);
        BlockPos4 p = Pos4Argument.getVec(c, "pos4").toBlockPos();
        w.loadNow(p.x() >> 4, p.z() >> 4, p.w() >> 4);
        if (!w.setBlock(p, block.getState(), true)) {
            c.getSource().sendFailure(Component.literal("Could not set the block (unchanged or out of bounds)"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Changed the block at " + p), true);
        return 1;
    }

    private enum Mode { REPLACE, KEEP, HOLLOW, OUTLINE }

    private static int fill(CommandContext<CommandSourceStack> c, Mode mode, Predicate<BlockInWorld> filter) {
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
                case REPLACE -> {
                    if (filter != null && !filter.test(new BlockInWorld(c.getSource().getLevel(), net.minecraft.core.BlockPos.ZERO, false) {
                        @Override public BlockState getState() { return w.getBlock(p); }
                    })) continue;
                }
            }
            if (w.setBlock(p, target, notify)) changed++;
        }
        int n = changed;
        c.getSource().sendSuccess(() -> Component.literal("Successfully filled " + n + " block(s) across 4 dimensions"), true);
        return n;
    }

    private static int clone(CommandContext<CommandSourceStack> c) {
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
        i = 0;
        int changed = 0;
        for (int dw = 0; dw < sw; dw++) for (int dz = 0; dz < sz; dz++) for (int dy = 0; dy < sy; dy++) for (int dx = 0; dx < sx; dx++) {
            BlockPos4 p = new BlockPos4(dst.x() + dx, dst.y() + dy, dst.z() + dz, dst.w() + dw);
            w.loadNow(p.x() >> 4, p.z() >> 4, p.w() >> 4);
            if (w.setBlock(p, buf[i++], volume <= 4096)) changed++;
        }
        int n = changed;
        c.getSource().sendSuccess(() -> Component.literal("Successfully cloned " + n + " block(s)"), true);
        return n;
    }

    private static int tp(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players, Vec4 to) {
        ServerWorld4 w = world(c);
        for (ServerPlayer p : players) w.teleport(p, to, null);
        c.getSource().sendSuccess(() -> Component.literal("Teleported " + players.size() + " player(s) to " + to), true);
        return players.size();
    }

    private static int summon(CommandContext<CommandSourceStack> c, Entity4Type type, Vec4 pos) {
        ServerWorld4 w = world(c);
        w.loadNow((int) Math.floor(pos.x()) >> 4, (int) Math.floor(pos.z()) >> 4, (int) Math.floor(pos.w()) >> 4);
        w.addEntity(new Entity4(type, pos));
        c.getSource().sendSuccess(() -> Component.literal("Summoned " + type.name().toLowerCase(Locale.ROOT) + " at " + pos), true);
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

    private static int explode(CommandContext<CommandSourceStack> c, float power) {
        ServerWorld4 w = world(c);
        Vec4 pos = Pos4Argument.getVec(c, "pos4");
        w.explodeAt(pos, power);
        return 1;
    }

    private static int killMobs(CommandContext<CommandSourceStack> c) {
        ServerWorld4 w = world(c);
        int n = 0;
        for (Entity4 e : w.entities()) if (e.type != Entity4Type.PLAYER) { e.removed = true; n++; }
        int k = n;
        c.getSource().sendSuccess(() -> Component.literal("Removed " + k + " 4D entities"), true);
        return n;
    }
}
