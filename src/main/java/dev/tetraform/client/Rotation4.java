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
    /** Degrees per tick for the held smooth-turn buttons (60 degrees per second). */
    public static final double SMOOTH_DEG_PER_TICK = 3.0;

    public static void tick(LocalPlayer player) {
        if (Keys4.SNAP_270.consumeClick()) ClientState4.setZW(Math.toRadians(270));
        if (Keys4.SNAP_0.consumeClick()) ClientState4.setZW(0);
        if (Keys4.SNAP_90.consumeClick()) ClientState4.setZW(Math.toRadians(90));
        if (Keys4.SNAP_180.consumeClick()) ClientState4.setZW(Math.toRadians(180));
        int dir = (Keys4.ROTATE_ANA.isDown() ? 1 : 0) - (Keys4.ROTATE_KATA.isDown() ? 1 : 0);
        if (dir != 0) ClientState4.setZW(ClientState4.zw + Math.toRadians(SMOOTH_DEG_PER_TICK * dir));
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
