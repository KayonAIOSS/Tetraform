package dev.tetraform.client;

import dev.tetraform.entity.Entity4Type;
import dev.tetraform.entity.Physics4;
import dev.tetraform.math.AABB4;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import dev.tetraform.world.Level4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** 4D targeting (a voxel DDA plus ray/hyperbox tests) and block and entity interaction. */
public final class Interaction4 {
    private Interaction4() {}

    private static int useCooldown;
    private static int attackCooldown;

    public static Vec4 lookDir(LocalPlayer p) {
        return ClientState4.FRAME.lookDirection(p.getYRot(), p.getXRot());
    }

    public static void tick(LocalPlayer player) {
        if (useCooldown > 0) useCooldown--;
        if (attackCooldown > 0) attackCooldown--;
        Vec4 eye = ClientState4.pos.add(0, player.isShiftKeyDown() ? 1.27 : 1.62, 0, 0);
        Vec4 dir = lookDir(player);
        double reach = player.isCreative() ? 5.0 : 4.5;
        boolean fluids = player.getMainHandItem().is(Items.BUCKET);
        Level4.Hit hit = ClientState4.LEVEL.raycast(eye, dir, reach, fluids);
        double blockDist = hit == null ? reach : hit.point().distance(eye);
        int bestEntity = -1;
        double bestDist = Math.min(blockDist, 3.5);
        for (ClientState4.EntityState es : ClientState4.ENTITIES.values()) {
            Net4.EntityView v = es.now;
            Entity4Type t = Entity4Type.VALUES[Math.floorMod(v.type(), Entity4Type.VALUES.length)];
            if (t == Entity4Type.ITEM) continue;
            AABB4 box = AABB4.ofCenterBottom(new Vec4(v.x(), v.y(), v.z(), v.w()), t.halfWidth, t.height);
            double d = Physics4.rayBox(eye, dir, box);
            if (!Double.isNaN(d) && d < bestDist) {
                bestDist = d;
                bestEntity = v.id();
            }
        }
        ClientState4.target = hit;
        ClientState4.targetEntity = bestEntity;

        // continuous breaking while the attack key is held
        Minecraft mc = Minecraft.getInstance();
        boolean attacking = mc.options.keyAttack.isDown() && mc.gui.screen() == null;
        if (!attacking || hit == null || bestEntity >= 0) {
            ClientState4.breaking = null;
            ClientState4.breakProgress = 0;
            return;
        }
        if (player.isCreative()) {
            if (attackCooldown == 0 && ClientState4.breaking != null) {
                send(Net4.ACTION_BREAK, hit.pos(), hit, -1);
                predictBreak(hit.pos());
                attackCooldown = 5;
            }
            return;
        }
        if (!hit.pos().equals(ClientState4.breaking)) {
            ClientState4.breaking = hit.pos();
            ClientState4.breakProgress = 0;
        }
        BlockState s = ClientState4.LEVEL.getBlock(hit.pos());
        float hardness = s.getDestroySpeed(EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
        if (hardness < 0) return;
        float speed = player.getDestroySpeed(s);
        boolean harvest = !s.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(s);
        float delta = hardness == 0 ? 1 : speed / hardness / (harvest ? 30f : 100f);
        ClientState4.breakProgress += delta;
        if (ClientState4.breakProgress >= 1) {
            send(Net4.ACTION_BREAK, hit.pos(), hit, -1);
            predictBreak(hit.pos());
            ClientState4.breaking = null;
            ClientState4.breakProgress = 0;
        }
    }

    private static void predictBreak(BlockPos4 p) {
        ClientState4.LEVEL.setClient(p, Level4.AIR);
        SliceManager.markBlock(p);
    }

    private static void send(byte action, BlockPos4 p, Level4.Hit hit, int entity) {
        ClientPacketDistributor.sendToServer(new Net4.Action4C2S(action, p.x(), p.y(), p.z(), p.w(),
                (byte) (hit == null ? 0 : hit.face().ordinal()), entity));
    }

    public static void pick() {
        Level4.Hit hit = ClientState4.target;
        if (hit != null) send(Net4.ACTION_PICK, hit.pos(), hit, -1);
    }

    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!ClientState4.active) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Level4.Hit hit = ClientState4.target;
        int ent = ClientState4.targetEntity;
        if (event.isAttack()) {
            event.setCanceled(true);
            event.setSwingHand(true);
            if (ent >= 0) {
                send(Net4.ACTION_ATTACK, BlockPos4.ZERO, null, ent);
                player.resetAttackStrengthTicker();
            } else if (hit != null) {
                ClientState4.breaking = hit.pos();
                if (player.isCreative() && attackCooldown == 0) {
                    send(Net4.ACTION_BREAK, hit.pos(), hit, -1);
                    predictBreak(hit.pos());
                    attackCooldown = 5;
                }
            }
        } else if (event.isUseItem()) {
            if (ent >= 0) {
                // right-click on a 4D creature (instead of placing a block behind it)
                event.setCanceled(true);
                if (useCooldown > 0) return;
                useCooldown = 4;
                event.setSwingHand(true);
                send(Net4.ACTION_USE_ENTITY, BlockPos4.ZERO, null, ent);
                return;
            }
            if (hit == null) return; // let vanilla handle eating, bows, etc.
            event.setCanceled(true);
            if (useCooldown > 0) return;
            useCooldown = 4;
            event.setSwingHand(true);
            send(Net4.ACTION_USE, hit.pos(), hit, -1);
        } else if (event.isPickBlock()) {
            event.setCanceled(true);
            if (hit != null) send(Net4.ACTION_PICK, hit.pos(), hit, -1);
        }
    }
}
