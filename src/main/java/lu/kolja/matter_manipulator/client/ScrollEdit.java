package lu.kolja.matter_manipulator.client;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMHud;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.PasteAnchor;
import lu.kolja.matter_manipulator.common.manipulator.SelectionEdit;
import lu.kolja.matter_manipulator.common.network.EditSelectionPacket;
import lu.kolja.matter_manipulator.common.network.NudgeAnchorPacket;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

@EventBusSubscriber(modid = MatterManipulator.MOD_ID, value = Dist.CLIENT)
public final class ScrollEdit {

    private static final ScrollSteps<Edit> PENDING = new ScrollSteps<>();

    private ScrollEdit() {}

    private record Edit(InteractionHand hand, SelectionEdit.Scroll action, @Nullable Direction direction) {}

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        int steps = (int) Math.signum(event.getScrollDeltaY());
        var hand = ClientEvents.manipulatorHand();
        if (player == null || minecraft.level == null || steps == 0 || hand == null) {
            return;
        }
        var stack = player.getItemInHand(hand);
        var state = ToolState.get(stack);
        var action = heldAction(state, minecraft);
        if (action == SelectionEdit.Scroll.NONE) {
            return;
        }
        event.setCanceled(true);
        if (action == SelectionEdit.Scroll.FLOATING_DISTANCE) {
            if (PasteAnchor.state(state, minecraft.level.dimension()) == PasteAnchor.State.FOLLOWING) {
                PasteFollow.changeFloatingDistance(minecraft, player, (ManipulatorItem) stack.getItem(), steps);
            } else {
                send(PENDING.add(new Edit(hand, action, null), steps));
            }
            return;
        }
        send(PENDING.add(new Edit(hand, action, PasteAnchor.nudgeDirection(player.getViewVector(1.0F))), steps));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        send(PENDING.take());
    }

    private static SelectionEdit.Scroll heldAction(ToolState state, Minecraft minecraft) {
        boolean move = ClientEvents.NUDGE_MODIFIER.isDown();
        if (!move && !ClientEvents.RESIZE_MODIFIER.isDown()) {
            return SelectionEdit.Scroll.NONE;
        }
        return SelectionEdit.scrollAction(state, minecraft.level.dimension(), !move);
    }

    private static void send(@Nullable ScrollSteps.Batch<Edit> batch) {
        if (batch == null) {
            return;
        }
        var edit = batch.edit();
        var direction = edit.direction();
        switch (edit.action()) {
            case MOVE_ANCHOR -> PacketDistributor.sendToServer(new NudgeAnchorPacket(edit.hand(), direction.getAxis(),
                    batch.steps() * direction.getAxisDirection().getStep()));
            case MOVE_SELECTION -> PacketDistributor.sendToServer(new EditSelectionPacket(edit.hand(),
                    EditSelectionPacket.Edit.MOVE, direction, batch.steps()));
            case RESIZE -> PacketDistributor.sendToServer(new EditSelectionPacket(edit.hand(),
                    EditSelectionPacket.Edit.RESIZE, direction, batch.steps()));
            case FLOATING_DISTANCE -> PacketDistributor.sendToServer(new EditSelectionPacket(edit.hand(),
                    EditSelectionPacket.Edit.FLOATING_DISTANCE, Direction.UP, batch.steps()));
            case NONE -> {
            }
        }
    }

    static void registerOverlay(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, MatterManipulator.id("scroll_edit"), ScrollEdit::renderHud);
    }

    private static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var hand = ClientEvents.manipulatorHand();
        if (minecraft.options.hideGui || player == null || minecraft.level == null || hand == null) {
            return;
        }
        var stack = player.getItemInHand(hand);
        var state = ToolState.get(stack);
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        var direction = PasteAnchor.nudgeDirection(player.getViewVector(partialTick)).getName();
        Component text;
        switch (heldAction(state, minecraft)) {
            case MOVE_ANCHOR -> text = MMHud.SCROLL_MOVE_ANCHOR.text(direction);
            case MOVE_SELECTION -> text = MMHud.SCROLL_MOVE_SELECTION.text(
                    direction);
            case RESIZE -> text = MMHud.SCROLL_RESIZE.text(direction);
            case FLOATING_DISTANCE -> {
                boolean following = PasteAnchor.state(state, minecraft.level.dimension()) == PasteAnchor.State.FOLLOWING;
                text = MMHud.SCROLL_DISTANCE.text(following
                        ? PasteFollow.changeFloatingDistance(minecraft, player, (ManipulatorItem) stack.getItem(), 0)
                        : Mth.floor(FloatingPlacement.distance(player, state)));
            }
            default -> {
                return;
            }
        }
        graphics.drawString(minecraft.font, text, (graphics.guiWidth() - minecraft.font.width(text)) / 2,
                graphics.guiHeight() - 98, 0xFFFFFF);
    }
}
