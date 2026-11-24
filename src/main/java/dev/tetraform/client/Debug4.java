package dev.tetraform.client;

import dev.tetraform.client.render.SliceMesher;
import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Vec4;
import dev.tetraform.world.Column4;
import dev.tetraform.world.Level4;
import dev.tetraform.world.TerrainGenerator4;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryBiome;
import net.minecraft.client.gui.components.debug.DebugEntryHeightmap;
import net.minecraft.client.gui.components.debug.DebugEntryLight;
import net.minecraft.client.gui.components.debug.DebugEntryLookingAt;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Four-dimensional replacements for the vanilla F3 entries (position, section, targeted block/fluid, light,
 * heightmap, biome). Mixins route the vanilla entries here whenever the player is in a 4D level, so the debug
 * screen keeps its normal layout and toggles but shows 4D data.
 */
public final class Debug4 {
    private Debug4() {}

    public static boolean active() {
        return ClientState4.active && ClientState4.hasPos && Minecraft.getInstance().player != null;
    }

    private static Vec4 pos() {
        return ClientState4.pos;
    }

    public static void position(DebugScreenDisplayer d) {
        Minecraft mc = Minecraft.getInstance();
        Vec4 p = pos();
        BlockPos4 b = p.toBlockPos();
        Vec4 look = ClientState4.FRAME.lookDirection(mc.player.getYRot(), mc.player.getXRot());
        Vec4 n = ClientState4.FRAME.normal();
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "XYZW: %.3f / %.5f / %.3f / %.3f", p.x(), p.y(), p.z(), p.w()));
        lines.add(String.format(Locale.ROOT, "Block: %d %d %d %d", b.x(), b.y(), b.z(), b.w()));
        lines.add(String.format(Locale.ROOT, "Chunk: %d %d %d %d [in c.%d.%d.%d.t4]",
                b.x() >> 4, b.y() >> 4, b.z() >> 4, b.w() >> 4, b.x() >> 4, b.z() >> 4, b.w() >> 4));
        lines.add(String.format(Locale.ROOT, "Facing: %s (%s) (%.1f / %.1f / %.1f)", facingName(look), facingTowards(look),
                Mth.wrapDegrees(mc.player.getYRot()), Mth.wrapDegrees(mc.player.getXRot()), Math.toDegrees(ClientState4.zw)));
        lines.add(String.format(Locale.ROOT, "Slice: zw %.1f° normal (%.3f, %.3f, %.3f, %.3f) depth %.3f",
                Math.toDegrees(ClientState4.zw), n.x() + 0.0, n.y() + 0.0, n.z() + 0.0, n.w() + 0.0, ClientState4.FRAME.depthOf(p) + 0.0));
        lines.add(mc.level.dimension().identifier() + " (4D) Columns: " + ClientState4.LEVEL.columns().size());
        lines.add(String.format(Locale.ROOT, "Mesh cache: %d sections, %.1f MB", dev.tetraform.client.render.MeshCache.size(),
                dev.tetraform.client.render.MeshCache.usedBytes() / 1048576.0));
        d.addToGroup(DebugEntryPosition.GROUP, lines);
    }

    public static void section(DebugScreenDisplayer d) {
        BlockPos4 b = pos().toBlockPos();
        d.addToGroup(DebugEntryPosition.GROUP, String.format(Locale.ROOT, "Section-relative: %02d %02d %02d %02d",
                b.x() & 15, b.y() & 15, b.z() & 15, b.w() & 15));
    }

    /** Dominant horizontal look axis, named like vanilla's directions plus ana/kata for W. */
    private static int dominant(Vec4 look) {
        double ax = Math.abs(look.x()), az = Math.abs(look.z()), aw = Math.abs(look.w());
        return ax >= az && ax >= aw ? 0 : (az >= aw ? 2 : 3);
    }

    private static String facingName(Vec4 look) {
        return switch (dominant(look)) {
            case 0 -> look.x() > 0 ? "east" : "west";
            case 2 -> look.z() > 0 ? "south" : "north";
            default -> look.w() > 0 ? "ana" : "kata";
        };
    }

    private static String facingTowards(Vec4 look) {
        return switch (dominant(look)) {
            case 0 -> look.x() > 0 ? "Towards positive X" : "Towards negative X";
            case 2 -> look.z() > 0 ? "Towards positive Z" : "Towards negative Z";
            default -> look.w() > 0 ? "Towards positive W" : "Towards negative W";
        };
    }

    public static void lookingAt(DebugScreenDisplayer d, DebugEntryLookingAt entry) {
        Level4.Hit hit = ClientState4.target;
        List<String> out = new ArrayList<>();
        boolean fluid = entry.group() == DebugEntryLookingAt.FLUID_GROUP;
        boolean tags = entry instanceof DebugEntryLookingAt.DebugEntryLookingAtTags<?>;
        if (hit != null) {
            BlockPos4 b = hit.pos();
            BlockState state = ClientState4.LEVEL.getBlock(b);
            if (fluid) {
                FluidState fs = state.getFluidState();
                if (!fs.isEmpty()) {
                    if (tags) DebugEntryLookingAt.addTagEntries(out, fs);
                    else stateLines(out, "Targeted Fluid", b, fs.typeHolder().getRegisteredName(), fs);
                }
            } else if (tags) {
                DebugEntryLookingAt.addTagEntries(out, state);
            } else {
                stateLines(out, "Targeted Block", b, state.typeHolder().getRegisteredName(), state);
                out.add("Face: " + hit.face().name().toLowerCase(Locale.ROOT));
            }
        }
        d.addToGroup(entry.group(), out);
    }

    private static void stateLines(List<String> out, String prefix, BlockPos4 b, String name, StateHolder<?, ?> state) {
        out.add(ChatFormatting.UNDERLINE + prefix + ": " + b.x() + ", " + b.y() + ", " + b.z() + ", " + b.w());
        out.add(name);
        state.getValues().forEach(entry -> {
            Property.Value<?> v = (Property.Value<?>) entry;
            String value = v.valueName();
            if (Boolean.TRUE.equals(v.value())) value = ChatFormatting.GREEN + value;
            else if (Boolean.FALSE.equals(v.value())) value = ChatFormatting.RED + value;
            out.add(v.property().getName() + ": " + value);
        });
    }

    public static void light(DebugScreenDisplayer d) {
        BlockPos4 b = pos().add(0, 0.5, 0, 0).toBlockPos();
        ClientLevel4 l = ClientState4.LEVEL;
        int sky = SliceMesher.skyLight(l, b.x(), b.y(), b.z(), b.w());
        long[][] em = new long[27][];
        int i = 0;
        for (int dw = -1; dw <= 1; dw++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            em[i++] = l.emitters(Column4.key((b.x() >> 4) + dx, (b.z() >> 4) + dz, (b.w() >> 4) + dw));
        }
        int block = SliceMesher.blockLight(em, b.x(), b.y(), b.z(), b.w(), 0);
        d.addToGroup(DebugEntryLight.GROUP, "Client Light: " + Math.max(sky, block) + " (" + sky + " sky, " + block + " block) [4D]");
    }

    public static void heightmap(DebugScreenDisplayer d) {
        BlockPos4 b = pos().toBlockPos();
        d.addToGroup(DebugEntryHeightmap.GROUP, "4D surface (x, z, w column): " + ClientState4.LEVEL.heightAt(b.x(), b.z(), b.w()));
    }

    public static void biome(DebugScreenDisplayer d) {
        BlockPos4 b = pos().toBlockPos();
        int h = ClientState4.LEVEL.heightAt(b.x(), b.z(), b.w());
        String kind = h < TerrainGenerator4.SEA_LEVEL - 3 ? "ocean" : h <= TerrainGenerator4.SEA_LEVEL + 1 ? "beach"
                : h > 118 ? "snowy_peaks" : h > 92 ? "hills" : "plains";
        d.addToGroup(DebugEntryBiome.GROUP, "Biome: tetraform:4d_" + kind);
    }
}
