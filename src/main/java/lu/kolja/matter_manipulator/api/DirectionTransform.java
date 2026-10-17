package lu.kolja.matter_manipulator.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/** Immutable rotation and mirroring for directions. */
@FunctionalInterface
public interface DirectionTransform {
    Direction transform(Direction direction);

    default BlockPos transformOffset(Vec3i offset) {
        return BlockPos.ZERO
                .relative(transform(Direction.EAST), offset.getX())
                .relative(transform(Direction.UP), offset.getY())
                .relative(transform(Direction.SOUTH), offset.getZ());
    }

    default Direction.Axis transform(Direction.Axis axis) {
        return transform(switch (axis) {
            case X -> Direction.EAST;
            case Y -> Direction.UP;
            case Z -> Direction.SOUTH;
        }).getAxis();
    }
}
