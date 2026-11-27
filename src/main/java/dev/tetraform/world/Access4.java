package dev.tetraform.world;

import dev.tetraform.Tetraform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lets ordinary vanilla code run inside the 4D world. While an entity ticks, every vanilla block read
 * (pathfinding, collisions, line of sight, fluids...) and block write (doors, explosions, endermen...) is
 * answered from the 4D world in that entity's own XYZ slice at its W. Vanilla AI therefore works unchanged:
 * each creature is an ordinary 3D creature living in its own W layer.
 * <p>
 * On the client's render thread, block reads outside any entity tick use the viewer's render mapping (camera
 * fog, particles, sounds near the camera).
 */
public final class Access4 {
    private Access4() {}

    /** Maps vanilla (x, y, z) to a 4D block for the current context. */
    public interface Mapper {
        BlockState get(int x, int y, int z);

        /** Returns false when writes are not supported in this context (client). */
        boolean set(int x, int y, int z, BlockState state, boolean notify);

        int surface(int x, int z);

        /** The W of this slice (NaN on the client's render mapping, which isn't a fixed W). */
        default double w() { return Double.NaN; }

        /** The 4D world behind this mapping, on the logical server; null on the client. */
        default ServerWorld4 world() { return null; }

        /** The block entity at (x, y, z) in this slice, or null. */
        default net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(int x, int y, int z) { return null; }
    }

    /** The server-side slice context active on this thread (an entity tick, a 4D interaction or block tick), or null. */
    public static Mapper serverContext() {
        Mapper m = CTX.get();
        return m != null && m.world() != null ? m : null;
    }

    private static final ThreadLocal<Mapper> CTX = new ThreadLocal<>();

    /** Installed by the client: render-space mapping used on the render thread when no entity is ticking. */
    public static volatile java.util.function.Function<Level, Mapper> clientDefault = l -> null;

    public static Mapper current(Level level) {
        Mapper m = CTX.get();
        if (m != null) {
            // a slice of one 4D level never answers for another level (e.g. the Nether during portal travel)
            if (m.world() == null || m.world().serverLevel() == level) return m;
            return null;
        }
        if (level.isClientSide()) return clientDefault.apply(level);
        return null;
    }

    /** The server slice context, only if it belongs to {@code level}. */
    public static Mapper serverContext(Level level) {
        Mapper m = CTX.get();
        return m != null && m.world() != null && m.world().serverLevel() == level ? m : null;
    }

    /** Only an explicit entity context (never the client render default), used by chunk-level access. */
    public static Mapper entityContextOnly() {
        return CTX.get();
    }

    public static Mapper push(Mapper m) {
        Mapper prev = CTX.get();
        CTX.set(m);
        return prev;
    }

    public static void pop(Mapper prev) {
        if (prev == null) CTX.remove();
        else CTX.set(prev);
    }

    /** A world-coordinate mapper for an XYZ slice at a fixed W, on the logical server. */
    public static Mapper server(ServerWorld4 w, double wCoord) {
        final int bw = (int) Math.floor(wCoord);
        return new Mapper() {
            @Override
            public BlockState get(int x, int y, int z) {
                return w.level().getBlock(x, y, z, bw);
            }

            @Override
            public boolean set(int x, int y, int z, BlockState state, boolean notify) {
                return w.setBlock(new dev.tetraform.math.BlockPos4(x, y, z, bw), state, notify);
            }

            @Override
            public int surface(int x, int z) {
                return w.level().heightAt(x, z, bw);
            }

            @Override
            public double w() {
                return wCoord;
            }

            @Override
            public ServerWorld4 world() {
                return w;
            }

            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(int x, int y, int z) {
                return w.vanillaBlockEntity(new dev.tetraform.math.BlockPos4(x, y, z, bw));
            }
        };
    }

