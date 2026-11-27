package dev.tetraform.client;

import dev.tetraform.math.Direction4;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Level4;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Publishes the 4D target through vanilla's own fields, {@code Minecraft.hitResult} and
 * {@code Minecraft.crosshairPickEntity}, in render-space coordinates. Mods that show or act on "what the player
 * is looking at" (Jade, WTHIT, minimaps, accessibility mods) read exactly these, so they see what the 4D crosshair
 * sees. Runs right after vanilla's pick, which can't see 4D terrain on its own.
 */
public final class Pick4 {
    private Pick4() {}

    private record Cell(int x, int y, int z, BlockState state, dev.tetraform.math.BlockPos4 pos4) {}

    private static volatile Cell cell;
    private static Cell sent;
    private static boolean sentAny;
    /** A client-side stand-in block entity for the targeted block (mods choose their tooltip providers by it). */
    private static net.minecraft.world.level.block.entity.BlockEntity standIn;
    private static Cell standInFor;

    /** The block entity to show for render cell (x, y, z): a stand-in when it is the targeted block, else null. */
    public static net.minecraft.world.level.block.entity.BlockEntity blockEntityAt(int x, int y, int z) {
        Cell c = cell;
        if (c == null || c.x != x || c.y != y || c.z != z) return null;
        if (standInFor != c) {
            standInFor = c;
            standIn = null;
            if (c.state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb) {
                try {
                    standIn = eb.newBlockEntity(new BlockPos(x, y, z), c.state);
                    if (standIn != null) standIn.setLevel(Minecraft.getInstance().level);
                } catch (RuntimeException e) {
                    standIn = null;
                }
            }
        }
        return standIn;
    }

    /** Tells the server which 4D block the crosshair is on, whenever that changes. */
    private static void report(Cell c) {
        boolean same = c == null ? !sentAny : sentAny && sent != null && sent.x == c.x && sent.y == c.y && sent.z == c.z && sent.pos4.equals(c.pos4);
        if (same) return;
        sent = c;
        sentAny = c != null;
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(c == null
                ? new dev.tetraform.net.Net4.Look4C2S(false, 0, 0, 0, 0, 0, 0, 0)
                : new dev.tetraform.net.Net4.Look4C2S(true, c.x, c.y, c.z, c.pos4.x(), c.pos4.y(), c.pos4.z(), c.pos4.w()));
    }

    /** The targeted block if (x, y, z) is the render cell named by the current block hit result, else null. */
    public static BlockState targetAt(int x, int y, int z) {
        Cell c = cell;
        return c != null && c.x == x && c.y == y && c.z == z ? c.state : null;
    }

    public static void afterVanillaPick(Minecraft mc) {
        if (!ClientState4.active || !ClientState4.hasPos || mc.player == null) {
            cell = null;
            return;
        }
        Entity ent = ClientState4.targetEntity;
        Level4.Hit hit = ClientState4.target;
        if (ent != null) {
            cell = null;
            report(null);
            double[] r = SliceManager.toRender(new Vec4(ent.getX(), ent.getY() + ent.getBbHeight() / 2, ent.getZ(), dev.tetraform.world.W4.get(ent)));
            mc.hitResult = new EntityHitResult(ent, new Vec3(r[0], r[1], r[2]));
            mc.crosshairPickEntity = ent;
            return;
        }
        mc.crosshairPickEntity = null;
        Vec3 eye = mc.player.getEyePosition();
        if (hit == null) {
            cell = null;
            report(null);
            Vec3 end = eye.add(mc.player.getViewVector(1f).scale(mc.player.blockInteractionRange()));
            mc.hitResult = BlockHitResult.miss(end, Direction.getApproximateNearest(end.subtract(eye)), BlockPos.containing(end));
            return;
        }
        ClientState4.MeshParams m = ClientState4.mesh;
        Vec4 p = hit.point();
        Direction face = toDirection(hit.face(), m);
        double rx = m.renderX(p.x(), p.z(), p.w()), rz = m.renderZ(p.x(), p.z(), p.w());
        Vec3 loc = new Vec3(rx, p.y(), rz);
        // the cell just inside the hit face holds the block
        BlockPos pos = BlockPos.containing(loc.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01));
        Cell prev = cell;
        BlockState st = ClientState4.LEVEL.getBlock(hit.pos());
        cell = prev != null && prev.x == pos.getX() && prev.y == pos.getY() && prev.z == pos.getZ() && prev.pos4.equals(hit.pos()) && prev.state == st
                ? prev : new Cell(pos.getX(), pos.getY(), pos.getZ(), st, hit.pos());
        report(cell);
        mc.hitResult = new BlockHitResult(loc, face, pos, false);
    }

    /** A 4D face direction as the nearest render-space direction (w faces land on the rotated z axis). */
    private static Direction toDirection(Direction4 d, ClientState4.MeshParams m) {
        if (d.dx != 0) return d.dx > 0 ? Direction.EAST : Direction.WEST;
        if (d.dy != 0) return d.dy > 0 ? Direction.UP : Direction.DOWN;
        // render z' of the face normal (0, dz, dw): frame.localZ is linear, so take the difference
        double nz = m.frame().localZ(0, d.dz, d.dw);
        if (Math.abs(nz) < 1.0E-6) return Direction.UP;
        return nz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
