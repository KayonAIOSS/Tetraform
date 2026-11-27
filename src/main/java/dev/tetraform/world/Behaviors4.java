package dev.tetraform.world;

import dev.tetraform.PlayerState4;
import dev.tetraform.math.AABB4;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Direction4;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

import java.util.*;

/**
 * Block behaviour in four dimensions. Every rule here works on the eight tesseract neighbours of a block:
 * <ul>
 *   <li><b>Redstone</b>: dust networks connect along all six horizontal directions (x, z and w) and step up or
 *   down one block. Sources are levers, buttons, redstone blocks and torches. Opaque blocks conduct power.
 *   Lamps, torches and TNT react to it.</li>
 *   <li><b>Fluids</b>: water and lava flow down, then spread across the six horizontal directions, form infinite
 *   sources, and mix into obsidian and cobblestone.</li>
 *   <li><b>Gravity</b>: sand, gravel and other falling blocks drop.</li>
 *   <li><b>TNT</b>: explodes as a 4D hyperball.</li>
 * </ul>
 */
public final class Behaviors4 {
    private final ServerWorld4 world;
    private final Set<Long> primedTnt = new HashSet<>();
    private final Map<Long, Integer> buttonRelease = new HashMap<>();
    private boolean updatingWire;

    public Behaviors4(ServerWorld4 world) {
        this.world = world;
    }

    private BlockState get(BlockPos4 p) {
        return world.getBlock(p);
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * Blocks whose 4D behaviour this class implements itself (gravity, fluids, redstone dust, torches and lamps,
     * TNT, command blocks). Vanilla's 3D logic for them would fight the 4D version. Every other block, including
     * modded ones, runs its vanilla logic in its own W slice.
     */
    public static boolean custom4D(Block b) {
        return b instanceof FallingBlock || b instanceof LiquidBlock || b instanceof RedstoneWireBlock || b instanceof RedstoneTorchBlock
                || b instanceof RedstoneLampBlock || b instanceof TntBlock || b instanceof CommandBlock;
    }

    private static net.minecraft.core.BlockPos pos3(BlockPos4 p) {
        return new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
    }

    public void onChanged(BlockPos4 p, BlockState old, BlockState now) {
        neighborChanged(p, p);
        // vanilla's onPlace: fire lights nether portals, rails and pistons settle, modded blocks initialise...
        if (old.getBlock() != now.getBlock() && !now.isAir() && !custom4D(now.getBlock())) {
            Access4.inSlice(world, p.w() + 0.5, () -> {
                if (get(p) == now) now.onPlace(world.serverLevel(), pos3(p), old, false);
            });
        }
    }

    /** Vanilla's neighbour reaction (shape updates like fences and stairs, popping off unsupported blocks...). */
    private void vanillaNeighbor(BlockPos4 p, BlockPos4 from, BlockState s) {
        int dx = from.x() - p.x(), dy = from.y() - p.y(), dz = from.z() - p.z(), dw = from.w() - p.w();
        net.minecraft.core.Direction dir = dw != 0 ? null : net.minecraft.core.Direction.getNearest(dx, dy, dz, null);
        Block fromBlock = get(from).getBlock();
        Access4.inSlice(world, p.w() + 0.5, () -> {
            var level = world.serverLevel();
            net.minecraft.core.BlockPos bp = pos3(p);
            if (dir != null) {
                BlockState updated = s.updateShape(level, level, bp, dir, pos3(from), get(from), world.random());
                if (updated != s) Block.updateOrDestroy(s, updated, level, bp, Block.UPDATE_ALL);
            }
            BlockState now = get(p);
            if (now.getBlock() == s.getBlock()) now.handleNeighborChanged(level, bp, fromBlock, null, false);
        });
    }

    public void neighborChanged(BlockPos4 p, BlockPos4 from) {
        BlockState s = get(p);
        Block b = s.getBlock();
        if (!s.isAir() && !p.equals(from) && !custom4D(b)) {
            vanillaNeighbor(p, from, s);
            return;
        }
        if (b instanceof FallingBlock) {
            BlockState below = get(p.below());
            if (canFallInto(below)) world.schedule(p, 2);
        } else if (b instanceof LiquidBlock) {
            world.schedule(p, s.getFluidState().is(Fluids.LAVA) || s.getFluidState().is(Fluids.FLOWING_LAVA) ? 30 : 5);
        } else if (b instanceof RedstoneWireBlock) {
            if (!updatingWire) updateWireNetwork(p);
        } else if (b instanceof RedstoneTorchBlock) {
            world.schedule(p, 2);
        } else if (b instanceof RedstoneLampBlock) {
            boolean lit = s.getValue(RedstoneLampBlock.LIT);
            boolean powered = powerAt(p) > 0;
            if (powered && !lit) world.setBlock(p, s.setValue(RedstoneLampBlock.LIT, true), true);
            else if (!powered && lit) world.schedule(p, 4);
        } else if (b instanceof TntBlock) {
            if (powerAt(p) > 0) prime(p);
        } else if (b instanceof CommandBlock) {
            commandBlockNeighbor(p, s);
        }
        // Water touching lava
        if (b instanceof LiquidBlock && isLava(s)) world.schedule(p, 2);
    }

    public void scheduledTick(BlockPos4 p) {
        long key = p.asLong();
        if (primedTnt.remove(key)) {
            explode(p.center(), 3.6f);
            return;
        }
        Integer rel = buttonRelease.get(key);
        BlockState s = get(p);
        Block b = s.getBlock();
        if (rel != null && b instanceof ButtonBlock) {
            buttonRelease.remove(key);
            world.setBlock(p, s.setValue(ButtonBlock.POWERED, false), true);
            return;
        }
        if (b instanceof CommandBlock) {
            tickCommandBlock(p, s);
        } else if (b instanceof FallingBlock) {
            fall(p, s);
        } else if (b instanceof LiquidBlock) {
            tickFluid(p, s);
        } else if (b instanceof RedstoneTorchBlock) {
            boolean lit = s.getValue(BlockStateProperties.LIT);
            boolean shouldLit = !torchAttachedPowered(p, s);
            if (lit != shouldLit) world.setBlock(p, s.setValue(BlockStateProperties.LIT, shouldLit), true);
        } else if (b instanceof RedstoneLampBlock) {
            if (s.getValue(RedstoneLampBlock.LIT) && powerAt(p) == 0) world.setBlock(p, s.setValue(RedstoneLampBlock.LIT, false), true);
        } else if (!s.isAir()) {
            // a tick vanilla code scheduled (buttons, repeaters, observers, fire, modded blocks...)
            Access4.inSlice(world, p.w() + 0.5, () -> s.tick(world.serverLevel(), pos3(p), world.random()));
        }
    }

    // ------------------------------------------------------------------ gravity

    private static boolean canFallInto(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced();
    }

    private void fall(BlockPos4 p, BlockState s) {
        BlockPos4 to = p;
        while (to.y() > 0 && canFallInto(get(to.below()))) to = to.below();
        if (to.equals(p)) return;
        world.setBlock(p, Level4.AIR, true);
        world.setBlock(to, s, true);
    }

    // ------------------------------------------------------------------ fluids

    private static boolean isLava(BlockState s) {
        return s.getFluidState().is(Fluids.LAVA) || s.getFluidState().is(Fluids.FLOWING_LAVA);
    }

    private static boolean sameFluid(BlockState a, BlockState b) {
        return !a.getFluidState().isEmpty() && b.getBlock() == a.getBlock();
    }

    private static int level(BlockState s) {
        return s.getValue(LiquidBlock.LEVEL);
    }

    private static boolean replaceableByFluid(BlockState s) {
        return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty() && !(s.getBlock() instanceof LiquidBlock));
    }

