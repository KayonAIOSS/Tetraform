package dev.tetraform.entity;

import dev.tetraform.math.AABB4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Level4;
import dev.tetraform.world.ServerWorld4;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A server-simulated four-dimensional entity: 4D position and velocity, a 4D orientation (a rotation of the
 * horizontal x/z/w space), 4D collision, and 4D AI.
 */
public class Entity4 {
    private static int nextId = 1 << 24;

    public final int id;
    public final Entity4Type type;
    public Vec4 pos;
    public Vec4 vel = Vec4.ZERO;
    public final SliceFrame frame = new SliceFrame();
    public boolean onGround;
    public float health;
    public int hurtTime;
    public int age;
    public boolean removed;
    public ItemStack stack = ItemStack.EMPTY;
    public int pickupDelay;

    // AI state
    private double moveX, moveZ, moveW; // desired horizontal direction (unit) in world xzw
    private double moveSpeed;
    private int wanderTimer;
    private int attackCooldown;
    private int panicTicks;
    private Vec4 fleeFrom;
    private boolean wantJump;

    public Entity4(Entity4Type type, Vec4 pos) {
        this.id = nextId++;
        this.type = type;
        this.pos = pos;
        this.health = type.maxHealth;
    }

    public AABB4 box() {
        return AABB4.ofCenterBottom(pos, type.halfWidth, type.height);
    }

    public void tick(ServerWorld4 world) {
        age++;
        if (hurtTime > 0) hurtTime--;
        if (attackCooldown > 0) attackCooldown--;
        if (pickupDelay > 0) pickupDelay--;
        Level4 level = world.level();
        if (!level.isLoaded((int) Math.floor(pos.x()), (int) Math.floor(pos.z()), (int) Math.floor(pos.w()))) return;

        if (type == Entity4Type.ITEM) {
            tickItem(world);
        } else {
            ai(world);
        }

        boolean inFluid = level.isFluidAt(pos.add(0, 0.2, 0, 0));
        double gravity = inFluid ? 0.02 : 0.08;
        double hx = vel.x(), hz = vel.z(), hw = vel.w();
        if (moveSpeed > 0) {
            double accel = onGround ? 0.25 : 0.05;
            hx += (moveX * moveSpeed - hx) * accel;
            hz += (moveZ * moveSpeed - hz) * accel;
            hw += (moveW * moveSpeed - hw) * accel;
        } else {
            double f = onGround ? 0.55 : 0.91;
            hx *= f;
            hz *= f;
            hw *= f;
        }
        double vy = vel.y();
        if (wantJump && (onGround || inFluid)) {
            vy = inFluid ? 0.12 : 0.42;
        }
        wantJump = false;
        vy = (vy - gravity) * (inFluid ? 0.8 : 0.98);
        Vec4 delta = new Vec4(hx, vy, hz, hw);
        Physics4.Result r = Physics4.move(level, box(), delta, type == Entity4Type.ITEM ? 0 : 0.6, onGround);
        pos = pos.add(r.delta());
        onGround = r.onGround();
        vel = new Vec4(r.delta().x() == hx ? hx : 0, r.verticalCollision() ? 0 : vy, r.delta().z() == hz ? hz : 0, r.delta().w() == hw ? hw : 0);
        if (r.horizontalCollision() && moveSpeed > 0) wantJump = true;

        // Turn smoothly toward the direction of travel in all three horizontal axes (x, z AND w).
        double sp = Math.sqrt(hx * hx + hz * hz + hw * hw);
        if (sp > 0.01 && type != Entity4Type.ITEM) {
            frame.turnForwardTowards(hx, hz, hw, Math.toRadians(12));
        }
        if (pos.y() < -64) removed = true;
        if (type.hostile && world.nearestPlayer(pos, 128) == null && age > 600) removed = true;
    }

