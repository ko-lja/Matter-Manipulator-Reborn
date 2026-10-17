package lu.kolja.matter_manipulator.client;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMKey;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.BlockSlot;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.network.ConfigActionPacket;
import lu.kolja.matter_manipulator.common.network.PickBlockPacket;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.oreui.OreGalleryScreen;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

@EventBusSubscriber(modid = MatterManipulator.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    public static final KeyMapping OPEN_MENU = key(MMKey.OPEN_MENU, GLFW.GLFW_KEY_G);
    public static final KeyMapping PICK_BLOCK = key(MMKey.PICK_BLOCK, GLFW.GLFW_KEY_UNKNOWN);
    public static final KeyMapping COPY = key(MMKey.COPY, GLFW.GLFW_KEY_C);
    public static final KeyMapping CUT = key(MMKey.CUT, GLFW.GLFW_KEY_X);
    public static final KeyMapping PASTE = key(MMKey.PASTE, GLFW.GLFW_KEY_V);
    public static final KeyMapping NUDGE_MODIFIER = key(MMKey.NUDGE_MODIFIER, GLFW.GLFW_KEY_LEFT_CONTROL);
    public static final KeyMapping RESIZE_MODIFIER = key(MMKey.RESIZE_MODIFIER, GLFW.GLFW_KEY_LEFT_ALT);
    private static final KeyMapping TOGGLE_CABLE_SEARCH_PROGRESS = unboundKey(MMKey.TOGGLE_CABLE_SEARCH_PROGRESS);
    private static final KeyMapping TOGGLE_CABLE_PATH_MESSAGES = unboundKey(MMKey.TOGGLE_CABLE_PATH_MESSAGES);
    private static final List<ActionKey> ACTION_KEYS = List.of(
            actionKey(MMKey.CYCLE_MODE, ConfigActionPacket.Action.CYCLE_MODE),
            actionKey(MMKey.MODE_GEOMETRY, ConfigActionPacket.Action.MODE_GEOMETRY),
            actionKey(MMKey.MODE_CABLE, ConfigActionPacket.Action.MODE_CABLE),
            actionKey(MMKey.MODE_EXCHANGE, ConfigActionPacket.Action.MODE_EXCHANGE),
            actionKey(MMKey.MODE_COPY, ConfigActionPacket.Action.MODE_COPY),
            actionKey(MMKey.MODE_MOVE, ConfigActionPacket.Action.MODE_MOVE),
            actionKey(MMKey.MODE_BLUEPRINT, ConfigActionPacket.Action.MODE_BLUEPRINT),
            actionKey(MMKey.CYCLE_SHAPE, ConfigActionPacket.Action.CYCLE_SHAPE),
            actionKey(MMKey.CYCLE_REMOVE, ConfigActionPacket.Action.CYCLE_REMOVE),
            actionKey(MMKey.CYCLE_CABLE_ROUTE, ConfigActionPacket.Action.CYCLE_CABLE_ROUTE),
            actionKey(MMKey.CLEAR_SELECTION, ConfigActionPacket.Action.CLEAR_COORDS),
            actionKey(MMKey.TOGGLE_FLOATING_PLACEMENT, ConfigActionPacket.Action.TOGGLE_FLOATING_PLACEMENT),
            actionKey(MMKey.TOGGLE_AUTO_CRAFT_MISSING, ConfigActionPacket.Action.TOGGLE_AUTO_CRAFT_MISSING),
            actionKey(MMKey.ROTATE_X, ConfigActionPacket.Action.ROTATE_X),
            actionKey(MMKey.ROTATE_Y, ConfigActionPacket.Action.ROTATE_Y),
            actionKey(MMKey.ROTATE_Z, ConfigActionPacket.Action.ROTATE_Z),
            actionKey(MMKey.MIRROR_X, ConfigActionPacket.Action.MIRROR_X),
            actionKey(MMKey.MIRROR_Y, ConfigActionPacket.Action.MIRROR_Y),
            actionKey(MMKey.MIRROR_Z, ConfigActionPacket.Action.MIRROR_Z),
            actionKey(MMKey.STACK_MINUS, ConfigActionPacket.Action.STACK_MINUS),
            actionKey(MMKey.STACK_AXIS, ConfigActionPacket.Action.STACK_AXIS),
            actionKey(MMKey.STACK_PLUS, ConfigActionPacket.Action.STACK_PLUS),
            actionKey(MMKey.REQUEST_MISSING, ConfigActionPacket.Action.REQUEST_MISSING),
            actionKey(MMKey.START_OPERATION, ConfigActionPacket.Action.START)
    );

    private ClientEvents() {}

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MENU);
        event.register(PICK_BLOCK);
        event.register(COPY);
        event.register(CUT);
        event.register(PASTE);
        event.register(NUDGE_MODIFIER);
        event.register(RESIZE_MODIFIER);
        for (var actionKey : ACTION_KEYS) {
            event.register(actionKey.mapping());
        }
        event.register(TOGGLE_CABLE_SEARCH_PROGRESS);
        event.register(TOGGLE_CABLE_PATH_MESSAGES);
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        if (FMLEnvironment.production) {
            return;
        }
        event.getDispatcher().register(Commands.literal("oregallery").executes(context -> {
            var minecraft = Minecraft.getInstance();
            minecraft.tell(() -> minecraft.setScreen(new OreGalleryScreen()));
            return 1;
        }));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        while (OPEN_MENU.consumeClick()) {
            var hand = manipulatorHand();
            if (hand != null) {
                ClientMatterManipulator.openScreen(hand);
            }
        }
        while (PICK_BLOCK.consumeClick()) {
            pickLookedBlock(false);
        }
        while (COPY.consumeClick()) {
            if (Screen.hasControlDown()) {
                sendAction(ConfigActionPacket.Action.MODE_COPY);
            }
        }
        while (CUT.consumeClick()) {
            if (Screen.hasControlDown()) {
                sendAction(ConfigActionPacket.Action.MODE_MOVE);
            }
        }
        while (PASTE.consumeClick()) {
            if (Screen.hasControlDown()) {
                start();
            }
        }
        for (var actionKey : ACTION_KEYS) {
            while (actionKey.mapping().consumeClick()) {
                if (actionKey.action() == ConfigActionPacket.Action.START) {
                    start();
                } else {
                    sendAction(actionKey.action());
                }
            }
        }
        while (TOGGLE_CABLE_SEARCH_PROGRESS.consumeClick()) {
            if (manipulatorHand() != null) {
                PreviewRenderer.toggleCableSearchProgress();
            }
        }
        while (TOGGLE_CABLE_PATH_MESSAGES.consumeClick()) {
            if (manipulatorHand() != null) {
                PreviewRenderer.toggleCableSearchMessages();
            }
        }
    }

    public static void pickLookedBlock(boolean forceWhitelist) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.hitResult == null || minecraft.hitResult.getType() != HitResult.Type.BLOCK) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(MMMessage.PICK_MISSING.text(), true);
            }
            return;
        }
        var hand = manipulatorHand();
        if (hand == null || minecraft.player == null) {
            return;
        }
        var stack = minecraft.player.getItemInHand(hand);
        var slot = pickSlot(stack);
        boolean replace = forceWhitelist || Screen.hasShiftDown();
        PacketDistributor.sendToServer(new PickBlockPacket(((BlockHitResult) minecraft.hitResult).getBlockPos(), slot, replace));
    }

    private static BlockSlot pickSlot(ItemStack stack) {
        var mode = ToolState.get(stack).mode();
        if (mode == ManipulatorMode.CABLE) {
            return BlockSlot.CABLE;
        }
        if (mode == ManipulatorMode.EXCHANGE) {
            return BlockSlot.EXCHANGE_TARGET;
        }
        return BlockSlot.VOLUME;
    }

    public static InteractionHand manipulatorHand() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        if (player.getMainHandItem().getItem() instanceof ManipulatorItem) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().getItem() instanceof ManipulatorItem) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    private static void start() {
        var hand = manipulatorHand();
        if (hand != null && !PasteFollow.commit(hand)) {
            PacketDistributor.sendToServer(new ConfigActionPacket(ConfigActionPacket.Action.START, hand));
        }
    }

    private static void sendAction(ConfigActionPacket.Action action) {
        var hand = manipulatorHand();
        if (hand != null) {
            PacketDistributor.sendToServer(new ConfigActionPacket(action, hand));
        }
    }

    private static ActionKey actionKey(MMKey id, ConfigActionPacket.Action action) {
        return new ActionKey(unboundKey(id), action);
    }

    private static KeyMapping unboundKey(MMKey id) {
        return key(id, GLFW.GLFW_KEY_UNKNOWN);
    }

    private static KeyMapping key(MMKey id, int defaultKey) {
        return new KeyMapping(
                id.getTranslationKey(),
                InputConstants.Type.KEYSYM,
                defaultKey,
                MMKey.CATEGORY.getTranslationKey()
        );
    }

    private record ActionKey(KeyMapping mapping, ConfigActionPacket.Action action) {}
}
