package lu.kolja.matter_manipulator.common.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ChatUtil {

    private ChatUtil() {}

    public static void status(ServerPlayer player, Component component) {
        player.displayClientMessage(component, true);
    }

    public static void info(ServerPlayer player, Component component) {
        player.displayClientMessage(component, false);
    }

    public static void error(ServerPlayer player, Component component) {
        player.displayClientMessage(component.copy().withStyle(ChatFormatting.RED), false);
    }
}
