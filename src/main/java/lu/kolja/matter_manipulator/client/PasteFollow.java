package lu.kolja.matter_manipulator.client;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.build.BuildPlanner;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMHud;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.PasteAnchor;
import lu.kolja.matter_manipulator.common.network.PasteAnchorPacket;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

@EventBusSubscriber(modid = MatterManipulator.MOD_ID, value = Dist.CLIENT)
public final class PasteFollow {

    private static double floatingDistance;
    private static boolean useLatched;

    private PasteFollow() {}

    public record Target(BlockPos c, boolean hit, boolean inRange) {}

    public static FloatingPlacement.Anchors anchors(Minecraft minecraft, ItemStack stack, float partialTick) {
        var anchors = FloatingPlacement.anchors(minecraft.player, ToolState.get(stack));
        var target = target(minecraft, stack, partialTick);
        return target == null ? anchors : new FloatingPlacement.Anchors(anchors.a(), anchors.b(), Optional.of(target.c()));
    }

    public static @Nullable Target target(Minecraft minecraft, ItemStack stack, float partialTick) {
        if (minecraft.player == null || minecraft.level == null || !(stack.getItem() instanceof ManipulatorItem item)) {
            return null;
        }
        var state = ToolState.get(stack);
        if (PasteAnchor.state(state, minecraft.level.dimension()) != PasteAnchor.State.FOLLOWING) {
            return null;
        }
        return aim(minecraft, minecraft.player, state, item, partialTick);
    }

    public static boolean commit(InteractionHand hand) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        var stack = minecraft.player.getItemInHand(hand);
        var target = target(minecraft, stack, 1.0F);
        if (target == null) {
            return false;
        }
        send(minecraft.player, hand, PasteAnchorPacket.Action.START, target);
        return true;
    }

    private static Target aim(Minecraft minecraft, Player player, ToolState state, ManipulatorItem item,
            float partialTick) {
        var size = PasteAnchor.transformedSize(state);
        double range = rayRange(minecraft, item);
        var eye = player.getEyePosition(partialTick);
        var look = player.getViewVector(partialTick);
        if (state.floatingPlacement()) {
            var point = BlockPos.containing(eye.add(look.scale(floatingDistance(player, range))));
            return target(player, item, PasteAnchor.coordC(point, size), false);
        }
        var end = eye.add(look.scale(range));
        var result = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
        if (result.getType() != HitResult.Type.BLOCK) {
            return target(player, item, PasteAnchor.coordC(BlockPos.containing(end), size), false);
        }
        var bottomCentre = PasteAnchor.place(result.getBlockPos(), result.getDirection(), size);
        return target(player, item, PasteAnchor.coordC(bottomCentre, size), true);
    }

    private static Target target(Player player, ManipulatorItem item, BlockPos c, boolean hit) {
        return new Target(c, hit, inRange(player, item, c));
    }

    private static boolean inRange(Player player, ManipulatorItem item, BlockPos c) {
        return BuildPlanner.checkRange(player.blockPosition(), item.tier().range(), List.of(c)).isEmpty();
    }

    private static double rayRange(Minecraft minecraft, ManipulatorItem item) {
        int range = item.tier().range();
        return range < 0 ? minecraft.options.getEffectiveRenderDistance() * 16 : range;
    }

    private static double floatingDistance(Player player, double range) {
        if (floatingDistance <= 0) {
            floatingDistance = player.blockInteractionRange();
        }
        return Mth.clamp(floatingDistance, 1, range);
    }

    private static void send(Player player, InteractionHand hand, PasteAnchorPacket.Action action, Target target) {
        if (!target.inRange()) {
            int range = ((ManipulatorItem) player.getItemInHand(hand).getItem()).tier().range();
            player.displayClientMessage(MMMessage.OUT_OF_RANGE.text(range)
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        PacketDistributor.sendToServer(new PasteAnchorPacket(hand, action, target.c()));
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (!event.isUseItem() || player == null || minecraft.level == null) {
            return;
        }
        var hand = event.getHand();
        var stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ManipulatorItem item)) {
            return;
        }
        var state = ToolState.get(stack);
        var anchorState = PasteAnchor.state(state, minecraft.level.dimension());
        if (anchorState == PasteAnchor.State.NONE || anchorState == PasteAnchor.State.PINNED && player.isCrouching()) {
            return;
        }
        var target = aim(minecraft, player, state, item, 1.0F);
        if (!player.isCrouching() && !useLatched && !target.hit() && !state.floatingPlacement()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (useLatched) {
            return;
        }
        useLatched = true;
        if (anchorState == PasteAnchor.State.PINNED) {
            PacketDistributor.sendToServer(new PasteAnchorPacket(hand, PasteAnchorPacket.Action.UNPIN, BlockPos.ZERO));
        } else {
            send(player, hand, player.isCrouching() ? PasteAnchorPacket.Action.START : PasteAnchorPacket.Action.PIN,
                    target);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!Minecraft.getInstance().options.keyUse.isDown()) {
            useLatched = false;
        }
    }

    static int changeFloatingDistance(Minecraft minecraft, Player player, ManipulatorItem item, int steps) {
        double range = rayRange(minecraft, item);
        floatingDistance = Mth.clamp(floatingDistance(player, range) + steps, 1, range);
        return Mth.floor(floatingDistance);
    }

    static void registerOverlay(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, MatterManipulator.id("paste_anchor"), PasteFollow::renderHud);
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
        var anchorState = PasteAnchor.state(state, minecraft.level.dimension());
        if (anchorState == PasteAnchor.State.NONE) {
            return;
        }
        var item = (ManipulatorItem) stack.getItem();
        var c = anchorState == PasteAnchor.State.PINNED
                ? state.coordC().orElseThrow()
                : aim(minecraft, player, state, item, deltaTracker.getGameTimeDeltaPartialTick(true)).c();
        Component text;
        if (!inRange(player, item, c)) {
            text = MMHud.ANCHOR_OUT_OF_RANGE.text(c.toShortString())
                    .withStyle(ChatFormatting.RED);
        } else if (anchorState == PasteAnchor.State.PINNED) {
            text = MMHud.ANCHOR_PINNED.text(c.toShortString())
                    .withStyle(ChatFormatting.GREEN);
        } else {
            text = MMHud.ANCHOR_FOLLOWING.text(c.toShortString())
                    .withStyle(ChatFormatting.WHITE);
        }
        graphics.drawString(minecraft.font, text, (graphics.guiWidth() - minecraft.font.width(text)) / 2,
                graphics.guiHeight() - 86, 0xFFFFFF);
    }
}
