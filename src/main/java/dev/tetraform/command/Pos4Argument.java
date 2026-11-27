package dev.tetraform.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.tetraform.Tetraform;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.ServerWorld4;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A four-component coordinate argument: {@code x y z w}. Every component accepts absolute values or
 * {@code ~}/{@code ~offset} relative to the executing player's 4D position.
 */
public final class Pos4Argument implements ArgumentType<Pos4Argument.Coords> {
    private static final SimpleCommandExceptionType INCOMPLETE = new SimpleCommandExceptionType(Component.literal("Expected four coordinates: x y z w"));
    private static final Collection<String> EXAMPLES = List.of("0 64 0 0", "~ ~ ~ ~5", "1.5 70 -3 ~-2");

    /**
     * Parsed coordinates. {@code local} means caret notation {@code ^left ^up ^forward ^ana}: offsets along the
     * source's view axes (yaw, pitch, then the player's zw rotation), the fourth one along the slice normal.
     */
    public record Coords(double[] v, boolean[] rel, boolean local) {
        public Vec4 resolve(CommandSourceStack src) {
            Vec4 base = origin(src);
            if (local) return base.add(localOffset(src, v[0], v[1], v[2], v[3]));
            return new Vec4(
                    rel[0] ? base.x() + v[0] : v[0], rel[1] ? base.y() + v[1] : v[1],
                    rel[2] ? base.z() + v[2] : v[2], rel[3] ? base.w() + v[3] : v[3]);
        }
    }

    public static Pos4Argument pos4() {
        return new Pos4Argument();
    }

    public static Vec4 getVec(CommandContext<CommandSourceStack> ctx, String name) {
        return ctx.getArgument(name, Coords.class).resolve(ctx.getSource());
    }

    /** The 4D position of an entity in a 4D level: players' tracked state, other entities' vanilla x/y/z plus their W. */
    public static Vec4 pos4(net.minecraft.world.entity.Entity e) {
        if (e instanceof ServerPlayer sp) {
            var st = ServerWorld4.state(sp);
            if (st.initialized) return st.pos;
        }
        return new Vec4(e.getX(), e.getY(), e.getZ(), dev.tetraform.world.W4.get(e));
    }

    /**
     * Where {@code ~} and {@code ^} are measured from. This follows the command source like vanilla does, so
     * {@code /execute at <entity>} and {@code /execute positioned x y z} work: x, y and z come from the source
     * position, and W from the entity standing exactly there (the executor first, then any other entity, as
     * after {@code execute at}). Otherwise W is the executor's own W.
     */
    public static Vec4 origin(CommandSourceStack src) {
        Vec4 cb = dev.tetraform.world.BlockEntities4.COMMAND_ORIGIN.get();
        if (cb != null && src.getEntity() == null) return cb;
        var p = src.getPosition();
        net.minecraft.world.entity.Entity self = src.getEntity();
        if (!Tetraform.is4D(src.getLevel())) return new Vec4(p.x, p.y, p.z, 0);
        if (self != null && self.level() == src.getLevel() && self.position().distanceToSqr(p) < 1.0E-6) return pos4(self);
        // after "execute at <entity>" the source sits exactly on that entity: take its W
        var box = net.minecraft.world.phys.AABB.ofSize(p, 1.0E-3, 1.0E-3, 1.0E-3);
        for (net.minecraft.world.entity.Entity e : src.getLevel().getEntities((net.minecraft.world.entity.Entity) null, box.inflate(2), x -> true)) {
            if (e.position().distanceToSqr(p) < 1.0E-6) {
                Vec4 at = pos4(e);
                return new Vec4(p.x, p.y, p.z, at.w());
            }
        }
        double w = self != null && Tetraform.is4D(self.level()) ? pos4(self).w() : 0;
        return new Vec4(p.x, p.y, p.z, w);
    }

    /** Caret offsets in 4D: vanilla's left/up/forward axes from yaw and pitch, carried through the player's zw frame. */
    static Vec4 localOffset(CommandSourceStack src, double left, double up, double forward, double ana) {
        var rot = src.getRotation(); // x = pitch, y = yaw
        double f = Math.cos(Math.toRadians(rot.y + 90)), f1 = Math.sin(Math.toRadians(rot.y + 90));
        double f2 = Math.cos(Math.toRadians(-rot.x)), f3 = Math.sin(Math.toRadians(-rot.x));
        double f4 = Math.cos(Math.toRadians(-rot.x + 90)), f5 = Math.sin(Math.toRadians(-rot.x + 90));
        double[] fwd = {f * f2, f3, f1 * f2};
        double[] upv = {f * f4, f5, f1 * f4};
        double[] lft = {-(fwd[1] * upv[2] - fwd[2] * upv[1]), -(fwd[2] * upv[0] - fwd[0] * upv[2]), -(fwd[0] * upv[1] - fwd[1] * upv[0])};
        double lx = fwd[0] * forward + upv[0] * up + lft[0] * left;
        double ly = fwd[1] * forward + upv[1] * up + lft[1] * left;
        double lz = fwd[2] * forward + upv[2] * up + lft[2] * left;
        dev.tetraform.math.SliceFrame frame = src.getEntity() instanceof ServerPlayer sp && Tetraform.is4D(sp.level())
                ? ServerWorld4.state(sp).frame : new dev.tetraform.math.SliceFrame();
        Vec4 o = frame.toWorld(lx, ly, lz, ana);
        if (src.getAnchor() == net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES && src.getEntity() != null) {
            o = o.add(0, src.getEntity().getEyeHeight(), 0, 0);
        }
        return o;
    }

    @Override
    public Coords parse(StringReader reader) throws CommandSyntaxException {
        double[] v = new double[4];
        boolean[] rel = new boolean[4];
        int start = reader.getCursor();
        boolean local = reader.canRead() && reader.peek() == '^';
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                if (!reader.canRead() || reader.peek() != ' ') {
                    reader.setCursor(start);
                    throw INCOMPLETE.createWithContext(reader);
                }
                reader.skip();
            }
            char mark = local ? '^' : '~';
            if (reader.canRead() && reader.peek() == mark) {
                reader.skip();
                rel[i] = true;
                if (reader.canRead() && reader.peek() != ' ') v[i] = reader.readDouble();
            } else {
                // caret notation can't be mixed with absolute or ~ coordinates (same rule as vanilla)
                if (local || !reader.canRead() || !(StringReader.isAllowedNumber(reader.peek()))) {
                    reader.setCursor(start);
                    throw INCOMPLETE.createWithContext(reader);
                }
                v[i] = reader.readDouble();
            }
        }
        return new Coords(v, rel, local);
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        String rem = builder.getRemaining();
        int parts = rem.isEmpty() ? 0 : rem.split(" ", -1).length;
        StringBuilder sb = new StringBuilder(rem);
        if (parts == 0) sb.append("~ ~ ~ ~");
        else {
            if (!rem.endsWith(" ")) return builder.buildFuture();
            for (int i = parts; i <= 4; i++) {
                sb.append("~");
                if (i < 4) sb.append(' ');
            }
        }
        builder.suggest(sb.toString().trim());
        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
