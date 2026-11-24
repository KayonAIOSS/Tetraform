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

    public record Coords(double[] v, boolean[] rel) {
        public Vec4 resolve(CommandSourceStack src) {
            Vec4 base = origin(src);
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

    /** The executing entity's 4D position (players in the tesseract), else its vanilla position with w = 0. */
    public static Vec4 origin(CommandSourceStack src) {
        if (src.getEntity() instanceof ServerPlayer sp && Tetraform.is4D(sp.level())) {
            return ServerWorld4.state(sp).pos;
        }
        var p = src.getPosition();
        return new Vec4(p.x, p.y, p.z, 0);
    }

    @Override
    public Coords parse(StringReader reader) throws CommandSyntaxException {
        double[] v = new double[4];
        boolean[] rel = new boolean[4];
        int start = reader.getCursor();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                if (!reader.canRead() || reader.peek() != ' ') {
                    reader.setCursor(start);
                    throw INCOMPLETE.createWithContext(reader);
                }
                reader.skip();
            }
            if (reader.canRead() && reader.peek() == '~') {
                reader.skip();
                rel[i] = true;
                if (reader.canRead() && reader.peek() != ' ') v[i] = reader.readDouble();
            } else {
                if (!reader.canRead() || !(StringReader.isAllowedNumber(reader.peek()))) {
                    reader.setCursor(start);
                    throw INCOMPLETE.createWithContext(reader);
                }
                v[i] = reader.readDouble();
            }
        }
        return new Coords(v, rel);
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
