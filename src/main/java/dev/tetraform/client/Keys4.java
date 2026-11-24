package dev.tetraform.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.tetraform.Tetraform;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

public final class Keys4 {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Tetraform.id("tetraform"));

    public static final KeyMapping ANA = key("ana", InputConstants.KEY_R);
    public static final KeyMapping KATA = key("kata", InputConstants.KEY_V);
    public static final KeyMapping ROTATE_ANA = key("rotate_ana", InputConstants.KEY_G);
    public static final KeyMapping ROTATE_KATA = key("rotate_kata", InputConstants.KEY_B);
    public static final KeyMapping RESET_SLICE = key("reset_slice", InputConstants.KEY_N);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.tetraform." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYBOARD, code, CATEGORY);
    }

    private Keys4() {}

    public static void register(RegisterKeyMappingsEvent e) {
        e.registerCategory(CATEGORY);
        e.register(ANA);
        e.register(KATA);
        e.register(ROTATE_ANA);
        e.register(ROTATE_KATA);
        e.register(RESET_SLICE);
    }
}
