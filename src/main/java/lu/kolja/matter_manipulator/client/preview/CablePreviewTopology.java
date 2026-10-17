package lu.kolja.matter_manipulator.client.preview;

import lu.kolja.matter_manipulator.common.build.CableRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;
import java.util.Set;

final class CablePreviewTopology {
    private static final CablePreviewTopology EMPTY = new CablePreviewTopology(
            List.of(),
            0,
            CableRoute.expand(List.of(), 0)
    );

    private final List<BlockPos> sourcePath;
    private final int maxPositions;
    private final CableRoute.Result route;

    private CablePreviewTopology(List<BlockPos> sourcePath, int maxPositions, CableRoute.Result route) {
        this.sourcePath = sourcePath;
        this.maxPositions = maxPositions;
        this.route = route;
    }

    static CablePreviewTopology empty() {
        return EMPTY;
    }

    CablePreviewTopology retain(List<BlockPos> path, int nextMaxPositions) {
        if (matches(path, nextMaxPositions)) {
            return this;
        }
        PreviewDebugStats.topologyRecomputed();
        return new CablePreviewTopology(path, nextMaxPositions, CableRoute.expand(path, nextMaxPositions));
    }

    boolean matches(List<BlockPos> path, int nextMaxPositions) {
        return maxPositions == nextMaxPositions && (sourcePath == path || sourcePath.equals(path));
    }

    int connectionMask(BlockPos pos) {
        return route.connectionMask(pos);
    }

    Set<Direction> directions(BlockPos pos) {
        return CableRoute.directions(connectionMask(pos));
    }
}
