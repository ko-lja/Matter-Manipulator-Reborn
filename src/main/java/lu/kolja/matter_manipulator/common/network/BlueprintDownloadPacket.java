package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.blueprint.BlueprintLibrary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;

public record BlueprintDownloadPacket(int transferId, int index, int count, byte[] chunk) implements CustomPacketPayload {

    public static final Type<BlueprintDownloadPacket> TYPE = new Type<>(MatterManipulator.id("blueprint_download"));
    public static final StreamCodec<FriendlyByteBuf, BlueprintDownloadPacket> STREAM_CODEC =
            StreamCodec.ofMember(BlueprintDownloadPacket::encode, BlueprintDownloadPacket::decode);

    public static final int CHUNK_BYTES = 30_000;

    private static int nextTransfer;

    @Override
    public Type<BlueprintDownloadPacket> type() {
        return TYPE;
    }

    public static void encode(BlueprintDownloadPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.transferId);
        buffer.writeVarInt(packet.index);
        buffer.writeVarInt(packet.count);
        buffer.writeByteArray(packet.chunk);
    }

    public static BlueprintDownloadPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintDownloadPacket(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readByteArray(CHUNK_BYTES));
    }

    public static void handle(BlueprintDownloadPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BlueprintLibrary.receive(packet));
    }

    public static void send(ServerPlayer player, byte[] stream) {
        int transferId = nextTransfer++;
        int count = Math.max(1, (stream.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        for (int index = 0; index < count; index++) {
            int from = index * CHUNK_BYTES;
            var chunk = Arrays.copyOfRange(stream, from, Math.min(stream.length, from + CHUNK_BYTES));
            PacketDistributor.sendToPlayer(player, new BlueprintDownloadPacket(transferId, index, count, chunk));
        }
    }
}
