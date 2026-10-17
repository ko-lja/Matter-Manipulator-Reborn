package lu.kolja.matter_manipulator.common.integration.projectred;

import net.minecraft.core.Direction;

import java.util.Set;

final class WireConnections {

    private static final int[] ROTATION_TO = {
            -1, -1, 2, 0, 1, 3,
            -1, -1, 2, 0, 3, 1,
            2, 0, -1, -1, 3, 1,
            2, 0, -1, -1, 1, 3,
            2, 0, 1, 3, -1, -1,
            2, 0, 3, 1, -1, -1
    };

    private WireConnections() {}

    static int face(int side, Set<Direction> neighbours) {
        int connMap = 0;
        for (var direction : neighbours) {
            int rotation = ROTATION_TO[side * 6 + direction.get3DDataValue()];
            if (rotation >= 0) {
                connMap |= 0x10 << rotation;
            }
        }
        return connMap;
    }

    static int centre(Set<Direction> neighbours) {
        int connMap = 0;
        for (var direction : neighbours) {
            connMap |= 1 << direction.get3DDataValue();
        }
        return connMap;
    }
}
