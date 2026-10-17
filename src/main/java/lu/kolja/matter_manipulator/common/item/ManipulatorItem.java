package lu.kolja.matter_manipulator.common.item;

import lombok.Getter;
import lombok.experimental.Accessors;
import lu.kolja.matter_manipulator.client.ClientMatterManipulator;
import lu.kolja.matter_manipulator.common.build.BlockCapture;
import lu.kolja.matter_manipulator.common.build.CapturePurpose;
import lu.kolja.matter_manipulator.common.build.BuildManager;
import lu.kolja.matter_manipulator.common.build.CableRoute;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.energy.StackEnergyStorage;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.localization.MMScreen;
import lu.kolja.matter_manipulator.common.localization.MMTooltip;
import lu.kolja.matter_manipulator.common.manipulator.BlockSlot;
import lu.kolja.matter_manipulator.common.manipulator.CableRouteMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import lu.kolja.matter_manipulator.common.manipulator.MarkTarget;
import lu.kolja.matter_manipulator.common.network.ToolStateSyncPacket;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.matter_manipulator.common.util.ChatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.function.Consumer;

@Getter
@Accessors(fluent = true)
public class ManipulatorItem extends Item {

    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance();

    private final ManipulatorTier tier;

    public ManipulatorItem(ManipulatorTier tier, Properties properties) {
        super(properties.rarity(tier == ManipulatorTier.MKIII ? Rarity.EPIC : Rarity.RARE));
        this.tier = tier;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        var stack = context.getItemInHand();
        var level = context.getLevel();
        if (player.isCrouching()) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                BuildManager.start(serverPlayer, context.getHand());
            }
            return InteractionResult.SUCCESS;
        }

        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            storeCoordinate(serverPlayer, stack, context.getHand(), level, new BlockHitResult(
                    context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (player.isCrouching()) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                BuildManager.start(serverPlayer, hand);
            }
            return InteractionResult.SUCCESS;
        }
        if (ToolState.get(stack).floatingPlacement()) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                storeCoordinate(serverPlayer, stack, hand, level, null);
            }
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide() && FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientMatterManipulator.openScreen(hand);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return StackEnergyStorage.stored(stack) < tier.energyCapacity();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * StackEnergyStorage.stored(stack) / Math.max(1.0F, tier.energyCapacity()));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        float ratio = StackEnergyStorage.stored(stack) / (float) Math.max(1, tier.energyCapacity());
        return Mth.hsvToRgb(ratio / 3.0F, 1.0F, 1.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
            TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        var state = ToolState.get(stack);
        tooltip.accept(MMTooltip.TIER.text(tier.displayName())
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(MMTooltip.ENERGY.text(
                NUMBER_FORMAT.format(StackEnergyStorage.stored(stack)),
                NUMBER_FORMAT.format(tier.energyCapacity())
        ).withStyle(ChatFormatting.GRAY));
        tooltip.accept(MMTooltip.MODE.text(state.mode().displayName())
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(MMTooltip.SHAPE.text(state.shape().displayName())
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(MMTooltip.REMOVE_MODE.text(state.removeMode().displayName())
                .withStyle(ChatFormatting.GRAY));
        if (state.mode() == ManipulatorMode.CABLE) {
            tooltip.accept(MMTooltip.CABLE_ROUTE.text(
                    state.cableRouteMode().displayName(),
                    state.cableWaypoints().size()
            ).withStyle(ChatFormatting.GRAY));
        }
        tooltip.accept(MMTooltip.FLOATING.text((state.floatingPlacement() ? MMScreen.ON : MMScreen.OFF).text())
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(MMTooltip.TRANSFORM.text(state.transform().summary())
                .withStyle(ChatFormatting.GRAY));
        var aeLink = state.aeLinkTag();
        if (aeLink.contains("dimension")) {
            tooltip.accept(MMTooltip.AE_LINK.text(
                    aeLink.getString("dimension"),
                    aeLink.getInt("x"),
                    aeLink.getInt("y"),
                    aeLink.getInt("z")
            ).withStyle(ChatFormatting.GRAY));
        }
        tooltip.accept(MMTooltip.RANGE.text(
                tier.range() < 0 ? MMTooltip.RANGE_UNLIMITED.text() : Component.literal(Integer.toString(tier.range()))
        ).withStyle(ChatFormatting.GRAY));

        if (flag.isAdvanced()) {
            for (var slot : BlockSlot.values()) {
                state.slot(slot).ifPresent(spec -> tooltip.accept(MMTooltip.SLOT.text(
                        slot.displayName(),
                        spec.displayName()
                ).withStyle(ChatFormatting.DARK_GRAY)));
            }
        }
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static void storeCoordinate(
            ServerPlayer player,
            ItemStack stack,
            InteractionHand hand,
            Level level,
            @Nullable BlockHitResult hit
    ) {
        var state = ToolState.get(stack);
        var targetPos = MarkTarget.next(level, player, state, hit).orElse(null);
        if (targetPos == null) {
            return;
        }
        var capturePos = hit == null ? null : hit.getBlockPos();
        state.enterDimension(level.dimension());
        if (state.mode() == ManipulatorMode.CABLE && state.cableRouteMode() == CableRouteMode.EDGES) {
            storeCableWaypoint(player, stack, hand, level, state, targetPos, capturePos);
            return;
        }
        var stored = state.storeNextCoord(level, targetPos);
        if (capturePos != null && state.slot(BlockSlot.VOLUME).isEmpty()) {
            if (level instanceof ServerLevel serverLevel) {
                state.setCapturedSlot(BlockSlot.VOLUME, BlockCapture.capture(serverLevel, capturePos, CapturePurpose.COPY_TEMPLATE));
            } else {
                state.setSlot(BlockSlot.VOLUME, BlockStateSpec.fromState(level.getBlockState(capturePos)));
            }
        }
        ChatUtil.status(
                player,
                MMMessage.COORD.text(stored, formatPos(targetPos))
        );
        ToolStateSyncPacket.send(player, hand);
    }

    private static void storeCableWaypoint(
            ServerPlayer player,
            ItemStack stack,
            InteractionHand hand,
            Level level,
            ToolState state,
            BlockPos point,
            BlockPos capturePos
    ) {
        var waypoints = state.cableWaypoints();
        if (!waypoints.isEmpty() && waypoints.get(waypoints.size() - 1).equals(point)) {
            ChatUtil.status(player, MMMessage.CABLE_ROUTE_SAME.text());
            return;
        }
        if (waypoints.size() >= ToolState.MAX_CABLE_WAYPOINTS) {
            ChatUtil.error(player, MMMessage.CABLE_ROUTE_TOO_MANY_POINTS.text(
                    ToolState.MAX_CABLE_WAYPOINTS
            ));
            return;
        }

        var candidate = new ArrayList<>(waypoints);
        candidate.add(point);
        var route = CableRoute.expand(candidate, MMConfig.maxQueuedBlocks());
        if (!route.complete()) {
            if (route.failure() == CableRoute.Failure.MALFORMED_SEGMENT) {
                ChatUtil.error(player, MMMessage.CABLE_ROUTE_NOT_AXIS_ALIGNED.text());
                return;
            }
            ChatUtil.error(player, MMMessage.CABLE_ROUTE_TOO_LARGE.text(
                    MMConfig.maxQueuedBlocks()
            ));
            return;
        }

        state.appendCableWaypoint(level, point);
        if (capturePos != null && state.slot(BlockSlot.VOLUME).isEmpty()) {
            if (level instanceof ServerLevel serverLevel) {
                state.setCapturedSlot(BlockSlot.VOLUME, BlockCapture.capture(serverLevel, capturePos, CapturePurpose.COPY_TEMPLATE));
            } else {
                state.setSlot(BlockSlot.VOLUME, BlockStateSpec.fromState(level.getBlockState(capturePos)));
            }
        }
        ChatUtil.status(player, MMMessage.CABLE_ROUTE_POINT.text(
                candidate.size(),
                formatPos(point),
                route.positions().size()
        ));
        ToolStateSyncPacket.send(player, hand);
    }
}
