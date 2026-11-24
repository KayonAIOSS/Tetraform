package dev.tetraform.entity;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A client-only stand-in for a 4D entity (mob, item or other player) placed at its render-space position, so
 * that the vanilla entity pipeline draws it. Its renderer submits the 4D body sliced by the viewer's hyperplane.
 * Being a normal entity means shader packs (Iris) include it in every pass, shadow pass included. It is never
 * created on the server, never saved, and cannot be picked, pushed or collided with.
 */
public class ProxyEntity4 extends Entity {
    /** Id of the {@code Net4.EntityView} this proxy shows. */
    public int viewId;

    public ProxyEntity4(EntityType<? extends ProxyEntity4> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** Moves the proxy, keeping the previous position for render interpolation. */
    public void moveProxy(double x, double y, double z, boolean snap) {
        if (snap) {
            setPos(x, y, z);
            setOldPos();
        } else {
            setOldPos();
            setPos(x, y, z);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        // moved by the client from 4D entity snapshots
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
