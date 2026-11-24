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
    }

    private static final ThreadLocal<Mapper> CTX = new ThreadLocal<>();

    /** Installed by the client: render-space mapping used on the render thread when no entity is ticking. */
    public static volatile java.util.function.Function<Level, Mapper> clientDefault = l -> null;

    public static Mapper current(Level level) {
        Mapper m = CTX.get();
        if (m != null) return m;
        if (level.isClientSide()) return clientDefault.apply(level);
        return null;
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
        };
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
            return w == null ? null : server(w, W4.get(e));
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
