package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public final class NetworkUtil {

    private NetworkUtil() {}

    public static ItemStack heldManipulator(ServerPlayer player) {
        var hand = heldManipulatorHand(player);
        return hand == null ? ItemStack.EMPTY : player.getItemInHand(hand);
    }

    public static InteractionHand heldManipulatorHand(ServerPlayer player) {
        var main = player.getMainHandItem();
        if (main.getItem() instanceof ManipulatorItem) {
            return InteractionHand.MAIN_HAND;
        }
        var offhand = player.getOffhandItem();
        if (offhand.getItem() instanceof ManipulatorItem) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }
}
