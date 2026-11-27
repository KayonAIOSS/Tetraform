package dev.tetraform.world;

import dev.tetraform.Config4;
import dev.tetraform.PlayerState4;
import dev.tetraform.Tetraform;
import dev.tetraform.math.AABB4;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Direction4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
    private final Map<Long, BlockEntities4.BE4> blockEntities = new HashMap<>();
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
        this.generator.structures = new Structures4(server, generator, level.getSeed());
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
        // the server keeps players' vanilla positions in true world coordinates (x, y, z); W is separate
        W4.set(p, st.pos.w());
        if (p.level() == level) {
            p.connection.teleport(st.pos.x(), st.pos.y(), st.pos.z(), p.getYRot(), p.getXRot());
        }
        st.teleportSeq++;
        PacketDistributor.sendToPlayer(p, new Net4.Teleport4S2C(st.pos.x(), st.pos.y(), st.pos.z(), st.pos.w(), st.frame.values(), reset, st.teleportSeq));
        Tracker t = trackers.get(p.getUUID());
        if (t != null) t.recomputeIn = 0;
    }

    /** Moves a player to a 4D location, bringing them into the tesseract dimension first if needed. */
    public void teleport(ServerPlayer p, Vec4 target, SliceFrame frame) {
        teleport(p, target, frame, p.getYRot(), p.getXRot());
    }

    /** Teleport with an explicit view: vanilla yaw and pitch plus (via {@code frame}) the zw rotation. */
    public void teleport(ServerPlayer p, Vec4 target, SliceFrame frame, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        PlayerState4 st = state(p);
        st.pos = target;
        st.initialized = true;
        st.dimension = level.dimension().identifier().toString();
        if (frame != null) st.frame.set(frame);
        loadNow((int) Math.floor(target.x()) >> 4, (int) Math.floor(target.z()) >> 4, (int) Math.floor(target.w()) >> 4);
        if (p.level() != level) {
            W4.set(p, st.pos.w());
            p.teleportTo(level, st.pos.x(), st.pos.y(), st.pos.z(), Set.of(), yaw, pitch, true);
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
        if (m.teleportSeq() != st.teleportSeq) return; // sent before the client saw the latest teleport
        Vec4 np =new Vec4(m.x(), m.y(), m.z(), m.w());
        if (!Double.isFinite(np.lengthSqr())) return;
        double moved = np.distance(st.pos);
        long dt = Math.max(1, w.tick - st.lastUpdate);
        if (moved > 12 + dt * 4 && !p.isCreative() && !p.isSpectator()) {
            Tetraform.LOGGER.warn("{} moved too quickly in 4D ({} blocks)", p.getPlainTextName(), moved);
            w.sendTeleport(p, false);
            return;
        }
        st.pos = np;
        W4.set(p, np.w());
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
        loadBlockEntities(c);
        resumeFluids(c);
        spawnPending(c);
        lastNeeded.put(key, tick);
        return c;
    }

    /** Spawns a newly generated column's structure entities (villagers, animals) exactly once. */
    private void spawnPending(Column4 c) {
        var list = c.pendingEntities;
        if (list == null) return;
        c.pendingEntities = null;
        for (Structures4.PendingEntity pe : list) {
            String id = pe.tag().getStringOr("id", "");
            var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.tryParse(id));
            if (type.isEmpty()) continue;
            CompoundTag tag = pe.tag().copy();
            tag.remove("Pos");
            tag.remove("UUID");
            net.minecraft.world.entity.Entity e = spawn(type.get(), pe.pos(), net.minecraft.world.entity.EntitySpawnReason.STRUCTURE, tag);
            if (e instanceof net.minecraft.world.entity.Mob m) m.setPersistenceRequired();
        }
    }

    private static boolean flowing(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                && s.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) != 0;
    }

    /** Scheduled ticks are not saved, so flowing fluid in a freshly loaded column is re-scheduled to settle. */
    private void resumeFluids(Column4 c) {
        for (int sy = 0; sy < Column4.SECTIONS; sy++) {
            Section4 sec = c.section(sy);
            if (sec == null || sec.isEmpty() || !sec.paletteAny(ServerWorld4::flowing)) continue;
            for (int i = 0; i < Section4.VOLUME; i++) {
                if (!flowing(sec.get(i))) continue;
                int lx = i & 15, lz = (i >> 4) & 15, ly = (i >> 8) & 15, lw = (i >> 12) & 15;
                schedule(new BlockPos4((c.cx << 4) + lx, sy * 16 + ly, (c.cz << 4) + lz, (c.cw << 4) + lw), 5 + random.nextInt(10));
            }
        }
    }

    private void request(long key) {
        if (level4.columns.containsKey(key) || loading.containsKey(key)) return;
        if (loading.size() > 256) return;
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
                if (level4.columns.putIfAbsent(e.getKey(), c) == null) {
                    loadBlockEntities(c);
                    resumeFluids(c);
                    spawnPending(c);
                }
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
        boolean keepEntity = old.getBlock() instanceof net.minecraft.world.level.block.CommandBlock
                && state.getBlock() instanceof net.minecraft.world.level.block.CommandBlock;
        if (old.getBlock() != state.getBlock() && !keepEntity) {
            BlockEntities4.BE4 be = blockEntities.remove(p.asLong());
            if (be instanceof BlockEntities4.Container4 ct) {
                for (int i = 0; i < ct.inventory.getContainerSize(); i++) dropItem(p.center(), ct.inventory.getItem(i));
            }
            if (BlockEntities4.needsEntity(state)) attach(BlockEntities4.create(p, state), c);
        }
        if (notify) {
            behaviors.onChanged(p, old, state);
            for (Direction4 d : Direction4.VALUES) behaviors.neighborChanged(p.relative(d), p);
        }
        return true;
    }

    public BlockEntities4.BE4 blockEntity(BlockPos4 p) {
        return blockEntities.get(p.asLong());
    }

    /** The block entity at p, created on demand for blocks that need one (e.g. placed before this existed). */
    public BlockEntities4.BE4 blockEntityOrCreate(BlockPos4 p) {
        BlockEntities4.BE4 be = blockEntities.get(p.asLong());
        if (be == null && BlockEntities4.needsEntity(getBlock(p))) {
            Column4 c = level4.getColumnAt(p.x(), p.z(), p.w());
            if (c != null) {
                be = BlockEntities4.create(p, getBlock(p));
                attach(be, c);
            }
        }
        return be;
    }

    private void attach(BlockEntities4.BE4 be, Column4 c) {
        if (be == null) return;
        be.onChange = () -> c.setDirty(true);
        blockEntities.put(be.pos.asLong(), be);
        c.setDirty(true);
    }

    private Path entityFile(long key) {
        return dir.resolve("c." + Column4.keyX(key) + "." + Column4.keyZ(key) + "." + Column4.keyW(key) + ".be");
    }

    private void loadBlockEntities(Column4 c) {
        Path f = entityFile(c.key());
        if (!Files.exists(f)) return;
        try {
            net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(f, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            net.minecraft.nbt.ListTag list = root.getListOrEmpty("entries");
            for (int i = 0; i < list.size(); i++) {
                net.minecraft.nbt.CompoundTag e = list.getCompoundOrEmpty(i);
                BlockPos4 p = BlockPos4.of(e.getLongOr("pos", 0));
                BlockEntities4.BE4 be = BlockEntities4.create(p, e.getStringOr("type", ""));
                if (be == null) continue;
                be.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                        server.registryAccess(), e.getCompoundOrEmpty("data")));
                be.onChange = () -> c.setDirty(true);
                blockEntities.put(p.asLong(), be);
            }
        } catch (Exception ex) {
            Tetraform.LOGGER.error("Failed to load 4D block entities {}", f, ex);
        }
    }

    private net.minecraft.nbt.CompoundTag saveBlockEntities(Column4 c) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        int bx = c.cx, bz = c.cz, bw = c.cw;
        for (BlockEntities4.BE4 be : blockEntities.values()) {
            if (be.pos.x() >> 4 != bx || be.pos.z() >> 4 != bz || be.pos.w() >> 4 != bw) continue;
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess());
            be.save(out);
            net.minecraft.nbt.CompoundTag e = new net.minecraft.nbt.CompoundTag();
            e.putLong("pos", be.pos.asLong());
            e.putString("type", be.type());
            e.put("data", out.buildResult());
            list.add(e);
        }
        if (list.isEmpty()) return null;
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        root.put("entries", list);
        return root;
    }

    public void schedule(BlockPos4 p, int delay) {
        long k = p.asLong();
        if (scheduledKeys.add(k)) scheduled.add(new Scheduled(tick + Math.max(1, delay), k, seq++));
    }

    private void runScheduled() {
        // At most 4096 ticks and ~15 ms per server tick; anything left over runs next tick, so a large fluid
        // or redstone event slows down instead of stalling the server.
        int budget = 4096;
        long deadline = System.nanoTime() + 15_000_000L;
        while (!scheduled.isEmpty() && scheduled.peek().at <= tick && budget-- > 0 && ((budget & 63) != 0 || System.nanoTime() < deadline)) {
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

    // ------------------------------------------------------------------ entities (vanilla entities with a W)

    /** Drops an item as a vanilla item entity at a 4D position. */
    public void dropItem(Vec4 pos, ItemStack stack) {
        if (stack.isEmpty()) return;
        net.minecraft.world.entity.item.ItemEntity e = new net.minecraft.world.entity.item.ItemEntity(level, pos.x(), pos.y(), pos.z(), stack.copy());
        W4.set(e, pos.w());
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
    }

    /** Vanilla entities (not players) whose 4D box intersects {@code box}. */
    public List<net.minecraft.world.entity.Entity> entitiesIn(AABB4 box) {
        List<net.minecraft.world.entity.Entity> out = new ArrayList<>();
        net.minecraft.world.phys.AABB a3 = new net.minecraft.world.phys.AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null, a3, x -> true)) {
            double w = W4.get(e), hw = e.getBbWidth() / 2;
            if (w + hw > box.minW() && w - hw < box.maxW()) out.add(e);
        }
        return out;
    }

    public boolean anyEntityIn(AABB4 box) {
        for (net.minecraft.world.entity.Entity e : entitiesIn(box)) {
            if (e instanceof net.minecraft.world.entity.LivingEntity && !e.isSpectator()) return true;
        }
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (AABB4.ofCenterBottom(state(p).pos, 0.3, 1.8).intersects(box)) return true;
        }
        return false;
    }

    private static final net.minecraft.world.entity.EntityType<?>[] DAY = {
            net.minecraft.world.entity.EntityTypes.COW, net.minecraft.world.entity.EntityTypes.PIG,
            net.minecraft.world.entity.EntityTypes.SHEEP, net.minecraft.world.entity.EntityTypes.CHICKEN};
    private static final net.minecraft.world.entity.EntityType<?>[] NIGHT = {
            net.minecraft.world.entity.EntityTypes.ZOMBIE, net.minecraft.world.entity.EntityTypes.SKELETON,
            net.minecraft.world.entity.EntityTypes.CREEPER, net.minecraft.world.entity.EntityTypes.SPIDER};

    /**
     * Natural spawning of vanilla mobs at 4D positions (vanilla spawning can't see the 4D terrain): animals by
     * day on grass, monsters at night. Each mob gets its own W and then lives in that slice.
     */
    private void spawnMobs() {
        if (!Config4.SPAWN_MOBS.get() || tick % 40 != 0) return;
        if (!level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS)) return;
        int cap = Config4.MOB_CAP.get();
        for (ServerPlayer p : level.players()) {
            Vec4 pp = state(p).pos;
            int near = 0;
            for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null, p.getBoundingBox().inflate(64), x -> x instanceof net.minecraft.world.entity.Mob)) {
                if (Math.abs(W4.get(e) - pp.w()) < 64) near++;
            }
            if (near >= cap) continue;
            double u = random.nextDouble() * 2 - 1, th = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - u * u);
            double dist = 16 + random.nextDouble() * 24;
            double x = pp.x() + r * Math.cos(th) * dist, z = pp.z() + u * dist, w = pp.w() + r * Math.sin(th) * dist;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), bw = (int) Math.floor(w);
            if (!level4.isLoaded(bx, bz, bw)) continue;
            int y = level4.heightAt(bx, bz, bw);
            BlockState ground = level4.getBlock(bx, y - 1, bz, bw);
            if (y <= 1 || !ground.getFluidState().isEmpty() || !level4.getBlock(bx, y, bz, bw).isAir()
                    || !level4.getBlock(bx, y + 1, bz, bw).isAir()) continue;
            boolean night = level.isDarkOutside();
            if (!night && !ground.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) continue;
            net.minecraft.world.entity.EntityType<?>[] pool = night ? NIGHT : DAY;
            net.minecraft.world.entity.EntityType<?> type = pool[random.nextInt(pool.length)];
            spawn(type, new Vec4(bx + 0.5, y, bz + 0.5, bw + 0.5), net.minecraft.world.entity.EntitySpawnReason.NATURAL);
        }
    }

    /** Creates a vanilla entity at a 4D position; mob spawn logic runs inside that entity's slice. */
    public net.minecraft.world.entity.Entity spawn(net.minecraft.world.entity.EntityType<?> type, Vec4 pos, net.minecraft.world.entity.EntitySpawnReason reason) {
        return spawn(type, pos, reason, null);
    }

    public net.minecraft.world.entity.Entity spawn(net.minecraft.world.entity.EntityType<?> type, Vec4 pos, net.minecraft.world.entity.EntitySpawnReason reason,
                                                   net.minecraft.nbt.CompoundTag nbt) {
        net.minecraft.world.entity.Entity e = type.create(level, reason);
        if (e == null) return null;
        if (nbt != null && !nbt.isEmpty()) {
            try (var reporter = new net.minecraft.util.ProblemReporter.ScopedCollector(org.slf4j.LoggerFactory.getLogger("tetraform"))) {
                e.load(net.minecraft.world.level.storage.TagValueInput.create(reporter, level.registryAccess(), nbt));
            }
        }
        e.snapTo(pos.x(), pos.y(), pos.z(), random.nextFloat() * 360f, 0f);
        W4.set(e, pos.w());
        Access4.Mapper prev = Access4.push(Access4.server(this, pos.w()));
        try {
            if (e instanceof net.minecraft.world.entity.Mob mob) {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()), reason, null);
            }
            level.addFreshEntity(e);
        } finally {
            Access4.pop(prev);
        }
        return e;
    }

    // ------------------------------------------------------------------ creatures moving through W

    private final Map<net.minecraft.world.entity.Entity, Double> wTargets = new WeakHashMap<>();

    /**
     * Gives every vanilla mob a fourth degree of freedom: now and then a mob steps to a neighbouring W layer when
     * there is room there (feet and head clear, ground below), and a mob with a target lines its W up with it.
     */
    public void tickMobW(net.minecraft.world.entity.Mob mob) {
        if (mob.isNoAi() || mob.isPassenger()) return;
        double w = W4.get(mob);
        Double target = wTargets.get(mob);
        net.minecraft.world.entity.LivingEntity t = mob.getTarget();
        if (t != null && Math.abs(W4.get(t) - w) > 0.02) target = W4.get(t);
        // villagers and golems stay with their village's layer; other creatures drift through W now and then
        boolean homebody = mob instanceof net.minecraft.world.entity.npc.villager.AbstractVillager || mob instanceof net.minecraft.world.entity.animal.golem.IronGolem;
        if (target == null && !homebody && mob.getNavigation().isDone() && random.nextInt(1200) == 0) {
            double nw = Math.floor(w) + 0.5 + (random.nextBoolean() ? 1 : -1);
            if (roomAt(mob, nw)) target = nw;
        }
        if (target == null) return;
        double step = Math.max(-0.1, Math.min(0.1, target - w));
        double nw = w + step;
        if (!roomAt(mob, nw)) {
            wTargets.remove(mob);
            return;
        }
        W4.set(mob, Math.abs(target - nw) < 1.0E-3 ? target : nw);
        if (Math.abs(target - nw) < 1.0E-3) wTargets.remove(mob);
        else wTargets.put(mob, target);
    }

    private boolean roomAt(net.minecraft.world.entity.Entity e, double w) {
        AABB4 box = new AABB4(e.getBoundingBox().minX, e.getBoundingBox().minY, e.getBoundingBox().minZ, w - e.getBbWidth() / 2,
                e.getBoundingBox().maxX, e.getBoundingBox().maxY, e.getBoundingBox().maxZ, w + e.getBbWidth() / 2);
        if (!level4.collisions(box.inflate(-0.01)).isEmpty()) return false;
        // something to stand on in the new layer
        BlockPos4 below = new Vec4(e.getX(), e.getY() - 0.5, e.getZ(), w).toBlockPos();
        return !e.onGround() || BlockShapes4.collision(level4.getBlock(below)) != null;
    }

    public void explodeAt(Vec4 pos, float power) {
        behaviors.explode(pos, power);
    }

    // ------------------------------------------------------------------ actions

    /** Saves edits from the command block screen (mode switches swap the block type but keep the command). */
    public static void handleSetCommandBlock(ServerPlayer p, Net4.SetCommandBlock4C2S m) {
        ServerWorld4 w = get(p.level());
        if (w == null || !p.canUseGameMasterBlocks()) return;
        BlockPos4 pos = BlockPos4.of(m.pos());
        if (state(p).pos.distance(pos.center()) > 10) return;
        BlockState old = w.getBlock(pos);
        if (!(old.getBlock() instanceof net.minecraft.world.level.block.CommandBlock)) return;
        var modes = net.minecraft.world.level.block.entity.CommandBlockEntity.Mode.values();
        var mode = modes[Math.floorMod(m.mode(), modes.length)];
        net.minecraft.world.level.block.Block nb = switch (mode) {
            case SEQUENCE -> net.minecraft.world.level.block.Blocks.CHAIN_COMMAND_BLOCK;
            case AUTO -> net.minecraft.world.level.block.Blocks.REPEATING_COMMAND_BLOCK;
            case REDSTONE -> net.minecraft.world.level.block.Blocks.COMMAND_BLOCK;
        };
        BlockState ns = nb.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CommandBlock.FACING, old.getValue(net.minecraft.world.level.block.CommandBlock.FACING))
                .setValue(net.minecraft.world.level.block.CommandBlock.CONDITIONAL, m.conditional());
        if (!(w.blockEntityOrCreate(pos) instanceof BlockEntities4.CommandBlock4 cb)) return;
        w.setBlock(pos, ns, false);
        cb.commandBlock.setCommand(m.command());
        cb.commandBlock.setTrackOutput(m.trackOutput());
        if (!m.trackOutput()) cb.commandBlock.setLastOutput(null);
        cb.auto = m.auto();
        cb.markChanged();
        if (mode != net.minecraft.world.level.block.entity.CommandBlockEntity.Mode.SEQUENCE && (cb.auto || cb.powered)) w.schedule(pos, 1);
        if (!m.command().isEmpty()) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.translatable("advMode.setCommand.success", m.command()));
        }
    }

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
        spawnMobs();
        flushBlockUpdates();
        if (tick % 200 == 0) unloadStale();
    }

    private void unloadStale() {
        Iterator<Map.Entry<Long, Column4>> it = level4.columns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Column4> e = it.next();
            Long last = lastNeeded.get(e.getKey());
            if (last != null && tick - last < 600) continue;
            saveColumn(e.getValue(), false);
            Column4 uc = e.getValue();
            blockEntities.values().removeIf(be -> be.pos.x() >> 4 == uc.cx && be.pos.z() >> 4 == uc.cz && be.pos.w() >> 4 == uc.cw);
            it.remove();
            lastNeeded.remove(e.getKey());
        }
    }

    private void saveColumn(Column4 c, boolean sync) {
        if (!c.isDirty()) return;
        c.setDirty(false);
        byte[] data = c.toBytes();
        Path f = columnFile(c.key());
        net.minecraft.nbt.CompoundTag bes = saveBlockEntities(c);
        Path bf = entityFile(c.key());
        Runnable write = () -> {
            try {
                if (bes != null) net.minecraft.nbt.NbtIo.writeCompressed(bes, bf);
                else Files.deleteIfExists(bf);
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
