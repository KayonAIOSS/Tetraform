package dev.tetraform;

import net.minecraft.world.entity.player.Player;

/** Side-neutral hook points used by mixins; the client installs its implementation at startup. */
public final class Hooks4 {
    public interface TravelHook {
        boolean skip(Player player);
    }

    public static volatile TravelHook travelHook = p -> false;

    private Hooks4() {}

    public static boolean skipVanillaTravel(Player p) {
        return p.level().isClientSide() && travelHook.skip(p);
    }
}
