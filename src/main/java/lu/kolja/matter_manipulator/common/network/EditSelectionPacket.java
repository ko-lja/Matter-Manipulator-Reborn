package lu.kolja.matter_manipulator.common.network;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.build.BuildPlanner;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import lu.kolja.matter_manipulator.common.manipulator.SelectionEdit;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.matter_manipulator.common.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record EditSelectionPacket(InteractionHand hand, Edit edit, Direction direction, int amount) implements CustomPacketPayload {

    public static final Type<EditSelectionPacket> TYPE = new Type<>(MatterManipulator.id("edit_selection"));
    public static final StreamCodec<FriendlyByteBuf, EditSelectionPacket> STREAM_CODEC =
            StreamCodec.ofMember(EditSelectionPacket::encode, EditSelectionPacket::decode);

    private static final int MAX_STEPS = 64;

    @Override
    public Type<EditSelectionPacket> type() {
        return TYPE;
    }

    public static void encode(EditSelectionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.hand);
        buffer.writeEnum(packet.edit);
        buffer.writeEnum(packet.direction);
        buffer.writeVarInt(packet.amount);
    }

    public static EditSelectionPacket decode(FriendlyByteBuf buffer) {
        return new EditSelectionPacket(buffer.readEnum(InteractionHand.class), buffer.readEnum(Edit.class),
                buffer.readEnum(Direction.class), buffer.readVarInt());
    }

    public static void handle(EditSelectionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                apply(player, packet.hand, packet.edit, packet.direction, packet.amount);
            }
        });
    }

    public static boolean apply(ServerPlayer player, InteractionHand hand, Edit edit, Direction direction, int amount) {
        var stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ManipulatorItem item) || amount == 0) {
            return false;
        }
        var state = ToolState.get(stack);
        if (SelectionEdit.scrollAction(state, player.level().dimension(), edit == Edit.RESIZE) != edit.scroll) {
            return false;
        }
        int steps = Mth.clamp(amount, -MAX_STEPS, MAX_STEPS);
        boolean changed = switch (edit) {
            case MOVE -> move(player, item.tier(), state, direction, steps);
            case RESIZE -> resize(player, item.tier(), state, direction, steps);
            case FLOATING_DISTANCE -> changeFloatingDistance(player, item.tier(), state, steps);
        };
        if (changed) {
            ToolStateSyncPacket.send(player, hand);
        }
        return changed;
    }

    private static boolean move(ServerPlayer player, ManipulatorTier tier, ToolState state, Direction direction,
            int amount) {
        boolean cable = state.mode() == ManipulatorMode.CABLE;
        var moved = new ArrayList<BlockPos>();
        for (var key : List.of("A", "B", "C")) {
            state.coord(key).ifPresent(pos -> moved.add(pos.relative(direction, amount)));
        }
        if (cable) {
            for (var waypoint : state.cableWaypoints()) {
                moved.add(waypoint.relative(direction, amount));
            }
        }
        var a = state.coordA();
        var b = state.coordB();
        var error = cable || a.isEmpty() || b.isEmpty()
                ? BuildPlanner.checkRange(player.blockPosition(), tier.range(), moved)
                : BuildPlanner.checkSelection(player, tier, a.get(), b.get(), moved);
        if (error.isPresent()) {
            ChatUtil.error(player, error.get());
            return false;
        }
        for (var key : List.of("A", "B", "C")) {
            state.coord(key).ifPresent(pos -> state.setCoord(key, pos.relative(direction, amount)));
        }
        if (cable) {
            state.moveCableWaypoints(direction, amount);
        }
        ChatUtil.status(player, MMMessage.SELECTION_MOVED.text(
                Math.abs(amount), (amount > 0 ? direction : direction.getOpposite()).getName()));
        return true;
    }

    private static boolean resize(ServerPlayer player, ManipulatorTier tier, ToolState state, Direction face,
            int amount) {
        var resized = SelectionEdit.resize(state.coordA().orElseThrow(), state.coordB().orElseThrow(), face, amount)
                .orElse(null);
        if (resized == null) {
            ChatUtil.error(player, MMMessage.SELECTION_MIN_SIZE.text());
            return false;
        }
        var error = BuildPlanner.checkSelection(player, tier, resized.a(), resized.b(),
                List.of(resized.a(), resized.b()));
        if (error.isPresent()) {
            ChatUtil.error(player, error.get());
            return false;
        }
        state.setCoord("A", resized.a());
        state.setCoord("B", resized.b());
        ChatUtil.status(player, MMMessage.SELECTION_RESIZED.text(
                Math.abs(resized.a().getX() - resized.b().getX()) + 1,
                Math.abs(resized.a().getY() - resized.b().getY()) + 1,
                Math.abs(resized.a().getZ() - resized.b().getZ()) + 1));
        return true;
    }

    private static boolean changeFloatingDistance(ServerPlayer player, ManipulatorTier tier, ToolState state,
            int amount) {
        int furthest = tier.range() < 0 ? FloatingPlacement.MAX_DISTANCE : tier.range();
        int distance = Mth.clamp(Mth.floor(FloatingPlacement.distance(player, state)) + amount, 1, furthest);
        if (distance == state.floatingDistance()) {
            return false;
        }
        state.setFloatingDistance(distance);
        ChatUtil.status(player, MMMessage.FLOATING_DISTANCE.text(distance));
        return true;
    }

    public enum Edit {
        MOVE(SelectionEdit.Scroll.MOVE_SELECTION),
        RESIZE(SelectionEdit.Scroll.RESIZE),
        FLOATING_DISTANCE(SelectionEdit.Scroll.FLOATING_DISTANCE);

        private final SelectionEdit.Scroll scroll;

        Edit(SelectionEdit.Scroll scroll) {
            this.scroll = scroll;
        }
    }
}
