package lu.kolja.matter_manipulator.common.integration.framedblocks;

import lu.kolja.matter_manipulator.api.DirectionTransform;
import net.minecraft.core.Direction;

final class FramedShapeTransforms {

    private static final Direction[][] CORNERS = {
            {Direction.WEST, Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.NORTH, Direction.EAST, Direction.SOUTH},
            {Direction.WEST, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST, Direction.SOUTH, Direction.EAST, Direction.NORTH},
            {Direction.EAST, Direction.UP, Direction.EAST, Direction.DOWN, Direction.WEST, Direction.DOWN, Direction.WEST, Direction.UP},
            {Direction.WEST, Direction.UP, Direction.WEST, Direction.DOWN, Direction.EAST, Direction.DOWN, Direction.EAST, Direction.UP},
            {Direction.NORTH, Direction.UP, Direction.NORTH, Direction.DOWN, Direction.SOUTH, Direction.DOWN, Direction.SOUTH, Direction.UP},
            {Direction.SOUTH, Direction.UP, Direction.SOUTH, Direction.DOWN, Direction.NORTH, Direction.DOWN, Direction.NORTH, Direction.UP},
    };

    private FramedShapeTransforms() {}

    static boolean flipsParity(DirectionTransform transform) {
        int negated = 0;
        for (var axis : new Direction[] {Direction.EAST, Direction.UP, Direction.SOUTH}) {
            if (transform.transform(axis).getAxisDirection() == Direction.AxisDirection.NEGATIVE) {
                negated++;
            }
        }
        return negated % 2 == 1;
    }

    static int permuteVertexOffsets(int packed, Direction face, DirectionTransform transform) {
        int result = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = packed >> (vertex * 5) & 0x1F;
            result |= offset << (transformedVertex(face, vertex, transform) * 5);
        }
        return result;
    }

    static boolean splitLineFlips(Direction face, DirectionTransform transform) {
        return transformedVertex(face, 0, transform) % 2 == 1;
    }

    static int permuteFaceOffsets(int packed, DirectionTransform transform) {
        int result = 0;
        for (var side : Direction.values()) {
            int offset = packed >> (side.ordinal() * 4) & 0xF;
            result |= offset << (transform.transform(side).ordinal() * 4);
        }
        return result;
    }

    static int permuteFaceMask(int mask, DirectionTransform transform) {
        int result = 0;
        for (var side : Direction.values()) {
            if ((mask & (1 << side.ordinal())) != 0) {
                result |= 1 << transform.transform(side).ordinal();
            }
        }
        return result;
    }

    private static int transformedVertex(Direction face, int vertex, DirectionTransform transform) {
        var corners = CORNERS[face.ordinal()];
        var first = transform.transform(corners[vertex * 2]);
        var second = transform.transform(corners[vertex * 2 + 1]);
        var turned = CORNERS[transform.transform(face).ordinal()];
        for (int candidate = 0; candidate < 4; candidate++) {
            var a = turned[candidate * 2];
            var b = turned[candidate * 2 + 1];
            if ((a == first && b == second) || (a == second && b == first)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No vertex of " + transform.transform(face) + " at " + first + "/" + second);
    }
}
