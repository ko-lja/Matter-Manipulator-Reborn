package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintServerCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;


public record BlueprintUploadPacket(int transferId, int index, int count, @Nullable InteractionHand hand,
                                    byte[] chunk) implements CustomPacketPayload {

    public static final Type<BlueprintUploadPacket> TYPE = new Type<>(MatterManipulator.id("blueprint_upload"));
    public static final StreamCodec<FriendlyByteBuf, BlueprintUploadPacket> STREAM_CODEC =
            StreamCodec.ofMember(BlueprintUploadPacket::encode, BlueprintUploadPacket::decode);

    public static final int CHUNK_BYTES = 30_000;

    @Override
    public Type<BlueprintUploadPacket> type() {
        return TYPE;
    }

    public static void encode(BlueprintUploadPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.transferId);
        buffer.writeVarInt(packet.index);
        buffer.writeVarInt(packet.count);
        buffer.writeBoolean(packet.hand != null);
        if (packet.hand != null) {
            buffer.writeEnum(packet.hand);
        }
        buffer.writeByteArray(packet.chunk);
    }

    public static BlueprintUploadPacket decode(FriendlyByteBuf buffer) {
        int transferId = buffer.readVarInt();
        int index = buffer.readVarInt();
        int count = buffer.readVarInt();
        var hand = buffer.readBoolean() ? buffer.readEnum(InteractionHand.class) : null;
        return new BlueprintUploadPacket(transferId, index, count, hand, buffer.readByteArray());
    }

    public static void handle(BlueprintUploadPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                BlueprintServerCache.receive(player, packet);
            }
        });
    }
}
