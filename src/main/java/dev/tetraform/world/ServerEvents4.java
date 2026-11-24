package dev.tetraform.world;

import dev.tetraform.PlayerState4;
import dev.tetraform.Tetraform;
import dev.tetraform.math.Vec4;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class ServerEvents4 {
    private ServerEvents4() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var level = event.getServer().getLevel(Tetraform.TESSERACT);
        if (level == null) return;
        ServerWorld4 w = level.players().isEmpty() ? ServerWorld4.getIfLoaded() : ServerWorld4.get(event.getServer());
        if (w != null) w.tick();
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        ServerWorld4 w = ServerWorld4.get(sp.level().getServer());
        if (w == null) return;
        if (event.getTo() == Tetraform.TESSERACT) w.onPlayerEnter(sp);
        else if (event.getFrom() == Tetraform.TESSERACT) w.onPlayerLeave(sp);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && Tetraform.isTesseract(sp.level())) {
            ServerWorld4 w = ServerWorld4.get(sp.level().getServer());
            if (w != null) w.onPlayerEnter(sp);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerWorld4 w = ServerWorld4.getIfLoaded();
            if (w != null) w.onPlayerLeave(sp);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && Tetraform.isTesseract(sp.level())) {
            ServerWorld4 w = ServerWorld4.get(sp.level().getServer());
            if (w != null) {
                PlayerState4 st = ServerWorld4.state(sp);
                st.pos = w.findSurface(0.5, 0.5, 0.5);
                w.onPlayerEnter(sp);
            }
        }
    }

    @SubscribeEvent
    public static void onSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl && Tetraform.isTesseract(sl)) {
            ServerWorld4 w = ServerWorld4.getIfLoaded();
            if (w != null) w.saveAll(false);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        ServerWorld4.shutdown();
    }

    /** Items thrown inside the tesseract become 4D item entities thrown along the 4D look direction. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp) || !Tetraform.isTesseract(sp.level())) return;
        ServerWorld4 w = ServerWorld4.get(sp.level().getServer());
        if (w == null) return;
        event.setCanceled(true);
        PlayerState4 st = ServerWorld4.state(sp);
        Vec4 look = st.frame.lookDirection(sp.getYRot(), sp.getXRot());
        w.dropItem(st.pos.add(0, 1.3, 0, 0).add(look.scale(0.4)), event.getEntity().getItem());
        var e = w.entities().stream().reduce((a, b) -> b).orElse(null);
        if (e != null) {
            e.vel = look.scale(0.3).add(0, 0.1, 0, 0);
            e.pickupDelay = 40;
        }
    }
}
