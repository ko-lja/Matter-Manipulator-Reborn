package lu.kolja.matter_manipulator.common.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CableRoute {

    private CableRoute() {}

    public static BlockPos snapToAxis(BlockPos from, BlockPos target) {
        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        int dz = target.getZ() - from.getZ();
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        int az = Math.abs(dz);

        if (ax == 0 && ay == 0 && az == 0) {
            return from.immutable();
        }
        if (ax >= ay && ax >= az) {
            return new BlockPos(target.getX(), from.getY(), from.getZ());
        }
        if (az >= ay) {
            return new BlockPos(from.getX(), from.getY(), target.getZ());
        }
        return new BlockPos(from.getX(), target.getY(), from.getZ());
    }

    public static Result expand(List<BlockPos> waypoints, int maxPositions) {
        if (waypoints.isEmpty() || maxPositions <= 0) {
            return new Result(
                    List.of(),
                    Map.of(),
                    waypoints.isEmpty() ? Failure.NONE : Failure.POSITION_LIMIT
            );
        }

        var positions = new LinkedHashSet<BlockPos>();
        var masks = new LinkedHashMap<BlockPos, Integer>();
        var first = waypoints.get(0).immutable();
        positions.add(first);
        masks.put(first, 0);

        for (int i = 1; i < waypoints.size(); i++) {
            var from = waypoints.get(i - 1);
            var to = waypoints.get(i);
            var direction = segmentDirection(from, to);
            if (direction == null) {
                return result(positions, masks, Failure.MALFORMED_SEGMENT);
            }

            var current = from.immutable();
            int steps = manhattanDistance(from, to);
            for (int step = 0; step < steps; step++) {
                var next = current.relative(direction);
                mergeConnection(masks, current, direction);
                mergeConnection(masks, next, direction.getOpposite());
                positions.add(next.immutable());
                if (positions.size() > maxPositions) {
                    return result(positions, masks, Failure.POSITION_LIMIT);
                }
                current = next;
            }
        }
        return result(positions, masks, Failure.NONE);
    }

    public static Set<Direction> directions(int mask) {
        var directions = EnumSet.noneOf(Direction.class);
        for (var direction : Direction.values()) {
            if ((mask & 1 << direction.ordinal()) != 0) {
                directions.add(direction);
            }
        }
        return Collections.unmodifiableSet(directions);
    }

    private static Result result(Set<BlockPos> positions, Map<BlockPos, Integer> masks, Failure failure) {
        return new Result(List.copyOf(positions), Map.copyOf(masks), failure);
    }

    private static Direction segmentDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        if (changedAxes == 0) {
            return Direction.UP;
        }
        if (changedAxes != 1) {
            return null;
        }
        if (dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dy != 0) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int manhattanDistance(BlockPos from, BlockPos to) {
        return Math.abs(to.getX() - from.getX())
                + Math.abs(to.getY() - from.getY())
                + Math.abs(to.getZ() - from.getZ());
    }

    private static void mergeConnection(Map<BlockPos, Integer> masks, BlockPos pos, Direction direction) {
        masks.merge(pos.immutable(), 1 << direction.ordinal(), (left, right) -> left | right);
    }

    public enum Failure {
        NONE,
        MALFORMED_SEGMENT,
        POSITION_LIMIT
    }

    public record Result(List<BlockPos> positions, Map<BlockPos, Integer> connectionMasks, Failure failure) {
        public Result {
            positions = List.copyOf(positions);
            connectionMasks = Map.copyOf(connectionMasks);
        }

        public boolean complete() {
            return failure == Failure.NONE;
        }

        public int connectionMask(BlockPos pos) {
            return connectionMasks.getOrDefault(pos, 0);
        }
    }
}
