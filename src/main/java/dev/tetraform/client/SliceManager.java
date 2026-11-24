package dev.tetraform.client;

import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Column4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Keeps the terrain meshes in step with the viewer's hyperplane. A remesh happens only when the
 * <em>hyperplane</em> changes: the slice rotates into W, or the viewer crosses a slab along the normal. Walking
 * and mouse look inside the slice never trigger one. Block and column changes dirty only the sections their
 * projection touches.
 */
public final class SliceManager {
    private SliceManager() {}

    private static long dirtyMinX = Long.MAX_VALUE, dirtyMinZ, dirtyMaxX, dirtyMaxZ;
    private static boolean dirtyAll;

    /** Depth used for meshing. Axis-aligned slices only change when crossing an integer W. */
    public static double quantizeDepth(SliceFrame f, double depth) {
        if (f.isAxisAligned()) return Math.floor(depth) + 0.5;
        return Math.round(depth * 8.0) / 8.0 + 1.0E-4;
    }

    /** Call each tick after the frame or position changed; schedules a full remesh if the hyperplane moved. */
    public static void update() {
        ClientState4.MeshParams m = ClientState4.mesh;
        double depth = quantizeDepth(ClientState4.FRAME, ClientState4.FRAME.depthOf(ClientState4.pos.add(0, 0, 0, 0)));
        boolean frameChanged = !m.frame().approxEquals(ClientState4.FRAME, 1.0E-9);
        if (frameChanged || Math.abs(depth - m.depth()) > 1.0E-6) {
            ClientState4.mesh = new ClientState4.MeshParams(ClientState4.FRAME.copy(), depth, m.version() + 1);
            dirtyAll = true;
        }
        flush();
    }

    public static void forceRemesh() {
        ClientState4.MeshParams m = ClientState4.mesh;
        ClientState4.mesh = new ClientState4.MeshParams(ClientState4.FRAME.copy(),
                quantizeDepth(ClientState4.FRAME, ClientState4.FRAME.depthOf(ClientState4.pos)), m.version() + 1);
        dirtyAll = true;
    }

    /** Render-space position (vanilla coordinates) of a 4D point under the current mesh frame. */
    public static double[] toRender(Vec4 p) {
        SliceFrame f = ClientState4.mesh.frame();
        return new double[]{f.localX(p.x(), p.z(), p.w()), p.y(), f.localZ(p.x(), p.z(), p.w())};
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
        SliceFrame f = ClientState4.mesh.frame();
        double cx = p.x() + 0.5, cz = p.z() + 0.5, cw = p.w() + 0.5;
        double rx = f.localX(cx, cz, cw), rz = f.localZ(cx, cz, cw);
        markRender(rx - 2, rz - 2, rx + 2, rz + 2);
    }

    public static void markColumn(int cx, int cz, int cw) {
        SliceFrame f = ClientState4.mesh.frame();
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double depth = ClientState4.mesh.depth();
        // skip columns that do not reach the hyperplane at all
        double ccx = cx * 16 + 8, ccz = cz * 16 + 8, ccw = cw * 16 + 8;
        double h = 8 * (Math.abs(f.normalX()) + Math.abs(f.normalZ()) + Math.abs(f.normalW())) + 1.5;
        if (Math.abs(f.normalX() * ccx + f.normalZ() * ccz + f.normalW() * ccw - depth) > h) return;
        for (int i = 0; i < 8; i++) {
            double x = cx * 16 + ((i & 1) != 0 ? 17 : -1);
            double z = cz * 16 + ((i & 2) != 0 ? 17 : -1);
            double w = cw * 16 + ((i & 4) != 0 ? 17 : -1);
            double rx = f.localX(x, z, w), rz = f.localZ(x, z, w);
            minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
            minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
        }
        markRender(minX, minZ, maxX, maxZ);
    }

    private static void flush() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int ymax = (Column4.HEIGHT >> 4) - 1;
        if (dirtyAll) {
            dirtyAll = false;
            dirtyMinX = Long.MAX_VALUE;
            var p = Minecraft.getInstance().player;
            if (p == null) return;
            int rd = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
            int sx = p.blockPosition().getX() >> 4, sz = p.blockPosition().getZ() >> 4;
            level.setSectionRangeDirty(sx - rd, 0, sz - rd, sx + rd, ymax, sz + rd);
            return;
        }
        if (dirtyMinX != Long.MAX_VALUE) {
            var p = Minecraft.getInstance().player;
            int rd = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
            long x0 = dirtyMinX, x1 = dirtyMaxX, z0 = dirtyMinZ, z1 = dirtyMaxZ;
            if (p != null) {
                int sx = p.blockPosition().getX() >> 4, sz = p.blockPosition().getZ() >> 4;
                x0 = Math.max(x0, sx - rd); x1 = Math.min(x1, sx + rd);
                z0 = Math.max(z0, sz - rd); z1 = Math.min(z1, sz + rd);
            }
            if (x0 <= x1 && z0 <= z1) level.setSectionRangeDirty((int) x0, 0, (int) z0, (int) x1, ymax, (int) z1);
            dirtyMinX = Long.MAX_VALUE;
        }
    }
}
