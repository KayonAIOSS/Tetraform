package dev.tetraform;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config4 {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue VIEW_RADIUS;
    public static final ModConfigSpec.IntValue PHYSICS_RADIUS;
    public static final ModConfigSpec.IntValue COLUMNS_PER_TICK;
    public static final ModConfigSpec.IntValue GEN_THREADS;
    public static final ModConfigSpec.IntValue HOSTILE_CAP;
    public static final ModConfigSpec.IntValue PASSIVE_CAP;
    public static final ModConfigSpec.DoubleValue PASSIVE_GROUP_SCALE;
    public static final ModConfigSpec.DoubleValue CREEPER_DAMAGE;
    public static final ModConfigSpec.BooleanValue SPAWN_MOBS;
    public static final ModConfigSpec.BooleanValue THROTTLE_FAR_MOBS;

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
        HOSTILE_CAP = b.comment("Most hostile mobs within 128 blocks (4D distance) of a player before natural spawning stops.",
                "Default 4900 is vanilla's 70 squared.").defineInRange("hostileCap", 4900, 0, 100000);
        PASSIVE_CAP = b.comment("Most passive animals within 128 blocks (4D distance) of a player before natural spawning stops.",
                "Default 1000 is vanilla's 10 cubed.").defineInRange("passiveCap", 1000, 0, 100000);
        PASSIVE_GROUP_SCALE = b.comment("Passive animals spawn in groups up to this many times vanilla's largest group.")
                .defineInRange("passiveGroupScale", 2.0, 1.0, 8.0);
        CREEPER_DAMAGE = b.comment("Creeper explosion damage multiplier (applies everywhere, 4D or not).",
                "Whether creepers break blocks is the gamerule tetraform:creepers_spare_terrain (on by default).")
                .defineInRange("creeperDamage", 0.5, 0.0, 1.0);
        THROTTLE_FAR_MOBS = b.comment("Tick mobs that are far from every player (in 4D) less often: every 2nd tick past 48 blocks,",
                "every 4th past 96. Keeps big 4D mob counts affordable.").define("throttleFarMobs", true);
        b.pop();
        SPEC = b.build();
    }

    private Config4() {}
}
