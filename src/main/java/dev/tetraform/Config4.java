package dev.tetraform;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config4 {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue VIEW_RADIUS;
    public static final ModConfigSpec.IntValue PHYSICS_RADIUS;
    public static final ModConfigSpec.IntValue COLUMNS_PER_TICK;
    public static final ModConfigSpec.IntValue GEN_THREADS;
    public static final ModConfigSpec.IntValue MOB_CAP;
    public static final ModConfigSpec.BooleanValue SPAWN_MOBS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("world");
        VIEW_RADIUS = b.comment("Radius, in 16^4 columns, of the visible slice that is streamed to each player.").defineInRange("viewRadius", 6, 2, 16);
        PHYSICS_RADIUS = b.comment("Radius, in columns, of the full 4D neighbourhood kept loaded around each player (all W directions).").defineInRange("physicsRadius", 1, 1, 4);
        COLUMNS_PER_TICK = b.comment("Maximum columns sent to one player per tick.").defineInRange("columnsPerTick", 6, 1, 64);
        GEN_THREADS = b.comment("Worker threads for 4D terrain generation and disk IO (0 = automatic).").defineInRange("genThreads", 0, 0, 32);
        b.pop();
        b.push("mobs");
        SPAWN_MOBS = b.define("spawnMobs", true);
        MOB_CAP = b.comment("Maximum 4D mobs per player neighbourhood.").defineInRange("mobCap", 24, 0, 200);
        b.pop();
        SPEC = b.build();
    }

    private Config4() {}
}
