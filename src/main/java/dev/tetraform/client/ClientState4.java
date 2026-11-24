package dev.tetraform.client;

import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import dev.tetraform.world.Level4;

import java.util.HashMap;
import java.util.Map;

/** Everything the client knows about its own 4D state. Main-thread only unless noted. */
public final class ClientState4 {
    private ClientState4() {}

    public static boolean active;
    public static boolean hasPos;
    public static final ClientLevel4 LEVEL = new ClientLevel4();

    public static Vec4 pos = new Vec4(0.5, 100, 0.5, 0.5);
    public static Vec4 prevPos = pos;
    public static Vec4 vel = Vec4.ZERO;
    public static boolean onGround;
    public static boolean waitingForTerrain = true;

    /** Live orientation of the slice (physics and look). */
    public static final SliceFrame FRAME = new SliceFrame();

    /** Snapshot that terrain meshes were built with. Read by mesh worker threads through {@link MeshParams}. */
    public static volatile MeshParams mesh = new MeshParams(new SliceFrame(), 0.5, 0);

    public record MeshParams(SliceFrame frame, double depth, int version) {}

    // targeting
    public static Level4.Hit target;
    public static int targetEntity = -1;
    public static BlockPos4 breaking;
    public static float breakProgress;

    public static final class EntityState {
        public Net4.EntityView now;
        public Net4.EntityView prev;
    }

    public static final Map<Integer, EntityState> ENTITIES = new HashMap<>();

    public static Vec4 eye() {
        return pos.add(0, 1.62, 0, 0);
    }

    public static void reset() {
        LEVEL.clear();
        ENTITIES.clear();
        hasPos = false;
        waitingForTerrain = true;
        vel = Vec4.ZERO;
        target = null;
        targetEntity = -1;
        breaking = null;
        breakProgress = 0;
    }
}
