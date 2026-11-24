package dev.tetraform.world;

import dev.tetraform.Tetraform;
import dev.tetraform.math.Vec4;
import dev.tetraform.mixin.StructureTemplateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla structures in the 4D world. Each structure picks two of the horizontal axes as its basis: template x
 * and z become (x, z), (z, x), (x, w) or (w, x), and the remaining axis is fixed at one layer. Villages are put
 * together from vanilla's own template pools with a small jigsaw assembler; single-template structures (ruined
 * portals, igloos, shipwrecks) are placed directly. Seen in the slice that contains their plane they look exactly
 * like vanilla (a transposed one comes out mirrored).
 */
public final class Structures4 {
    private static final int REGION = 128;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_PIECES = 80;
    private static final int MAX_RADIUS = 80;

    /** Template x maps to world axis {@code u}, template z to {@code v}; {@code c} is the fixed axis (0=x, 1=z, 2=w). */
    enum Plane {
        XZ(0, 1, 2, false), ZX(1, 0, 2, true), XW(0, 2, 1, false), WX(2, 0, 1, true);
        final int u, v, c;
        final boolean transposed;

        Plane(int u, int v, int c, boolean transposed) {
            this.u = u;
            this.v = v;
            this.c = c;
            this.transposed = transposed;
        }
    }

    /** A resolved structure: blocks in template space (u, y, v) and entities to spawn. */
    static final class Site {
        final Plane plane;
        final int cCoord;
        int minU = Integer.MAX_VALUE, minV = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, maxV = Integer.MIN_VALUE;
        int[] pos = new int[0]; // u, y, v triples
        BlockState[] states = new BlockState[0];
        int count;
        final List<double[]> entityPos = new ArrayList<>(); // u, y, v
        final List<CompoundTag> entityTags = new ArrayList<>();
        String name = "";
        Vec4 origin = Vec4.ZERO;

        Site(Plane plane, int cCoord) {
            this.plane = plane;
            this.cCoord = cCoord;
        }

        void add(int u, int y, int v, BlockState s) {
            if (count == states.length) {
                int n = Math.max(256, count * 2);
                pos = Arrays.copyOf(pos, n * 3);
                states = Arrays.copyOf(states, n);
            }
            pos[count * 3] = u;
            pos[count * 3 + 1] = y;
            pos[count * 3 + 2] = v;
            states[count++] = s;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
    }

    private static final Site NONE = new Site(Plane.XZ, 0);

    private final MinecraftServer server;
    private final TerrainGenerator4 gen;
    private final long seed;
    private final Map<Long, Site> sites = new ConcurrentHashMap<>();

    public Structures4(MinecraftServer server, TerrainGenerator4 gen, long seed) {
        this.server = server;
        this.gen = gen;
        this.seed = seed;
    }

    // ------------------------------------------------------------------ coordinates

    private static int[] world(Plane p, int u, int v, int c) {
        int[] xzw = new int[3];
        xzw[p.u] = u;
        xzw[p.v] = v;
        xzw[p.c] = c;
        return xzw;
    }

    private int surface(Plane p, int u, int v, int c) {
        int[] w = world(p, u, v, c);
        return Math.max(4, Math.min(Column4.HEIGHT - 24, gen.surfaceHeight(w[0], w[1], w[2])));
    }

    private static long regionKey(int rx, int rz, int rw) {
        return ((long) (rx & 0x1FFFFF) << 42) | ((long) (rz & 0x1FFFFF) << 21) | (rw & 0x1FFFFF);
    }

    private Site site(int rx, int rz, int rw) {
        long k = regionKey(rx, rz, rw);
        Site s = sites.get(k);
        if (s != null) return s;
        if (sites.size() > 1024) sites.clear();
        try {
            s = plan(rx, rz, rw);
        } catch (Exception e) {
            Tetraform.LOGGER.error("4D structure planning failed in region {} {} {}", rx, rz, rw, e);
            s = NONE;
        }
        Site prev = sites.putIfAbsent(k, s);
        return prev != null ? prev : s;
    }

