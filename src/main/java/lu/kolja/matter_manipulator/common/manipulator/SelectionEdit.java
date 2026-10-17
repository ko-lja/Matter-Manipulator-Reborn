package lu.kolja.matter_manipulator.common.manipulator;

import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;

public final class SelectionEdit {

    private SelectionEdit() {}

    public enum Scroll {
        NONE,
        MOVE_ANCHOR,
        FLOATING_DISTANCE,
        MOVE_SELECTION,
        RESIZE
    }

    public record Corners(BlockPos a, BlockPos b) {}

    public static Scroll scrollAction(ToolState tool, ResourceKey<Level> dimension, boolean resize) {
        if (tool.mode() == ManipulatorMode.BLUEPRINT) {
            if (resize) {
                return Scroll.NONE;
            }
            if (PasteAnchor.state(tool, dimension) == PasteAnchor.State.PINNED) {
                return Scroll.MOVE_ANCHOR;
            }
            return tool.floatingPlacement() ? Scroll.FLOATING_DISTANCE : Scroll.NONE;
        }
        boolean here = tool.dimensionId().filter(id -> !id.equals(dimension.location())).isEmpty();
        boolean box = here && tool.coordA().isPresent() && tool.coordB().isPresent();
        if (resize) {
            return box && tool.mode() != ManipulatorMode.CABLE ? Scroll.RESIZE : Scroll.NONE;
        }
        if (PasteAnchor.state(tool, dimension) == PasteAnchor.State.PINNED) {
            return Scroll.MOVE_ANCHOR;
        }
        if (tool.floatingPlacement()) {
            return Scroll.FLOATING_DISTANCE;
        }
        boolean route = here && tool.mode() == ManipulatorMode.CABLE && tool.cableRouteMode() == CableRouteMode.EDGES
                && !tool.cableWaypoints().isEmpty();
        return box || route ? Scroll.MOVE_SELECTION : Scroll.NONE;
    }

    public static Optional<Corners> resize(BlockPos a, BlockPos b, Direction face, int amount) {
        var axis = face.getAxis();
        int towardsFace = face.getAxisDirection().getStep();
        int aAhead = (a.get(axis) - b.get(axis)) * towardsFace;
        int steps = Math.max(amount, -Math.abs(aAhead));
        if (steps == 0) {
            return Optional.empty();
        }
        return Optional.of(aAhead > 0 ? new Corners(a.relative(face, steps), b) : new Corners(a, b.relative(face, steps)));
    }
}
