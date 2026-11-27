package dev.tetraform.world;

import dev.tetraform.Config4;
import dev.tetraform.Tetraform;
import dev.tetraform.math.Vec4;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.List;

/**
 * Mobs in a 4D world: natural spawning with vanilla's groups and spawn lists, 4D-distance mob caps and
 * despawning, cheaper ticking far from players, and creeper explosions.
 * <p>
 * Caps count mobs within 128 blocks of a player in 4D (vanilla counts the 8-chunk square around each player).
 * By default the hostile cap is vanilla's 70 squared and the passive cap vanilla's 10 cubed, and passive animals
 * come in groups up to twice vanilla's size.
 */
public final class Mobs4 {
    private Mobs4() {}

    private record Spawn(EntityType<?> type, int weight, int min, int max) {}

    // vanilla plains spawn lists (weights and group sizes)
    private static final List<Spawn> HOSTILE = List.of(
            new Spawn(EntityTypes.SPIDER, 100, 4, 4), new Spawn(EntityTypes.ZOMBIE, 95, 4, 4),
            new Spawn(EntityTypes.ZOMBIE_VILLAGER, 5, 1, 1), new Spawn(EntityTypes.SKELETON, 100, 4, 4),
            new Spawn(EntityTypes.CREEPER, 100, 4, 4), new Spawn(EntityTypes.ENDERMAN, 10, 1, 4),
            new Spawn(EntityTypes.WITCH, 5, 1, 1));
    private static final List<Spawn> PASSIVE = List.of(
            new Spawn(EntityTypes.SHEEP, 12, 4, 4), new Spawn(EntityTypes.PIG, 10, 4, 4),
            new Spawn(EntityTypes.CHICKEN, 10, 4, 4), new Spawn(EntityTypes.COW, 8, 4, 4),
            new Spawn(EntityTypes.HORSE, 5, 2, 6), new Spawn(EntityTypes.DONKEY, 1, 1, 3));

    // vanilla Nether biome and fortress spawn lists, and the End's
    private static final List<Spawn> NETHER_WASTES = List.of(
            new Spawn(EntityTypes.GHAST, 50, 4, 4), new Spawn(EntityTypes.ZOMBIFIED_PIGLIN, 100, 4, 4),
            new Spawn(EntityTypes.MAGMA_CUBE, 2, 4, 4), new Spawn(EntityTypes.ENDERMAN, 1, 4, 4), new Spawn(EntityTypes.PIGLIN, 15, 4, 4));
    private static final List<Spawn> CRIMSON_FOREST = List.of(
            new Spawn(EntityTypes.ZOMBIFIED_PIGLIN, 1, 2, 4), new Spawn(EntityTypes.HOGLIN, 9, 3, 4), new Spawn(EntityTypes.PIGLIN, 5, 3, 4));
    private static final List<Spawn> WARPED_FOREST = List.of(new Spawn(EntityTypes.ENDERMAN, 1, 4, 4));
    private static final List<Spawn> SOUL_SAND_VALLEY = List.of(
            new Spawn(EntityTypes.SKELETON, 20, 5, 5), new Spawn(EntityTypes.GHAST, 50, 4, 4), new Spawn(EntityTypes.ENDERMAN, 1, 4, 4));
    private static final List<Spawn> BASALT_DELTAS = List.of(new Spawn(EntityTypes.GHAST, 40, 1, 1), new Spawn(EntityTypes.MAGMA_CUBE, 100, 2, 5));
    private static final List<Spawn> FORTRESS = List.of(
            new Spawn(EntityTypes.BLAZE, 10, 2, 3), new Spawn(EntityTypes.ZOMBIFIED_PIGLIN, 5, 4, 4),
            new Spawn(EntityTypes.WITHER_SKELETON, 8, 5, 5), new Spawn(EntityTypes.SKELETON, 2, 5, 5), new Spawn(EntityTypes.MAGMA_CUBE, 3, 4, 4));
    private static final List<Spawn> END = List.of(new Spawn(EntityTypes.ENDERMAN, 10, 4, 4));