    /**
     * A mapper for code that names blocks by a client's render-space cell: render cell {@code anchor} is the 4D
     * block {@code anchor4}, and other cells are offset from it along the axes of the viewer's {@code frame}.
     */
    public static Mapper relative(ServerWorld4 w, BlockPos anchor, dev.tetraform.math.BlockPos4 anchor4, dev.tetraform.math.SliceFrame frame) {
        final dev.tetraform.math.SliceFrame f = frame.copy();
        return new Mapper() {
            private dev.tetraform.math.BlockPos4 at(int x, int y, int z) {
                if (x == anchor.getX() && y == anchor.getY() && z == anchor.getZ()) return anchor4;
                var d = f.toWorld(x - anchor.getX(), 0, z - anchor.getZ(), 0);
                return new dev.tetraform.math.BlockPos4(anchor4.x() + (int) Math.round(d.x()), anchor4.y() + y - anchor.getY(),
                        anchor4.z() + (int) Math.round(d.z()), anchor4.w() + (int) Math.round(d.w()));
            }

            @Override
            public BlockState get(int x, int y, int z) {
                return w.getBlock(at(x, y, z));
            }

            @Override
            public boolean set(int x, int y, int z, BlockState state, boolean notify) {
                return w.setBlock(at(x, y, z), state, notify);
            }

            @Override
            public int surface(int x, int z) {
                var p = at(x, 0, z);
                return w.level().heightAt(p.x(), p.z(), p.w());
            }

            @Override
            public double w() {
                return anchor4.w() + 0.5;
            }

            @Override
            public ServerWorld4 world() {
                return w;
            }

            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(int x, int y, int z) {
                return w.vanillaBlockEntity(at(x, y, z));
            }
        };
    }

    /**
     * A sideways slice for vanilla code: vanilla x is 4D x, vanilla z is 4D <b>w</b>, and 4D z is fixed at
     * {@code zFixed}. Nether portal frames built in the w-y plane are found and lit through this.
     */
    public static Mapper swapped(ServerWorld4 w, int zFixed) {
        return new Mapper() {
            @Override
            public BlockState get(int x, int y, int z) {
                return w.level().getBlock(x, y, zFixed, z);
            }

            @Override
            public boolean set(int x, int y, int z, BlockState state, boolean notify) {
                return w.setBlock(new dev.tetraform.math.BlockPos4(x, y, zFixed, z), state, notify);
            }

            @Override
            public int surface(int x, int z) {
                return w.level().heightAt(x, zFixed, z);
            }

            @Override
            public ServerWorld4 world() {
                return w;
            }

            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(int x, int y, int z) {
                return w.vanillaBlockEntity(new dev.tetraform.math.BlockPos4(x, y, zFixed, z));
            }
        };
    }

    public static <T> T run(Mapper m, java.util.function.Supplier<T> s) {
        Mapper prev = push(m);
        try {
            return s.get();
        } finally {
            pop(prev);
        }
    }

    private static final ThreadLocal<java.util.ArrayDeque<Object>> SCOPES = ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final Object SKIP = new Object(), NONE = new Object();

    /** For HEAD/RETURN injection pairs: enters {@code m} (or nothing if null) until the matching {@link #exitScope()}. */
    public static void enterScope(Mapper m) {
        if (m == null) {
            SCOPES.get().push(SKIP);
            return;
        }
        Mapper prev = push(m);
        SCOPES.get().push(prev == null ? NONE : prev);
    }

    public static void exitScope() {
        Object o = SCOPES.get().poll();
        if (o == null || o == SKIP) return;
        pop(o == NONE ? null : (Mapper) o);
    }

    /** Runs {@code r} with vanilla block access mapped to the slice at W = {@code wCoord} of {@code w}. */
    public static void inSlice(ServerWorld4 w, double wCoord, Runnable r) {
        Mapper prev = push(server(w, wCoord));
        try {
            r.run();
        } finally {
            pop(prev);
        }
    }

    public static <T> T inSlice(ServerWorld4 w, double wCoord, java.util.function.Supplier<T> s) {
        Mapper prev = push(server(w, wCoord));
        try {
            return s.get();
        } finally {
            pop(prev);
        }
    }

    /** Client-side slice mapper used while a (non-local-player) entity ticks. */
    public static volatile java.util.function.DoubleFunction<Mapper> clientSlice = wCoord -> null;
    /** Client-side render-space mapper for the local player. */
    public static volatile java.util.function.Supplier<Mapper> clientRender = () -> null;

    /** The mapper for ticking {@code e} in {@code level}, or null if the level is not 4D. */
    public static Mapper forEntity(Level level, Entity e) {
        if (!Tetraform.is4D(level)) return null;
        if (level instanceof ServerLevel sl) {
            ServerWorld4 w = ServerWorld4.getIfLoaded(sl);
            if (w == null) return null;
            if (e instanceof net.minecraft.server.level.ServerPlayer sp) {
                Double mw = w.menuSlice(sp);
                if (mw != null) return server(w, mw);
            }
            return server(w, W4.get(e));
        }
        if (Hooks4Client.isLocalPlayer(e)) return clientRender.get();
        return clientSlice.apply(W4.get(e));
    }

    public static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Small indirection so common code can ask "is this the local player?" without client classes. */
    public static final class Hooks4Client {
        public static volatile java.util.function.Predicate<Entity> localPlayer = e -> false;

        public static boolean isLocalPlayer(Entity e) {
            return localPlayer.test(e);
        }
    }
}
