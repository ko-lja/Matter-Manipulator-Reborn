package lu.kolja.matter_manipulator.client;

import lu.kolja.matter_manipulator.client.gui.ManipulatorScreen;
import lu.kolja.matter_manipulator.common.network.GuiStatusPacket;
import net.minecraft.client.Minecraft;

public final class ClientGuiStatus {

    private ClientGuiStatus() {}

    public static void apply(GuiStatusPacket packet) {
        if (Minecraft.getInstance().screen instanceof ManipulatorScreen screen) {
            screen.acceptStatus(packet);
        }
    }
}
