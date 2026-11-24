package dev.tetraform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The server tracks players at their true world (x, y, z), but terrain is drawn in the viewer's own render space,
 * which a W rotation can move far away from it. In a 4D level every vanilla chunk is the same invisible
 * filler, so the client keeps its own render chunks: it replays the first real chunk packet at whatever
 * positions surround the camera, and ignores the server's chunk centre and unload messages.
 */
public final class ClientChunks4 {
    private ClientChunks4() {}

    private static ClientboundLevelChunkWithLightPacket template;
    private static ClientLevel templateLevel;
    private static final Set<Long> loaded = new HashSet<>();
    public static boolean allowForget;

    public static void onChunkPacket(ClientboundLevelChunkWithLightPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (template == null || templateLevel != mc.level) {
            template = p;
            templateLevel = mc.level;
            loaded.clear();
        }
        loaded.add(ChunkPos.pack(p.x(), p.z()));
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        var player = mc.player;
        var listener = mc.getConnection();
        if (level == null || player == null || listener == null || template == null || templateLevel != level) return;
        int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
        var cache = level.getChunkSource();
        cache.updateViewCenter(cx, cz);
        int rd = mc.options.getEffectiveRenderDistance() + 1;
        // load missing chunks, nearest first, a bounded number per tick
        List<long[]> missing = new ArrayList<>();
        for (int dz = -rd; dz <= rd; dz++) for (int dx = -rd; dx <= rd; dx++) {
            if (!cache.hasChunk(cx + dx, cz + dz)) missing.add(new long[]{cx + dx, cz + dz, (long) dx * dx + (long) dz * dz});
        }
        missing.sort((a, b) -> Long.compare(a[2], b[2]));
        int budget = 24;
        for (long[] m : missing) {
            if (budget-- <= 0) break;
            listener.handleLevelChunkWithLight(new ClientboundLevelChunkWithLightPacket((int) m[0], (int) m[1], template.chunkData(), template.lightData()));
        }
        // forget chunks far from the camera
        if (level.getGameTime() % 20 == 0) {
            List<Long> far = new ArrayList<>();
            for (long k : loaded) {
                int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
                if (Math.abs(x - cx) > rd + 3 || Math.abs(z - cz) > rd + 3) far.add(k);
            }
            allowForget = true;
            try {
                for (long k : far) {
                    loaded.remove(k);
                    listener.handleForgetLevelChunk(new ClientboundForgetLevelChunkPacket(new ChunkPos(ChunkPos.getX(k), ChunkPos.getZ(k))));
                }
            } finally {
                allowForget = false;
            }
        }
    }

    public static void reset() {
        template = null;
        templateLevel = null;
        loaded.clear();
    }
}
