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
            // the 4D arrival point was chosen by the respawn lookup (bed, anchor or world spawn, see ServerPlayerRespawn4Mixin)
            ServerWorld4 w = ServerWorld4.get(sp.level());
            if (w != null) w.onPlayerEnter(sp);
        }
    }

    /** Remembers the W of a bed, respawn anchor or /spawnpoint in a 4D world. */
    @SubscribeEvent
    public static void onSetSpawn(net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        PlayerState4 st = ServerWorld4.state(sp);
        if (event.getNewSpawn() == null) {
            st.spawnW = Double.NaN;
            return;
        }
        ServerLevel target = sp.level().getServer().getLevel(event.getSpawnLevel());
        if (target == null || !Tetraform.is4D(target)) return;
        Access4.Mapper ctx = Access4.serverContext(target);
        st.spawnW = ctx != null ? ctx.w() : st.pos.w();
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
        // spawned by something running in a slice (a spawner, breeding, a block, a mob's own tick): that slice's W
        Access4.Mapper ctx = Access4.serverContext();
        if (ctx == null && (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal)) {
            // a dragon or spike crystal saved by the End before it became 4D: the 4D End has its own
            event.setCanceled(true);
            return;
        }
        if (ctx != null && !(e instanceof ServerPlayer)) {
            W4.set(e, ctx.w());
            return;
        }
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
