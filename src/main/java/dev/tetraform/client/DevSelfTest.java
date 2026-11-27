package dev.tetraform.client;

import dev.tetraform.client.render.MeshCache;
import dev.tetraform.client.render.MeshWorkers;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Development aid only: with {@code -Dtetraform.selftest=true} the client runs a scripted survival check after
 * joining a 4D world (portals, placement, spawners, eyes of ender, elytra, creepers, mob spawning) and logs
 * "SELFTEST" lines; "SELFTEST MARK" lines are where an outside script takes screenshots. Does nothing in normal play.
 */
public final class DevSelfTest {
    private DevSelfTest() {}

    public static final boolean ENABLED = Boolean.getBoolean("tetraform.selftest");

    private record Step(int delay, Runnable action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int t = -1, index, wait;
    private static int x, y, z, w;

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void log(String s) {
        if (s.startsWith("MARK ")) {
            String name = s.substring(5).split(" ")[0];
            net.minecraft.client.Screenshot.grab(mc().gameDirectory, "selftest-" + name + ".png", mc().gameRenderer.mainRenderTarget(), 1, c -> {});
        }
        dev.tetraform.Tetraform.LOGGER.info("SELFTEST {} | cache {} cells, queued {}, zw {}", s, MeshCache.size(), MeshWorkers.queued(),
                Math.round(Math.toDegrees(ClientState4.zw)));
    }

    private static void cmd(String c) {
        dev.tetraform.Tetraform.LOGGER.info("SELFTEST command /{}", c);
        mc().player.connection.sendCommand(c);
    }

    /** A right click on block (bx, by, bz, bw) from above, as the 4D crosshair would send it. */
    private static void useOnTop(int bx, int by, int bz, int bw) {
        ClientPacketDistributor.sendToServer(new Net4.Action4C2S(Net4.ACTION_USE, bx, by, bz, bw,
                (byte) dev.tetraform.math.Direction4.UP.ordinal(), -1, 0.5f, 1f, 0.5f));
    }

    private static String at(int dx, int yy, int dz) {
        return (x + dx) + " " + yy + " " + (z + dz) + " " + w;
    }

    /** Runs on the integrated server thread (dev only). */
    private static void server(java.util.function.Consumer<net.minecraft.server.level.ServerLevel> r) {
        var s = mc().getSingleplayerServer();
        if (s != null) s.execute(() -> r.accept(s.overworld()));
    }

    private static void step(int delay, Runnable r) {
        STEPS.add(new Step(delay, r));
    }

    /** Shader check: the same views (lake, village-ish terrain, zw 0 / 45 / 90, day and sunset) for screenshots. */
    private static void buildShaders() {
        step(0, () -> {
            Vec4 p = ClientState4.pos;
            x = (int) Math.floor(p.x()); z = (int) Math.floor(p.z()); w = (int) Math.floor(p.w());
            cmd("gamemode creative");
            cmd("time set 1000");
            cmd("weather clear");
            mc().options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            cmd("tp @s 1357.5 86 1335.5 -54.5 20 25 0");
        });
        step(300, () -> log("MARK shader-day-zw0"));
        step(10, () -> ClientState4.setZW(Math.toRadians(45)));
        step(100, () -> log("MARK shader-day-zw45"));
        step(10, () -> ClientState4.setZW(Math.toRadians(90)));
        step(100, () -> log("MARK shader-day-zw90"));
        step(10, () -> {
            ClientState4.setZW(0);
            cmd("time set 12500");
        });
        step(100, () -> log("MARK shader-sunset"));
        step(10, () -> cmd("time set 18000"));
        step(100, () -> log("MARK shader-night"));
        step(20, () -> log("MARK done"));
        step(40, () -> mc().stop());
    }

    private static net.minecraft.server.level.ServerPlayer serverPlayer() {
        var s = mc().getSingleplayerServer();
        return s == null ? null : s.getPlayerList().getPlayer(mc().player.getUUID());
    }

    /** Runs on the integrated server thread with the player's current level. */
    private static void serverHere(java.util.function.BiConsumer<net.minecraft.server.level.ServerLevel, net.minecraft.server.level.ServerPlayer> r) {
        var s = mc().getSingleplayerServer();
        if (s != null) s.execute(() -> {
            var sp = s.getPlayerList().getPlayer(mc().player.getUUID());
            if (sp != null) r.accept(sp.level(), sp);
        });
    }

    /** 0.3.5 checks: hidden-layer collision, snap cycling, Jade server data, w-y portals, the 4D Nether and End. */
    private static void buildDims() {
        int py = 150;
        // an earlier run may have left the player dead or in another dimension
        step(0, () -> {
            if (!mc().player.isAlive()) mc().player.respawn();
        });
        step(60, () -> serverHere((l, sp) -> {
            if (l.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
                sp.teleport(sp.findRespawnPositionAndUseSpawnBlock(false, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            }
        }));
        step(140, () -> {
            cmd("gamemode creative");
            cmd("tp @s ~" + (300 + (int) (System.currentTimeMillis() % 400)) + " " + py + " ~ ~");
        });
        step(80, () -> {
            Vec4 p = ClientState4.pos;
            x = (int) Math.floor(p.x()); z = (int) Math.floor(p.z()); w = (int) Math.floor(p.w()); y = py;
            log("start at " + x + " " + z + " " + w);
            cmd("time set day");
            cmd("fill " + at(-8, py - 1, -8) + " " + at(8, py - 1, 8) + " stone");
            cmd("fill " + at(-8, py, -8) + " " + at(8, py + 6, 8) + " air");
            cmd("tp @s " + (x + 0.5) + " " + py + " " + (z + 0.5) + " " + (w + 0.5) + " 0 20 0");
        });
        // --- collision only with blocks drawn in the slice: a wall one W layer over must not stop you
        step(40, () -> {
            cmd("fill " + (x + 2) + " " + py + " " + (z - 2) + " " + (w + 1) + " " + (x + 2) + " " + (py + 2) + " " + (z + 2) + " " + (w + 1) + " stone");
            cmd("fill " + (x - 2) + " " + py + " " + (z - 2) + " " + w + " " + (x - 2) + " " + (py + 2) + " " + (z + 2) + " " + w + " stone");
        });
        step(20, () -> {
            ClientState4.setZW(0);
            Vec4 at = new Vec4(x + 0.5, py, z + 0.5, w + 0.85);
            var box = dev.tetraform.math.AABB4.ofCenterBottom(at, 0.3, 1.8);
            var hidden = dev.tetraform.entity.Physics4.move(ClientState4.LEVEL, box, new Vec4(3, 0, 0, 0), 0, true, ClientState4.FRAME);
            var seen = dev.tetraform.entity.Physics4.move(ClientState4.LEVEL, box, new Vec4(-3, 0, 0, 0), 0, true, ClientState4.FRAME);
            var old = dev.tetraform.entity.Physics4.move(ClientState4.LEVEL, box, new Vec4(3, 0, 0, 0), 0, true);
            log("collision: through hidden-layer wall dx=" + hidden.delta().x() + " (want 3), visible wall dx=" + seen.delta().x()
                    + " (want about -1.2), old full-body rule dx=" + old.delta().x());
            var fall = dev.tetraform.entity.Physics4.move(ClientState4.LEVEL, dev.tetraform.math.AABB4.ofCenterBottom(new Vec4(x + 2.5, py + 3.2, z + 0.5, w + 0.85), 0.3, 1.8),
                    new Vec4(0, -2, 0, 0), 0, false, ClientState4.FRAME);
            log("standing on a hidden-layer block: onGround=" + fall.onGround() + " dy=" + fall.delta().y() + " (want false, -2)");
        });
        // --- side-button snap cycle: X(0) -> Z(270) -> B(180) -> C(90) -> X; from between Z and X: forward Z, back X
        step(5, () -> {
            StringBuilder sb = new StringBuilder();
            double a = 0;
            for (int i = 0; i < 4; i++) {
                a = Rotation4.nextQuarter(a, -1);
                sb.append(Math.round(Math.toDegrees(((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI)))).append(' ');
            }
            double mid = Math.toRadians(315);
            log("snap forward from 0: " + sb + "| from 315: forward " + Math.round(Math.toDegrees(Rotation4.nextQuarter(mid, -1)))
                    + " back " + Math.round(Math.toDegrees(Rotation4.nextQuarter(mid, 1)) % 360));
        });
        // --- Jade: a chest with items, read on the server through the 4D bridge
        step(5, () -> {
            cmd("fill " + (x - 2) + " " + py + " " + (z - 2) + " " + w + " " + (x - 2) + " " + (py + 2) + " " + (z + 2) + " " + w + " air");
            cmd("fill " + (x + 2) + " " + py + " " + (z - 2) + " " + (w + 1) + " " + (x + 2) + " " + (py + 2) + " " + (z + 2) + " " + (w + 1) + " air");
            cmd("setblock " + at(0, py, 2) + " chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5},{Slot:1b,id:\"minecraft:blaze_rod\",count:3}]}");
            cmd("setblock " + at(2, py, 2) + " furnace{Items:[{Slot:0b,id:\"minecraft:raw_iron\",count:8},{Slot:1b,id:\"minecraft:coal\",count:4}]}");
        });
        step(20, () -> cmd("tp @s " + (x + 0.5) + " " + py + " " + (z + 0.5) + " " + (w + 0.5) + " 0 45 0"));
        step(60, () -> log("MARK jade-chest target=" + mc().hitResult + " cell " + (mc().hitResult instanceof net.minecraft.world.phys.BlockHitResult b ? mc().level.getBlockEntity(b.getBlockPos()) : null)));
        step(5, () -> cmd("tp @s " + (x + 2.5) + " " + py + " " + (z + 0.5) + " " + (w + 0.5) + " 0 45 0"));
        step(60, () -> log("MARK jade-furnace"));
        // --- a nether portal frame in the w-y plane (x and z fixed), lit with fire
        int fx = 6, fz = 2;
        step(5, () -> {
            cmd("fill " + (x + fx) + " " + py + " " + (z + fz) + " " + w + " " + (x + fx) + " " + (py + 4) + " " + (z + fz) + " " + (w + 3) + " obsidian");
            cmd("fill " + (x + fx) + " " + (py + 1) + " " + (z + fz) + " " + (w + 1) + " " + (x + fx) + " " + (py + 3) + " " + (z + fz) + " " + (w + 2) + " air");
        });
        step(10, () -> cmd("setblock " + (x + fx) + " " + (py + 1) + " " + (z + fz) + " " + (w + 1) + " fire"));
        step(10, () -> server(l -> {
            var w4 = dev.tetraform.world.ServerWorld4.get(l);
            StringBuilder sb = new StringBuilder();
            for (int yy = 1; yy <= 3; yy++) for (int ww = 1; ww <= 2; ww++) {
                var s = w4.getBlock(new dev.tetraform.math.BlockPos4(x + fx, py + yy, z + fz, w + ww));
                sb.append(s.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) ? "P" + s.getValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS) : s.getBlock().getName().getString()).append(' ');
            }
            log("w-y portal blocks: " + sb);
        }));
        // walk into it from the view where it faces you (zw 90: the slice holds x, y and w)
        step(10, () -> cmd("tp @s " + (x + fx - 2.5) + " " + (py + 1) + " " + (z + fz + 0.5) + " " + (w + 1.5) + " -90 0 90"));
        step(120, () -> log("MARK wy-portal"));
        step(10, () -> cmd("tp @s " + (x + fx + 0.5) + " " + (py + 1) + " " + (z + fz + 0.5) + " " + (w + 1.5) + " -90 0 90"));
        step(200, () -> log("dimension after w-y portal: " + mc().level.dimension().identifier() + " 4D=" + ClientState4.active + " pos4 " + ClientState4.pos));
        step(60, () -> {
            ClientState4.setZW(0);
            log("MARK nether-arrival");
        });
        // --- the 4D Nether: terrain and a fortress with a blaze spawner
        step(10, () -> serverHere((l, sp) -> {
            var w4 = dev.tetraform.world.ServerWorld4.get(l);
            if (w4 == null) {
                log("nether is not 4D");
                return;
            }
            var sp4 = dev.tetraform.world.Fortress4.nearestBlazeSpawner(w4.generator().seed(), ServerWorld4Pos.of(sp));
            log("nearest fortress blaze spawner: " + sp4);
            if (sp4 != null) {
                room4[0] = sp4.x(); room4[1] = sp4.y(); room4[2] = sp4.z(); room4[3] = sp4.w();
            }
        }));
        step(10, () -> cmd("tp @s " + (room4[0] + 3.5) + " " + (room4[1] - 1) + " " + (room4[2] + 0.5) + " " + (room4[3] + 0.5) + " 90 10 0"));
        step(200, () -> server2(l -> {
            var w4 = dev.tetraform.world.ServerWorld4.get(l);
            var b = w4.getBlock(new dev.tetraform.math.BlockPos4(room4[0], room4[1], room4[2], room4[3]));
            var be = w4.vanillaBlockEntity(new dev.tetraform.math.BlockPos4(room4[0], room4[1], room4[2], room4[3]));
            log("fortress spawner block " + b + " entity " + (be == null ? null : be.saveWithoutMetadata(l.registryAccess())));
        }));
        step(10, () -> log("MARK fortress"));
        step(10, () -> cmd("tp @s " + (room4[0] + 0.5) + " " + (room4[1] + 30) + " " + (room4[2] + 0.5) + " " + (room4[3] + 0.5) + " 0 40 0"));
        step(100, () -> log("MARK nether-view"));
        // --- the 4D End: through an end portal (from here, as vanilla allows from any dimension but the End)
        step(10, () -> serverHere((l, sp) -> {
            var t = ((net.minecraft.world.level.block.EndPortalBlock) net.minecraft.world.level.block.Blocks.END_PORTAL).getPortalDestination(l, sp, sp.blockPosition());
            if (t != null) sp.teleport(t);
        }));
        step(200, () -> log("dimension after end portal: " + mc().level.dimension().identifier() + " 4D=" + ClientState4.active + " pos4 " + ClientState4.pos));
        step(20, () -> log("MARK end-arrival"));
        step(200, () -> server2(l -> {
            int dragons = 0, crystals = 0;
            for (var e : l.getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) dragons++;
                if (e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) crystals++;
            }
            var w4 = dev.tetraform.world.ServerWorld4.get(l);
            int top = w4.level().heightAt(0, 0, 0);
            log("end: dragons " + dragons + " crystals " + crystals + " centre top y " + top + " block " + w4.getBlock(new dev.tetraform.math.BlockPos4(0, top, 0, 0)));
        }));
        step(10, () -> {
            cmd("gamemode spectator");
            cmd("tp @s 60.5 110 0.5 0.5 90 35 0");
        });
        step(150, () -> log("MARK end-island"));
        step(10, () -> {
            mc().player.getAbilities().flying = true;
            cmd("tp @s 1400.5 120 0.5 0.5 90 35 0");
        });
        step(100, () -> server2(l -> {
            var w4 = dev.tetraform.world.ServerWorld4.get(l);
            String c = w4.generator().structures.locate(new Vec4(1400, 100, 0, 0), "end_city");
            log("nearest end city: " + c);
            if (c != null) {
                String[] f = c.substring(c.indexOf(" at ") + 4, c.indexOf(" (", c.indexOf(" at "))).split(" ");
                room4[0] = Integer.parseInt(f[0]); room4[1] = Integer.parseInt(f[1]); room4[2] = Integer.parseInt(f[2]); room4[3] = Integer.parseInt(f[3]);
            }
        }));
        step(10, () -> {
            mc().player.getAbilities().flying = true;
            cmd("tp @s " + (room4[0] - 30.5) + " " + (room4[1] + 40) + " " + (room4[2] + 0.5) + " " + (room4[3] + 0.5) + " -90 30 0");
        });
        step(300, () -> log("MARK end-city"));
        step(10, () -> server2(l -> {
            int frames = 0, shulkers = 0;
            for (var e : l.getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.decoration.ItemFrame f && f.getItem().is(net.minecraft.world.item.Items.ELYTRA)) frames++;
                if (e instanceof net.minecraft.world.entity.monster.Shulker) shulkers++;
            }
            log("end city entities: elytra frames " + frames + " shulkers " + shulkers);
        }));
        step(20, () -> log("MARK done"));
        step(40, () -> mc().stop());
    }

