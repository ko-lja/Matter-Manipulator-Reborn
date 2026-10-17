package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.ClientToolStateSync;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public record ToolStateSyncPacket(InteractionHand hand, CompoundTag tag) implements CustomPacketPayload {

    public static final Type<ToolStateSyncPacket> TYPE = new Type<>(MatterManipulator.id("tool_state_sync"));
    public static final StreamCodec<FriendlyByteBuf, ToolStateSyncPacket> STREAM_CODEC =
            StreamCodec.ofMember(ToolStateSyncPacket::encode, ToolStateSyncPacket::decode);

    @Override
    public Type<ToolStateSyncPacket> type() {
        return TYPE;
    }

    public static void encode(ToolStateSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.hand);
        buffer.writeNbt(packet.tag);
    }

    public static ToolStateSyncPacket decode(FriendlyByteBuf buffer) {
        var hand = buffer.readEnum(InteractionHand.class);
        var tag = buffer.readNbt();
        return new ToolStateSyncPacket(hand, tag == null ? new CompoundTag() : tag);
    }

    public static void handle(ToolStateSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientToolStateSync.apply(packet.hand, packet.tag));
    }

    public static void syncHeld(ServerPlayer player) {
        var hand = NetworkUtil.heldManipulatorHand(player);
        if (hand != null) {
            send(player, hand);
        }
    }

    public static void send(ServerPlayer player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        PacketDistributor.sendToPlayer(player, new ToolStateSyncPacket(hand, ToolState.stackTag(stack).copy()));
    }
}
