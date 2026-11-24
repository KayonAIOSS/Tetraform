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
        Hooks4.travelHook = p -> ClientState4.active && p == Minecraft.getInstance().player;
    }

    private static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        boolean now = mc.level != null && mc.level.dimension() == Tetraform.TESSERACT;
        if (ClientState4.active && !now) {
            ClientState4.reset();
            ClientNet4.clearPending();
        }
        ClientState4.active = now;
        if (!now) return;
        ClientNet4.drain();
        LocalPlayer player = mc.player;
        if (player != null && ClientState4.hasPos) Interaction4.tick(player);
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
