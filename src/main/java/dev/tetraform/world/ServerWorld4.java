package dev.tetraform.world;

import dev.tetraform.Config4;
import dev.tetraform.PlayerState4;
import dev.tetraform.Tetraform;
import dev.tetraform.entity.Entity4;
import dev.tetraform.entity.Entity4Type;
import dev.tetraform.math.AABB4;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Direction4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * The authoritative 4D world on the logical server: column streaming and persistence, block updates,
 * scheduled ticks (fluids, redstone, gravity), and 4D entities.
 */
public final class ServerWorld4 {
    private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, ServerWorld4> INSTANCES = new HashMap<>();

    private final MinecraftServer server;
    private final ServerLevel level;
    private final Level4 level4 = new Level4();
    private final TerrainGenerator4 generator;
    private final ExecutorService workers;
    private final Path dir;
    private final Behaviors4 behaviors;
    private final RandomSource random = RandomSource.create();

    private final Map<Long, CompletableFuture<Column4>> loading = new HashMap<>();
    private final Map<Long, Long> lastNeeded = new HashMap<>();
    private final Map<UUID, Tracker> trackers = new HashMap<>();
    private final Map<Integer, Entity4> entities = new LinkedHashMap<>();
    private final Set<Long> changedBlocks = new LinkedHashSet<>();
    private final PriorityQueue<Scheduled> scheduled = new PriorityQueue<>();
    private final Set<Long> scheduledKeys = new HashSet<>();
    private long tick;

    private record Scheduled(long at, long pos, int seq) implements Comparable<Scheduled> {
        public int compareTo(Scheduled o) {
            int c = Long.compare(at, o.at);
            return c != 0 ? c : Integer.compare(seq, o.seq);
        }
    }

    private int seq;

    private static final class Tracker {
        final Set<Long> sent = new HashSet<>();
        List<Long> needed = List.of();
        Set<Long> neededSet = Set.of();
        Vec4 lastComputePos;
        double[] lastFrame;
        int recomputeIn;
    }

