package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.build.BuildManager;
import lu.kolja.matter_manipulator.common.build.BuildPlanner;
import lu.kolja.matter_manipulator.common.build.BuildRequirements;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.localization.MMScreen;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.PasteAnchor;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public record GuiStatusRequestPacket(InteractionHand hand, int requestId, int stateHash,
        @Nullable BlockPos followAnchor) implements CustomPacketPayload {

    public static final Type<GuiStatusRequestPacket> TYPE = new Type<>(MatterManipulator.id("gui_status_request"));
    public static final StreamCodec<FriendlyByteBuf, GuiStatusRequestPacket> STREAM_CODEC =
            StreamCodec.ofMember(GuiStatusRequestPacket::encode, GuiStatusRequestPacket::decode);

    @Override
    public Type<GuiStatusRequestPacket> type() {
        return TYPE;
    }

    public static void encode(GuiStatusRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.hand);
        buffer.writeVarInt(packet.requestId);
        buffer.writeInt(packet.stateHash);
        buffer.writeNullable(packet.followAnchor, BlockPos.STREAM_CODEC);
    }

    public static GuiStatusRequestPacket decode(FriendlyByteBuf buffer) {
        return new GuiStatusRequestPacket(buffer.readEnum(InteractionHand.class), buffer.readVarInt(), buffer.readInt(),
                buffer.readNullable(BlockPos.STREAM_CODEC));
    }

    public static void handle(GuiStatusRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                respond(player, packet);
            }
        });
    }

    private static void respond(ServerPlayer player, GuiStatusRequestPacket packet) {
        var tool = player.getItemInHand(packet.hand);
        if (!(tool.getItem() instanceof ManipulatorItem item)) {
            send(player, GuiStatusPacket.invalid(
                    packet.hand,
                    packet.requestId,
                    packet.stateHash,
                    MMScreen.STATUS_TOOL_MISSING.text()
            ));
            return;
        }

        var state = ToolState.get(tool);
        int actualHash = state.previewHash();
        if (actualHash != packet.stateHash) {
            send(player, GuiStatusPacket.invalid(
                    packet.hand,
                    packet.requestId,
                    actualHash,
                    MMScreen.STATUS_REFRESHING.text()
            ));
            return;
        }
        if (!item.tier().canUse(state.mode())) {
            send(player, GuiStatusPacket.invalid(
                    packet.hand,
                    packet.requestId,
                    actualHash,
                    MMMessage.UNSUPPORTED_TIER.text(state.mode().displayName())
            ));
            return;
        }
        if (state.removeMode() != RemoveMode.NONE && !item.tier().canRemove()) {
            send(player, GuiStatusPacket.invalid(
                    packet.hand,
                    packet.requestId,
                    actualHash,
                    MMMessage.UNSUPPORTED_TIER.text(
                            ManipulatorMode.GEOMETRY.displayName()
                    )
            ));
            return;
        }

        var anchors = FloatingPlacement.anchors(player, state);
        if (packet.followAnchor != null
                && PasteAnchor.state(state, player.level().dimension()) == PasteAnchor.State.FOLLOWING) {
            anchors = new FloatingPlacement.Anchors(anchors.a(), anchors.b(), Optional.of(packet.followAnchor));
        }
        var key = new StatusKey(
                packet.hand,
                actualHash,
                player.level().dimension(),
                player.blockPosition(),
                anchors
        );
        int now = player.server.getTickCount();
        var cached = BuildManager.PLANNING.recall(player.getUUID(), key, now);
        if (cached != null) {
            send(player, cached.withRequestId(packet.requestId));
            return;
        }
        if (!BuildManager.admitStatus(player, retry -> respond(retry, packet))) {
            return;
        }

        var status = status(player, tool, anchors, packet.hand, packet.requestId);
        if (!status.compiling()) {
            BuildManager.PLANNING.remember(player.getUUID(), now, key, status);
        }
        send(player, status);
    }

    public static GuiStatusPacket status(
            ServerPlayer player,
            ItemStack tool,
            FloatingPlacement.Anchors anchors,
            InteractionHand hand,
            int requestId
    ) {
        var item = (ManipulatorItem) tool.getItem();
        var state = ToolState.get(tool);
        int actualHash = state.previewHash();
        var result = BuildPlanner.plan(player, tool, anchors, BuildPlanner.Use.STATUS);
        if (result.compiling()) {
            return GuiStatusPacket.compiling(hand, requestId, actualHash, result.error());
        }
        if (result.plan().isEmpty()) {
            return GuiStatusPacket.invalid(hand, requestId, actualHash, result.error());
        }

        var plan = result.plan().get();
        var applicable = BuildRequirements.collectApplicable(player, item.tier(), state.removeMode(), plan);
        var requirements = applicable.requirements();
        var statuses = player.getAbilities().instabuild
                ? BuildRequirements.creativeStatuses(requirements)
                : BuildRequirements.statuses(player, tool, requirements);
        var notice = result.notice().copy();
        var applicableNotice = applicable.notice();
        if (!applicableNotice.getSiblings().isEmpty()) {
            if (result.hasNotice()) {
                notice.append(" ");
            }
            notice.append(applicableNotice);
        }
        return new GuiStatusPacket(
                hand,
                requestId,
                actualHash,
                true,
                notice,
                plan.totalOperations(),
                statuses,
                false
        );
    }

    private record StatusKey(
            InteractionHand hand,
            int stateHash,
            ResourceKey<Level> dimension,
            BlockPos origin,
            FloatingPlacement.Anchors anchors
    ) {}

    private static void send(ServerPlayer player, GuiStatusPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