    private static final int[] room4 = new int[4];

    /** Server-thread action on the level the player is in. */
    private static void server2(java.util.function.Consumer<net.minecraft.server.level.ServerLevel> r) {
        serverHere((l, sp) -> r.accept(l));
    }

    private static final class ServerWorld4Pos {
        static Vec4 of(net.minecraft.server.level.ServerPlayer sp) {
            return dev.tetraform.world.ServerWorld4.state(sp).pos;
        }
    }

    private static void build() {
        if ("shaders".equals(System.getProperty("tetraform.selftest.mode"))) {
            buildShaders();
            return;
        }
        if ("dims".equals(System.getProperty("tetraform.selftest.mode"))) {
            buildDims();
            return;
        }
        int py = 150;
        // start somewhere new each run (earlier runs left portals behind)
        step(0, () -> {
            cmd("gamemode creative");
            cmd("tp @s ~" + (200 + (int) (System.currentTimeMillis() % 400)) + " " + py + " ~ ~");
        });
        step(80, () -> {
            Vec4 p = ClientState4.pos;
            x = (int) Math.floor(p.x()); z = (int) Math.floor(p.z()); w = (int) Math.floor(p.w()); y = py;
            log("start at " + x + " " + z + " " + w);
            cmd("gamemode creative");
            cmd("time set day");
            cmd("fill " + at(-8, py - 1, -8) + " " + at(8, py - 1, 8) + " stone");
            cmd("tp @s " + (x + 0.5) + " " + py + " " + (z + 0.5) + " " + (w + 0.5) + " 0 20 0");
        });
        // --- nether portal frame (x-y plane at z+4), lit with flint and steel through vanilla's item code
        step(40, () -> {
            cmd("fill " + at(2, py, 4) + " " + at(5, py + 4, 4) + " obsidian");
            cmd("fill " + at(3, py + 1, 4) + " " + at(4, py + 3, 4) + " air");
            cmd("item replace entity @s weapon.mainhand with flint_and_steel");
        });
        step(20, () -> useOnTop(x + 3, py, z + 4, w));
        step(20, () -> cmd("tetra probe " + at(3, py + 1, 4)));
        // --- chest and bed placed with vanilla placement
        step(10, () -> cmd("item replace entity @s weapon.mainhand with chest"));
        step(10, () -> useOnTop(x - 2, py - 1, z + 1, w));
        step(10, () -> cmd("item replace entity @s weapon.mainhand with red_bed"));
        step(10, () -> useOnTop(x - 4, py - 1, z - 2, w));
        step(10, () -> cmd("tetra probe " + at(-2, py, 1)));
        step(5, () -> cmd("tetra probe " + at(-4, py, -2)));
        // --- spawner with block entity data from the command
        step(10, () -> {
            // closed in all four dimensions (a room one W layer thick is open to the sky along W)
            cmd("fill " + (x - 9) + " " + (py - 8) + " " + (z - 1) + " " + (w - 1) + " " + (x - 1) + " " + (py - 2) + " " + (z + 7) + " " + (w + 1) + " stone");
            cmd("fill " + at(-8, py - 7, 0) + " " + at(-2, py - 3, 6) + " air");
        });
        step(10, () -> cmd("setblock " + at(-5, py - 7, 3) + " spawner{SpawnData:{entity:{id:\"minecraft:spider\"}},MinSpawnDelay:20,MaxSpawnDelay:40}"));
        step(200, () -> server(l -> {
            int n = 0;
            for (var e : l.getAllEntities()) if (e instanceof net.minecraft.world.entity.monster.spider.Spider && e.distanceToSqr(x, py, z) < 30 * 30) n++;
            log("spiders near the spawner: " + n);
            var w4 = dev.tetraform.world.ServerWorld4.get(l);
            var be = w4.vanillaBlockEntity(new dev.tetraform.math.BlockPos4(x - 5, py - 7, z + 3, w));
            dev.tetraform.world.Access4.inSlice(w4, w + 0.5, () -> {
                var sp = new net.minecraft.core.BlockPos(x - 4, py - 7, z + 4);
                var aabb = net.minecraft.world.entity.EntityTypes.SPIDER.getSpawnAABB(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5);
                var spider = net.minecraft.world.entity.EntityTypes.SPIDER.create(l, net.minecraft.world.entity.EntitySpawnReason.SPAWNER);
                spider.snapTo(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, 0, 0);
                log("spawner checks: state=" + l.getBlockState(sp) + " below=" + l.getBlockState(sp.below())
                        + " noCollision=" + l.noCollision(aabb) + " noBlock=" + l.noBlockCollision(null, aabb) + " firstHit=" + (l.getBlockCollisions(null, aabb).iterator().hasNext() ? l.getBlockCollisions(null, aabb).iterator().next().bounds() : null) + " aabb=" + aabb + " raw=" + l.getRawBrightness(sp, 0) + " maxLocal=" + l.getMaxLocalRawBrightness(sp)
                        + " sky=" + l.getBrightness(net.minecraft.world.level.LightLayer.SKY, sp)
                        + " rules=" + net.minecraft.world.entity.SpawnPlacements.checkSpawnRules(net.minecraft.world.entity.EntityTypes.SPIDER, l,
                        net.minecraft.world.entity.EntitySpawnReason.SPAWNER, sp, l.getRandom())
                        + " mobRules=" + spider.checkSpawnRules(l, net.minecraft.world.entity.EntitySpawnReason.SPAWNER)
                        + " obstruction=" + spider.checkSpawnObstruction(l) + " nearPlayer=" + l.hasNearbyAlivePlayer(x - 4.5, py - 6.5, z + 3.5, 16)
                        + " spawnerEnabled=" + l.isSpawnerBlockEnabled());
            });
            log("spawner entity: " + be + " " + (be == null ? "" : be.saveWithoutMetadata(l.registryAccess())) + " block " + w4.getBlock(new dev.tetraform.math.BlockPos4(x - 5, py - 7, z + 3, w)));
        }));
        // --- eye of ender thrown into the air
        step(10, () -> {
            cmd("item replace entity @s weapon.mainhand with ender_eye");
            mc().player.setXRot(-60);
        });
        step(10, () -> mc().gameMode.useItem(mc().player, net.minecraft.world.InteractionHand.MAIN_HAND));
        step(10, () -> server(l -> {
            int n = 0;
            for (var e : l.getAllEntities()) if (e instanceof net.minecraft.world.entity.projectile.EyeOfEnder) n++;
            log("eyes of ender flying: " + n);
        }));
        step(5, () -> cmd("locate structure minecraft:stronghold"));
        // --- ender pearl: the 4D position must follow vanilla's teleport
        step(10, () -> {
            cmd("item replace entity @s weapon.mainhand with ender_pearl");
            mc().player.setXRot(55);
            log("before pearl pos " + ClientState4.pos);
        });
        step(10, () -> mc().gameMode.useItem(mc().player, net.minecraft.world.InteractionHand.MAIN_HAND));
        step(80, () -> log("after pearl pos " + ClientState4.pos));
        // --- creeper next to the platform: blocks must survive, damage reduced
        step(20, () -> {
            mc().player.setXRot(20);
            cmd("gamemode survival");
            cmd("summon creeper " + (x + 2.5) + " " + py + " " + (z - 1.5) + " " + (w + 0.5));
        });
        step(5, () -> server(l -> {
            for (var e : l.getAllEntities()) if (e instanceof net.minecraft.world.entity.monster.Creeper c) c.ignite();
        }));
        step(60, () -> {
            log("health after creeper: " + mc().player.getHealth());
            cmd("tetra probe " + at(2, py - 1, -2));
            cmd("gamerule tetraform:creepers_spare_terrain");
            cmd("gamemode creative");
            cmd("effect give @s instant_health 1 5");
        });
        // --- elytra glide
        step(20, () -> {
            cmd("item replace entity @s armor.chest with elytra");
            cmd("tp @s " + (x + 0.5) + " 220 " + (z + 0.5) + " " + (w + 0.5) + " 0 10 90");
        });
        step(40, () -> {
            log("before glide pos " + ClientState4.pos);
            mc().player.getAbilities().flying = false;
            if (mc().player.tryToStartFallFlying()) {
                mc().player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc().player,
                        net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
        });
        step(40, () -> log("gliding=" + mc().player.isFallFlying() + " pos " + ClientState4.pos + " vel " + ClientState4.vel));
        step(5, () -> {
            log("MARK glide");
            cmd("item replace entity @s armor.chest with air");
            cmd("tp @s " + (x + 0.5) + " " + py + " " + (z + 0.5) + " " + (w + 0.5) + " 0 20 0");
        });
        // --- night: hostile mobs spawn in 4D
        step(20, () -> cmd("time set midnight"));
        step(400, () -> server(l -> {
            int hostile = 0, passive = 0;
            for (var e : l.getAllEntities()) {
                var cat = e.getType().getCategory();
                if (cat == net.minecraft.world.entity.MobCategory.MONSTER) hostile++;
                else if (cat == net.minecraft.world.entity.MobCategory.CREATURE) passive++;
            }
            log("after 20 s of night: hostile " + hostile + ", passive " + passive);
        }));
        step(5, () -> cmd("time set day"));
        // --- Jade: look at the chest
        step(20, () -> {
            cmd("tp @s " + (x - 2 + 0.5) + " " + py + " " + (z - 1 + 0.5) + " " + (w + 0.5) + " 0 50 0");
        });
        step(40, () -> log("MARK jade-chest target=" + mc().hitResult))
        ;
        // --- nether and back
        step(10, () -> cmd("tp @s " + (x + 3.5) + " " + (py + 1) + " " + (z + 4.5) + " " + (w + 0.5) + " 0 0 0"));
        step(160, () -> log("dimension after entering portal: " + mc().level.dimension().identifier()));
        step(20, () -> log("MARK nether"));
        step(10, () -> {
            var p = mc().player;
            cmd("tp @s ~3 ~ ~3");
        });
        step(40, () -> {
            // walk back into the arrival portal
            var bp = mc().player.blockPosition();
            for (var q : net.minecraft.core.BlockPos.betweenClosed(bp.offset(-6, -4, -6), bp.offset(6, 4, 6))) {
                if (mc().level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
                    cmd("tp @s " + (q.getX() + 0.5) + " " + q.getY() + " " + (q.getZ() + 0.5));
                    break;
                }
            }
        });
        step(200, () -> {
            log("dimension after returning: " + mc().level.dimension().identifier() + " pos4 " + ClientState4.pos);
            cmd("tetra where");
        });
        step(60, () -> log("MARK back-from-nether"));
        // --- the End: fill a generated stronghold's frames with eyes, go through, die there, respawn at the 4D spawnpoint
        step(10, () -> {
            cmd("tp @s " + (x + 0.5) + " " + py + " " + (z + 0.5) + " " + (w + 0.5) + " 0 20 0");
            cmd("spawnpoint @s " + x + " " + py + " " + z);
        });
        int[] room = new int[4];
        step(20, () -> {
            var sv = mc().getSingleplayerServer();
            var w4 = dev.tetraform.world.ServerWorld4.get(sv.overworld());
            Vec4 s = dev.tetraform.world.Rooms4.nearestStronghold(w4.generator().seed(), ClientState4.pos);
            room[0] = (int) Math.floor(s.x() - 5.5); room[1] = (int) Math.floor(s.y()) - 2;
            room[2] = (int) Math.floor(s.z() - 11.5); room[3] = (int) Math.floor(s.w());
            log("stronghold room at " + room[0] + " " + room[1] + " " + room[2] + " " + room[3]);
            cmd("tp @s " + (room[0] + 2.5) + " " + (room[1] + 2) + " " + (room[2] + 8.5) + " " + (room[3] + 0.5) + " 0 30 0");
        });
        step(100, () -> {
            cmd("item replace entity @s weapon.mainhand with ender_eye 64");
            // hover over the middle of the ring so every frame is within reach
            mc().player.getAbilities().flying = true;
            cmd("tp @s " + (room[0] + 5.5) + " " + (room[1] + 3.5) + " " + (room[2] + 11.5) + " " + (room[3] + 0.5) + " 0 60 0");
            cmd("tetra probe " + (room[0] + 4) + " " + (room[1] + 2) + " " + (room[2] + 9) + " " + room[3]);
        });
        // one right click per few ticks, like a player placing eyes one after another
        int[][] ringPos = {{4, 9}, {5, 9}, {6, 9}, {4, 13}, {5, 13}, {6, 13}, {3, 10}, {3, 11}, {3, 12}, {7, 10}, {7, 11}, {7, 12}};
        for (int[] q : ringPos) step(4, () -> useOnTop(room[0] + q[0], room[1] + 2, room[2] + q[1], room[3]));
        step(20, () -> cmd("tetra probe " + (room[0] + 5) + " " + (room[1] + 2) + " " + (room[2] + 11) + " " + room[3]));
        step(5, () -> server(l -> {
            var w4 = dev.tetraform.world.ServerWorld4.get(l);
            StringBuilder sb = new StringBuilder();
            int fy = room[1] + 2, x0 = room[0], z0 = room[2], cw = room[3];
            int[][] ring = {{4, 9}, {5, 9}, {6, 9}, {4, 13}, {5, 13}, {6, 13}, {3, 10}, {3, 11}, {3, 12}, {7, 10}, {7, 11}, {7, 12}};
            for (int[] q : ring) {
                var s = w4.getBlock(new dev.tetraform.math.BlockPos4(x0 + q[0], fy, z0 + q[1], cw));
                sb.append(s.hasProperty(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE)
                        ? (s.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE) ? "E" : "o") + s.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.FACING).getName().charAt(0) : "?").append(' ');
            }
            var match = dev.tetraform.world.Access4.inSlice(w4, cw + 0.5, () -> net.minecraft.world.level.block.EndPortalFrameBlock.getOrCreatePortalShape()
                    .find(l, new net.minecraft.core.BlockPos(x0 + 4, fy, z0 + 9)));
            log("frames: " + sb + " pattern match: " + (match != null));
        }));
        step(10, () -> log("MARK end-portal"));
        step(10, () -> cmd("tp @s " + (room[0] + 5.5) + " " + (room[1] + 2.2) + " " + (room[2] + 11.5) + " " + (room[3] + 0.5)));
        step(100, () -> log("dimension after end portal: " + mc().level.dimension().identifier()));
        step(20, () -> log("MARK the-end"));
        step(10, () -> cmd("kill @s"));
        step(40, () -> mc().player.respawn());
        step(120, () -> log("after respawn: " + mc().level.dimension().identifier() + " pos4 " + ClientState4.pos + " expected near " + x + " " + py + " " + z + " " + w));
        step(20, () -> log("MARK done"));
        step(40, () -> mc().stop());
    }

    public static void tick(Minecraft mc) {
        if (!ENABLED) return;
        // NeoForge's dev-time "mods loaded with warnings" screen would block the scripted run: press Proceed
        if (mc.gui.screen() instanceof net.neoforged.neoforge.client.gui.LoadingErrorScreen s) {
            try {
                var errors = net.neoforged.neoforge.client.gui.LoadingErrorScreen.class.getDeclaredField("modLoadErrors");
                errors.setAccessible(true);
                if (((java.util.List<?>) errors.get(s)).isEmpty()) {
                    var next = net.neoforged.neoforge.client.gui.LoadingErrorScreen.class.getDeclaredField("nextScreenTask");
                    next.setAccessible(true);
                    ((Runnable) next.get(s)).run();
                }
            } catch (ReflectiveOperationException e) {
                dev.tetraform.Tetraform.LOGGER.warn("SELFTEST could not skip the loading warning screen", e);
            }
        }
        if (mc.player == null) return;
        if (t < 0 && !ClientState4.hasPos && mc.level != null && dev.tetraform.Tetraform.is4D(mc.level)) return;
        t++;
        if (t < 100) return;
        if (STEPS.isEmpty()) build();
        if (index >= STEPS.size()) return;
        if (wait < STEPS.get(index).delay) {
            wait++;
            return;
        }
        wait = 0;
        try {
            STEPS.get(index++).action.run();
        } catch (Throwable e) {
            dev.tetraform.Tetraform.LOGGER.error("SELFTEST step {} failed", index - 1, e);
        }
    }
}
