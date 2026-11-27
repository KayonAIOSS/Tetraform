package dev.tetraform.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.tetraform.Tetraform;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * Tetraform controls; all of them are rebindable under Options &rarr; Controls &rarr; Key Binds &rarr; Tetraform (4D).
 * Defaults work out of the box: hold Left Alt and scroll to rotate smoothly into W; mouse buttons 4/5 step through
 * the four quarter-turn views.
 */
public final class Keys4 {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Tetraform.id("tetraform"));

    /** Hold this and scroll the mouse wheel to rotate z into w (the hotbar does not change while held). */
    public static final KeyMapping ROTATE_MODIFIER = key("w_rotation_modifier", InputConstants.Type.KEYBOARD, InputConstants.KEY_LALT);
    /**
     * Snap to the next quarter-turn view in the order +X, -W, -X, +W (the X, Z, B, C keys), i.e. from between the
     * Z and X views it goes to Z (default: mouse "forward" side button).
     */
    public static final KeyMapping ROTATE_ANA = key("snap_turn_forward", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_5);
    /** The same cycle the other way: from between the Z and X views it goes to X (default: mouse "back" side button). */
    public static final KeyMapping ROTATE_KATA = key("snap_turn_back", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_4);
    /** Snap the zw rotation to a quarter turn: "left" 270, "forward" 0, "right" 90, "backward" 180. */
    public static final KeyMapping SNAP_270 = key("snap_zw_270", InputConstants.Type.KEYBOARD, InputConstants.KEY_Z);
    public static final KeyMapping SNAP_0 = key("snap_zw_0", InputConstants.Type.KEYBOARD, InputConstants.KEY_X);
    public static final KeyMapping SNAP_90 = key("snap_zw_90", InputConstants.Type.KEYBOARD, InputConstants.KEY_C);
    public static final KeyMapping SNAP_180 = key("snap_zw_180", InputConstants.Type.KEYBOARD, InputConstants.KEY_B);
    /** Strafe along the hidden axis (the slice normal). */
    public static final KeyMapping ANA = key("strafe_ana", InputConstants.Type.KEYBOARD, InputConstants.KEY_R);
    public static final KeyMapping KATA = key("strafe_kata", InputConstants.Type.KEYBOARD, InputConstants.KEY_V);

    private static KeyMapping key(String name, InputConstants.Type type, int code) {
        return new KeyMapping("key.tetraform." + name, KeyConflictContext.IN_GAME, type, code, CATEGORY);
    }

    private Keys4() {}

    public static void register(RegisterKeyMappingsEvent e) {
        e.registerCategory(CATEGORY);
        e.register(ROTATE_MODIFIER);
        e.register(ROTATE_ANA);
        e.register(ROTATE_KATA);
        e.register(SNAP_270);
        e.register(SNAP_0);
        e.register(SNAP_90);
        e.register(SNAP_180);
        e.register(ANA);
        e.register(KATA);
    }
}
