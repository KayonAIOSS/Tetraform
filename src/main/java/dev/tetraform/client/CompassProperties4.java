package dev.tetraform.client;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tetraform.Tetraform;
import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.CompassAngleState;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;

/**
 * The compass has two needles, like the orientation dial: a blue one pointing at its target (spawn or a
 * lodestone) within your current slice, and a red one showing the zw rotation. Values are clockwise turns from
 * "straight ahead", in [0, 1).
 */
public final class CompassProperties4 {
    private CompassProperties4() {}

    public static void register(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath(Tetraform.MODID, "needle"), Needle.CODEC);
        event.register(Identifier.fromNamespaceAndPath(Tetraform.MODID, "zw"), Zw.CODEC);
    }

    private static boolean local4D(ItemOwner owner) {
        Minecraft mc = Minecraft.getInstance();
        return ClientState4.active && ClientState4.hasPos && owner != null && owner == mc.player;
    }

    /** The red needle: the zw angle of the holder's view (0 in 3D worlds). */
    public record Zw() implements RangeSelectItemModelProperty {
        public static final MapCodec<Zw> CODEC = MapCodec.unit(new Zw());

        @Override
        public float get(ItemStack stack, ClientLevel level, ItemOwner owner, int seed) {
            if (!local4D(owner)) return 0f;
            return (float) (ClientState4.zw / (Math.PI * 2));
        }

        @Override
        public MapCodec<Zw> type() {
            return CODEC;
        }
    }

    /** The blue needle: vanilla compass behaviour, measured in the holder's slice in 4D. */
    public record Needle(CompassAngleState.CompassTarget target, CompassAngleState vanilla) implements RangeSelectItemModelProperty {
        public static final MapCodec<Needle> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                CompassAngleState.CompassTarget.CODEC.optionalFieldOf("target", CompassAngleState.CompassTarget.SPAWN).forGetter(Needle::target)
        ).apply(i, Needle::new));

        public Needle(CompassAngleState.CompassTarget target) {
            this(target, new CompassAngleState(true, target));
        }

        @Override
        public float get(ItemStack stack, ClientLevel level, ItemOwner owner, int seed) {
            if (level == null || !local4D(owner)) return vanilla.get(stack, level, owner, seed);
            BlockPos t = null;
            if (target == CompassAngleState.CompassTarget.SPAWN) {
                t = level.getRespawnData().pos();
            } else if (target == CompassAngleState.CompassTarget.LODESTONE) {
                var tracker = stack.get(DataComponents.LODESTONE_TRACKER);
                if (tracker != null && tracker.target().isPresent() && tracker.target().get().dimension() == level.dimension()) {
                    t = tracker.target().get().pos();
                }
            }
            if (t == null) return spin(level);
            Vec4 p = ClientState4.pos;
            // the target's W is unknown to vanilla data; spawn and lodestones are treated as being at W 0
            double dx = t.getX() + 0.5 - p.x(), dz = t.getZ() + 0.5 - p.z(), dw = 0.5 - p.w();
            SliceFrame f = ClientState4.mesh.frame();
            double lx = f.localX(dx, dz, dw), lz = f.localZ(dx, dz, dw);
            if (lx * lx + lz * lz < 0.25) return spin(level);
            double targetYaw = Math.toDegrees(Math.atan2(-lx, lz));
            double rel = Mth.wrapDegrees(targetYaw - Minecraft.getInstance().player.getYRot());
            return (float) Mth.positiveModulo(rel / 360.0, 1.0);
        }

        private static float spin(ClientLevel level) {
            return (level.getGameTime() % 32) / 32f;
        }

        @Override
        public MapCodec<Needle> type() {
            return CODEC;
        }
    }
}
