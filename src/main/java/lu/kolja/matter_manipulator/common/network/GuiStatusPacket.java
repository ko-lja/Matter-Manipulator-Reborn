package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.ClientGuiStatus;
import lu.kolja.matter_manipulator.common.build.ItemRequirementStatus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record GuiStatusPacket(
        InteractionHand hand,
        int requestId,
        int stateHash,
        boolean valid,
        Component error,
        int totalOperations,
        List<ItemRequirementStatus> materials,
        boolean compiling
) implements CustomPacketPayload {

    public static final Type<GuiStatusPacket> TYPE = new Type<>(MatterManipulator.id("gui_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GuiStatusPacket> STREAM_CODEC =
            StreamCodec.ofMember(GuiStatusPacket::encode, GuiStatusPacket::decode);

    public GuiStatusPacket {
        error = error == null ? Component.empty() : error;
        materials = materials == null ? List.of() : List.copyOf(materials);
    }

    public static GuiStatusPacket invalid(
            InteractionHand hand,
            int requestId,
            int stateHash,
            Component error
    ) {
        return new GuiStatusPacket(hand, requestId, stateHash, false, error, 0, List.of(), false);
    }

    public static GuiStatusPacket compiling(InteractionHand hand, int requestId, int stateHash, Component progress) {
        return new GuiStatusPacket(hand, requestId, stateHash, false, progress, 0, List.of(), true);
    }

    public Component notice() {
        return valid ? error : Component.empty();
    }

    public GuiStatusPacket withRequestId(int newRequestId) {
        return new GuiStatusPacket(hand, newRequestId, stateHash, valid, error, totalOperations, materials, compiling);
    }

    public boolean matches(InteractionHand expectedHand, int expectedRequestId, int expectedStateHash) {
        return hand == expectedHand && requestId == expectedRequestId && stateHash == expectedStateHash;
    }

    @Override
    public Type<GuiStatusPacket> type() {
        return TYPE;
    }

    public static void encode(GuiStatusPacket packet, RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(packet.hand);
        buffer.writeVarInt(packet.requestId);
        buffer.writeInt(packet.stateHash);
        buffer.writeBoolean(packet.valid);
        ComponentSerialization.STREAM_CODEC.encode(buffer, packet.error);
        buffer.writeVarInt(packet.totalOperations);
        buffer.writeVarInt(packet.materials.size());
        for (var material : packet.materials) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, material.stack());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, material.fallback());
            buffer.writeUtf(material.description());
            buffer.writeLong(material.available());
            buffer.writeLong(material.required());
            ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buffer, material.alternatives());
            buffer.writeBoolean(material.variants());
        }
        buffer.writeBoolean(packet.compiling);
    }

    public static GuiStatusPacket decode(RegistryFriendlyByteBuf buffer) {
        var hand = buffer.readEnum(InteractionHand.class);
        int requestId = buffer.readVarInt();
        int stateHash = buffer.readInt();
        boolean valid = buffer.readBoolean();
        var error = ComponentSerialization.STREAM_CODEC.decode(buffer);
        int totalOperations = buffer.readVarInt();
        int size = buffer.readVarInt();
        var materials = new ArrayList<ItemRequirementStatus>(size);
        for (int i = 0; i < size; i++) {
            materials.add(new ItemRequirementStatus(
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                    buffer.readUtf(),
                    buffer.readLong(),
                    buffer.readLong(),
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buffer),
                    buffer.readBoolean()
            ));
        }
        return new GuiStatusPacket(hand, requestId, stateHash, valid, error, totalOperations, materials,
                buffer.readBoolean());
    }

    public static void handle(GuiStatusPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientGuiStatus.apply(packet));
    }
}
