package dev.tetraform.client;

import dev.tetraform.mixin.CommandBlockEditScreenAccessor;
import dev.tetraform.net.Net4;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** The vanilla command block screen, editing a 4D command block (changes go back over Tetraform's channel). */
public final class CommandBlockScreen4 extends CommandBlockEditScreen {
    private final long pos4;
    private final CommandBlockEntity proxy;

    private CommandBlockScreen4(CommandBlockEntity proxy, long pos4) {
        super(proxy);
        this.proxy = proxy;
        this.pos4 = pos4;
    }

    public static void open(Net4.OpenCommandBlock4S2C p) {
        CommandBlockEntity proxy = new CommandBlockEntity(BlockPos.ZERO, Block.stateById(p.state()));
        proxy.getCommandBlock().setCommand(p.command());
        proxy.getCommandBlock().setTrackOutput(p.trackOutput());
        proxy.getCommandBlock().setLastOutput(p.lastOutput().isEmpty() ? null : Component.literal(p.lastOutput()));
        proxy.setAutomatic(p.auto());
        CommandBlockScreen4 screen = new CommandBlockScreen4(proxy, p.pos());
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(screen);
        screen.updateGui();
    }

    @Override
    protected void populateAndSendPacket() {
        CommandBlockEditScreenAccessor a = (CommandBlockEditScreenAccessor) (Object) this;
        ClientPacketDistributor.sendToServer(new Net4.SetCommandBlock4C2S(pos4, this.commandEdit.getValue(),
                a.tetraform$mode().ordinal(), proxy.getCommandBlock().isTrackOutput(), a.tetraform$conditional(), a.tetraform$autoexec()));
    }
}
