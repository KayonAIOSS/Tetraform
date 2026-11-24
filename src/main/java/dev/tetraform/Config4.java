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
        VIEW_RADIUS = b.comment("Radius, in 16^4 columns, of the visible slice that is streamed to each player.").defineInRange("sliceRadius", 8, 2, 16);
        PHYSICS_RADIUS = b.comment("Radius, in columns, of the full 4D neighbourhood preloaded around each player in every direction",
                "(x, z and w). Larger values use more memory (about 100 KB per column, (2r+1)^3 columns) but make rotating",
                "into W and strafing along W instant instead of waiting for terrain to stream. 3 suits 8+ GB; 4 suits 16 GB.")
                .defineInRange("preloadRadius", 3, 1, 6);
        COLUMNS_PER_TICK = b.comment("Maximum columns sent to one player per tick.").defineInRange("columnSendRate", 12, 1, 64);
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
