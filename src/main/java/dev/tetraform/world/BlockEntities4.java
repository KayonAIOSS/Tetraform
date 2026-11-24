package dev.tetraform.world;

import dev.tetraform.math.BlockPos4;
import dev.tetraform.math.Vec4;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Block entities for 4D blocks: command blocks and storage containers. They live at 4D positions, are saved
 * next to each column, and drive vanilla menus and the vanilla command block screen.
 */
public final class BlockEntities4 {
    private BlockEntities4() {}

    /** 4D origin of the command currently executing from a 4D command block (read by the 4D coordinate argument). */
    public static final ThreadLocal<Vec4> COMMAND_ORIGIN = new ThreadLocal<>();

    public abstract static class BE4 {
        public final BlockPos4 pos;
        protected Runnable onChange = () -> {};

        protected BE4(BlockPos4 pos) {
            this.pos = pos;
        }

        public abstract String type();

        public abstract void save(ValueOutput out);

        public abstract void load(ValueInput in);

        public void markChanged() {
            onChange.run();
        }
    }

    /** Which blocks get a storage inventory, and how many slots. 0 = none. */
    public static int containerSize(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock) return 27;
        if (b instanceof DispenserBlock) return 9;
        if (b instanceof HopperBlock) return 5;
        return 0;
    }

    public static boolean needsEntity(BlockState s) {
        return s.getBlock() instanceof CommandBlock || containerSize(s) > 0;
    }

    public static BE4 create(BlockPos4 pos, BlockState s) {
        if (s.getBlock() instanceof CommandBlock) return new CommandBlock4(pos);
        int size = containerSize(s);
        return size > 0 ? new Container4(pos, size) : null;
    }

    public static BE4 create(BlockPos4 pos, String type) {
        if ("command_block".equals(type)) return new CommandBlock4(pos);
        if (type.startsWith("container")) {
            int size = 27;
            try {
                size = Integer.parseInt(type.substring("container".length()));
            } catch (NumberFormatException ignored) {
            }
            return new Container4(pos, size);
        }
        return null;
    }

    // ------------------------------------------------------------------ containers

    public static final class Container4 extends BE4 {
        public final SimpleContainer inventory;

        Container4(BlockPos4 pos, int size) {
            super(pos);
            this.inventory = new SimpleContainer(size) {
                @Override
                public void setChanged() {
                    super.setChanged();
                    markChanged();
                }
            };
        }

        @Override
        public String type() {
            return "container" + inventory.getContainerSize();
        }

        @Override
        public void save(ValueOutput out) {
            NonNullList<ItemStack> items = NonNullList.withSize(inventory.getContainerSize(), ItemStack.EMPTY);
            for (int i = 0; i < items.size(); i++) items.set(i, inventory.getItem(i));
            ContainerHelper.saveAllItems(out, items);
        }

        @Override
        public void load(ValueInput in) {
            NonNullList<ItemStack> items = NonNullList.withSize(inventory.getContainerSize(), ItemStack.EMPTY);
            ContainerHelper.loadAllItems(in, items);
            for (int i = 0; i < items.size(); i++) inventory.setItem(i, items.get(i));
        }
    }

    // ------------------------------------------------------------------ command blocks

    public static final class CommandBlock4 extends BE4 {
        public boolean powered;
        public boolean auto;
        public boolean conditionMet;
        private ServerLevel level;

        public final BaseCommandBlock commandBlock = new BaseCommandBlock() {
            @Override
            public void setCommand(String command) {
                super.setCommand(command);
                markChanged();
            }

            @Override
            public void onUpdated(ServerLevel level) {
                markChanged();
            }

            @Override
            public CommandSourceStack createCommandSourceStack(ServerLevel level, CommandSource source) {
                return new CommandSourceStack(source, new Vec3(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5), Vec2.ZERO, level,
                        LevelBasedPermissionSet.GAMEMASTER, this.getName(), level.getServer());
            }

            @Override
            public boolean isValid() {
                return true;
            }
        };

        CommandBlock4(BlockPos4 pos) {
            super(pos);
        }

        @Override
        public String type() {
            return "command_block";
        }

        /** Runs the command with this block's 4D position as the origin of {@code ~ ~ ~ ~}. */
        public boolean execute(ServerLevel level) {
            this.level = level;
            Vec4 prev = COMMAND_ORIGIN.get();
            COMMAND_ORIGIN.set(pos.center());
            try {
                return commandBlock.performCommand(level);
            } finally {
                COMMAND_ORIGIN.set(prev);
                markChanged();
            }
        }

        public Component lastOutput() {
            return commandBlock.getLastOutput();
        }

        @Override
        public void save(ValueOutput out) {
            commandBlock.save(out);
            out.putBoolean("powered", powered);
            out.putBoolean("auto", auto);
            out.putBoolean("conditionMet", conditionMet);
        }

        @Override
        public void load(ValueInput in) {
            commandBlock.load(in);
            powered = in.getBooleanOr("powered", false);
            auto = in.getBooleanOr("auto", false);
            conditionMet = in.getBooleanOr("conditionMet", false);
        }
    }
}
