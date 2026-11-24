package dev.tetraform.client;

import dev.tetraform.Hooks4;
import dev.tetraform.Tetraform;
import dev.tetraform.client.render.EntityRenderer4;
import dev.tetraform.client.render.SliceMesher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Tetraform.MODID, dist = Dist.CLIENT)
public final class TetraformClient {
    public TetraformClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(Keys4::register);
        modBus.addListener(ClientNet4::register);
        NeoForge.EVENT_BUS.addListener(SliceMesher::onAddSectionGeometry);
        NeoForge.EVENT_BUS.addListener(EntityRenderer4::onSubmit);
        NeoForge.EVENT_BUS.addListener(Interaction4::onInteraction);
        NeoForge.EVENT_BUS.addListener(Hud4::onRenderGui);
        NeoForge.EVENT_BUS.addListener(TetraformClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TetraformClient::onMovementInput);
        NeoForge.EVENT_BUS.addListener(TetraformClient::onRenderPlayer);
        NeoForge.EVENT_BUS.addListener(TetraformClient::onLogout);
        NeoForge.EVENT_BUS.addListener(Rotation4::onScroll);
        Hooks4.travelHook = p -> ClientState4.active && p == Minecraft.getInstance().player;
    }

    private static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        boolean now = Tetraform.is4D(mc.level);
        if (ClientState4.active && !now) {
            ClientState4.reset();
            ClientNet4.clearPending();
        }
        ClientState4.active = now;
        if (!now) {
            hintIn3D(mc);
            return;
        }
        ClientNet4.drain();
        LocalPlayer player = mc.player;
        // Middle click: vanilla only picks from its own (empty) 3D hit result, so handle it here first.
        while (mc.options.keyPickItem.consumeClick()) {
            if (player != null && ClientState4.target != null && ClientState4.targetEntity < 0) Interaction4.pick();
        }
        if (player != null && ClientState4.hasPos) Interaction4.tick(player);
    }

    private static long lastHint;

    /** Pressing a 4D control in a 3D world explains why nothing happens instead of failing silently. */
    private static void hintIn3D(Minecraft mc) {
        boolean pressed = false;
        for (var k : new net.minecraft.client.KeyMapping[]{Keys4.ANA, Keys4.KATA, Keys4.ROTATE_ANA, Keys4.ROTATE_KATA, Keys4.SNAP_270, Keys4.SNAP_0, Keys4.SNAP_90, Keys4.SNAP_180}) {
            while (k.consumeClick()) pressed = true;
        }
        if (!pressed || mc.player == null || mc.level == null) return;
        long now = mc.level.getGameTime();
        if (now - lastHint < 60) return;
        lastHint = now;
        mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.translatable("tetraform.hint.not_4d"), false);
    }

    private static void onMovementInput(MovementInputUpdateEvent event) {
        if (!ClientState4.active || !(event.getEntity() instanceof LocalPlayer lp)) return;
        Movement4.tick(lp, event.getInput());
    }

    /** Other players are drawn as sliced 4D bodies by {@link EntityRenderer4}; hide their 3D models. */
    private static void onRenderPlayer(RenderPlayerEvent.Pre<?> event) {
        if (!ClientState4.active) return;
        var mc = Minecraft.getInstance();
        if (mc.player != null && event.getRenderState().id != mc.player.getId()) event.setCanceled(true);
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState4.active = false;
        ClientState4.reset();
        ClientNet4.clearPending();
    }
}
