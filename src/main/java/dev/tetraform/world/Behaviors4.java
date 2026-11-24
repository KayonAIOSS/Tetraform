package dev.tetraform.world;

import dev.tetraform.PlayerState4;
import dev.tetraform.entity.Entity4;
import dev.tetraform.entity.Entity4Type;
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

    public void onChanged(BlockPos4 p, BlockState old, BlockState now) {
        neighborChanged(p, p);
    }

    public void neighborChanged(BlockPos4 p, BlockPos4 from) {
        BlockState s = get(p);
        Block b = s.getBlock();
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
        if (b instanceof FallingBlock) {
            fall(p, s);
        } else if (b instanceof LiquidBlock) {
            tickFluid(p, s);
        } else if (b instanceof RedstoneTorchBlock) {
            boolean lit = s.getValue(BlockStateProperties.LIT);
            boolean shouldLit = !torchAttachedPowered(p, s);
            if (lit != shouldLit) world.setBlock(p, s.setValue(BlockStateProperties.LIT, shouldLit), true);
        } else if (b instanceof RedstoneLampBlock) {
            if (s.getValue(RedstoneLampBlock.LIT) && powerAt(p) == 0) world.setBlock(p, s.setValue(RedstoneLampBlock.LIT, false), true);
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
        for (Entity4 e : world.entities()) {
            double d = e.pos.distance(c);
            if (d > reach) continue;
            e.hurt(world, (float) ((1 - d / reach) * 14), c, null);
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
            case Net4.ACTION_ATTACK -> attack(p, st, a.entityId());
            case Net4.ACTION_BREAK -> {
                if (eye(st).distance(pos.center()) > REACH) return;
                breakBlock(p, pos);
            }
            case Net4.ACTION_USE -> {
                if (eye(st).distance(pos.center()) > REACH) return;
                use(p, st, pos, face);
            }
            case Net4.ACTION_PLACE -> {
                if (eye(st).distance(pos.center()) > REACH) return;
                place(p, st, pos, face);
            }
            case Net4.ACTION_PICK -> {
                if (eye(st).distance(pos.center()) > REACH + 1) return;
                pick(p, get(pos));
            }
            case Net4.ACTION_USE_ENTITY -> useEntity(p, st, a.entityId());
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

    /** Right-click on a 4D mob: feed hyperhogs (carrots, potatoes, beetroot) to heal and breed them. */
    private void useEntity(ServerPlayer p, PlayerState4 st, int id) {
        Entity4 e = world.entity(id);
        if (e == null || e.pos.distance(st.pos) > 6) return;
        ItemStack held = p.getMainHandItem();
        if (e.type == Entity4Type.HYPERHOG && (held.is(Items.CARROT) || held.is(Items.POTATO) || held.is(Items.BEETROOT))) {
            if (e.feed(world)) {
                if (!p.hasInfiniteMaterials()) held.shrink(1);
                world.serverLevel().playSound(null, p.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 1f, 1f);
            }
        }
    }

    private void attack(ServerPlayer p, PlayerState4 st, int id) {
        float dmg = (float) p.getAttributeValue(Attributes.ATTACK_DAMAGE);
        Entity4 e = world.entity(id);
        if (e != null) {
            if (e.pos.distance(st.pos) > 6) return;
            e.hurt(world, dmg, st.pos, p);
            p.resetAttackStrengthTicker();
            return;
        }
        for (ServerPlayer o : world.players()) {
            if (o.getId() == id && o != p) {
                if (ServerWorld4.state(o).pos.distance(st.pos) > 6) return;
                o.hurtServer(world.serverLevel(), world.serverLevel().damageSources().playerAttack(p), dmg);
                world.knockPlayer(o, ServerWorld4.state(o).pos.sub(st.pos).withY(0).normalize().scale(0.5));
            }
        }
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

    private void use(ServerPlayer p, PlayerState4 st, BlockPos4 pos, Direction4 face) {
        BlockState s = get(pos);
        Block b = s.getBlock();
        ItemStack held = p.getMainHandItem();
        if (!p.isShiftKeyDown() || held.isEmpty()) {
            if (b instanceof LeverBlock) {
                world.setBlock(pos, s.cycle(BlockStateProperties.POWERED), true);
                playSound(p, SoundEvents.LEVER_CLICK);
                return;
            }
            if (b instanceof ButtonBlock) {
                if (!s.getValue(ButtonBlock.POWERED)) {
                    world.setBlock(pos, s.setValue(ButtonBlock.POWERED, true), true);
                    buttonRelease.put(pos.asLong(), 1);
                    world.schedule(pos, 20);
                    playSound(p, SoundEvents.STONE_BUTTON_CLICK_ON);
                }
                return;
            }
            if (b instanceof TntBlock && (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE))) {
                prime(pos);
                if (!p.isCreative()) held.shrink(held.is(Items.FIRE_CHARGE) ? 1 : 0);
                return;
            }
            if (s.hasProperty(BlockStateProperties.OPEN)) {
                if (b != Blocks.IRON_DOOR && b != Blocks.IRON_TRAPDOOR) {
                    world.setBlock(pos, s.cycle(BlockStateProperties.OPEN), true);
                    if (b instanceof DoorBlock) {
                        BlockPos4 other = s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? pos.above() : pos.below();
                        BlockState os = get(other);
                        if (os.getBlock() == b) world.setBlock(other, os.setValue(BlockStateProperties.OPEN, get(pos).getValue(BlockStateProperties.OPEN)), true);
                    }
                    playSound(p, SoundEvents.WOODEN_DOOR_OPEN);
                    return;
                }
            }
        }
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

    private void place(ServerPlayer p, PlayerState4 st, BlockPos4 clicked, Direction4 face) {
        ItemStack held = p.getMainHandItem();
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (!(held.getItem() instanceof BlockItem)) {
            held = p.getOffhandItem();
            hand = InteractionHand.OFF_HAND;
            if (!(held.getItem() instanceof BlockItem)) return;
        }
        BlockItem bi = (BlockItem) held.getItem();
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
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, p.getDirection().getOpposite());
        }
        if (block instanceof RedstoneTorchBlock) s = s.setValue(BlockStateProperties.LIT, true);
        if (s.hasProperty(BlockStateProperties.WATERLOGGED)) s = s.setValue(BlockStateProperties.WATERLOGGED, false);
        return s;
    }
}
