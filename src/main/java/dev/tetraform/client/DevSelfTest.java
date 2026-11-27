package dev.tetraform.client;

import dev.tetraform.client.render.MeshCache;
import dev.tetraform.client.render.MeshWorkers;
import net.minecraft.client.Minecraft;

/**
 * Development aid only: with {@code -Dtetraform.selftest=true} the client walks through a scripted check after
 * joining a 4D world (fly to a fresh village, rotate, rotate back, run 4D commands), logging "SELFTEST" markers
 * that an outside script uses to take screenshots. Does nothing in normal play.
 */
public final class DevSelfTest {
    private DevSelfTest() {}

    public static final boolean ENABLED = Boolean.getBoolean("tetraform.selftest");
    private static int t = -1;
    private static int step;
    private static long stepStart;

    private static void log(String s) {
        dev.tetraform.Tetraform.LOGGER.info("SELFTEST {} | cache {} cells {} MB, queued {}, zw {}", s, MeshCache.size(),
                MeshCache.usedBytes() >> 20, MeshWorkers.queued(), Math.round(Math.toDegrees(ClientState4.zw)));
    }

    private static void cmd(Minecraft mc, String c) {
        dev.tetraform.Tetraform.LOGGER.info("SELFTEST command /{}", c);
        mc.player.connection.sendCommand(c);
    }

    private static int cmdIndex;
    private static double base;
    private static int villageY = 80;
    private static final String[] COMMANDS = {
            "tetra where",
            "summon cow ~2 ~ ~ ~",
            "execute as @e[type=cow,limit=1,sort=nearest] run tp @s ~ ~ ~ ~3",
            "execute as @e[type=cow,limit=1,sort=nearest] at @s run setblock ~ ~-1 ~ ~ gold_block",
            "tetra probe ~2 ~-1 ~ ~3",
            "execute if block ~ ~-60 ~ ~ minecraft:air run say if-block-air (should not print)",
            "execute unless block ~ ~-60 ~ ~ minecraft:air run say unless-block-air ok",
            "setblock ^ ^ ^3 ^ glowstone keep",
            "tp @s @e[type=cow,limit=1,sort=nearest]",
            "tetra where",
            "locate structure minecraft:village_plains",
            "summon pig",
            "fill ~1 ~ ~1 ~1 ~2 ~2 ~2 ~1 stone",
            "clone ~1 ~ ~1 ~1 ~2 ~2 ~2 ~1 ~5 ~ ~ ~ masked move",
            "tetra probe ~5 ~ ~ ~",
            "tp ~ ~10 ~",
            "tetra where",
    };

    private static boolean settled() {
        return MeshWorkers.queued() == 0;
    }

    public static void tick(Minecraft mc) {
        if (!ENABLED || mc.player == null || !ClientState4.hasPos) return;
        t++;
        if (t < 60) return;
        long now = System.currentTimeMillis();
        switch (step) {
            case 0 -> {
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                cmd(mc, "gamemode creative");
                var server = mc.getSingleplayerServer();
                String where = null;
                if (server != null) {
                    var w = dev.tetraform.world.ServerWorld4.get(server.overworld());
                    var p = ClientState4.pos;
                    // far from anything generated before this build, so the village uses the new structure code
                    where = w == null ? null : w.generator().structures.locate(new dev.tetraform.math.Vec4(p.x() + 1500, p.y(), p.z() + 1500, p.w()), "village");
                }
                log("locate " + where);
                if (where != null) {
                    String[] a = where.substring(where.indexOf(" at ") + 4).split(" ");
                    int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]), w = Integer.parseInt(a[3]);
                    // stand in the village's own plane, 28 blocks back, looking along its second axis
                    if (where.contains("(XW") || where.contains("(WX")) cmd(mc, "tp @s " + x + " " + (y + 22) + " " + (z + 0.5) + " " + (w - 28) + " 0 35 90");
                    else cmd(mc, "tp @s " + x + " " + (y + 22) + " " + (z - 28) + " " + (w + 0.5) + " 0 35 0");
                    villageY = y;
                }
                step++; stepStart = now;
            }
            case 1 -> { if (now - stepStart > 25000 && settled()) { log("MARK village"); step++; stepStart = now; } }
            case 2 -> { if (now - stepStart > 2000) { base = ClientState4.zw; ClientState4.setZW(base + Math.toRadians(45)); log("rotate 45"); step++; stepStart = now; } }
            case 3 -> { if (now - stepStart > 150) { log("MARK rot45-early"); step++; } }
            case 4 -> { if (settled()) { log("MARK rot45-done after " + (now - stepStart) + " ms"); step++; stepStart = now; } }
            case 5 -> { if (now - stepStart > 3000) { ClientState4.setZW(base); log("rotate back"); step++; stepStart = now; } }
            case 6 -> { if (now - stepStart > 250) { log("MARK back0"); step++; stepStart = now; } }
            case 7 -> { if (now - stepStart > 3000) { ClientState4.setZW(base + Math.toRadians(45)); log("rotate 45 again"); step++; stepStart = now; } }
            case 8 -> { if (now - stepStart > 250) { log("MARK again45 (queued should be ~0: cache)"); step++; stepStart = now; } }
            case 9 -> {
                if (now - stepStart > 3000) {
                    ClientState4.setZW(base);
                    // land 10 blocks into the village and look at the ground for Jade
                    cmd(mc, "tp @s ^ ^ ^18 ^ ~ 60 ~");
                    step = 20; stepStart = now;
                }
            }
            case 20 -> { if (now - stepStart > 1500) { cmd(mc, "tp @s ~ " + (villageY + 3) + " ~ ~ ~ 70 ~"); step++; stepStart = now; } }
            case 21 -> { if (now - stepStart > 4000 && settled()) { log("MARK jade target=" + mc.hitResult + " state=" + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b ? mc.level.getBlockState(b.getBlockPos()) : null)); step = 10; stepStart = now; } }
            case 10 -> {
                if (now - stepStart > 600) {
                    if (cmdIndex < COMMANDS.length) cmd(mc, COMMANDS[cmdIndex++]);
                    else { log("MARK commands-done"); step++; }
                    stepStart = now;
                }
            }
            default -> {}
        }
    }
}
