package dev.tetraform.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.tetraform.Tetraform;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * Tetraform controls; all of them are rebindable under Options &rarr; Controls &rarr; Key Binds &rarr; Tetraform (4D).
 * Defaults work out of the box: hold Left Alt and scroll to rotate into W; mouse buttons 4/5 rotate smoothly.
 */
public final class Keys4 {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Tetraform.id("tetraform"));

    /** Hold this and scroll the mouse wheel to rotate z into w (the hotbar does not change while held). */
    public static final KeyMapping ROTATE_MODIFIER = key("w_rotation_modifier", InputConstants.Type.KEYBOARD, InputConstants.KEY_LALT);
    /** Smooth rotation toward +W while held (default: mouse "forward" side button). */
    public static final KeyMapping ROTATE_ANA = key("smooth_turn_ana", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_5);
    /** Smooth rotation toward -W while held (default: mouse "back" side button). */
    public static final KeyMapping ROTATE_KATA = key("smooth_turn_kata", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_4);
    public static final KeyMapping RESET_SLICE = key("reset_w_rotation", InputConstants.Type.KEYBOARD, InputConstants.KEY_N);
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
        e.register(RESET_SLICE);
        e.register(ANA);
        e.register(KATA);
    }
}
