package dev.tetraform.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The three view rotations the mouse and W controls drive: {@code yaw pitch zw} in degrees. Each accepts
 * {@code ~} / {@code ~offset} relative to the target's current value.
 */
public final class Rot3Argument implements ArgumentType<Rot3Argument.Rot> {
    private static final SimpleCommandExceptionType INCOMPLETE = new SimpleCommandExceptionType(Component.literal("Expected three rotations: yaw pitch zw"));
    private static final Collection<String> EXAMPLES = List.of("0 0 90", "~ ~ ~45", "-90 30 0");

    public record Rot(double[] v, boolean[] rel) {
        public double yaw(double current) { return rel[0] ? current + v[0] : v[0]; }
        public double pitch(double current) { return rel[1] ? current + v[1] : v[1]; }
        public double zw(double current) { return rel[2] ? current + v[2] : v[2]; }
    }

    public static Rot3Argument rot3() {
        return new Rot3Argument();
    }

    public static Rot get(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, Rot.class);
    }

    @Override
    public Rot parse(StringReader reader) throws CommandSyntaxException {
        double[] v = new double[3];
        boolean[] rel = new boolean[3];
        int start = reader.getCursor();
        for (int i = 0; i < 3; i++) {
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
                if (!reader.canRead() || !StringReader.isAllowedNumber(reader.peek())) {
                    reader.setCursor(start);
                    throw INCOMPLETE.createWithContext(reader);
                }
                v[i] = reader.readDouble();
            }
        }
        return new Rot(v, rel);
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        if (builder.getRemaining().isEmpty()) builder.suggest("~ ~ ~");
        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