    private static List<Spawn> hostileList(ServerWorld4 w, int x, int y, int z, int ww) {
        return switch (w.kind) {
            case NETHER -> {
                if (Fortress4.inside(w.generator().seed(), x, y, z, ww)) yield FORTRESS;
                yield switch (w.generator().nether().biome(x, z, ww)) {
                    case CRIMSON -> CRIMSON_FOREST;
                    case WARPED -> WARPED_FOREST;
                    case SOUL_VALLEY -> SOUL_SAND_VALLEY;
                    case DELTAS -> BASALT_DELTAS;
                    default -> NETHER_WASTES;
                };
            }
            case END -> END;
            default -> HOSTILE;
        };
    }

    public static final double CAP_RADIUS = 128;

    private static Spawn pick(List<Spawn> list, net.minecraft.util.RandomSource r) {
        int total = 0;
        for (Spawn s : list) total += s.weight;
        int n = r.nextInt(total);
        for (Spawn s : list) {
            n -= s.weight;
            if (n < 0) return s;
        }
        return list.get(0);
    }

    /** 4D position of any entity in a 4D level. */
    public static Vec4 pos4(Entity e) {
        if (e instanceof ServerPlayer sp) return ServerWorld4.state(sp).pos;
        return new Vec4(e.getX(), e.getY(), e.getZ(), W4.get(e));
    }

    /** Squared 4D distance from {@code e} to the nearest non-spectator player, or +inf. */
    public static double nearestPlayerDist2(ServerLevel level, Entity e) {
        Vec4 p = pos4(e);
        double best = Double.POSITIVE_INFINITY;
        for (ServerPlayer pl : level.players()) {
            if (pl.isSpectator()) continue;
            best = Math.min(best, ServerWorld4.state(pl).pos.distanceSqr(p));
        }
        return best;
    }

    // ------------------------------------------------------------------ natural spawning

    private static int[] hostileNear = new int[0], passiveNear = new int[0];

