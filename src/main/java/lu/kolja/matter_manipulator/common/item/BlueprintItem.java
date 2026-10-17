package lu.kolja.matter_manipulator.common.item;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintRef;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintServerCache;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintWorldStore;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.localization.MMTooltip;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.network.BlueprintDownloadPacket;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.matter_manipulator.common.util.ChatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.nio.file.NoSuchFileException;
import java.util.Optional;
import java.util.function.Consumer;

public class BlueprintItem extends Item {

    public static final String TAG_BLUEPRINT = "Blueprint";

    public BlueprintItem(Properties properties) {
        super(properties);
    }

    public static Optional<BlueprintRef> ref(ItemStack stack) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.getCompound(TAG_BLUEPRINT).flatMap(BlueprintRef::fromTag);
    }

    public static void setRef(ItemStack stack, BlueprintRef ref) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(TAG_BLUEPRINT, ref.save()));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            var ref = ref(stack);
            var other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND
                    : InteractionHand.MAIN_HAND);
            var tool = other.getItem() instanceof ManipulatorItem ? ToolState.get(other) : null;
            if (ref.isPresent() && player.isSecondaryUseActive()) {
                read(serverPlayer, hand, stack, ref.get());
            } else if (ref.isPresent()) {
                ChatUtil.status(serverPlayer, MMMessage.BLUEPRINT_ITEM_WRITTEN.text(
                        ref.get().name()));
            } else if (tool != null && tool.mode() == ManipulatorMode.BLUEPRINT && tool.blueprintRef().isPresent()) {
                write(serverPlayer, hand, stack, tool.blueprintRef().get());
            } else {
                ChatUtil.status(serverPlayer, MMMessage.BLUEPRINT_ITEM_BLANK.text());
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void write(ServerPlayer player, InteractionHand hand, ItemStack stack, BlueprintRef toolRef) {
        var blueprint = BlueprintServerCache.require(player, toolRef.hash(), toolRef.size());
        if (blueprint == null) {
            ChatUtil.status(player, MMMessage.BLUEPRINT_ITEM_FETCHING.text());
            return;
        }
        if (!BlueprintServerCache.admitItemUse(player)) {
            ChatUtil.status(player, MMMessage.BLUEPRINT_ITEM_TOO_SOON.text());
            return;
        }
        var header = blueprint.header();
        var ref = new BlueprintRef(header.hash(), header.name(), header.size());
        var file = BlueprintWorldStore.path(player.level().getServer(), ref);
        int maxBytes = MMConfig.maxBlueprintBytes();
        var maxVolume = MMConfig.MAX_REGION_VOLUME.get();
        BlueprintServerCache.submitItemUse(player, "item write", dropped -> {
            BlueprintWorldStore.put(file, blueprint, maxBytes, maxVolume, dropped);
            return target -> {
                if (target != player || player.getItemInHand(hand) != stack || ref(stack).isPresent()) {
                    return;
                }
                setRef(stack, ref);
                ChatUtil.info(player, MMMessage.BLUEPRINT_WRITTEN.text(
                        header.name()));
            };
        }, (target, failure) -> {
            MatterManipulator.LOGGER.warn("Could not store the blueprint {}", toolRef.hash(), failure);
            ChatUtil.error(target, MMMessage.BLUEPRINT_STORE_FAILED.text(
                    String.valueOf(failure.getMessage())));
        });
    }

    private static void read(ServerPlayer player, InteractionHand hand, ItemStack stack, BlueprintRef ref) {
        if (!BlueprintServerCache.admitItemUse(player)) {
            ChatUtil.status(player, MMMessage.BLUEPRINT_ITEM_TOO_SOON.text());
            return;
        }
        var file = BlueprintWorldStore.path(player.level().getServer(), ref);
        int maxBytes = MMConfig.maxBlueprintBytes();
        var maxVolume = MMConfig.MAX_REGION_VOLUME.get();
        BlueprintServerCache.submitItemUse(player, "item read", dropped -> {
            var stream = Blueprint.renamed(BlueprintWorldStore.read(file, ref, maxBytes, maxVolume), ref.name());
            return target -> {
                if (target != player || player.getItemInHand(hand) != stack || !ref(stack).equals(Optional.of(ref))) {
                    return;
                }
                BlueprintDownloadPacket.send(player, stream);
                ChatUtil.info(player, MMMessage.BLUEPRINT_READ.text(ref.name()));
            };
        }, (target, failure) -> {
            if (failure instanceof NoSuchFileException) {
                ChatUtil.error(target, MMMessage.BLUEPRINT_STORE_MISSING.text(
                        ref.name()));
                return;
            }
            MatterManipulator.LOGGER.warn("Could not read the stored blueprint {}", ref.hash(), failure);
            ChatUtil.error(target, MMMessage.BLUEPRINT_STORE_UNREADABLE.text(
                    ref.name(), String.valueOf(failure.getMessage())));
        });
    }

    @Override
    public Component getName(ItemStack stack) {
        return ref(stack)
                .<Component>map(ref -> MMTooltip.BLUEPRINT_WRITTEN.text(ref.name()))
                .orElseGet(() -> super.getName(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
            TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        var ref = ref(stack);
        if (ref.isEmpty()) {
            tooltip.accept(MMTooltip.BLUEPRINT_BLANK.text()
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        var size = ref.get().size();
        tooltip.accept(MMTooltip.BLUEPRINT_SIZE.text(
                size.getX() + "x" + size.getY() + "x" + size.getZ()).withStyle(ChatFormatting.GRAY));
        tooltip.accept(MMTooltip.BLUEPRINT_HASH.text(
                ref.get().hash().substring(0, 8)).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.accept(MMTooltip.BLUEPRINT_READ.text()
                .withStyle(ChatFormatting.GRAY));
    }
}
