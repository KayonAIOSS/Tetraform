package dev.tetraform.world;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.tetraform.Tetraform;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The Nether and the End of a 4D world are 4D too. New worlds get them from the world preset; worlds created
 * before they existed are upgraded when they load (their overworld is 4D but their Nether and End were vanilla),
 * and the old 3D chunks there are cleared as they load so only 4D terrain is left.
 */
public final class Dimensions4 {
    private Dimensions4() {}

    private static String stem(String type, String biome) {
        return "{\"type\":\"" + type + "\",\"generator\":{\"type\":\"minecraft:flat\",\"settings\":{\"biome\":\"" + biome
                + "\",\"features\":false,\"lakes\":false,\"layers\":[{\"block\":\"tetraform:slice_anchor\",\"height\":256}],\"structure_overrides\":[]}}}";
    }

    private static boolean ours(LevelStem s) {
        return s != null && s.type().unwrapKey().map(k -> k.identifier().getNamespace().equals(Tetraform.MODID)).orElse(false);
    }

    /** A 4D world's saved settings with 4D Nether and End (unchanged if they already are, or the world isn't 4D). */
    public static WorldGenSettings upgrade(WorldGenSettings settings, HolderLookup.Provider registries) {
        if (settings == null || registries == null) return settings;
        Map<ResourceKey<LevelStem>, LevelStem> dims = settings.dimensions().dimensions();
        if (!ours(dims.get(LevelStem.OVERWORLD))) return settings;
        boolean nether = dims.containsKey(LevelStem.NETHER) && !ours(dims.get(LevelStem.NETHER));
        boolean end = dims.containsKey(LevelStem.END) && !ours(dims.get(LevelStem.END));
        if (!nether && !end) return settings;
        try {
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            Map<ResourceKey<LevelStem>, LevelStem> out = new HashMap<>(dims);
            if (nether) out.put(LevelStem.NETHER, parse(ops, stem("tetraform:nether", "minecraft:nether_wastes")));
            if (end) out.put(LevelStem.END, parse(ops, stem("tetraform:end", "minecraft:the_end")));
            Tetraform.LOGGER.info("Upgrading this world's {} to 4D", nether && end ? "Nether and End" : nether ? "Nether" : "End");
            return new WorldGenSettings(settings.options(), new WorldDimensions(out));
        } catch (RuntimeException e) {
            Tetraform.LOGGER.error("Could not make this world's Nether and End 4D", e);
            return settings;
        }
    }

    private static LevelStem parse(RegistryOps<com.google.gson.JsonElement> ops, String json) {
        return LevelStem.CODEC.parse(ops, JsonParser.parseString(json)).getOrThrow();
    }

    /**
     * A 4D level's vanilla chunks only ever hold the invisible slice anchor. Chunks saved while the level was
     * still 3D (a Nether or End visited before the upgrade) are cleared when they load.
     */
    @SubscribeEvent
    public static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Tetraform.is4D(level) || level.dimension() == Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        BlockState anchor = Tetraform.SLICE_ANCHOR.get().defaultBlockState();
        boolean old = false;
        for (LevelChunkSection sec : chunk.getSections()) {
            if (sec.getStates().maybeHas(s -> s != anchor)) {
                old = true;
                break;
            }
        }
        if (!old) return;
        int n = 0;
        for (LevelChunkSection sec : chunk.getSections()) {
            if (!sec.getStates().maybeHas(s -> s != anchor)) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (sec.getBlockState(x, y, z) != anchor) {
                    sec.setBlockState(x, y, z, anchor, false);
                    n++;
                }
            }
        }
        for (var pos : new java.util.ArrayList<>(chunk.getBlockEntities().keySet())) chunk.removeBlockEntity(pos);
        chunk.markUnsaved();
        Tetraform.LOGGER.debug("Cleared {} old 3D blocks from chunk {} in {}", n, chunk.getPos(), level.dimension().identifier());
    }

    /** Vanilla's own natural spawning sees only the anchor-filled 3D chunks; the 4D spawner replaces it. */
    @SubscribeEvent
    public static void onSpawnCheck(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.PositionCheck event) {
        var r = event.getSpawnType();
        if (r != net.minecraft.world.entity.EntitySpawnReason.NATURAL && r != net.minecraft.world.entity.EntitySpawnReason.CHUNK_GENERATION) return;
        if (!(event.getLevel().getLevel() instanceof ServerLevel level) || !Tetraform.is4D(level)) return;
        if (Access4.serverContext(level) == null) event.setResult(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.PositionCheck.Result.FAIL);
    }
}
