package lu.kolja.matter_manipulator.common.manipulator;

import lu.kolja.matter_manipulator.common.build.CableRoute;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public final class MarkTarget {

    private MarkTarget() {}

    public static Optional<BlockPos> next(Level level, Player player, ToolState state, @Nullable BlockHitResult hit) {
        if (state.floatingPlacement()) {
            return stored(state, level.dimension(), FloatingPlacement.lookTarget(player, state));
        }
        if (hit == null) {
            return Optional.empty();
        }
        return stored(state, level.dimension(), clicked(level, state, hit.getBlockPos(), hit.getDirection()));
    }

    static Optional<BlockPos> stored(ToolState state, ResourceKey<Level> dimension, BlockPos aimed) {
        if (state.mode() == ManipulatorMode.BLUEPRINT || PasteAnchor.state(state, dimension) != PasteAnchor.State.NONE) {
            return Optional.empty();
        }
        boolean routeContinues = state.mode() == ManipulatorMode.CABLE && state.cableRouteMode() == CableRouteMode.EDGES
                && state.dimensionId().filter(id -> !id.equals(dimension.location())).isEmpty();
        var waypoints = routeContinues ? state.cableWaypoints() : List.<BlockPos>of();
        if (waypoints.isEmpty()) {
            return Optional.of(aimed.immutable());
        }
        return Optional.of(CableRoute.snapToAxis(waypoints.get(waypoints.size() - 1), aimed));
    }

    private static BlockPos clicked(Level level, ToolState state, BlockPos clickedPos, Direction clickedFace) {
        if (state.mode() != ManipulatorMode.CABLE) {
            return clickedPos;
        }
        var clickedState = level.getBlockState(clickedPos);
        var targetCable = state.preferredSlot(BlockSlot.CABLE).flatMap(BlockStateSpec::tryResolve);
        var reusableCable = targetCable
                .map(target -> IntegrationDispatcher.canReuseCable(level, clickedPos, clickedState, target,
                        state.preferredCapturedSlot(BlockSlot.CABLE).orElse(null)))
                .orElseGet(() -> IntegrationDispatcher.isCable(clickedState));
        if (clickedState.canBeReplaced() || reusableCable) {
            return clickedPos;
        }
        var adjacent = clickedPos.relative(clickedFace);
        if (adjacent.getY() < level.getMinBuildHeight()
                || adjacent.getY() >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(adjacent)) {
            return clickedPos;
        }
        return adjacent;
    }
}