    private ServerWorld4(MinecraftServer server, ServerLevel level) {
        this.server = server;
        this.level = level;
        this.generator = new TerrainGenerator4(level.getSeed());
        int threads = Config4.GEN_THREADS.get();
        if (threads <= 0) threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Tetraform-4D-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        var id = level.dimension().identifier();
        this.dir = server.getWorldPath(LevelResource.ROOT).resolve("tetraform4d").resolve(id.getNamespace()).resolve(id.getPath());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Tetraform.LOGGER.error("Could not create 4D save directory", e);
        }
        this.behaviors = new Behaviors4(this);
    }

    /** The 4D world backing {@code level}, or null if that level is not four-dimensional. */
    public static ServerWorld4 get(ServerLevel level) {
        if (!Tetraform.is4D(level)) return null;
        ServerWorld4 w = INSTANCES.get(level.dimension());
        if (w == null || w.level != level) {
            if (w != null) w.close();
            w = new ServerWorld4(level.getServer(), level);
            INSTANCES.put(level.dimension(), w);
        }
        return w;
    }

    public static ServerWorld4 getIfLoaded(ServerLevel level) {
        ServerWorld4 w = INSTANCES.get(level.dimension());
        return w != null && w.level == level ? w : null;
    }

    /**
     * The 4D world a command or 3D player should go to: their own level if it is 4D, otherwise the overworld if
     * it is 4D, otherwise the tesseract dimension.
     */
    public static ServerWorld4 defaultFor(MinecraftServer server, ServerLevel current) {
        if (current != null && Tetraform.is4D(current)) return get(current);
        ServerLevel ow = server.overworld();
        if (Tetraform.is4D(ow)) return get(ow);
        ServerLevel t = server.getLevel(Tetraform.TESSERACT);
        return t == null ? null : get(t);
    }

    public static void shutdown() {
        for (ServerWorld4 w : INSTANCES.values()) w.close();
        INSTANCES.clear();
    }

    private void close() {
        saveAll(true);
        workers.shutdown();
        try {
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public Level4 level() { return level4; }
    public ServerLevel serverLevel() { return level; }
    public RandomSource random() { return random; }
    public TerrainGenerator4 generator() { return generator; }
    public long gameTick() { return tick; }

    // ------------------------------------------------------------------ players

    public static PlayerState4 state(ServerPlayer p) {
        return p.getData(Tetraform.PLAYER4.get());
    }

    public Vec4 pos4(ServerPlayer p) {
        return state(p).pos;
    }

    public List<ServerPlayer> players() {
        return level.players();
    }

    public ServerPlayer nearestPlayer(Vec4 pos, double maxDist) {
        ServerPlayer best = null;
        double bd = maxDist * maxDist;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = state(p).pos.add(0, 0.9, 0, 0).distanceSqr(pos);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    public void knockPlayer(ServerPlayer p, Vec4 impulse) {
        PacketDistributor.sendToPlayer(p, new Net4.Impulse4S2C(impulse.x(), Math.max(impulse.y(), 0.25), impulse.z(), impulse.w()));
    }

    /** Ensures the spawn columns exist and returns a safe surface position. */
    public Vec4 findSurface(double x, double z, double w) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), bw = (int) Math.floor(w);
        Column4 c = loadNow(bx >> 4, bz >> 4, bw >> 4);
        int y = c.height(bx & 15, bz & 15, bw & 15);
        while (y < Column4.HEIGHT - 2 && (!c.get(bx & 15, y, bz & 15, bw & 15).isAir() || !c.get(bx & 15, y + 1, bz & 15, bw & 15).isAir())) y++;
        return new Vec4(bx + 0.5, Math.max(y, TerrainGenerator4.SEA_LEVEL + 1), bz + 0.5, bw + 0.5);
    }

    public void onPlayerEnter(ServerPlayer p) {
        trackers.put(p.getUUID(), new Tracker());
        PlayerState4 st = state(p);
        String dim = level.dimension().identifier().toString();
        if (!st.initialized || !st.dimension.equals(dim)) {
            st.pos = findSurface(0.5, 0.5, 0.5);
            st.frame.setIdentity();
            st.initialized = true;
            st.dimension = dim;
        }
        sendTeleport(p, true);
    }

    public void onPlayerLeave(ServerPlayer p) {
        trackers.remove(p.getUUID());
    }

    public void sendTeleport(ServerPlayer p, boolean reset) {
        PlayerState4 st = state(p);
        st.lastUpdate = tick;
        double[] rp = PlayerState4.renderPosition(st.frame, st.pos);
        if (p.level() == level) {
            p.connection.teleport(rp[0], rp[1], rp[2], p.getYRot(), p.getXRot());
        }
        PacketDistributor.sendToPlayer(p, new Net4.Teleport4S2C(st.pos.x(), st.pos.y(), st.pos.z(), st.pos.w(), st.frame.values(), reset));
        Tracker t = trackers.get(p.getUUID());
        if (t != null) t.recomputeIn = 0;
    }

    /** Moves a player to a 4D location, bringing them into the tesseract dimension first if needed. */
    public void teleport(ServerPlayer p, Vec4 target, SliceFrame frame) {
        PlayerState4 st = state(p);
        st.pos = target;
        st.initialized = true;
        st.dimension = level.dimension().identifier().toString();
        if (frame != null) st.frame.set(frame);
        loadNow((int) Math.floor(target.x()) >> 4, (int) Math.floor(target.z()) >> 4, (int) Math.floor(target.w()) >> 4);
        if (p.level() != level) {
            double[] rp = PlayerState4.renderPosition(st.frame, st.pos);
            p.teleportTo(level, rp[0], rp[1], rp[2], Set.of(), p.getYRot(), p.getXRot(), true);
            // onPlayerEnter is triggered by the dimension change event
        } else {
            sendTeleport(p, false);
        }
    }

    public static void handleMove(ServerPlayer p, Net4.Move4C2S m) {
        ServerWorld4 w = get(p.level());
        if (w == null) return;
        PlayerState4 st = state(p);
        if (!st.dimension.equals(p.level().dimension().identifier().toString())) return;
        Vec4 np = new Vec4(m.x(), m.y(), m.z(), m.w());
        if (!Double.isFinite(np.lengthSqr())) return;
        double moved = np.distance(st.pos);
        long dt = Math.max(1, w.tick - st.lastUpdate);
        if (moved > 12 + dt * 4 && !p.isCreative() && !p.isSpectator()) {
            Tetraform.LOGGER.warn("{} moved too quickly in 4D ({} blocks)", p.getPlainTextName(), moved);
            w.sendTeleport(p, false);
            return;
        }
        st.pos = np;
        SliceFrame f = new SliceFrame(m.frame());
        f.orthonormalize();
        st.frame.set(f);
        st.lastUpdate = w.tick;
    }

    // ------------------------------------------------------------------ columns

    private Path columnFile(long key) {
        return dir.resolve("c." + Column4.keyX(key) + "." + Column4.keyZ(key) + "." + Column4.keyW(key) + ".t4");
    }

    private Column4 loadOrGenerate(int cx, int cz, int cw) {
        long key = Column4.key(cx, cz, cw);
        Path f = columnFile(key);
        if (Files.exists(f)) {
            try {
                Column4 c = Column4.fromBytes(Files.readAllBytes(f));
                c.setDirty(false);
                return c;
            } catch (Exception e) {
                Tetraform.LOGGER.error("Corrupt 4D column {}, regenerating", f, e);
            }
        }
        return generator.generate(cx, cz, cw);
    }

    /** Synchronously loads a column (used for spawn and teleports). */
    public Column4 loadNow(int cx, int cz, int cw) {
        Column4 c = level4.getColumn(cx, cz, cw);
        if (c != null) return c;
        long key = Column4.key(cx, cz, cw);
        CompletableFuture<Column4> f = loading.remove(key);
        c = f != null ? f.join() : loadOrGenerate(cx, cz, cw);
        level4.columns.put(key, c);
        lastNeeded.put(key, tick);
        return c;
    }

    private void request(long key) {
        if (level4.columns.containsKey(key) || loading.containsKey(key)) return;
        if (loading.size() > 96) return;
        int cx = Column4.keyX(key), cz = Column4.keyZ(key), cw = Column4.keyW(key);
        loading.put(key, CompletableFuture.supplyAsync(() -> loadOrGenerate(cx, cz, cw), workers));
    }

    private void pollLoads() {
        if (loading.isEmpty()) return;
        Iterator<Map.Entry<Long, CompletableFuture<Column4>>> it = loading.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, CompletableFuture<Column4>> e = it.next();
            if (!e.getValue().isDone()) continue;
            it.remove();
            try {
                Column4 c = e.getValue().join();
                level4.columns.putIfAbsent(e.getKey(), c);
            } catch (Exception ex) {
                Tetraform.LOGGER.error("4D column generation failed", ex);
            }
        }
    }

    private void updateTracker(ServerPlayer p, Tracker t) {
        PlayerState4 st = state(p);
        Vec4 pos = st.pos;
        double[] fr = st.frame.values();
        boolean moved = t.lastComputePos == null || t.lastComputePos.distanceSqr(pos) > 16 || !Arrays.equals(fr, t.lastFrame);
        if (--t.recomputeIn <= 0 || moved) {
            t.recomputeIn = 20;
            t.lastComputePos = pos;
            t.lastFrame = fr;
            computeNeeded(t, pos, st.frame);
        }
        for (long k : t.needed) lastNeeded.put(k, tick);
        int budget = Config4.COLUMNS_PER_TICK.get();
        for (long k : t.needed) {
            if (budget <= 0) break;
            if (t.sent.contains(k)) continue;
            Column4 c = level4.columns.get(k);
            if (c == null) {
                request(k);
                continue;
            }
            PacketDistributor.sendToPlayer(p, new Net4.ColumnS2C(c.toBytes()));
            t.sent.add(k);
            budget--;
        }
        // Unload columns well outside the needed set.
        if (tick % 20 == 0) {
            List<Long> drop = new ArrayList<>();
            int vr = Config4.VIEW_RADIUS.get() + 2;
            int pcx = (int) Math.floor(pos.x()) >> 4, pcz = (int) Math.floor(pos.z()) >> 4, pcw = (int) Math.floor(pos.w()) >> 4;
            for (long k : t.sent) {
                if (t.neededSet.contains(k)) continue;
                int dx = Math.abs(Column4.keyX(k) - pcx), dz = Math.abs(Column4.keyZ(k) - pcz), dw = Math.abs(Column4.keyW(k) - pcw);
                if (dx > vr || dz > vr || dw > vr || !nearSlice(k, st.frame, st.frame.depthOf(pos), 2.0)) drop.add(k);
            }
            if (!drop.isEmpty()) {
                drop.forEach(t.sent::remove);
                PacketDistributor.sendToPlayer(p, new Net4.UnloadColumnsS2C(drop.stream().mapToLong(Long::longValue).toArray()));
            }
        }
    }

    private static boolean nearSlice(long k, SliceFrame f, double depth, double slack) {
        double cx = Column4.keyX(k) * 16 + 8, cz = Column4.keyZ(k) * 16 + 8, cw = Column4.keyW(k) * 16 + 8;
        double h = 8 * (Math.abs(f.normalX()) + Math.abs(f.normalZ()) + Math.abs(f.normalW())) + slack;
        double s = f.normalX() * cx + f.normalZ() * cz + f.normalW() * cw - depth;
        return Math.abs(s) <= h;
    }

    private void computeNeeded(Tracker t, Vec4 pos, SliceFrame frame) {
        int vr = Config4.VIEW_RADIUS.get();
        int pr = Config4.PHYSICS_RADIUS.get();
        int pcx = (int) Math.floor(pos.x()) >> 4, pcz = (int) Math.floor(pos.z()) >> 4, pcw = (int) Math.floor(pos.w()) >> 4;
        double depth = frame.depthOf(pos);
        record Cand(long key, double dist) {}
        List<Cand> list = new ArrayList<>();
        for (int dw = -vr; dw <= vr; dw++) for (int dz = -vr; dz <= vr; dz++) for (int dx = -vr; dx <= vr; dx++) {
            long k = Column4.key(pcx + dx, pcz + dz, pcw + dw);
            boolean near = Math.abs(dx) <= pr && Math.abs(dz) <= pr && Math.abs(dw) <= pr;
            if (!near) {
                if (!nearSlice(k, frame, depth, 1.0)) continue;
                if (dx * dx + dz * dz + dw * dw > (vr + 0.5) * (vr + 0.5)) continue;
            }
            list.add(new Cand(k, near ? -1 : dx * dx + dz * dz + dw * dw));
        }
        list.sort(Comparator.comparingDouble(Cand::dist));
        List<Long> keys = new ArrayList<>(list.size());
        for (Cand c : list) keys.add(c.key);
        t.needed = keys;
        t.neededSet = new HashSet<>(keys);
    }

    // ------------------------------------------------------------------ blocks

    public BlockState getBlock(BlockPos4 p) {
        return level4.getBlock(p);
    }

    public boolean setBlock(BlockPos4 p, BlockState state, boolean notify) {
        if (p.y() < 0 || p.y() >= Column4.HEIGHT) return false;
        Column4 c = level4.getColumnAt(p.x(), p.z(), p.w());
        if (c == null) return false;
        BlockState old = c.set(p.x() & 15, p.y(), p.z() & 15, p.w() & 15, state);
        if (old == state) return false;
        changedBlocks.add(p.asLong());
        if (notify) {
            behaviors.onChanged(p, old, state);
            for (Direction4 d : Direction4.VALUES) behaviors.neighborChanged(p.relative(d), p);
        }
        return true;
    }

    public void schedule(BlockPos4 p, int delay) {
        long k = p.asLong();
        if (scheduledKeys.add(k)) scheduled.add(new Scheduled(tick + Math.max(1, delay), k, seq++));
    }

    private void runScheduled() {
        int budget = 4096;
        while (!scheduled.isEmpty() && scheduled.peek().at <= tick && budget-- > 0) {
            Scheduled s = scheduled.poll();
            scheduledKeys.remove(s.pos);
            BlockPos4 p = BlockPos4.of(s.pos);
            if (level4.isLoaded(p.x(), p.z(), p.w())) behaviors.scheduledTick(p);
        }
    }

    private void flushBlockUpdates() {
        if (changedBlocks.isEmpty()) return;
        long[] all = changedBlocks.stream().mapToLong(Long::longValue).toArray();
        changedBlocks.clear();
        for (ServerPlayer p : level.players()) {
            Tracker t = trackers.get(p.getUUID());
            if (t == null) continue;
            int n = 0;
            long[] pos = new long[all.length];
            int[] st = new int[all.length];
            for (long l : all) {
                BlockPos4 b = BlockPos4.of(l);
                if (!t.sent.contains(Column4.key(b.x() >> 4, b.z() >> 4, b.w() >> 4))) continue;
                pos[n] = l;
                st[n] = Block.getId(level4.getBlock(b));
                n++;
            }
            if (n > 0) PacketDistributor.sendToPlayer(p, new Net4.BlockUpdatesS2C(Arrays.copyOf(pos, n), Arrays.copyOf(st, n)));
        }
    }

    // ------------------------------------------------------------------ entities

    public Entity4 addEntity(Entity4 e) {
        entities.put(e.id, e);
        return e;
    }

    public Collection<Entity4> entities() {
        return entities.values();
    }

    public Entity4 entity(int id) {
        return entities.get(id);
    }

    public void dropItem(Vec4 pos, ItemStack stack) {
        if (stack.isEmpty()) return;
        Entity4 e = new Entity4(Entity4Type.ITEM, pos);
        e.stack = stack.copy();
        e.pickupDelay = 10;
        double u = random.nextDouble() * 2 - 1, th = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - u * u);
        e.vel = new Vec4(r * Math.cos(th) * 0.1, 0.2, u * 0.1, r * Math.sin(th) * 0.1);
        addEntity(e);
    }

    public boolean anyEntityIn(AABB4 box) {
        for (Entity4 e : entities.values()) if (e.type != Entity4Type.ITEM && e.box().intersects(box)) return true;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (AABB4.ofCenterBottom(state(p).pos, 0.3, 1.8).intersects(box)) return true;
        }
        return false;
    }

    private void tickEntities() {
        Iterator<Entity4> it = entities.values().iterator();
        while (it.hasNext()) {
            Entity4 e = it.next();
            if (e.removed) { it.remove(); continue; }
            e.tick(this);
            if (e.removed) it.remove();
        }
    }

    private void spawnMobs() {
        if (!Config4.SPAWN_MOBS.get() || tick % 40 != 0) return;
        int cap = Config4.MOB_CAP.get();
        for (ServerPlayer p : level.players()) {
            Vec4 pp = state(p).pos;
            int near = 0;
            for (Entity4 e : entities.values()) if (e.type != Entity4Type.ITEM && e.pos.distanceSqr(pp) < 64 * 64) near++;
            if (near >= cap) continue;
            double u = random.nextDouble() * 2 - 1, th = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - u * u);
            double dist = 14 + random.nextDouble() * 22;
            double x = pp.x() + r * Math.cos(th) * dist, z = pp.z() + u * dist, w = pp.w() + r * Math.sin(th) * dist;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), bw = (int) Math.floor(w);
            if (!level4.isLoaded(bx, bz, bw)) continue;
            int y = level4.heightAt(bx, bz, bw);
            BlockState ground = level4.getBlock(bx, y - 1, bz, bw);
            if (y <= 1 || !ground.getFluidState().isEmpty() || !level4.getBlock(bx, y, bz, bw).isAir()) continue;
            boolean night = level.isDarkOutside();
            Entity4Type type = (night && random.nextFloat() < 0.7f) || random.nextFloat() < 0.12f ? Entity4Type.STALKER : Entity4Type.HYPERHOG;
            Entity4 e = new Entity4(type, new Vec4(bx + 0.5, y, bz + 0.5, bw + 0.5));
            e.frame.turnForwardTowards(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5, Math.PI);
            addEntity(e);
        }
    }

    private void sendEntities() {
        List<ServerPlayer> players = level.players();
        for (ServerPlayer p : players) {
            Vec4 pp = state(p).pos;
            List<Net4.EntityView> views = new ArrayList<>();
            for (Entity4 e : entities.values()) {
                if (e.pos.distanceSqr(pp) > 80 * 80) continue;
                int extra = e.type == Entity4Type.ITEM ? BuiltInRegistries.ITEM.getId(e.stack.getItem()) : 0;
                views.add(view(e.id, e.type, e.pos, e.frame, extra, e.hurtTime));
            }
            for (ServerPlayer o : players) {
                if (o == p || o.isSpectator()) continue;
                PlayerState4 os = state(o);
                if (os.pos.distanceSqr(pp) > 80 * 80) continue;
                SliceFrame facing = os.frame.copy();
                facing.rotateInSlice(Math.toRadians(o.getYRot()));
                views.add(view(o.getId(), Entity4Type.PLAYER, os.pos, facing, 0, o.hurtTime));
            }
            PacketDistributor.sendToPlayer(p, new Net4.EntitiesS2C(views.toArray(new Net4.EntityView[0])));
        }
    }

    private static Net4.EntityView view(int id, Entity4Type t, Vec4 pos, SliceFrame f, int extra, int hurt) {
        float[] fr = new float[9];
        double[] v = f.values();
        for (int i = 0; i < 9; i++) fr[i] = (float) v[i];
        return new Net4.EntityView(id, (byte) t.ordinal(), (float) pos.x(), (float) pos.y(), (float) pos.z(), (float) pos.w(), fr, extra, (byte) hurt);
    }

    public void explodeAt(Vec4 pos, float power) {
        behaviors.explode(pos, power);
    }

    // ------------------------------------------------------------------ actions

    public static void handleAction(ServerPlayer p, Net4.Action4C2S a) {
        ServerWorld4 w = get(p.level());
        if (w != null) w.behaviors.handleAction(p, a);
    }

    // ------------------------------------------------------------------ tick & save

    public void tick() {
        tick++;
        pollLoads();
        for (ServerPlayer p : level.players()) {
            Tracker t = trackers.computeIfAbsent(p.getUUID(), u -> new Tracker());
            updateTracker(p, t);
        }
        runScheduled();
        tickEntities();
        spawnMobs();
        flushBlockUpdates();
        sendEntities();
        if (tick % 200 == 0) unloadStale();
    }

    private void unloadStale() {
        Iterator<Map.Entry<Long, Column4>> it = level4.columns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Column4> e = it.next();
            Long last = lastNeeded.get(e.getKey());
            if (last != null && tick - last < 600) continue;
            saveColumn(e.getValue(), false);
            it.remove();
            lastNeeded.remove(e.getKey());
        }
    }

    private void saveColumn(Column4 c, boolean sync) {
        if (!c.isDirty()) return;
        c.setDirty(false);
        byte[] data = c.toBytes();
        Path f = columnFile(c.key());
        Runnable write = () -> {
            try {
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Tetraform.LOGGER.error("Failed to save 4D column {}", f, ex);
            }
        };
        if (sync || workers.isShutdown()) write.run();
        else workers.execute(write);
    }

    public void saveAll(boolean sync) {
        for (Column4 c : level4.columns.values()) saveColumn(c, sync);
    }
}