    static void tick(ServerWorld4 w, long tick) {
        ServerLevel level = w.serverLevel();
        if (!Config4.SPAWN_MOBS.get() || !level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS)) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        if (tick % 20 == 0 || hostileNear.length != players.size()) count(level, players);
        var rnd = w.random();
        boolean peaceful = level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL;
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            if (p.isSpectator()) continue;
            Vec4 pp = ServerWorld4.state(p).pos;
            // hostile: a few group attempts every tick, like vanilla's per-chunk attempts
            if (!peaceful && hostileNear[i] < Config4.HOSTILE_CAP.get()) {
                for (int a = 0; a < 4; a++) hostileNear[i] += attempt(w, pp, rnd, true);
            }
            // passive: occasional attempts on lit grass
            if (tick % 20 == i % 20 && passiveNear[i] < Config4.PASSIVE_CAP.get()) {
                for (int a = 0; a < 2; a++) passiveNear[i] += attempt(w, pp, rnd, false);
            }
        }
    }

    private static void count(ServerLevel level, List<ServerPlayer> players) {
        hostileNear = new int[players.size()];
        passiveNear = new int[players.size()];
        Vec4[] pp = new Vec4[players.size()];
        for (int i = 0; i < pp.length; i++) pp[i] = ServerWorld4.state(players.get(i)).pos;
        double r2 = CAP_RADIUS * CAP_RADIUS;
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Mob m) || m.isPersistenceRequired()) continue;
            MobCategory cat = e.getType().getCategory();
            boolean hostile = cat == MobCategory.MONSTER, passive = cat == MobCategory.CREATURE;
            if (!hostile && !passive) continue;
            Vec4 q = pos4(e);
            for (int i = 0; i < pp.length; i++) {
                if (pp[i].distanceSqr(q) > r2) continue;
                if (hostile) hostileNear[i]++;
                else passiveNear[i]++;
            }
        }
    }

    /** One spawn attempt (a whole group) somewhere 24..96 blocks from the player in 4D; returns mobs spawned. */
    private static int attempt(ServerWorld4 w, Vec4 pp, net.minecraft.util.RandomSource rnd, boolean hostile) {
        // uniform direction in the 3D horizontal space (x, z, w)
        double u = rnd.nextDouble() * 2 - 1, th = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - u * u);
        double dist = 24 + rnd.nextDouble() * 72;
        int x = (int) Math.floor(pp.x() + r * Math.cos(th) * dist), z = (int) Math.floor(pp.z() + u * dist),
                ww = (int) Math.floor(pp.w() + r * Math.sin(th) * dist);
        Level4 l = w.level();
        if (!hostile && w.kind != dev.tetraform.Tetraform.Kind.OVERWORLD) return 0;
        if (!l.isLoaded(x, z, ww)) return 0;
        int surface = l.heightAt(x, z, ww);
        // hostiles anywhere from bedrock to the surface (caves included), animals on the surface
        int y = hostile ? 1 + rnd.nextInt(Math.max(1, surface)) : surface;
        Spawn s = pick(hostile ? hostileList(w, x, y, z, ww) : PASSIVE, rnd);
        int max = hostile ? s.max : (int) Math.round(s.max * Config4.PASSIVE_GROUP_SCALE.get());
        int n = s.min + (max > s.min ? rnd.nextInt(max - s.min + 1) : 0);
        int spawned = 0;
        for (int k = 0; k < n * 3 && spawned < n; k++) {
            int sx = x + (k == 0 ? 0 : rnd.nextInt(11) - 5), sz = z + (k == 0 ? 0 : rnd.nextInt(11) - 5),
                    sw = ww + (k == 0 ? 0 : rnd.nextInt(5) - 2);
            if (!l.isLoaded(sx, sz, sw)) continue;
            int sy = hostile ? findFloor(l, sx, y, sz, sw) : l.heightAt(sx, sz, sw);
            if (sy < 1 || !spot(w, sx, sy, sz, sw, hostile)) continue;
            Entity e = w.spawn(s.type, new Vec4(sx + 0.5, sy, sz + 0.5, sw + 0.5), EntitySpawnReason.NATURAL);
            if (e == null) continue;
            // big mobs (ghasts, hoglins) need the room vanilla checks for
            if (e.getBbWidth() > 1.0f || e.getBbHeight() > 2.0f) {
                final Entity fe = e;
                if (!Access4.inSlice(w, sw + 0.5, () -> w.serverLevel().noCollision(fe))) {
                    e.discard();
                    continue;
                }
            }
            spawned++;
        }
        return spawned;
    }

    /** The nearest standable y at or below {@code y} (scanning down a few blocks), or -1. */
    private static int findFloor(Level4 l, int x, int y, int z, int w) {
        for (int d = 0; d < 8 && y - d > 1; d++) {
            int yy = y - d;
            if (l.getBlock(x, yy, z, w).isAir() && solid(l.getBlock(x, yy - 1, z, w))) return yy;
        }
        return -1;
    }

    private static boolean solid(BlockState s) {
        return BlockShapes4.fullOpaque(s) && !s.is(Blocks.BEDROCK);
    }

    /** Vanilla spawn rules adapted to 4D: room for the body, a solid floor, and the right light. */
    private static boolean spot(ServerWorld4 w, int x, int y, int z, int ww, boolean hostile) {
        Level4 l = w.level();
        BlockState feet = l.getBlock(x, y, z, ww), head = l.getBlock(x, y + 1, z, ww), floor = l.getBlock(x, y - 1, z, ww);
        if (!feet.isAir() || !head.isAir() || !solid(floor)) return false;
        boolean sky = y >= l.heightAt(x, z, ww);
        if (w.kind == dev.tetraform.Tetraform.Kind.NETHER) return w.blockLight(x, y, z, ww) <= 11;
        if (w.kind == dev.tetraform.Tetraform.Kind.END) return true;
        if (hostile) {
            // monsters need block light 0, and outdoors also a dark sky
            if (w.blockLight(x, y, z, ww) > 0) return false;
            return !sky || w.serverLevel().isDarkOutside();
        }
        return floor.is(Blocks.GRASS_BLOCK) && sky && w.blockLight(x, y, z, ww) + (w.serverLevel().isDarkOutside() ? 0 : 15) >= 9;
    }

    // ------------------------------------------------------------------ despawning and ticking

    /** Vanilla despawn rules, measured in 4D (vanilla would only look at x, y, z and ignore W). */
    @SubscribeEvent
    public static void onDespawnCheck(net.neoforged.neoforge.event.entity.living.MobDespawnEvent event) {
        Mob mob = event.getEntity();
        if (!(mob.level() instanceof ServerLevel level) || !Tetraform.is4D(level)) return;
        if (mob.isPersistenceRequired() || mob.requiresCustomPersistence()) {
            event.setResult(net.neoforged.neoforge.event.entity.living.MobDespawnEvent.Result.DENY);
            return;
        }
        if (level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL && !mob.getType().isAllowedInPeaceful()) {
            event.setResult(net.neoforged.neoforge.event.entity.living.MobDespawnEvent.Result.ALLOW);
            return;
        }
        double d2 = nearestPlayerDist2(level, mob);
        if (Double.isInfinite(d2)) {
            event.setResult(net.neoforged.neoforge.event.entity.living.MobDespawnEvent.Result.DENY);
            return;
        }
        MobCategory cat = mob.getType().getCategory();
        int far = cat.getDespawnDistance(), near = cat.getNoDespawnDistance();
        boolean despawn = d2 > (double) far * far && mob.removeWhenFarAway(d2)
                || mob.getNoActionTime() > 600 && mob.getRandom().nextInt(800) == 0 && d2 > (double) near * near && mob.removeWhenFarAway(d2);
        if (!despawn && d2 < (double) near * near) mob.setNoActionTime(0);
        event.setResult(despawn ? net.neoforged.neoforge.event.entity.living.MobDespawnEvent.Result.ALLOW
                : net.neoforged.neoforge.event.entity.living.MobDespawnEvent.Result.DENY);
    }

    /** Far from every player (in 4D), mobs tick every 2nd or 4th tick: big 4D populations stay affordable. */
    @SubscribeEvent
    public static void onMobTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level) || !Tetraform.is4D(level)) return;
        if (!Config4.THROTTLE_FAR_MOBS.get() || mob.isPassenger() || mob.isVehicle() || mob.isPersistenceRequired()) return;
        int phase = (int) ((level.getGameTime() + mob.getId()) & 3);
        if (phase == 0) return;
        double d2 = nearestPlayerDist2(level, mob);
        if (d2 > 96 * 96 || d2 > 48 * 48 && (phase & 1) == 1) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ creepers and explosions

    /** Creepers always hurt less (everywhere); their blasts spare blocks while the gamerule is on. */
    @SubscribeEvent
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        var src = event.getSource();
        if (src.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) && src.getDirectEntity() instanceof net.minecraft.world.entity.monster.Creeper) {
            event.setAmount((float) (event.getAmount() * Config4.CREEPER_DAMAGE.get()));
        }
    }

    @SubscribeEvent
    public static void onDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var explosion = event.getExplosion();
        if (explosion.getDirectSourceEntity() instanceof net.minecraft.world.entity.monster.Creeper
                && level.getGameRules().get(Tetraform.CREEPERS_SPARE_TERRAIN.get())) {
            event.getAffectedBlocks().clear();
        }
        if (Tetraform.is4D(level)) {
            // a blast only reaches entities within its radius along W as well
            Access4.Mapper ctx = Access4.serverContext();
            double w0 = ctx != null ? ctx.w() : explosion.getDirectSourceEntity() != null ? W4.get(explosion.getDirectSourceEntity()) : Double.NaN;
            if (!Double.isNaN(w0)) {
                double r = explosion.radius() * 2;
                event.getAffectedEntities().removeIf(e -> Math.abs(pos4(e).w() - w0) > r);
            }
        }
    }
}
