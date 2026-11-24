package dev.tetraform.client;

import dev.tetraform.Tetraform;
import dev.tetraform.entity.ProxyEntity4;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Keeps one client-only proxy entity per visible 4D entity, positioned at its render-space location. */
public final class Proxies4 {
    private Proxies4() {}

    private static final Map<Integer, ProxyEntity4> PROXIES = new HashMap<>();
    private static ClientLevel boundLevel;
    private static int nextId = -0x4000_0000;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != boundLevel) {
            PROXIES.clear();
            boundLevel = level;
        }
        if (level == null || !ClientState4.active) {
            clear();
            return;
        }
        for (Map.Entry<Integer, ClientState4.EntityState> e : ClientState4.ENTITIES.entrySet()) {
            Net4.EntityView v = e.getValue().now;
            if (v == null) continue;
            ProxyEntity4 proxy = PROXIES.get(e.getKey());
            double[] rp = SliceManager.toRender(new Vec4(v.x(), v.y(), v.z(), v.w()));
            if (proxy == null || proxy.isRemoved()) {
                proxy = new ProxyEntity4(Tetraform.PROXY4.get(), level);
                proxy.setId(nextId++);
                proxy.viewId = e.getKey();
                proxy.moveProxy(rp[0], rp[1], rp[2], true);
                level.addEntity(proxy);
                PROXIES.put(e.getKey(), proxy);
            } else {
                proxy.moveProxy(rp[0], rp[1], rp[2], false);
            }
        }
        Iterator<Map.Entry<Integer, ProxyEntity4>> it = PROXIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ProxyEntity4> e = it.next();
            if (!ClientState4.ENTITIES.containsKey(e.getKey())) {
                level.removeEntity(e.getValue().getId(), Entity.RemovalReason.DISCARDED);
                it.remove();
            }
        }
    }

    public static void clear() {
        if (boundLevel != null) {
            for (ProxyEntity4 p : PROXIES.values()) boundLevel.removeEntity(p.getId(), Entity.RemovalReason.DISCARDED);
        }
        PROXIES.clear();
    }
}
