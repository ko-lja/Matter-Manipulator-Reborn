package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.build.BlockCapture;
import lu.kolja.matter_manipulator.common.build.BlockPolicy;
import lu.kolja.matter_manipulator.common.build.CapturePurpose;
import lu.kolja.matter_manipulator.common.build.WorldOperations;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.BlockSlot;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.matter_manipulator.common.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

public record PickBlockPacket(BlockPos pos, BlockSlot slot, boolean replaceWhitelist) implements CustomPacketPayload {

    public static final Type<PickBlockPacket> TYPE = new Type<>(MatterManipulator.id("pick_block"));
    public static final StreamCodec<FriendlyByteBuf, PickBlockPacket> STREAM_CODEC =
            StreamCodec.ofMember(PickBlockPacket::encode, PickBlockPacket::decode);

    @Override
    public Type<PickBlockPacket> type() {
        return TYPE;
    }

    public static void encode(PickBlockPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeEnum(packet.slot);
        buffer.writeBoolean(packet.replaceWhitelist);
    }

    public static PickBlockPacket decode(FriendlyByteBuf buffer) {
        return new PickBlockPacket(buffer.readBlockPos(), buffer.readEnum(BlockSlot.class), buffer.readBoolean());
    }

    public static void handle(PickBlockPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            var stack = NetworkUtil.heldManipulator(player);
            if (stack.isEmpty() || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            double reach = player.blockInteractionRange() + 1.0D;
            if (!WorldOperations.canTouch(player, level, packet.pos)
                    || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(packet.pos)) > reach * reach) {
                return;
            }
            var error = pickError(level, packet.pos);
            if (error.isPresent()) {
                ChatUtil.error(player, error.get());
                return;
            }
            var spec = BlockStateSpec.fromState(level.getBlockState(packet.pos));
            var state = ToolState.get(stack);
            if (packet.replaceWhitelist) {
                state.setReplaceWhitelist(spec);
                ChatUtil.status(player, MMMessage.SLOT.text("Replace", spec.displayName()));
            } else {
                state.setCapturedSlot(packet.slot, BlockCapture.capture(level, packet.pos, CapturePurpose.COPY_TEMPLATE));
                ChatUtil.status(player, MMMessage.SLOT.text(packet.slot.displayName(), spec.displayName()));
            }
            ToolStateSyncPacket.syncHeld(player);
        });
    }

    static Optional<Component> pickError(BlockGetter level, BlockPos pos) {
        var picked = level.getBlockState(pos);
        if (picked.isAir()) {
            return Optional.of(MMMessage.PICK_AIR.text());
        }
        if (!BlockPolicy.canManipulate(level, pos, picked)) {
            return Optional.of(MMMessage.IMMOVABLE_BLOCK.text(
                    BlockStateSpec.fromState(picked).displayName()
            ));
        }
        return Optional.empty();
    }
}
