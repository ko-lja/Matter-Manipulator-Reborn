package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.build.BuildManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public record SaveBlueprintPacket(InteractionHand hand, String name) implements CustomPacketPayload {

    public static final Type<SaveBlueprintPacket> TYPE = new Type<>(MatterManipulator.id("save_blueprint"));
    public static final StreamCodec<FriendlyByteBuf, SaveBlueprintPacket> STREAM_CODEC =
            StreamCodec.ofMember(SaveBlueprintPacket::encode, SaveBlueprintPacket::decode);

    @Override
    public Type<SaveBlueprintPacket> type() {
        return TYPE;
    }

    public static void encode(SaveBlueprintPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.hand);
        buffer.writeUtf(packet.name, Blueprint.MAX_NAME_LENGTH);
    }

    public static SaveBlueprintPacket decode(FriendlyByteBuf buffer) {
        return new SaveBlueprintPacket(buffer.readEnum(InteractionHand.class), buffer.readUtf(Blueprint.MAX_NAME_LENGTH));
    }

    public static void handle(SaveBlueprintPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildManager.saveBlueprint(player, packet.hand, packet.name);
            }
        });
    }
}
