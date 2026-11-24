package dev.tetraform.client;

import dev.tetraform.entity.Physics4;
import dev.tetraform.math.AABB4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.mixin.ClientInputAccessor;
import dev.tetraform.net.Net4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client-authoritative 4D player physics. Runs in place of vanilla movement each tick, mirrors the result into
 * the vanilla player as render-space coordinates, and reports the true 4D position to the server.
 */
public final class Movement4 {
    private Movement4() {}

    private static final double WALK = 0.2158, SPRINT = 0.2806, SNEAK_FACTOR = 0.3;
    private static int jumpTapTimer;
    private static boolean lastJump;
    private static Vec4 lastSent;
    private static double lastSentZW;
    private static int sendTimer;
    private static Vec4 pendingImpulse = Vec4.ZERO;

    public static void addImpulse(Vec4 v) {
        pendingImpulse = pendingImpulse.add(v);
    }

    public static void tick(LocalPlayer player, ClientInput input) {
        Input in = input.keyPresses;
        // Neutralize vanilla movement (it is replaced by 4D movement below). Keep shift for the sneak pose.
        input.keyPresses = new Input(false, false, false, false, false, in.shift(), false);
        ((ClientInputAccessor) input).tetraform$setMoveVector(Vec2.ZERO);
        player.setDeltaMovement(Vec3.ZERO);

        Rotation4.tick(player);
        if (!ClientState4.hasPos) return;
        ClientState4.prevPos = ClientState4.pos;

        Vec4 pos = ClientState4.pos;
        int bx = (int) Math.floor(pos.x()), bz = (int) Math.floor(pos.z()), bw = (int) Math.floor(pos.w());
        if (!ClientState4.LEVEL.isLoaded(bx, bz, bw)) {
            ClientState4.waitingForTerrain = true;
            mirror(player);
            return;
        }
        ClientState4.waitingForTerrain = false;

        var abilities = player.getAbilities();
        boolean jump = in.jump();
        if (jump && !lastJump && abilities.mayfly) {
            if (jumpTapTimer > 0) {
                abilities.flying = !abilities.flying;
                player.onUpdateAbilities();
                jumpTapTimer = 0;
            } else {
                jumpTapTimer = 7;
            }
        }
        if (jumpTapTimer > 0) jumpTapTimer--;
        lastJump = jump;
        if (!abilities.mayfly && abilities.flying) abilities.flying = false;
        boolean flying = abilities.flying;

        float fwd = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
        float str = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
        float ana = (Keys4.ANA.isDown() ? 1 : 0) - (Keys4.KATA.isDown() ? 1 : 0);
        double yaw = Math.toRadians(player.getYRot());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        double lx = str * cos - fwd * sin;
        double lz = fwd * cos + str * sin;
        double lw = ana;
        double len = Math.sqrt(lx * lx + lz * lz + lw * lw);
        if (len > 1) { lx /= len; lz /= len; lw /= len; }
        SliceFrame f = ClientState4.FRAME;
        Vec4 wish = f.toWorld(lx, 0, lz, lw); // 4D horizontal wish direction

        boolean sprint = in.sprint() && fwd > 0 && !in.shift() && player.getFoodData().getFoodLevel() > 6;
        player.setSprinting(sprint);
        double speed = flying ? (sprint ? 1.0 : 0.55) : (sprint ? SPRINT : WALK);
        if (in.shift() && !flying) speed *= SNEAK_FACTOR;
        boolean inFluid = ClientState4.LEVEL.isFluidAt(pos.add(0, 0.4, 0, 0));
        if (inFluid && !flying) speed *= 0.45;

        Vec4 v = ClientState4.vel.add(pendingImpulse);
        pendingImpulse = Vec4.ZERO;
        double accel = flying ? 0.25 : ClientState4.onGround ? 0.6 : inFluid ? 0.2 : 0.06;
        double vx = v.x() + (wish.x() * speed - v.x()) * accel;
        double vz = v.z() + (wish.z() * speed - v.z()) * accel;
        double vw = v.w() + (wish.w() * speed - v.w()) * accel;
        double vy = v.y();
        if (flying) {
            double target = (jump ? 0.42 : 0) - (in.shift() ? 0.42 : 0);
            vy += (target - vy) * 0.4;
        } else if (inFluid) {
            vy = jump ? Math.min(vy + 0.04, 0.1) : (vy - 0.02) * 0.8;
        } else {
            if (jump && ClientState4.onGround) {
                vy = 0.42;
                if (sprint) {
                    vx += wish.x() * 0.2;
                    vz += wish.z() * 0.2;
                    vw += wish.w() * 0.2;
                }
            }
            vy = (vy - 0.08) * 0.98;
        }

        AABB4 box = AABB4.ofCenterBottom(pos, 0.3, in.shift() ? 1.5 : 1.8);
        Physics4.Result r = player.isSpectator() ? new Physics4.Result(new Vec4(vx, vy, vz, vw), false, false, false)
                : Physics4.move(ClientState4.LEVEL, box, new Vec4(vx, vy, vz, vw), flying ? 0 : 0.6, ClientState4.onGround);
        Vec4 d = r.delta();
        ClientState4.pos = pos.add(d);
        ClientState4.onGround = r.onGround();
        ClientState4.vel = new Vec4(d.x() != vx ? 0 : vx, r.verticalCollision() ? 0 : vy, d.z() != vz ? 0 : vz, d.w() != vw ? 0 : vw);
        if (flying && r.onGround() && !player.isSpectator()) {
            abilities.flying = false;
            player.onUpdateAbilities();
        }
        mirror(player);
        sendState(player);
    }

    /** Copies the 4D state into the vanilla player (render-space position under the mesh frame). */
    public static void mirror(LocalPlayer player) {
        SliceManager.update();
        double[] rp = SliceManager.toRender(ClientState4.pos);
        player.setPos(rp[0], rp[1], rp[2]);
        player.setOnGround(ClientState4.onGround);
        player.setDeltaMovement(Vec3.ZERO);
        if (player.position().distanceToSqr(player.xo, player.yo, player.zo) > 4) {
            player.xo = player.xOld = rp[0];
            player.yo = player.yOld = rp[1];
            player.zo = player.zOld = rp[2];
        }
    }

    private static void sendState(LocalPlayer player) {
        Vec4 p = ClientState4.pos;
        if (lastSent != null && lastSent.distanceSqr(p) < 1.0E-8 && lastSentZW == ClientState4.zw && ++sendTimer < 20) return;
        sendTimer = 0;
        lastSent = p;
        lastSentZW = ClientState4.zw;
        ClientPacketDistributor.sendToServer(new Net4.Move4C2S(p.x(), p.y(), p.z(), p.w(), ClientState4.FRAME.values(), ClientState4.onGround));
    }

    public static void forceSend() {
        lastSent = null;
    }
}
