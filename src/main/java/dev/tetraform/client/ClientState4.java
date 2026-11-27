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
    /** Sequence number of the last server teleport, echoed in movement packets. */
    public static int teleportSeq;

    /** Live orientation of the slice (physics and look): the world z axis rotated into w by {@link #zw}. */
    public static final SliceFrame FRAME = new SliceFrame();
    /** The zw rotation angle in radians, kept in [0, 2pi). */
    public static double zw;

    /**
     * Snapshot that terrain meshes were built with (read by mesh worker threads). Render-space coordinates are
     * {@code x' = frame.localX(p) + offX}, {@code y' = y}, {@code z' = frame.localZ(p) + offZ}. The offsets are
     * chosen when the frame changes so that the player stays put on screen while the slice rotates.
     */
    public static volatile MeshParams mesh = new MeshParams(new SliceFrame(), 0.5, 0, 0, 0, true);

    /**
     * {@code loadVisibly}: geometry that isn't cached yet shows as empty space until it is built (like vanilla
     * chunks after a teleport) instead of leaving the previous slice on screen. Set when the slice turns or the
     * player teleports; a small step in depth keeps the old mesh until the new one is ready, since it is nearly
     * the same picture.
     */
    public record MeshParams(SliceFrame frame, double depth, double offX, double offZ, int version, boolean loadVisibly) {
        public double renderX(double x, double z, double w) { return frame.localX(x, z, w) + offX; }
        public double renderZ(double x, double z, double w) { return frame.localZ(x, z, w) + offZ; }

        /** Same hyperplane (orientation and depth); the render offset may differ. */
        public boolean sameSlice(SliceFrame f, double d) {
            return Math.abs(depth - d) < 1.0E-9 && frame.approxEquals(f, 1.0E-9);
        }
    }

    public static void setZW(double angle) {
        double twoPi = Math.PI * 2;
        zw = ((angle % twoPi) + twoPi) % twoPi;
        // snap to exact quarter turns so axis-aligned views are exactly axis-aligned
        double q = zw / (Math.PI / 2);
        if (Math.abs(q - Math.rint(q)) < 1.0E-9) zw = Math.rint(q) * (Math.PI / 2) % twoPi;
        FRAME.set(SliceFrame.zw(zw));
    }

    // targeting
    public static Level4.Hit target;
    /** Vanilla entity under the crosshair (found with a 4D ray), or null. */
    public static net.minecraft.world.entity.Entity targetEntity;
    public static BlockPos4 breaking;
    public static float breakProgress;



    public static Vec4 eye() {
        return pos.add(0, 1.62, 0, 0);
    }

    public static void reset() {
        SliceManager.reset();
        LEVEL.clear();
        hasPos = false;
        waitingForTerrain = true;
        vel = Vec4.ZERO;
        target = null;
        targetEntity = null;
        breaking = null;
        breakProgress = 0;
    }
}
