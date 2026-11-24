package dev.tetraform;

import dev.tetraform.math.SliceFrame;
import dev.tetraform.math.Vec4;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

/** Server-side persistent 4D state of a player: true 4D position and slice frame. */
public final class PlayerState4 implements ValueIOSerializable {
    public Vec4 pos = new Vec4(0.5, 100, 0.5, 0.5);
    public final SliceFrame frame = new SliceFrame();
    public boolean initialized;
    /** Server tick of the last movement update; used for speed sanity checks. */
    public transient long lastUpdate;

    @Override
    public void serialize(ValueOutput out) {
        out.putDouble("x", pos.x());
        out.putDouble("y", pos.y());
        out.putDouble("z", pos.z());
        out.putDouble("w", pos.w());
        double[] m = frame.values();
        for (int i = 0; i < 9; i++) out.putDouble("f" + i, m[i]);
        out.putBoolean("init", initialized);
    }

    @Override
    public void deserialize(ValueInput in) {
        pos = new Vec4(in.getDoubleOr("x", 0.5), in.getDoubleOr("y", 100), in.getDoubleOr("z", 0.5), in.getDoubleOr("w", 0.5));
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = in.getDoubleOr("f" + i, i % 4 == 0 ? 1 : 0);
        frame.set(new SliceFrame(m));
        frame.orthonormalize();
        initialized = in.getBooleanOr("init", false);
    }

    /** Vanilla (render-space) position of this player: the 4D position expressed in the slice frame. */
    public static double[] renderPosition(SliceFrame frame, Vec4 p) {
        return new double[]{frame.localX(p.x(), p.z(), p.w()), p.y(), frame.localZ(p.x(), p.z(), p.w())};
    }
}
