package dev.tetraform.client;

import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import dev.tetraform.net.Net4;
import dev.tetraform.world.Column4;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientNet4 {
    private ClientNet4() {}

    /** Columns are inflated off-thread and installed on the main thread. */
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Tetraform-Column-Decoder");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentLinkedQueue<Column4> DECODED = new ConcurrentLinkedQueue<>();
    private static volatile int generation;

    public static void register(RegisterClientPayloadHandlersEvent e) {
        e.register(Net4.ColumnS2C.TYPE, (p, ctx) -> {
            int gen = generation;
            byte[] data = p.data();
            DECODER.execute(() -> {
                Column4 c = Column4.fromBytes(data);
                if (gen == generation) DECODED.add(c);
            });
        });
        e.register(Net4.UnloadColumnsS2C.TYPE, (p, ctx) -> {
            for (long k : p.keys()) {
                ClientState4.LEVEL.remove(k);
                SliceManager.markColumn(Column4.keyX(k), Column4.keyZ(k), Column4.keyW(k));
            }
        });
        e.register(Net4.BlockUpdatesS2C.TYPE, (p, ctx) -> {
            long[] pos = p.positions();
            int[] st = p.states();
            for (int i = 0; i < pos.length; i++) {
                BlockPos4 b = BlockPos4.of(pos[i]);
                ClientState4.LEVEL.setClient(b, Block.stateById(st[i]));
                SliceManager.markBlock(b);
            }
        });
        e.register(Net4.EntitiesS2C.TYPE, (p, ctx) -> {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (Net4.EntityView v : p.entities()) {
                ClientState4.EntityState s = ClientState4.ENTITIES.computeIfAbsent(v.id(), k -> new ClientState4.EntityState());
                s.prev = s.now == null ? v : s.now;
                s.now = v;
                seen.add(v.id());
            }
            ClientState4.ENTITIES.keySet().retainAll(seen);
        });
        e.register(Net4.Teleport4S2C.TYPE, (p, ctx) -> {
            if (p.reset()) {
                generation++;
                DECODED.clear();
                ClientState4.reset();
            }
            ClientState4.pos = new Vec4(p.x(), p.y(), p.z(), p.w());
            ClientState4.prevPos = ClientState4.pos;
            ClientState4.vel = Vec4.ZERO;
            SliceFrame f = new SliceFrame(p.frame());
            f.orthonormalize();
            ClientState4.setZW(f.zwAngle());
            ClientState4.hasPos = true;
            SliceManager.forceRemesh();
            Movement4.forceSend();
            var player = Minecraft.getInstance().player;
            if (player != null) Movement4.mirror(player);
        });
        e.register(Net4.Impulse4S2C.TYPE, (p, ctx) -> Movement4.addImpulse(new Vec4(p.x(), p.y(), p.z(), p.w())));
    }

    /** Installs decoded columns; bounded per tick to avoid hitches. */
    public static void drain() {
        long deadline = System.nanoTime() + 3_000_000L;
        Column4 c;
        while ((c = DECODED.poll()) != null) {
            ClientState4.LEVEL.put(c);
            SliceManager.markColumn(c.cx, c.cz, c.cw);
            if (System.nanoTime() > deadline) break;
        }
    }

    public static void clearPending() {
        generation++;
        DECODED.clear();
    }
}
