package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.blueprint.BlueprintLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public record BlueprintRequestPacket(String hash, BlockPos size) implements CustomPacketPayload {

    public static final Type<BlueprintRequestPacket> TYPE = new Type<>(MatterManipulator.id("blueprint_request"));
    public static final StreamCodec<FriendlyByteBuf, BlueprintRequestPacket> STREAM_CODEC =
            StreamCodec.ofMember(BlueprintRequestPacket::encode, BlueprintRequestPacket::decode);

    @Override
    public Type<BlueprintRequestPacket> type() {
        return TYPE;
    }

    public static void encode(BlueprintRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.hash, 64);
        buffer.writeVarInt(packet.size.getX());
        buffer.writeVarInt(packet.size.getY());
        buffer.writeVarInt(packet.size.getZ());
    }

    public static BlueprintRequestPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintRequestPacket(buffer.readUtf(64),
                new BlockPos(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
    }

    public static void handle(BlueprintRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BlueprintLibrary.uploadRequested(packet.hash, packet.size));
    }
}
