package dev.tetraform.world;

import dev.tetraform.Tetraform;
import net.minecraft.world.entity.Entity;

/** The fourth coordinate of every entity in a 4D level (a synced attachment; NaN = not yet assigned). */
public final class W4 {
    private W4() {}

    public static double get(Entity e) {
        Double w = e.getData(Tetraform.ENTITY_W.get());
        return w == null || w.isNaN() ? 0.5 : w;
    }

    public static boolean has(Entity e) {
        Double w = e.getData(Tetraform.ENTITY_W.get());
        return w != null && !w.isNaN();
    }

    public static void set(Entity e, double w) {
        Double cur = e.getData(Tetraform.ENTITY_W.get());
        if (cur != null && cur == w) return;
        e.setData(Tetraform.ENTITY_W.get(), w);
    }

    /** True if two entities share a slice (their W ranges overlap), i.e. can see and touch each other. */
    public static boolean sameSlice(Entity a, Entity b) {
        return Math.abs(get(a) - get(b)) < 0.8;
    }
}
