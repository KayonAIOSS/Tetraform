package dev.tetraform.net;

import dev.tetraform.Tetraform;
import dev.tetraform.world.ServerWorld4;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * All Tetraform packets. Client-bound handlers are registered from the client-only side
 * ({@code dev.tetraform.client.ClientNet4}) so that no client class is touched on a dedicated server.
 */
public final class Net4 {
    private Net4() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(Tetraform.id(name));
    }

    // ---------------------------------------------------------------- client-bound

    /** One deflate-compressed 16x256x16x16 column. */
    public record ColumnS2C(byte[] data) implements CustomPacketPayload {
        public static final Type<ColumnS2C> TYPE = typeOf("column");
        public static final StreamCodec<RegistryFriendlyByteBuf, ColumnS2C> CODEC = StreamCodec.of(
                (buf, v) -> buf.writeByteArray(v.data), buf -> new ColumnS2C(buf.readByteArray(1 << 22)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UnloadColumnsS2C(long[] keys) implements CustomPacketPayload {
        public static final Type<UnloadColumnsS2C> TYPE = typeOf("unload_columns");
        public static final StreamCodec<RegistryFriendlyByteBuf, UnloadColumnsS2C> CODEC = StreamCodec.of(
                (buf, v) -> writeLongs(buf, v.keys), buf -> new UnloadColumnsS2C(readLongs(buf)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Batched block changes: packed BlockPos4 longs plus global block state ids. */
    public record BlockUpdatesS2C(long[] positions, int[] states) implements CustomPacketPayload {
        public static final Type<BlockUpdatesS2C> TYPE = typeOf("block_updates");
        public static final StreamCodec<RegistryFriendlyByteBuf, BlockUpdatesS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    writeLongs(buf, v.positions);
                    for (int s : v.states) buf.writeVarInt(s);
                },
                buf -> {
                    long[] p = readLongs(buf);
                    int[] s = new int[p.length];
                    for (int i = 0; i < s.length; i++) s[i] = buf.readVarInt();
                    return new BlockUpdatesS2C(p, s);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One visible 4D entity in a snapshot. Frame = 9 floats (orientation in x,z,w). */
    public record EntityView(int id, byte type, float x, float y, float z, float w, float[] frame, int extra, byte hurt) {
        static void write(ByteBuf buf, EntityView v) {
            buf.writeInt(v.id);
            buf.writeByte(v.type);
            buf.writeFloat(v.x);
            buf.writeFloat(v.y);
            buf.writeFloat(v.z);
            buf.writeFloat(v.w);
            for (int i = 0; i < 9; i++) buf.writeFloat(v.frame[i]);
            buf.writeInt(v.extra);
            buf.writeByte(v.hurt);
        }

        static EntityView read(ByteBuf buf) {
            int id = buf.readInt();
            byte t = buf.readByte();
            float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat(), w = buf.readFloat();
            float[] f = new float[9];
            for (int i = 0; i < 9; i++) f[i] = buf.readFloat();
            return new EntityView(id, t, x, y, z, w, f, buf.readInt(), buf.readByte());
        }
    }

    /** Per-tick snapshot of the 4D entities (mobs, items and other players) near the receiving player. */
    public record EntitiesS2C(EntityView[] entities) implements CustomPacketPayload {
        public static final Type<EntitiesS2C> TYPE = typeOf("entities");
        public static final StreamCodec<RegistryFriendlyByteBuf, EntitiesS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.entities.length);
                    for (EntityView e : v.entities) EntityView.write(buf, e);
                },
                buf -> {
                    int n = Math.min(buf.readVarInt(), 4096);
                    EntityView[] a = new EntityView[n];
                    for (int i = 0; i < n; i++) a[i] = EntityView.read(buf);
                    return new EntitiesS2C(a);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Authoritative 4D position (and optionally frame) from the server: teleports, respawn, entering. */
    public record Teleport4S2C(double x, double y, double z, double w, double[] frame, boolean reset) implements CustomPacketPayload {
        public static final Type<Teleport4S2C> TYPE = typeOf("teleport4");
        public static final StreamCodec<RegistryFriendlyByteBuf, Teleport4S2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z); buf.writeDouble(v.w);
                    for (int i = 0; i < 9; i++) buf.writeDouble(v.frame[i]);
                    buf.writeBoolean(v.reset);
                },
                buf -> {
                    double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble(), w = buf.readDouble();
                    double[] f = new double[9];
                    for (int i = 0; i < 9; i++) f[i] = buf.readDouble();
                    return new Teleport4S2C(x, y, z, w, f, buf.readBoolean());
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A 4D velocity impulse (knockback, explosions) applied by the client-side physics. */
    public record Impulse4S2C(double x, double y, double z, double w) implements CustomPacketPayload {
        public static final Type<Impulse4S2C> TYPE = typeOf("impulse4");
        public static final StreamCodec<RegistryFriendlyByteBuf, Impulse4S2C> CODEC = StreamCodec.of(
                (buf, v) -> { buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z); buf.writeDouble(v.w); },
                buf -> new Impulse4S2C(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Opens the vanilla command block screen for a 4D command block. */
    public record OpenCommandBlock4S2C(long pos, int state, String command, boolean trackOutput, String lastOutput, boolean auto) implements CustomPacketPayload {
        public static final Type<OpenCommandBlock4S2C> TYPE = typeOf("open_command_block");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenCommandBlock4S2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeLong(v.pos); buf.writeVarInt(v.state); buf.writeUtf(v.command, 32767);
                    buf.writeBoolean(v.trackOutput); buf.writeUtf(v.lastOutput, 32767); buf.writeBoolean(v.auto);
                },
                buf -> new OpenCommandBlock4S2C(buf.readLong(), buf.readVarInt(), buf.readUtf(32767), buf.readBoolean(), buf.readUtf(32767), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------------------------------------------------------------- server-bound

    public record SetCommandBlock4C2S(long pos, String command, int mode, boolean trackOutput, boolean conditional, boolean auto) implements CustomPacketPayload {
        public static final Type<SetCommandBlock4C2S> TYPE = typeOf("set_command_block");
        public static final StreamCodec<RegistryFriendlyByteBuf, SetCommandBlock4C2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeLong(v.pos); buf.writeUtf(v.command, 32767); buf.writeVarInt(v.mode);
                    buf.writeBoolean(v.trackOutput); buf.writeBoolean(v.conditional); buf.writeBoolean(v.auto);
                },
                buf -> new SetCommandBlock4C2S(buf.readLong(), buf.readUtf(32767), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Move4C2S(double x, double y, double z, double w, double[] frame, boolean onGround) implements CustomPacketPayload {
        public static final Type<Move4C2S> TYPE = typeOf("move4");
        public static final StreamCodec<RegistryFriendlyByteBuf, Move4C2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z); buf.writeDouble(v.w);
                    for (int i = 0; i < 9; i++) buf.writeDouble(v.frame[i]);
                    buf.writeBoolean(v.onGround);
                },
                buf -> {
                    double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble(), w = buf.readDouble();
                    double[] f = new double[9];
                    for (int i = 0; i < 9; i++) f[i] = buf.readDouble();
                    return new Move4C2S(x, y, z, w, f, buf.readBoolean());
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static final byte ACTION_BREAK = 0, ACTION_PLACE = 1, ACTION_USE = 2, ACTION_ATTACK = 3, ACTION_PICK = 4, ACTION_USE_ENTITY = 5;

    public record Action4C2S(byte action, int x, int y, int z, int w, byte face, int entityId) implements CustomPacketPayload {
        public static final Type<Action4C2S> TYPE = typeOf("action4");
        public static final StreamCodec<RegistryFriendlyByteBuf, Action4C2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeByte(v.action);
                    buf.writeVarInt(v.x); buf.writeVarInt(v.y); buf.writeVarInt(v.z); buf.writeVarInt(v.w);
                    buf.writeByte(v.face);
                    buf.writeVarInt(v.entityId);
                },
                buf -> new Action4C2S(buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByte(), buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------------------------------------------------------------- helpers

    static void writeLongs(ByteBuf buf, long[] a) {
        net.minecraft.network.VarInt.write(buf, a.length);
        for (long l : a) buf.writeLong(l);
    }

    static long[] readLongs(ByteBuf buf) {
        int n = net.minecraft.network.VarInt.read(buf);
        if (n > (1 << 20)) throw new IllegalArgumentException("too many entries: " + n);
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = buf.readLong();
        return a;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(ColumnS2C.TYPE, ColumnS2C.CODEC);
        r.playToClient(UnloadColumnsS2C.TYPE, UnloadColumnsS2C.CODEC);
        r.playToClient(BlockUpdatesS2C.TYPE, BlockUpdatesS2C.CODEC);
        r.playToClient(EntitiesS2C.TYPE, EntitiesS2C.CODEC);
        r.playToClient(Teleport4S2C.TYPE, Teleport4S2C.CODEC);
        r.playToClient(Impulse4S2C.TYPE, Impulse4S2C.CODEC);
        r.playToClient(OpenCommandBlock4S2C.TYPE, OpenCommandBlock4S2C.CODEC);
        r.playToServer(SetCommandBlock4C2S.TYPE, SetCommandBlock4C2S.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerWorld4.handleSetCommandBlock(sp, p);
        });
        r.playToServer(Move4C2S.TYPE, Move4C2S.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerWorld4.handleMove(sp, p);
        });
        r.playToServer(Action4C2S.TYPE, Action4C2S.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerWorld4.handleAction(sp, p);
        });
    }
}