    // ------------------------------------------------------------------ planning

    private Site plan(int rx, int rz, int rw) {
        long h = seed ^ (rx * 0x9E3779B97F4A7C15L) ^ (rz * 0xC2B2AE3D27D4EB4FL) ^ (rw * 0x165667B19E3779F9L);
        RandomSource rng = RandomSource.create(h);
        if (rng.nextFloat() > 0.35f) return NONE;
        int x0 = rx * REGION + 32 + rng.nextInt(REGION - 64);
        int z0 = rz * REGION + 32 + rng.nextInt(REGION - 64);
        int w0 = rw * REGION + 32 + rng.nextInt(REGION - 64);
        Plane plane = Plane.values()[rng.nextInt(Plane.values().length)];
        int[] o = {x0, z0, w0};
        int u0 = o[plane.u], v0 = o[plane.v], c0 = o[plane.c];
        int sh = surface(plane, u0, v0, c0);
        Site site = new Site(plane, c0);
        site.origin = new Vec4(x0 + 0.5, sh + 1, z0 + 0.5, w0 + 0.5);
        if (sh < TerrainGenerator4.SEA_LEVEL - 4) {
            String[] wrecks = {"shipwreck/rightsideup_full", "shipwreck/sideways_full", "shipwreck/upsidedown_full", "shipwreck/with_mast"};
            single(site, rng, Identifier.withDefaultNamespace(wrecks[rng.nextInt(wrecks.length)]), u0, sh + 1, v0, false);
            site.name = "shipwreck";
        } else if (sh < TerrainGenerator4.SEA_LEVEL + 2) {
            return NONE;
        } else if (sh > 118) {
            single(site, rng, Identifier.withDefaultNamespace("igloo/top"), u0, sh + 1, v0, true);
            site.name = "igloo";
        } else if (rng.nextFloat() < 0.6f && flatEnough(plane, u0, v0, c0, sh)) {
            village(site, rng, u0, v0, sh);
            site.name = "village";
        } else {
            int n = 1 + rng.nextInt(10);
            single(site, rng, Identifier.withDefaultNamespace("ruined_portal/portal_" + n), u0, sh - 1 - rng.nextInt(3), v0, true);
            site.name = "ruined_portal";
        }
        return site.count == 0 ? NONE : site;
    }

    private boolean flatEnough(Plane p, int u, int v, int c, int h) {
        for (int du = -24; du <= 24; du += 12) for (int dv = -24; dv <= 24; dv += 12) {
            int s = surface(p, u + du, v + dv, c);
            if (Math.abs(s - h) > 7 || s < TerrainGenerator4.SEA_LEVEL + 1) return false;
        }
        return true;
    }

    private StructureTemplateManager templates() {
        return server.getStructureTemplateManager();
    }

    private void single(Site site, RandomSource rng, Identifier id, int u, int y, int v, boolean foundation) {
        StructureTemplate t = templates().get(id).orElse(null);
        if (t == null) return;
        Rotation rot = Rotation.getRandom(rng);
        // centre the rotated template on (u, v)
        BoundingBox b = t.getBoundingBox(settings(rot), BlockPos.ZERO);
        BlockPos origin = new BlockPos(u - (b.minX() + b.maxX()) / 2, y, v - (b.minZ() + b.maxZ()) / 2);
        emit(site, t, rot, origin, true, 1, foundation);
    }

    private static StructurePlaceSettings settings(Rotation rot) {
        return new StructurePlaceSettings().setRotation(rot);
    }

    private record Piece(SinglePoolElement element, Rotation rot, BlockPos pos, BoundingBox box, boolean rigid, int depth) {}

