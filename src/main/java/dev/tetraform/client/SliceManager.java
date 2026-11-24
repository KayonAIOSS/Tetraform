package dev.tetraform.client;

import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Column4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Keeps the terrain meshes in step with the viewer's hyperplane. A remesh happens only when the hyperplane
 * changes: the zw angle changes, or the viewer crosses a slab along the normal. Walking and mouse look never
 * trigger one. Block and column changes dirty only the sections their projection touches.
 */
public final class SliceManager {
    private SliceManager() {}

    private static long dirtyMinX = Long.MAX_VALUE, dirtyMinZ, dirtyMaxX, dirtyMaxZ;
    private static boolean dirtyAll;


    /** Depth used for meshing. Axis-aligned slices only change when crossing a whole block. */
    public static double quantizeDepth(SliceFrame f, double depth) {
        if (f.isAxisAligned()) return Math.floor(depth) + 0.5;
        return Math.round(depth * 16.0) / 16.0 + 1.0E-4;
    }

    /** Call each tick; schedules a full remesh if the hyperplane moved. */
    public static void update() {
        ClientState4.MeshParams m = ClientState4.mesh;
        SliceFrame f = ClientState4.FRAME;
        Vec4 p = ClientState4.pos;
        double depth = quantizeDepth(f, f.depthOf(p));
        boolean frameChanged = !m.frame().approxEquals(f, 1.0E-12);
        if (frameChanged || Math.abs(depth - m.depth()) > 1.0E-9) {
            double offX = m.offX(), offZ = m.offZ();
            if (frameChanged) {
                // keep the player's render-space position fixed so rotating never moves the camera
                offX = m.renderX(p.x(), p.z(), p.w()) - f.localX(p.x(), p.z(), p.w());
                offZ = m.renderZ(p.x(), p.z(), p.w()) - f.localZ(p.x(), p.z(), p.w());
            }
            ClientState4.mesh = new ClientState4.MeshParams(f.copy(), depth, offX, offZ, m.version() + 1);
            // Everything in view is rebuilt at once; vanilla/Sodium build the closest sections first.
            dirtyAll = true;
        }
        flush();
    }

    /** Fresh mapping after a teleport: offsets reset to zero (matches the server's view of the player). */
    public static void forceRemesh() {
        ClientState4.MeshParams m = ClientState4.mesh;
        SliceFrame f = ClientState4.FRAME;
        ClientState4.mesh = new ClientState4.MeshParams(f.copy(), quantizeDepth(f, f.depthOf(ClientState4.pos)), 0, 0, m.version() + 1);
        dirtyAll = true;
    }

    /** Render-space position (vanilla coordinates) of a 4D point under the current mesh mapping. */
    public static double[] toRender(Vec4 p) {
        ClientState4.MeshParams m = ClientState4.mesh;
        return new double[]{m.renderX(p.x(), p.z(), p.w()), p.y(), m.renderZ(p.x(), p.z(), p.w())};
    }

    private static void markRender(double minX, double minZ, double maxX, double maxZ) {
        long x0 = (long) Math.floor(minX) >> 4, x1 = (long) Math.floor(maxX) >> 4;
        long z0 = (long) Math.floor(minZ) >> 4, z1 = (long) Math.floor(maxZ) >> 4;
        if (dirtyMinX == Long.MAX_VALUE) {
            dirtyMinX = x0; dirtyMaxX = x1; dirtyMinZ = z0; dirtyMaxZ = z1;
        } else {
            dirtyMinX = Math.min(dirtyMinX, x0); dirtyMaxX = Math.max(dirtyMaxX, x1);
            dirtyMinZ = Math.min(dirtyMinZ, z0); dirtyMaxZ = Math.max(dirtyMaxZ, z1);
        }
    }

    public static void markBlock(BlockPos4 p) {
        ClientState4.MeshParams m = ClientState4.mesh;
        double cx = p.x() + 0.5, cz = p.z() + 0.5, cw = p.w() + 0.5;
        double rx = m.renderX(cx, cz, cw), rz = m.renderZ(cx, cz, cw);
        markRender(rx - 2, rz - 2, rx + 2, rz + 2);
    }

    public static void markColumn(int cx, int cz, int cw) {
        ClientState4.MeshParams m = ClientState4.mesh;
        SliceFrame f = m.frame();
        double depth = m.depth();
        double ccx = cx * 16 + 8, ccz = cz * 16 + 8, ccw = cw * 16 + 8;
        double h = 8 * (Math.abs(f.normalX()) + Math.abs(f.normalZ()) + Math.abs(f.normalW())) + 1.5;
        if (Math.abs(f.normalX() * ccx + f.normalZ() * ccz + f.normalW() * ccw - depth) > h) return;
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double x = cx * 16 + ((i & 1) != 0 ? 17 : -1);
            double z = cz * 16 + ((i & 2) != 0 ? 17 : -1);
            double w = cw * 16 + ((i & 4) != 0 ? 17 : -1);
            double rx = m.renderX(x, z, w), rz = m.renderZ(x, z, w);
            minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
            minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
        }
        markRender(minX, minZ, maxX, maxZ);
    }

    private static void flush() {
        ClientLevel level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (level == null || player == null) return;
        int ymax = (Column4.HEIGHT >> 4) - 1;
        int rd = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
        int sx = player.blockPosition().getX() >> 4, sz = player.blockPosition().getZ() >> 4;
        int contentTop = Math.min(ymax, (ClientState4.LEVEL.maxY() >> 4));
        if (dirtyAll) {
            dirtyAll = false;
            dirtyMinX = Long.MAX_VALUE;
            level.setSectionRangeDirty(sx - rd, 0, sz - rd, sx + rd, contentTop, sz + rd);
            return;
        }
        if (dirtyMinX != Long.MAX_VALUE) {
            long x0 = Math.max(dirtyMinX, sx - rd), x1 = Math.min(dirtyMaxX, sx + rd);
            long z0 = Math.max(dirtyMinZ, sz - rd), z1 = Math.min(dirtyMaxZ, sz + rd);
            if (x0 <= x1 && z0 <= z1) level.setSectionRangeDirty((int) x0, 0, (int) z0, (int) x1, ymax, (int) z1);
            dirtyMinX = Long.MAX_VALUE;
        }
    }
}
