package dev.tetraform.world;

import dev.tetraform.PlayerState4;
import dev.tetraform.Tetraform;
import dev.tetraform.math.Vec4;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class ServerEvents4 {
    private ServerEvents4() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (!Tetraform.is4D(level)) continue;
            ServerWorld4 w = level.players().isEmpty() ? ServerWorld4.getIfLoaded(level) : ServerWorld4.get(level);
            if (w != null) w.tick();
        }
    }

    private static void enterIf4D(ServerPlayer sp) {
        ServerWorld4 w = ServerWorld4.get(sp.level());
        if (w != null) w.onPlayerEnter(sp);
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        ServerLevel from = sp.level().getServer().getLevel(event.getFrom());
        if (from != null) {
            ServerWorld4 old = ServerWorld4.getIfLoaded(from);
            if (old != null) old.onPlayerLeave(sp);
        }
        enterIf4D(sp);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) enterIf4D(sp);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerWorld4 w = ServerWorld4.getIfLoaded(sp.level());
            if (w != null) w.onPlayerLeave(sp);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerWorld4 w = ServerWorld4.get(sp.level());
            if (w != null) {
                PlayerState4 st = ServerWorld4.state(sp);
                st.pos = w.findSurface(0.5, 0.5, 0.5);
                w.onPlayerEnter(sp);
            }
        }
    }

    @SubscribeEvent
    public static void onSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel sl) {
            ServerWorld4 w = ServerWorld4.getIfLoaded(sl);
            if (w != null) w.saveAll(false);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        ServerWorld4.shutdown();
    }

    /**
     * Every entity that joins a 4D level gets a W: projectiles and dropped items take their owner's, everything
     * else (vanilla-spawned or summoned without a 4D position) the nearest player's.
     */
    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        var e = event.getEntity();
        if (event.getLevel().isClientSide() || !Tetraform.is4D(event.getLevel()) || W4.has(e)) return;
        net.minecraft.world.entity.Entity owner = null;
        if (e instanceof net.minecraft.world.entity.projectile.Projectile pr) owner = pr.getOwner();
        else if (e instanceof net.minecraft.world.entity.item.ItemEntity it) owner = it.getOwner();
        if (owner == null && e instanceof ServerPlayer sp) {
            W4.set(e, ServerWorld4.state(sp).pos.w());
            return;
        }
        if (owner == null) owner = event.getLevel().getNearestPlayer(e, 128);
        W4.set(e, owner == null ? 0.5 : W4.get(owner));
    }

    @SubscribeEvent
    public static void onEntityTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.Mob mob && mob.level() instanceof ServerLevel sl && Tetraform.is4D(sl)) {
            ServerWorld4 w = ServerWorld4.getIfLoaded(sl);
            if (w != null) w.tickMobW(mob);
        }
    }
}