    private void village(Site site, RandomSource rng, int u0, int v0, int sh) {
        var pools = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);
        var start = pools.get(ResourceKey.create(Registries.TEMPLATE_POOL, Identifier.withDefaultNamespace("village/plains/town_centers")));
        if (start.isEmpty()) return;
        StructurePoolElement first = start.get().value().getRandomTemplate(rng);
        if (!(first instanceof SinglePoolElement se)) return;
        Rotation rot0 = Rotation.getRandom(rng);
        BlockPos p0 = new BlockPos(u0, sh + 1 - first.getGroundLevelDelta(), v0);
        List<Piece> pieces = new ArrayList<>();
        Piece root = new Piece(se, rot0, p0, se.getBoundingBox(templates(), p0, rot0), se.getProjection() == StructureTemplatePool.Projection.RIGID, 0);
        pieces.add(root);
        ArrayDeque<Piece> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && pieces.size() < MAX_PIECES) {
            Piece parent = queue.poll();
            for (StructureTemplate.JigsawBlockInfo j : parent.element().getShuffledJigsawBlocks(templates(), parent.pos(), parent.rot(), rng)) {
                if (pieces.size() >= MAX_PIECES) break;
                var poolHolder = pools.get(j.pool());
                if (poolHolder.isEmpty()) continue;
                StructureTemplatePool pool = poolHolder.get().value();
                if (pool.size() == 0) continue;
                if (parent.depth() + 1 > MAX_DEPTH) pool = pool.getFallback().value();
                Direction front = JigsawBlock.getFrontFacing(j.state());
                BlockPos target = j.pos().relative(front);
                Piece placed = attach(parent, j, target, pool, pieces, rng, u0, v0, site);
                if (placed == null && parent.depth() + 1 <= MAX_DEPTH) {
                    placed = attach(parent, j, target, pool.getFallback().value(), pieces, rng, u0, v0, site);
                }
                if (placed != null) {
                    pieces.add(placed);
                    queue.add(placed);
                }
            }
        }
        for (Piece p : pieces) {
            StructureTemplate t = templates().get(p.element().getTemplateLocation()).orElse(null);
            if (t != null) emit(site, t, p.rot(), p.pos(), p.rigid(), p.element().getGroundLevelDelta(), p.rigid());
        }
    }

    private Piece attach(Piece parent, StructureTemplate.JigsawBlockInfo j, BlockPos target, StructureTemplatePool pool, List<Piece> pieces,
                         RandomSource rng, int u0, int v0, Site site) {
        for (StructurePoolElement e : pool.getShuffledTemplates(rng)) {
            if (e instanceof EmptyPoolElement) return null;
            if (!(e instanceof SinglePoolElement se)) continue;
            boolean rigid = se.getProjection() == StructureTemplatePool.Projection.RIGID;
            for (Rotation rot : Rotation.getShuffled(rng)) {
                for (StructureTemplate.JigsawBlockInfo k : se.getShuffledJigsawBlocks(templates(), BlockPos.ZERO, rot, rng)) {
                    if (!JigsawBlock.canAttach(j, k)) continue;
                    int y;
                    if (parent.rigid() && rigid) {
                        y = target.getY() - k.pos().getY();
                    } else {
                        y = surface(site.plane, target.getX(), target.getZ(), site.cCoord) + 1 - k.pos().getY();
                    }
                    BlockPos origin = new BlockPos(target.getX() - k.pos().getX(), y, target.getZ() - k.pos().getZ());
                    BoundingBox box = se.getBoundingBox(templates(), origin, rot);
                    if (Math.max(Math.abs(box.minX() - u0), Math.abs(box.maxX() - u0)) > MAX_RADIUS
                            || Math.max(Math.abs(box.minZ() - v0), Math.abs(box.maxZ() - v0)) > MAX_RADIUS) continue;
                    if (!fits(box, parent, pieces)) continue;
                    return new Piece(se, rot, origin, box, rigid, parent.depth() + 1);
                }
            }
        }
        return null;
    }

    private static boolean overlaps2D(BoundingBox a, BoundingBox b) {
        return a.minX() <= b.maxX() && a.maxX() >= b.minX() && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }

    private static boolean inside2D(BoundingBox in, BoundingBox out) {
        return in.minX() >= out.minX() && in.maxX() <= out.maxX() && in.minZ() >= out.minZ() && in.maxZ() <= out.maxZ();
    }

    private static boolean fits(BoundingBox box, Piece parent, List<Piece> pieces) {
        boolean internal = inside2D(box, parent.box());
        for (Piece p : pieces) {
            if (p == parent) {
                if (!internal && overlaps2D(box, p.box())) return false;
                continue;
            }
            if (internal && !inside2D(p.box(), parent.box())) continue; // internal pieces only compete inside the parent
            if (overlaps2D(box, p.box())) return false;
        }
        return true;
    }

    /** Writes a template's blocks and entities into the site in template space. */
    private void emit(Site site, StructureTemplate t, Rotation rot, BlockPos origin, boolean rigid, int groundDelta, boolean foundation) {
        var acc = (StructureTemplateAccessor) t;
        if (acc.tetraform$palettes().isEmpty()) return;
        StructurePlaceSettings st = settings(rot);
        var blockLookup = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        int minY = Integer.MAX_VALUE;
        List<StructureTemplate.StructureBlockInfo> infos = acc.tetraform$palettes().get(0).blocks();
        for (var info : infos) minY = Math.min(minY, info.pos().getY());
        Map<Long, Integer> footprint = new HashMap<>();
        for (var info : infos) {
            BlockState s = info.state();
            if (s.is(Blocks.STRUCTURE_VOID) || s.is(Blocks.STRUCTURE_BLOCK)) continue;
            if (s.is(Blocks.JIGSAW)) {
                String fin = info.nbt() == null ? "minecraft:air" : info.nbt().getStringOr("final_state", "minecraft:air");
                try {
                    s = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(blockLookup, fin, false).blockState();
                } catch (Exception e) {
                    s = Blocks.AIR.defaultBlockState();
                }
                if (s.is(Blocks.STRUCTURE_VOID)) continue;
            }
            s = s.rotate(rot);
            if (site.plane.transposed) s = s.mirror(Mirror.LEFT_RIGHT).rotate(Rotation.CLOCKWISE_90);
            BlockPos rel = StructureTemplate.calculateRelativePosition(st, info.pos());
            int u = origin.getX() + rel.getX(), v = origin.getZ() + rel.getZ();
            int y = rigid ? origin.getY() + rel.getY()
                    : surface(site.plane, u, v, site.cCoord) + 1 - groundDelta + rel.getY() - minY;
            site.add(u, y, v, s);
            if (foundation && rigid && info.pos().getY() == minY && !s.isAir() && s.getFluidState().isEmpty()) {
                footprint.put(((long) u << 32) | (v & 0xFFFFFFFFL), y);
            }
        }
        // fill under the bottom layer down to the ground, like vanilla terrain adaptation
        BlockState fill = Blocks.DIRT.defaultBlockState();
        for (var e : footprint.entrySet()) {
            int u = (int) (e.getKey() >> 32), v = (int) (long) e.getKey().longValue();
            int ground = surface(site.plane, u, v, site.cCoord);
            for (int y = e.getValue() - 1; y > ground && y > e.getValue() - 12; y--) site.add(u, y, v, fill);
        }
        for (var ent : acc.tetraform$entities()) {
            Vec3 p = StructureTemplate.transform(ent.pos, Mirror.NONE, rot, BlockPos.ZERO);
            double y = rigid ? origin.getY() + p.y
                    : surface(site.plane, (int) Math.floor(origin.getX() + p.x), (int) Math.floor(origin.getZ() + p.z), site.cCoord) + 1 + p.y - minY;
            site.entityPos.add(new double[]{origin.getX() + p.x, y, origin.getZ() + p.z});
            site.entityTags.add(ent.nbt.copy());
        }
    }

    /** Nearest structure (optionally of one kind) to a point: "name plane x y z w", or null. */
    public String locate(Vec4 p, String kind) {
        int rx = Math.floorDiv((int) Math.floor(p.x()), REGION), rz = Math.floorDiv((int) Math.floor(p.z()), REGION),
                rw = Math.floorDiv((int) Math.floor(p.w()), REGION);
        Site best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dw = -4; dw <= 4; dw++) {
            Site s = site(rx + dx, rz + dz, rw + dw);
            if (s == NONE || (kind != null && !kind.equals(s.name))) continue;
            double d = s.origin.distanceSqr(p);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        if (best == null) return null;
        Vec4 o = best.origin;
        return String.format(java.util.Locale.ROOT, "%s (%s plane) at %d %d %d %d", best.name, best.plane.name(),
                (int) Math.floor(o.x()), (int) Math.floor(o.y()), (int) Math.floor(o.z()), (int) Math.floor(o.w()));
    }

    // ------------------------------------------------------------------ placement into columns

    /** An entity a freshly generated column should spawn (once). */
    public record PendingEntity(Vec4 pos, CompoundTag tag) {}

    private List<Site> sitesNear(int bx, int bz, int bw) {
        List<Site> out = new ArrayList<>(2);
        int rx = Math.floorDiv(bx, REGION), rz = Math.floorDiv(bz, REGION), rw = Math.floorDiv(bw, REGION);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dw = -1; dw <= 1; dw++) {
            Site s = site(rx + dx, rz + dz, rw + dw);
            if (s != NONE) out.add(s);
        }
        return out;
    }

    /** True if (x, z, w) is inside (or right next to) a structure, so terrain decoration should stay away. */
    public boolean reserved(int x, int z, int w) {
        int[] p = {x, z, w};
        for (Site s : sitesNear(x, z, w)) {
            if (Math.abs(p[s.plane.c] - s.cCoord) > 2) continue;
            int u = p[s.plane.u], v = p[s.plane.v];
            if (u >= s.minU - 3 && u <= s.maxU + 3 && v >= s.minV - 3 && v <= s.maxV + 3) return true;
        }
        return false;
    }

    /** Writes every structure block that falls inside the column; returns entities to spawn. */
    public List<PendingEntity> decorate(Column4 col) {
        int bx = col.cx << 4, bz = col.cz << 4, bw = col.cw << 4;
        int[] lo = {bx, bz, bw};
        List<PendingEntity> ents = new ArrayList<>();
        for (Site s : sitesNear(bx + 8, bz + 8, bw + 8)) {
            int c = s.cCoord - lo[s.plane.c];
            if (c < 0 || c > 15) continue;
            int u0 = lo[s.plane.u], v0 = lo[s.plane.v];
            if (s.maxU < u0 || s.minU > u0 + 15 || s.maxV < v0 || s.minV > v0 + 15) continue;
            int[] l = new int[3];
            for (int i = 0; i < s.count; i++) {
                int u = s.pos[i * 3] - u0, y = s.pos[i * 3 + 1], v = s.pos[i * 3 + 2] - v0;
                if (u < 0 || u > 15 || v < 0 || v > 15 || y < 1 || y >= Column4.HEIGHT) continue;
                l[s.plane.u] = u;
                l[s.plane.v] = v;
                l[s.plane.c] = c;
                col.setRaw(l[0], y, l[1], l[2], s.states[i]);
            }
            for (int i = 0; i < s.entityPos.size(); i++) {
                double[] e = s.entityPos.get(i);
                if (e[0] < u0 || e[0] >= u0 + 16 || e[2] < v0 || e[2] >= v0 + 16) continue;
                double[] xzw = new double[3];
                xzw[s.plane.u] = e[0];
                xzw[s.plane.v] = e[2];
                xzw[s.plane.c] = s.cCoord + 0.5;
                ents.add(new PendingEntity(new Vec4(xzw[0], e[1], xzw[1], xzw[2]), s.entityTags.get(i)));
            }
        }
        return ents;
    }
}
