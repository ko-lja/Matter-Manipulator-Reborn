package lu.kolja.matter_manipulator.common.manipulator;

import lu.kolja.matter_manipulator.common.build.BuildPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;

public record SelectionBox(BlockPos min, BlockPos max, long volume, Optional<Component> refusal) {

    public static SelectionBox between(BlockPos a, BlockPos marker, BlockPos playerPos, int range, long maxVolume) {
        var min = new BlockPos(Math.min(a.getX(), marker.getX()), Math.min(a.getY(), marker.getY()),
                Math.min(a.getZ(), marker.getZ()));
        var max = new BlockPos(Math.max(a.getX(), marker.getX()), Math.max(a.getY(), marker.getY()),
                Math.max(a.getZ(), marker.getZ()));
        return new SelectionBox(min, max, BuildPlanner.regionVolume(a, marker),
                BuildPlanner.checkSelection(playerPos, range, maxVolume, a, marker, List.of(a, marker)));
    }

    public int sizeX() {
        return max.getX() - min.getX() + 1;
    }

    public int sizeY() {
        return max.getY() - min.getY() + 1;
    }

    public int sizeZ() {
        return max.getZ() - min.getZ() + 1;
    }

    public AABB bounds() {
        return AABB.encapsulatingFullBlocks(min, max);
    }
}
