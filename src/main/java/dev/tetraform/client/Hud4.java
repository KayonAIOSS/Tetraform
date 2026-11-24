package dev.tetraform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.Locale;

/**
 * Small 4D orientation dial in the top-right corner, modelled on the reference camera's indicator. Red is the
 * zw angle, blue the xz yaw, green the pitch. Full coordinates live in the F3 screen, which Tetraform turns 4D.
 */
public final class Hud4 {
    private Hud4() {}

    private static final int R = 22;

    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ClientState4.active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        GuiGraphicsExtractor g = event.getGuiGraphics();
        var font = mc.font;
        int width = g.guiWidth();
        boolean f3 = mc.debugEntries.isOverlayVisible();
        int cx = width - R - 8, cy = (f3 ? g.guiHeight() - R - 40 : R + 8);

        // ring
        for (int i = 0; i < 64; i++) {
            double a = i * Math.PI * 2 / 64;
            int x = cx + (int) Math.round(Math.sin(a) * R), y = cy - (int) Math.round(Math.cos(a) * R);
            g.fill(x, y, x + 1, y + 1, 0xC0FFFFFF);
        }
        g.fill(cx, cy - R, cx + 1, cy + R, 0x60FFFFFF);
        g.fill(cx - R, cy, cx + R, cy + 1, 0x60FFFFFF);
        double zw = ClientState4.zw;
        double xz = Math.toRadians(mc.player.getYRot());
        double pitch = Math.toRadians(-mc.player.getXRot());
        dot(g, cx + Math.sin(xz) * R, cy - Math.cos(xz) * R, 0xFF3737FF);
        dot(g, cx, cy - Math.sin(pitch) * R, 0xFF37FF37);
        dot(g, cx + Math.sin(zw) * R, cy - Math.cos(zw) * R, 0xFFFF3737);
        String label = String.format(Locale.ROOT, "zw %.0f°", Math.toDegrees(zw));
        g.text(font, label, cx - font.width(label) / 2, cy + R + 3, 0xFFFF6060, true);

        int mid = width / 2;
        if (ClientState4.waitingForTerrain) {
            String s = "Streaming 4D terrain…";
            g.text(font, s, mid - font.width(s) / 2, 4, 0xFFFFFF80, true);
        }
        if (ClientState4.breakProgress > 0) {
            String s = String.format(Locale.ROOT, "%d%%", (int) (ClientState4.breakProgress * 100));
            g.text(font, s, mid - font.width(s) / 2, g.guiHeight() / 2 + 8, 0xFFFFFFFF, true);
        }
    }

    private static void dot(GuiGraphicsExtractor g, double x, double y, int color) {
        int ix = (int) Math.round(x), iy = (int) Math.round(y);
        g.fill(ix - 2, iy - 2, ix + 3, iy + 3, color);
    }
}
