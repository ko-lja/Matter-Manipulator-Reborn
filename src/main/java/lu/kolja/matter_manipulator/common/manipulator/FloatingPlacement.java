package lu.kolja.matter_manipulator.common.manipulator;

import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;

public final class FloatingPlacement {

    private FloatingPlacement() {}

    public static final int MAX_DISTANCE = 256;

    public static double distance(Player player, ToolState state) {
        int stored = state.floatingDistance();
        return stored > 0 ? stored : player.blockInteractionRange();
    }

    public static BlockPos lookTarget(Player player, ToolState state) {
        var level = player.level();
        var start = player.getEyePosition();
        var end = start.add(player.getViewVector(1.0F).scale(distance(player, state)));
        var hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return BlockPos.containing(end);
        }

        var target = hit.getBlockPos().relative(hit.getDirection());
        if (target.getY() < level.getMinBuildHeight()
                || target.getY() >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(target)) {
            return hit.getBlockPos();
        }
        return target;
    }

    public static Anchors anchors(Player player, ToolState state) {
        if (state.mode() == ManipulatorMode.BLUEPRINT) {
            return new Anchors(Optional.empty(), Optional.empty(),
                    PasteAnchor.blueprintAnchor(state, player.level().dimension()));
        }
        var a = state.coordA();
        var b = state.coordB();
        var c = state.coordC();
        if (!state.floatingPlacement()) {
            return new Anchors(a, b, c);
        }

        var target = Optional.of(lookTarget(player, state));
        return switch (state.mode()) {
            case GEOMETRY -> new Anchors(
                    a,
                    b.isPresent() && state.shape() == ShapeType.CYLINDER ? b : target,
                    b.isPresent() && state.shape() == ShapeType.CYLINDER ? target : c
            );
            case CABLE, EXCHANGE -> new Anchors(a, target, c);
            case COPY, MOVE, BLUEPRINT -> new Anchors(a, b, c);
        };
    }

    public record Anchors(Optional<BlockPos> a, Optional<BlockPos> b, Optional<BlockPos> c) {}
}
