package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.build.BuildManager;
import lu.kolja.matter_manipulator.common.build.BuildPlanner;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.PasteAnchor;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.matter_manipulator.common.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public record PasteAnchorPacket(InteractionHand hand, Action action, BlockPos anchor) implements CustomPacketPayload {

    public static final Type<PasteAnchorPacket> TYPE = new Type<>(MatterManipulator.id("paste_anchor"));
    public static final StreamCodec<FriendlyByteBuf, PasteAnchorPacket> STREAM_CODEC =
            StreamCodec.ofMember(PasteAnchorPacket::encode, PasteAnchorPacket::decode);

    @Override
    public Type<PasteAnchorPacket> type() {
        return TYPE;
    }

    public static void encode(PasteAnchorPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.hand);
        buffer.writeEnum(packet.action);
        buffer.writeBlockPos(packet.anchor);
    }

    public static PasteAnchorPacket decode(FriendlyByteBuf buffer) {
        return new PasteAnchorPacket(buffer.readEnum(InteractionHand.class), buffer.readEnum(Action.class),
                buffer.readBlockPos());
    }

    public static void handle(PasteAnchorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                apply(player, packet.hand, packet.action, packet.anchor);
            }
        });
    }

    public static boolean apply(ServerPlayer player, InteractionHand hand, Action action, BlockPos anchor) {
        var stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ManipulatorItem item)) {
            return false;
        }
        var state = ToolState.get(stack);
        var dimension = player.level().dimension();
        if (action == Action.UNPIN) {
            if (!PasteAnchor.unpin(state, dimension)) {
                return false;
            }
            ChatUtil.status(player, MMMessage.ANCHOR_FOLLOWING.text());
            ToolStateSyncPacket.send(player, hand);
            return true;
        }
        if (PasteAnchor.state(state, dimension) != PasteAnchor.State.FOLLOWING) {
            return false;
        }
        var error = BuildPlanner.checkPasteAnchor(player, item.tier(), state, anchor);
        if (error.isPresent()) {
            ChatUtil.error(player, error.get());
            return false;
        }
        PasteAnchor.pin(state, dimension, anchor);
        ToolStateSyncPacket.send(player, hand);
        if (action == Action.START) {
            BuildManager.start(player, hand);
        } else {
            ChatUtil.status(player, MMMessage.ANCHOR_PINNED.text(
                    anchor.toShortString()));
        }
        return true;
    }

    public enum Action {
        PIN,
        UNPIN,
        START
    }
}
