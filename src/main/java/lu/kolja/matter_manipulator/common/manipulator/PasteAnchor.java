package lu.kolja.matter_manipulator.common.manipulator;

import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class PasteAnchor {

    private PasteAnchor() {}

    public enum State {
        NONE,
        FOLLOWING,
        PINNED
    }

    public static State state(ToolState tool, ResourceKey<Level> dimension) {
        var mode = tool.mode();
        if (mode == ManipulatorMode.BLUEPRINT) {
            if (tool.blueprintRef().isEmpty()) {
                return State.NONE;
            }
            return blueprintAnchor(tool, dimension).isPresent() ? State.PINNED : State.FOLLOWING;
        }
        if (mode != ManipulatorMode.COPY && mode != ManipulatorMode.MOVE
                || tool.coordA().isEmpty() || tool.coordB().isEmpty()
                || tool.dimensionId().filter(id -> !id.equals(dimension.location())).isPresent()) {
            return State.NONE;
        }
        return tool.coordC().isPresent() ? State.PINNED : State.FOLLOWING;
    }

    public static Optional<BlockPos> blueprintAnchor(ToolState tool, ResourceKey<Level> dimension) {
        return tool.coordDimension("C").filter(id -> id.equals(dimension.location())).flatMap(id -> tool.coordC());
    }

    public static void setAnchor(ToolState tool, ResourceKey<Level> dimension, BlockPos anchor) {
        if (tool.mode() == ManipulatorMode.BLUEPRINT) {
            tool.setCoord("C", anchor, dimension);
        } else {
            tool.setCoord("C", anchor);
        }
    }

    public static boolean pin(ToolState tool, ResourceKey<Level> dimension, BlockPos anchor) {
        if (state(tool, dimension) != State.FOLLOWING) {
            return false;
        }
        setAnchor(tool, dimension, anchor);
        return true;
    }

    public static boolean unpin(ToolState tool, ResourceKey<Level> dimension) {
        if (state(tool, dimension) != State.PINNED) {
            return false;
        }
        tool.clearCoord("C");
        return true;
    }

    public static BlockPos transformedSize(ToolState tool) {
        if (tool.mode() == ManipulatorMode.BLUEPRINT) {
            return tool.transform().transformedSize(tool.blueprintRef().orElseThrow().size().offset(-1, -1, -1));
        }
        var a = tool.coordA().orElseThrow();
        var b = tool.coordB().orElseThrow();
        var size = new BlockPos(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()),
                Math.abs(a.getZ() - b.getZ()));
        return tool.transform().transformedSize(size);
    }

    public static BlockPos place(BlockPos hit, Direction face, BlockPos size) {
        int centreX = size.getX() / 2;
        int centreZ = size.getZ() / 2;
        return switch (face) {
            case UP -> hit.above();
            case DOWN -> hit.below(size.getY() + 1);
            case EAST -> new BlockPos(hit.getX() + 1 + centreX, hit.getY(), hit.getZ());
            case WEST -> new BlockPos(hit.getX() - 1 - size.getX() + centreX, hit.getY(), hit.getZ());
            case SOUTH -> new BlockPos(hit.getX(), hit.getY(), hit.getZ() + 1 + centreZ);
            case NORTH -> new BlockPos(hit.getX(), hit.getY(), hit.getZ() - 1 - size.getZ() + centreZ);
        };
    }

    public static BlockPos coordC(BlockPos bottomCentre, BlockPos size) {
        return bottomCentre.offset(-(size.getX() / 2), 0, -(size.getZ() / 2));
    }

    public static Direction nudgeDirection(Vec3 look) {
        double x = Math.abs(look.x);
        double y = Math.abs(look.y);
        double z = Math.abs(look.z);
        if (y > x && y > z) {
            return look.y > 0 ? Direction.UP : Direction.DOWN;
        }
        if (x >= z) {
            return look.x >= 0 ? Direction.EAST : Direction.WEST;
        }
        return look.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
