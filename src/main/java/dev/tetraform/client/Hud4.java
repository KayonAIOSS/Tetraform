package dev.tetraform.client;

import dev.tetraform.math.Vec4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.Locale;

/** Minimal 4D HUD: true XYZW position, the slice tilt, and a W-axis compass. */
public final class Hud4 {
    private Hud4() {}

    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ClientState4.active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        GuiGraphicsExtractor g = event.getGuiGraphics();
        var font = mc.font;
        Vec4 p = ClientState4.pos;
        Vec4 look = Interaction4.lookDir(mc.player);
        Vec4 n = ClientState4.FRAME.normal();
        String[] lines = {
                String.format(Locale.ROOT, "XYZW %.1f / %.1f / %.1f / %.1f", p.x(), p.y(), p.z(), p.w()),
                String.format(Locale.ROOT, "Slice tilt %.0f°  normal (%.2f, %.2f, %.2f)", ClientState4.FRAME.tiltDegrees(), n.x(), n.z(), n.w()),
                String.format(Locale.ROOT, "Look (%.2f, %.2f, %.2f, %.2f)", look.x(), look.y(), look.z(), look.w()),
        };
        int width = g.guiWidth();
        int y = 4;
        for (String s : lines) {
            int w = font.width(s);
            g.fill(width - w - 6, y - 1, width - 2, y + 9, 0x90000000);
            g.text(font, s, width - w - 4, y, 0xFFE0E0FF, false);
            y += 11;
        }
        // W compass: a bar whose marker shows how much of the forward view points into +W/-W.
        int cx = width / 2, top = 4, half = 50;
        g.fill(cx - half, top, cx + half, top + 3, 0x80202040);
        int mx = cx + (int) Math.round(look.w() * half);
        g.fill(mx - 1, top - 2, mx + 2, top + 5, 0xFFB070FF);
        g.text(font, "-W", cx - half - 14, top - 2, 0xFFB0B0D0, false);
        g.text(font, "+W", cx + half + 3, top - 2, 0xFFB0B0D0, false);
        if (ClientState4.waitingForTerrain) {
            String s = "Streaming 4D terrain…";
            g.text(font, s, cx - font.width(s) / 2, top + 10, 0xFFFFFF80, true);
        }
        if (ClientState4.breakProgress > 0) {
            String s = String.format(Locale.ROOT, "%d%%", (int) (ClientState4.breakProgress * 100));
            g.text(font, s, cx - font.width(s) / 2, g.guiHeight() / 2 + 8, 0xFFFFFFFF, true);
        }
    }
}