    private void tickFluid(BlockPos4 p, BlockState s) {
        boolean lava = isLava(s);
        int drop = lava ? 2 : 1;
        int maxLevel = 7;
        int lvl = level(s);
        // lava + water
        if (lava) {
            for (Direction4 d : Direction4.VALUES) {
                if (d == Direction4.DOWN) continue;
                BlockState n = get(p.relative(d));
                if (!n.getFluidState().isEmpty() && !isLava(n)) {
                    world.setBlock(p, lvl == 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), true);
                    return;
                }
            }
        }
        if (lvl != 0) {
            // flowing: recompute from neighbours
            int newLevel;
            BlockState above = get(p.above());
            if (sameFluid(s, above)) {
                newLevel = 8;
            } else {
                int best = 99;
                int sources = 0;
                for (Direction4 d : Direction4.HORIZONTAL) {
                    BlockState n = get(p.relative(d));
                    if (!sameFluid(s, n)) continue;
                    int nl = level(n);
                    if (nl == 0) sources++;
                    int eff = nl >= 8 ? 0 : nl;
                    best = Math.min(best, eff + drop);
                }
                BlockState below = get(p.below());
                if (!lava && sources >= 2 && (below.isSolid() || sameFluid(s, below) && level(below) == 0)) {
                    newLevel = 0;
                } else {
                    newLevel = best;
                }
            }
            // Level 8 means "falling" (full strength) and may only come from fluid directly above. A horizontal
            // neighbour at level 7 gives 7 + 1 = 8, which must mean "too weak", not "falling": treating it as
            // falling made the thinnest edge of a flow resurrect itself, so water never drained.
            boolean falling = sameFluid(s, above);
            if (!falling && newLevel > maxLevel) {
                world.setBlock(p, Level4.AIR, true);
                return;
            }
            if (newLevel != lvl) {
                world.setBlock(p, s.setValue(LiquidBlock.LEVEL, newLevel), true);
                s = get(p);
                lvl = newLevel;
            }
        }
        // spread
        BlockPos4 down = p.below();
        BlockState belowState = get(down);
        if (down.y() >= 0 && replaceableByFluid(belowState)) {
            world.setBlock(down, s.setValue(LiquidBlock.LEVEL, 8), true);
            return;
        }
        if (sameFluid(s, belowState) && level(belowState) != 0 && lvl != 0) return; // feeding a fall
        int eff = lvl >= 8 ? 0 : lvl;
        int next = eff + drop;
        if (next > maxLevel) return;
        for (Direction4 d : spreadDirections(p, s, lava ? 2 : 4)) {
            BlockPos4 np = p.relative(d);
            BlockState n = get(np);
            if (replaceableByFluid(n)) {
                world.setBlock(np, s.setValue(LiquidBlock.LEVEL, next), true);
            } else if (sameFluid(s, n) && level(n) != 0 && level(n) < 8 && level(n) > next) {
                world.setBlock(np, s.setValue(LiquidBlock.LEVEL, next), true);
            }
        }
    }

    private boolean canFlowInto(BlockState s, BlockState n) {
        return replaceableByFluid(n) || (sameFluid(s, n) && level(n) != 0);
    }

    /**
     * Vanilla's flow rule in 4D: of the six horizontal directions, fluid only spreads toward the closest drop
     * (a place it can fall from) within {@code maxDepth} blocks. With no drop in reach, it spreads every way.
     * Without this rule, water floods outward evenly instead of running downhill.
     */
    private java.util.List<Direction4> spreadDirections(BlockPos4 p, BlockState s, int maxDepth) {
        // Breadth-first search over the horizontal cells the fluid could flow through (at most ~130 cells for
        // depth 4), tagging each cell with the starting direction(s) that reach it first.
        Direction4[] dirs = Direction4.HORIZONTAL;
        java.util.Map<Long, Integer> tags = new java.util.HashMap<>();
        java.util.ArrayDeque<BlockPos4> frontier = new java.util.ArrayDeque<>();
        int found = 0;
        for (int i = 0; i < dirs.length; i++) {
            BlockPos4 np = p.relative(dirs[i]);
            if (!canFlowInto(s, get(np))) continue;
            if (replaceableByFluid(get(np.below()))) found |= 1 << i;
            tags.merge(np.asLong(), 1 << i, (x, y) -> x | y);
            frontier.add(np);
        }
        tags.put(p.asLong(), 0);
        for (int depth = 1; found == 0 && depth <= maxDepth && !frontier.isEmpty(); depth++) {
            java.util.ArrayDeque<BlockPos4> next = new java.util.ArrayDeque<>();
            java.util.Map<Long, Integer> nextTags = new java.util.HashMap<>();
            for (BlockPos4 c : frontier) {
                int tag = tags.get(c.asLong());
                for (Direction4 d : dirs) {
                    BlockPos4 q = c.relative(d);
                    long k = q.asLong();
                    if (tags.containsKey(k)) continue;
                    if (!canFlowInto(s, get(q))) continue;
                    Integer prev = nextTags.get(k);
                    if (prev == null) next.add(q);
                    nextTags.put(k, prev == null ? tag : prev | tag);
                }
            }
            for (BlockPos4 q : next) {
                int tag = nextTags.get(q.asLong());
                tags.put(q.asLong(), tag);
                if (replaceableByFluid(get(q.below()))) found |= tag;
            }
            frontier = next;
        }
        java.util.List<Direction4> out = new java.util.ArrayList<>(6);
        for (int i = 0; i < dirs.length; i++) {
            boolean open = tags.containsKey(p.relative(dirs[i]).asLong()) && canFlowInto(s, get(p.relative(dirs[i])));
            if (!open) continue;
            if (found == 0 || (found & (1 << i)) != 0) out.add(dirs[i]);
        }
        return out;
    }

    // ------------------------------------------------------------------ redstone

    /** Power a block at {@code src} emits toward its neighbour in direction {@code dir}. */
    private int emits(BlockPos4 src, BlockState s, Direction4 dir, boolean includeWire) {
        Block b = s.getBlock();
        if (b == Blocks.REDSTONE_BLOCK) return 15;
        if (b instanceof LeverBlock || b instanceof ButtonBlock) return s.getValue(BlockStateProperties.POWERED) ? 15 : 0;
        if (b instanceof RedstoneTorchBlock) {
            if (!s.getValue(BlockStateProperties.LIT)) return 0;
            return dir == torchAttachment(s) ? 0 : 15;
        }
        if (includeWire && b instanceof RedstoneWireBlock) {
            return dir == Direction4.UP ? 0 : s.getValue(RedstoneWireBlock.POWER);
        }
        return 0;
    }

    private static Direction4 torchAttachment(BlockState s) {
        if (s.getBlock() instanceof RedstoneWallTorchBlock) {
            Direction f = s.getValue(RedstoneWallTorchBlock.FACING);
            return switch (f.getOpposite()) {
                case EAST -> Direction4.EAST;
                case WEST -> Direction4.WEST;
                case NORTH -> Direction4.NORTH;
                case SOUTH -> Direction4.SOUTH;
                default -> Direction4.DOWN;
            };
        }
        return Direction4.DOWN;
    }

    private static boolean conductor(BlockState s) {
        return BlockShapes4.fullOpaque(s) && s.getBlock() != Blocks.REDSTONE_BLOCK && !(s.getBlock() instanceof TntBlock);
    }

    /** Is the conductor block at p strongly powered by an adjacent (non-wire unless allowed) source? */
    private boolean conductorPowered(BlockPos4 p, boolean includeWire) {
        for (Direction4 d : Direction4.VALUES) {
            BlockPos4 n = p.relative(d);
            BlockState ns = get(n);
            if (ns.isAir()) continue;
            if (emits(n, ns, d.opposite(), includeWire) > 0) {
                // wire only powers the block underneath it or the one it runs into horizontally
                return true;
            }
        }
        return false;
    }

    /** Effective power arriving at position p (for lamps, TNT and so on). */
    public int powerAt(BlockPos4 p) {
        int best = 0;
        for (Direction4 d : Direction4.VALUES) {
            BlockPos4 n = p.relative(d);
            BlockState ns = get(n);
            if (ns.isAir()) continue;
            best = Math.max(best, emits(n, ns, d.opposite(), true));
            if (best >= 15) return 15;
            if (conductor(ns) && conductorPowered(n, true)) return 15;
        }
        return best;
    }

    private boolean torchAttachedPowered(BlockPos4 p, BlockState s) {
        BlockPos4 att = p.relative(torchAttachment(s));
        BlockState as = get(att);
        if (as.getBlock() == Blocks.REDSTONE_BLOCK) return true;
        if (!conductor(as)) return false;
        for (Direction4 d : Direction4.VALUES) {
            BlockPos4 n = att.relative(d);
            if (n.equals(p)) continue;
            BlockState ns = get(n);
            if (!ns.isAir() && emits(n, ns, d.opposite(), true) > 0) return true;
        }
        return false;
    }

    private List<BlockPos4> wireNeighbours(BlockPos4 p) {
        List<BlockPos4> out = new ArrayList<>(18);
        for (Direction4 d : Direction4.HORIZONTAL) {
            BlockPos4 n = p.relative(d);
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos4 q = n.offset(0, dy, 0, 0);
                if (get(q).getBlock() instanceof RedstoneWireBlock) out.add(q);
            }
        }
        BlockPos4 up = p.above(), dn = p.below();
        if (get(up).getBlock() instanceof RedstoneWireBlock) out.add(up);
        if (get(dn).getBlock() instanceof RedstoneWireBlock) out.add(dn);
        return out;
    }

    /** Recomputes an entire connected dust network with a bucket-queue flood fill (15 down to 1). */
    private void updateWireNetwork(BlockPos4 start) {
        Set<BlockPos4> net = new LinkedHashSet<>();
        Deque<BlockPos4> q = new ArrayDeque<>();
        q.add(start);
        net.add(start);
        while (!q.isEmpty() && net.size() < 8192) {
            BlockPos4 p = q.poll();
            for (BlockPos4 n : wireNeighbours(p)) if (net.add(n)) q.add(n);
        }
        Map<BlockPos4, Integer> power = new HashMap<>();
        List<List<BlockPos4>> buckets = new ArrayList<>();
        for (int i = 0; i <= 15; i++) buckets.add(new ArrayList<>());
        for (BlockPos4 w : net) {
            int src = 0;
            for (Direction4 d : Direction4.VALUES) {
                BlockPos4 n = w.relative(d);
                BlockState ns = get(n);
                if (ns.isAir() || ns.getBlock() instanceof RedstoneWireBlock) continue;
                src = Math.max(src, emits(n, ns, d.opposite(), false));
                if (conductor(ns) && conductorPowered(n, false)) src = 15;
                if (src >= 15) break;
            }
            power.put(w, src);
            if (src > 0) buckets.get(src).add(w);
        }
        for (int lvl = 15; lvl >= 1; lvl--) {
            for (BlockPos4 w : buckets.get(lvl)) {
                if (power.get(w) != lvl) continue;
                for (BlockPos4 n : wireNeighbours(w)) {
                    Integer cur = power.get(n);
                    if (cur != null && cur < lvl - 1) {
                        power.put(n, lvl - 1);
                        buckets.get(lvl - 1).add(n);
                    }
                }
            }
        }
        updatingWire = true;
        try {
            List<BlockPos4> changed = new ArrayList<>();
            for (BlockPos4 w : net) {
                BlockState s = get(w);
                int np = power.get(w);
                if (s.getValue(RedstoneWireBlock.POWER) != np) {
                    world.setBlock(w, s.setValue(RedstoneWireBlock.POWER, np), false);
                    changed.add(w);
                }
            }
            for (BlockPos4 w : changed) {
                for (Direction4 d : Direction4.VALUES) {
                    BlockPos4 n = w.relative(d);
                    BlockState ns = get(n);
                    if (ns.getBlock() instanceof RedstoneWireBlock) continue;
                    neighborChanged(n, w);
                    if (conductor(ns)) for (Direction4 d2 : Direction4.VALUES) {
                        BlockPos4 nn = n.relative(d2);
                        if (!(get(nn).getBlock() instanceof RedstoneWireBlock)) neighborChanged(nn, n);
                    }
                }
            }
        } finally {
            updatingWire = false;
        }
    }

    // ------------------------------------------------------------------ TNT

    private void prime(BlockPos4 p) {
        if (primedTnt.add(p.asLong())) {
            world.setBlock(p, Level4.AIR, false);
            world.schedule(p, 40);
        }
    }

    /** A four-dimensional explosion: destroys a hyperball and pushes entities radially in 4D. */
    public void explode(Vec4 c, float radius) {
        int r = (int) Math.ceil(radius);
        BlockPos4 cp = c.toBlockPos();
        for (int dw = -r; dw <= r; dw++) for (int dz = -r; dz <= r; dz++) for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            double d2 = dx * dx + dy * dy + dz * dz + dw * dw;
            if (d2 > radius * radius) continue;
            BlockPos4 p = cp.offset(dx, dy, dz, dw);
            BlockState s = get(p);
            if (s.isAir() || s.getBlock().getExplosionResistance() > 100) continue;
            if (s.getBlock() instanceof TntBlock) { prime(p); continue; }
            if (world.random().nextFloat() < 0.6f + 0.4f * (float) (1 - Math.sqrt(d2) / radius)) world.setBlock(p, Level4.AIR, true);
        }
        double reach = radius * 2;
        for (ServerPlayer pl : world.players()) {
            Vec4 pp = world.pos4(pl).add(0, 0.9, 0, 0);
            double d = pp.distance(c);
            if (d > reach) continue;
            float dmg = (float) ((1 - d / reach) * 14);
            pl.hurtServer(world.serverLevel(), world.serverLevel().damageSources().generic(), dmg);
            world.knockPlayer(pl, pp.sub(c).normalize().scale((1 - d / reach) * 1.6));
        }
        AABB4 area = new AABB4(c.x() - reach, c.y() - reach, c.z() - reach, c.w() - reach, c.x() + reach, c.y() + reach, c.z() + reach, c.w() + reach);
        for (net.minecraft.world.entity.Entity e : world.entitiesIn(area)) {
            if (e instanceof ServerPlayer) continue;
            Vec4 ep = new Vec4(e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ(), W4.get(e));
            double d = ep.distance(c);
            if (d > reach) continue;
            double f = 1 - d / reach;
            e.hurtServer(world.serverLevel(), world.serverLevel().damageSources().explosion(null, null), (float) (f * 14));
            Vec4 push = ep.sub(c).normalize().scale(f * 1.6);
            e.push(push.x(), Math.max(push.y(), 0.2), push.z());
            W4.set(e, W4.get(e) + push.w() * 0.5);
        }
    }

    // ------------------------------------------------------------------ player actions

    private static final double REACH = 6.5;

    private static Vec4 eye(PlayerState4 st) {
        return st.pos.add(0, 1.62, 0, 0);
    }

    public void handleAction(ServerPlayer p, Net4.Action4C2S a) {
        PlayerState4 st = ServerWorld4.state(p);
        if (p.isSpectator()) return;
        BlockPos4 pos = new BlockPos4(a.x(), a.y(), a.z(), a.w());
        Direction4 face = Direction4.VALUES[Math.floorMod(a.face(), 8)];
        switch (a.action()) {
            case Net4.ACTION_BREAK -> {
                if (eye(st).distance(pos.center()) > REACH) return;
                breakBlock(p, pos);
            }
            case Net4.ACTION_USE -> {
                if (eye(st).distance(pos.center()) > REACH) return;
                use(p, st, pos, face, a.hitX(), a.hitY(), a.hitZ());
            }
            case Net4.ACTION_PLACE -> {
                if (eye(st).distance(pos.center()) > REACH) return;
                place(p, st, pos, face);
            }
            case Net4.ACTION_PICK -> {
                if (eye(st).distance(pos.center()) > REACH + 1) return;
                pick(p, get(pos));
            }

            default -> {}
        }
    }

    /** Middle click: the same rules as vanilla's pick-block (hotbar, then inventory, then creative supply). */
    private void pick(ServerPlayer p, BlockState s) {
        if (s.isAir()) return;
        net.minecraft.world.item.Item item = s.getBlock() instanceof LiquidBlock
                ? (isLava(s) ? Items.LAVA_BUCKET : Items.WATER_BUCKET) : s.getBlock().asItem();
        ItemStack stack = new ItemStack(item);
        if (stack.isEmpty()) return;
        var inv = p.getInventory();
        int slot = inv.findSlotMatchingItem(stack);
        if (slot != -1) {
            if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)) inv.setSelectedSlot(slot);
            else inv.pickSlot(slot);
        } else if (p.hasInfiniteMaterials()) {
            inv.addAndPickItem(stack);
        }
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(inv.getSelectedSlot()));
        p.inventoryMenu.broadcastChanges();
    }

    private void breakBlock(ServerPlayer p, BlockPos4 pos) {
        BlockState s = get(pos);
        if (s.isAir() || s.getBlock() instanceof LiquidBlock) return;
        if (!p.isCreative() && s.getDestroySpeed(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO) < 0) return;
        if (!p.isCreative()) {
            ItemStack tool = p.getMainHandItem();
            boolean harvest = !s.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(s);
            if (harvest) {
                for (ItemStack drop : Block.getDrops(s, world.serverLevel(), p.blockPosition(), null, p, tool)) {
                    world.dropItem(pos.center(), drop);
                }
            }
            p.causeFoodExhaustion(0.005f);
        }
        world.setBlock(pos, Level4.AIR, true);
        playSound(p, s.getSoundType().getBreakSound());
    }

    private void playSound(ServerPlayer p, net.minecraft.sounds.SoundEvent sound) {
        world.serverLevel().playSound(null, p.blockPosition(), sound, SoundSource.BLOCKS, 1f, 1f);
    }

    private void use(ServerPlayer p, PlayerState4 st, BlockPos4 pos, Direction4 face, float fx, float fy, float fz) {
        BlockState s = get(pos);
        Block b = s.getBlock();
        ItemStack held = p.getMainHandItem();
        if (!p.isShiftKeyDown() || held.isEmpty()) {
            if (b instanceof CommandBlock) {
                if (p.canUseGameMasterBlocks() && world.blockEntityOrCreate(pos) instanceof BlockEntities4.CommandBlock4 cb) {
                    var last = cb.lastOutput();
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new Net4.OpenCommandBlock4S2C(pos.asLong(), Block.getId(s),
                            cb.commandBlock.getCommand(), cb.commandBlock.isTrackOutput(), last == null ? "" : last.getString(), cb.auto));
                }
                return;
            }
            if (b instanceof TntBlock && (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE))) {
                prime(pos);
                if (!p.isCreative()) held.shrink(held.is(Items.FIRE_CHARGE) ? 1 : 0);
                return;
            }
        }
        boolean bucket = held.is(Items.WATER_BUCKET) || held.is(Items.LAVA_BUCKET) || held.is(Items.BUCKET) && b instanceof LiquidBlock;
        // Everything else is vanilla's own interaction, run in the block's W slice: block menus and buttons, beds,
        // flint and steel (nether portals), eyes of ender (end portals), hoes, bone meal, placement with vanilla
        // orientation, modded blocks and items, and NeoForge's RightClickBlock event for other mods.
        if (!bucket && face.dw == 0 && vanillaUse(p, pos, face, fx, fy, fz)) return;
        // Buckets
        if (held.is(Items.WATER_BUCKET) || held.is(Items.LAVA_BUCKET)) {
            BlockPos4 target = s.canBeReplaced() ? pos : pos.relative(face);
            if (!replaceableByFluid(get(target)) && get(target).getFluidState().isEmpty()) return;
            world.setBlock(target, held.is(Items.WATER_BUCKET) ? Blocks.WATER.defaultBlockState() : Blocks.LAVA.defaultBlockState(), true);
            if (!p.isCreative()) p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
            playSound(p, SoundEvents.BUCKET_EMPTY);
            return;
        }
        if (held.is(Items.BUCKET) && b instanceof LiquidBlock && level(s) == 0) {
            world.setBlock(pos, Level4.AIR, true);
            if (!p.isCreative()) {
                ItemStack filled = new ItemStack(isLava(s) ? Items.LAVA_BUCKET : Items.WATER_BUCKET);
                held.shrink(1);
                if (held.isEmpty()) p.setItemInHand(InteractionHand.MAIN_HAND, filled);
                else if (!p.getInventory().add(filled)) world.dropItem(world.pos4(p), filled);
            }
            playSound(p, SoundEvents.BUCKET_FILL);
            return;
        }
        place(p, st, pos, face);
    }

    private static Direction dir3(Direction4 d) {
        return switch (d) {
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            default -> null;
        };
    }

    /**
     * Vanilla's right-click, exactly as its packet handler does it (each hand: use on the block, then use the
     * item), with the level mapped to the clicked block's W slice. Returns true if something happened.
     */
    private boolean vanillaUse(ServerPlayer p, BlockPos4 pos, Direction4 face, float fx, float fy, float fz) {
        if (p.isUsingItem()) return true; // already eating, drawing a bow...
        var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(pos.x() + fx, pos.y() + fy, pos.z() + fz),
                dir3(face), new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z()), false);
        boolean done = Access4.inSlice(world, pos.w() + 0.5, () -> {
            var level = world.serverLevel();
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = p.getItemInHand(hand);
                var r = p.gameMode.useItemOn(p, level, stack, hand, hit);
                if (r instanceof net.minecraft.world.InteractionResult.Success ok) {
                    if (ok.shouldSwing()) p.swingAndResetAttackStrength(hand, stack.getInteractAnimation(), ok.swingSource() != net.minecraft.world.InteractionResult.SwingSource.PREDICTED);
                    return true;
                }
                if (r instanceof net.minecraft.world.InteractionResult.Fail) return true;
                if (!stack.isEmpty() && p.gameMode.useItem(p, level, stack, hand) instanceof net.minecraft.world.InteractionResult.Success ok) {
                    if (ok.shouldSwing()) p.swingAndResetAttackStrength(hand, stack.getInteractAnimation(), true);
                    return true;
                }
            }
            return false;
        });
        // a menu opened by the block (chest, furnace, crafting table, modded machine) stays tied to that slice
        if (p.containerMenu != p.inventoryMenu) world.setMenuSlice(p, pos.w() + 0.5);
        return done;
    }

    // ------------------------------------------------------------------ command blocks

    static Direction4 dir4(Direction d) {
        return switch (d) {
            case EAST -> Direction4.EAST;
            case WEST -> Direction4.WEST;
            case NORTH -> Direction4.NORTH;
            case SOUTH -> Direction4.SOUTH;
            case UP -> Direction4.UP;
            case DOWN -> Direction4.DOWN;
        };
    }

    private static net.minecraft.world.level.block.entity.CommandBlockEntity.Mode mode(BlockState s) {
        if (s.is(Blocks.CHAIN_COMMAND_BLOCK)) return net.minecraft.world.level.block.entity.CommandBlockEntity.Mode.SEQUENCE;
        if (s.is(Blocks.REPEATING_COMMAND_BLOCK)) return net.minecraft.world.level.block.entity.CommandBlockEntity.Mode.AUTO;
        return net.minecraft.world.level.block.entity.CommandBlockEntity.Mode.REDSTONE;
    }

    private void commandBlockNeighbor(BlockPos4 p, BlockState s) {
        if (!(world.blockEntityOrCreate(p) instanceof BlockEntities4.CommandBlock4 cb)) return;
        boolean powered = powerAt(p) > 0;
        if (powered == cb.powered) return;
        cb.powered = powered;
        cb.markChanged();
        if (powered && !cb.auto && mode(s) != net.minecraft.world.level.block.entity.CommandBlockEntity.Mode.SEQUENCE) world.schedule(p, 1);
    }

    private boolean conditionMet(BlockPos4 p, BlockState s) {
        if (!s.getValue(CommandBlock.CONDITIONAL)) return true;
        BlockPos4 behind = p.relative(dir4(s.getValue(CommandBlock.FACING)).opposite());
        return world.blockEntity(behind) instanceof BlockEntities4.CommandBlock4 b && b.commandBlock.getSuccessCount() > 0;
    }

    /** Vanilla command block semantics (impulse / repeat / chain, conditional, always active) in 4D. */
    public void tickCommandBlock(BlockPos4 p, BlockState s) {
        if (!(world.blockEntityOrCreate(p) instanceof BlockEntities4.CommandBlock4 cb)) return;
        var mode = mode(s);
        if (mode == net.minecraft.world.level.block.entity.CommandBlockEntity.Mode.SEQUENCE) return;
        if (!cb.powered && !cb.auto) return;
        run(p, s, cb);
        // chain forward along FACING
        BlockPos4 cur = p;
        BlockState cs = s;
        for (int i = 0; i < 64; i++) {
            cur = cur.relative(dir4(cs.getValue(CommandBlock.FACING)));
            cs = get(cur);
            if (!cs.is(Blocks.CHAIN_COMMAND_BLOCK)) break;
            if (!(world.blockEntityOrCreate(cur) instanceof BlockEntities4.CommandBlock4 next)) break;
            if (next.powered || next.auto) run(cur, cs, next);
            else next.commandBlock.setSuccessCount(0);
        }
        if (mode == net.minecraft.world.level.block.entity.CommandBlockEntity.Mode.AUTO) world.schedule(p, 1);
    }

    private void run(BlockPos4 p, BlockState s, BlockEntities4.CommandBlock4 cb) {
        cb.conditionMet = conditionMet(p, s);
        if (cb.conditionMet) cb.execute(world.serverLevel());
        else cb.commandBlock.setSuccessCount(0);
    }

    private void place(ServerPlayer p, PlayerState4 st, BlockPos4 clicked, Direction4 face) {
        ItemStack held = p.getMainHandItem();
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (!(held.getItem() instanceof BlockItem)) {
            held = p.getOffhandItem();
            hand = InteractionHand.OFF_HAND;
            if (!(held.getItem() instanceof BlockItem)) return;
        }
        BlockItem bi = (BlockItem) held.getItem();
        if (bi.getBlock() instanceof GameMasterBlock && !p.canUseGameMasterBlocks()) return;
        BlockPos4 target = get(clicked).canBeReplaced() ? clicked : clicked.relative(face);
        if (target.y() < 0 || target.y() >= Column4.HEIGHT) return;
        BlockState existing = get(target);
        if (!existing.canBeReplaced()) return;
        BlockState state = placementState(bi.getBlock(), face, st, p);
        if (state == null) return;
        double[] col = BlockShapes4.collision(state);
        if (col != null) {
            AABB4 box = new AABB4(target.x() + col[0], target.y() + col[1], target.z() + col[2], target.w() + col[3],
                    target.x() + col[4], target.y() + col[5], target.z() + col[6], target.w() + col[7]);
            if (world.anyEntityIn(box)) return;
        }
        // support requirements for attached blocks
        if ((state.getBlock() instanceof TorchBlock || state.getBlock() instanceof RedstoneWireBlock
                || state.getBlock() instanceof LeverBlock || state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof BushBlock) && face != Direction4.UP && !(state.getBlock() instanceof WallTorchBlock)
                && !(state.getBlock() instanceof RedstoneWallTorchBlock) && !(state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock)) {
            if (!get(target.below()).isSolid()) return;
        }
        world.setBlock(target, state, true);
        if (state.getBlock() instanceof DoorBlock && get(target.above()).canBeReplaced()) {
            world.setBlock(target.above(), state.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), true);
        }
        if (!p.isCreative()) held.shrink(1);
        playSound(p, state.getSoundType().getPlaceSound());
    }

    /** Chooses an orientation for a block placed against a 4D face. */
    private BlockState placementState(Block block, Direction4 face, PlayerState4 st, ServerPlayer p) {
        BlockState s = block.defaultBlockState();
        Direction d3 = switch (face) {
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            default -> null; // ANA / KATA have no 3D equivalent
        };
        if (block == Blocks.TORCH && d3 != null && d3.getAxis().isHorizontal()) {
            return Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, d3);
        }
        if (block == Blocks.REDSTONE_TORCH && d3 != null && d3.getAxis().isHorizontal()) {
            return Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, d3);
        }
        if (s.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis axis = d3 == null ? Direction.Axis.Y : d3.getAxis();
            s = s.setValue(BlockStateProperties.AXIS, axis);
        }
        if (s.hasProperty(BlockStateProperties.FACING)) {
            s = s.setValue(BlockStateProperties.FACING, p.getNearestViewDirection().getOpposite());
        }
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, p.getDirection().getOpposite());
        }
        if (block instanceof RedstoneTorchBlock) s = s.setValue(BlockStateProperties.LIT, true);
        if (s.hasProperty(BlockStateProperties.WATERLOGGED)) s = s.setValue(BlockStateProperties.WATERLOGGED, false);
        return s;
    }
}
