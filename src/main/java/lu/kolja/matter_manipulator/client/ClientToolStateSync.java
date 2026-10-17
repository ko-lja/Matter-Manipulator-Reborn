package lu.kolja.matter_manipulator.client;

import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.state.ToolState;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;

public final class ClientToolStateSync {

    private ClientToolStateSync() {}

    public static void apply(InteractionHand hand, CompoundTag tag) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        var stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ManipulatorItem)) {
            return;
        }
        ToolState.setStackTag(stack, tag.copy());
        PreviewRenderer.invalidateCablePreview();
    }
}
