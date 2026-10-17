package lu.kolja.matter_manipulator.client;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMHud;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.SelectionBox;
import lu.kolja.matter_manipulator.common.state.ToolState;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Optional;

public final class PendingSelection {

    private PendingSelection() {}

    public static Optional<SelectionBox> box(Minecraft minecraft, ItemStack stack, FloatingPlacement.Anchors anchors,
            Optional<BlockPos> mark) {
        var state = ToolState.get(stack);
        if (state.mode() == ManipulatorMode.CABLE || anchors.a().isEmpty() || anchors.b().isPresent()
                || mark.isEmpty() || !state.isSameDimension(minecraft.level)) {
            return Optional.empty();
        }
        var tier = ((ManipulatorItem) stack.getItem()).tier();
        long maxVolume = state.mode() == ManipulatorMode.GEOMETRY ? Long.MAX_VALUE : MMConfig.MAX_REGION_VOLUME.get();
        return Optional.of(SelectionBox.between(anchors.a().get(), mark.get(), minecraft.player.blockPosition(),
                tier.range(), maxVolume));
    }

    static void registerOverlay(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, MatterManipulator.id("pending_selection"),
                PendingSelection::renderHud);
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var hand = ClientEvents.manipulatorHand();
        if (minecraft.options.hideGui || player == null || minecraft.level == null || hand == null) {
            return;
        }
        var stack = player.getItemInHand(hand);
        var state = ToolState.get(stack);
        var box = box(minecraft, stack, FloatingPlacement.anchors(player, state),
                PreviewRenderer.nextMark(minecraft, state)).orElse(null);
        if (box == null) {
            return;
        }
        var text = MMHud.SELECTION_SIZE.text(box.sizeX(), box.sizeY(),
                box.sizeZ(), box.volume()).withStyle(box.refusal().isPresent() ? ChatFormatting.RED
                : ChatFormatting.WHITE);
        graphics.text(minecraft.font, text, (graphics.guiWidth() - minecraft.font.width(text)) / 2,
                graphics.guiHeight() - 86, 0xFFFFFF);
    }
}