    private void tickItem(ServerWorld4 world) {
        moveSpeed = 0;
        if (age > 6000) { removed = true; return; }
        if (pickupDelay > 0) return;
        ServerPlayer p = world.nearestPlayer(pos, 1.6);
        if (p != null && !p.isSpectator()) {
            if (p.getInventory().add(stack)) {
                if (stack.isEmpty()) removed = true;
            }
        }
    }

    private void ai(ServerWorld4 world) {
        RandomSource rnd = world.random();
        ServerPlayer target = type.hostile ? world.nearestPlayer(pos, 20) : null;
        if (target != null && (target.isCreative() || target.isSpectator())) target = null;
        if (panicTicks > 0 && fleeFrom != null) {
            panicTicks--;
            Vec4 away = pos.sub(fleeFrom);
            setMove(away.x(), away.z(), away.w(), type.speed * 1.6);
            if (rnd.nextInt(10) == 0) wantJump = true;
            return;
        }
        if (target != null) {
            Vec4 tp = world.pos4(target);
            Vec4 d = tp.sub(pos);
            setMove(d.x(), d.z(), d.w(), type.speed);
            if (tp.y() > pos.y() + 0.6 && rnd.nextInt(4) == 0) wantJump = true;
            if (d.length() < 1.4 && attackCooldown == 0) {
                attackCooldown = 20;
                target.hurtServer(world.serverLevel(), world.serverLevel().damageSources().generic(), 3.0f);
                Vec4 kb = d.withY(0).normalize().scale(0.4);
                world.knockPlayer(target, kb);
            }
            return;
        }
        if (--wanderTimer <= 0) {
            if (rnd.nextFloat() < 0.35f) {
                moveSpeed = 0;
                wanderTimer = 40 + rnd.nextInt(80);
            } else {
                // pick a uniformly random direction on the 2-sphere of horizontal directions (x, z, w)
                double u = rnd.nextDouble() * 2 - 1, th = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - u * u);
                setMove(r * Math.cos(th), u, r * Math.sin(th), type.speed * 0.6);
                wanderTimer = 30 + rnd.nextInt(90);
            }
        }
        // avoid walking into deep water or off cliffs: probe ahead
        if (moveSpeed > 0 && onGround) {
            Vec4 ahead = pos.add(moveX * 0.9, -0.5, moveZ * 0.9, moveW * 0.9);
            int ground = 0;
            for (int i = 0; i < 4; i++) {
                if (!world.level().getBlock(ahead.add(0, -i, 0, 0).toBlockPos()).isAir()) { ground = i; break; }
                ground = i + 1;
            }
            if (ground >= 3) { moveSpeed = 0; wanderTimer = 5; }
        }
    }

    private void setMove(double x, double z, double w, double speed) {
        double l = Math.sqrt(x * x + z * z + w * w);
        if (l < 1.0E-6) { moveSpeed = 0; return; }
        moveX = x / l;
        moveZ = z / l;
        moveW = w / l;
        moveSpeed = speed;
    }

    public void hurt(ServerWorld4 world, float amount, Vec4 from, ServerPlayer attacker) {
        if (type == Entity4Type.ITEM || hurtTime > 0) return;
        health -= amount;
        hurtTime = 10;
        Vec4 kb = pos.sub(from).withY(0).normalize().scale(0.45);
        vel = vel.add(kb.x(), 0.3, kb.z(), kb.w());
        if (!type.hostile) {
            panicTicks = 80;
            fleeFrom = from;
        }
        if (health <= 0) {
            removed = true;
            if (type == Entity4Type.HYPERHOG) {
                world.dropItem(pos.add(0, 0.5, 0, 0), new ItemStack(Items.PORKCHOP, 1 + world.random().nextInt(3)));
            } else if (type == Entity4Type.STALKER) {
                world.dropItem(pos.add(0, 0.5, 0, 0), new ItemStack(Items.ENDER_PEARL, world.random().nextInt(2)));
                world.dropItem(pos.add(0, 0.5, 0, 0), new ItemStack(Items.OBSIDIAN, 1));
            }
            if (attacker != null) attacker.giveExperiencePoints(type.hostile ? 5 : 2);
        }
    }
}
