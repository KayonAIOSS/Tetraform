package dev.tetraform.client;

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
        net.minecraft.world.entity.Entity bestEntity = null;
        double bestDist = Math.min(blockDist, player.entityInteractionRange());
        for (net.minecraft.world.entity.Entity e : Minecraft.getInstance().level.entitiesForRendering()) {
            if (e == player || !e.isPickable() || e.isSpectator()) continue;
            net.minecraft.world.phys.AABB bb = e.getBoundingBox();
            double w = dev.tetraform.world.W4.get(e), hw = Math.max(0.15, e.getBbWidth() / 2);
            AABB4 box = new AABB4(bb.minX, bb.minY, bb.minZ, w - hw, bb.maxX, bb.maxY, bb.maxZ, w + hw);
            double d = Physics4.rayBox(eye, dir, box);
            if (!Double.isNaN(d) && d < bestDist) {
                bestDist = d;
                bestEntity = e;
            }
        }
        ClientState4.target = hit;
        ClientState4.targetEntity = bestEntity;

        // continuous breaking while the attack key is held
        Minecraft mc = Minecraft.getInstance();
        boolean attacking = mc.options.keyAttack.isDown() && mc.gui.screen() == null;
        if (!attacking || hit == null || bestEntity != null) {
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
        float hx = 0.5f, hy = 0.5f, hz = 0.5f;
        if (hit != null) {
            hx = clamp01((float) (hit.point().x() - p.x()));
            hy = clamp01((float) (hit.point().y() - p.y()));
            hz = clamp01((float) (hit.point().z() - p.z()));
        }
        ClientPacketDistributor.sendToServer(new Net4.Action4C2S(action, p.x(), p.y(), p.z(), p.w(),
                (byte) (hit == null ? 0 : hit.face().ordinal()), entity, hx, hy, hz));
    }

    private static float clamp01(float f) {
        return f < 0 ? 0 : (f > 1 ? 1 : f);
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
        net.minecraft.world.entity.Entity ent = ClientState4.targetEntity;
        if (event.isAttack()) {
            event.setCanceled(true);
            event.setSwingHand(true);
            if (ent != null) {
                // a vanilla entity: use vanilla's own attack (server checks reach in world coordinates)
                mc.gameMode.attack(player, ent);
            } else if (hit != null) {
                ClientState4.breaking = hit.pos();
                if (player.isCreative() && attackCooldown == 0) {
                    send(Net4.ACTION_BREAK, hit.pos(), hit, -1);
                    predictBreak(hit.pos());
                    attackCooldown = 5;
                }
            }
        } else if (event.isUseItem()) {
            if (ent != null) {
                // right-click a creature: vanilla interaction (trading, breeding, shearing, riding...)
                event.setCanceled(true);
                if (useCooldown > 0) return;
                useCooldown = 4;
                var hitResult = new net.minecraft.world.phys.EntityHitResult(ent, ent.position().add(0, ent.getBbHeight() / 2, 0));
                var result = mc.gameMode.interact(player, ent, hitResult, event.getHand());
                if (result.consumesAction()) event.setSwingHand(true);
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
