package dev.tetraform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * The zw (fourth-dimension) rotation controls. The view is built as yaw (xz) &rarr; pitch (yd) &rarr; zw, so
 * these controls only change {@link ClientState4#zw}; mouse look stays vanilla.
 */
public final class Rotation4 {
    private Rotation4() {}

    /** Degrees per scroll notch (the reference camera uses 0.08 rad, about 4.6 degrees). */
    public static final double SCROLL_STEP_DEG = 5.0;

    public static void tick(LocalPlayer player) {
        if (Keys4.SNAP_270.consumeClick()) ClientState4.setZW(Math.toRadians(270));
        if (Keys4.SNAP_0.consumeClick()) ClientState4.setZW(0);
        if (Keys4.SNAP_90.consumeClick()) ClientState4.setZW(Math.toRadians(90));
        if (Keys4.SNAP_180.consumeClick()) ClientState4.setZW(Math.toRadians(180));
        while (Keys4.ROTATE_ANA.consumeClick()) ClientState4.setZW(nextQuarter(ClientState4.zw, -1));
        while (Keys4.ROTATE_KATA.consumeClick()) ClientState4.setZW(nextQuarter(ClientState4.zw, 1));
    }

    /**
     * The next quarter-turn angle strictly past {@code angle} in direction {@code dir}: -1 steps 0 &rarr; 270
     * &rarr; 180 &rarr; 90 (the X, Z, B, C views), +1 the other way. From in between two views it lands on the
     * nearer one in that direction.
     */
    public static double nextQuarter(double angle, int dir) {
        double q = angle / (Math.PI / 2);
        double n = dir < 0 ? Math.ceil(q - 1.0E-6) - 1 : Math.floor(q + 1.0E-6) + 1;
        return n * (Math.PI / 2);
    }

    /** Modifier + scroll rotates into W instead of changing the selected hotbar slot. */
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientState4.active || mc.player == null || mc.gui.screen() != null) return;
        if (!Keys4.ROTATE_MODIFIER.isDown()) return;
        event.setCanceled(true);
        double dy = event.getScrollDeltaY();
        if (dy == 0) return;
        ClientState4.setZW(ClientState4.zw - Math.toRadians(SCROLL_STEP_DEG) * dy);
    }
}
