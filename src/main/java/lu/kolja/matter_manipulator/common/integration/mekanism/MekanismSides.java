package lu.kolja.matter_manipulator.common.integration.mekanism;

import lu.kolja.matter_manipulator.api.DirectionTransform;
import mekanism.api.NBTConstants;
import mekanism.api.RelativeSide;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

final class MekanismSides {

    private MekanismSides() {}

    static CompoundTag transformMachine(CompoundTag tag, Direction facing, Direction newFacing, DirectionTransform transform) {
        var result = tag.copy();
        var sides = new int[RelativeSide.values().length];
        for (var side : RelativeSide.values()) {
            var world = transform.transform(side.getDirection(facing));
            sides[side.ordinal()] = RelativeSide.fromDirections(newFacing, world).ordinal();
        }
        if (result.contains(NBTConstants.COMPONENT_CONFIG, CompoundTag.TAG_COMPOUND)) {
            var config = result.getCompound(NBTConstants.COMPONENT_CONFIG);
            for (var key : config.getAllKeys()) {
                if (key.startsWith(NBTConstants.CONFIG) && config.contains(key, CompoundTag.TAG_COMPOUND)) {
                    config.put(key, permute(config.getCompound(key), NBTConstants.SIDE, sides));
                }
            }
        }
        if (result.contains(NBTConstants.COMPONENT_EJECTOR, CompoundTag.TAG_COMPOUND)) {
            result.put(NBTConstants.COMPONENT_EJECTOR,
                    permute(result.getCompound(NBTConstants.COMPONENT_EJECTOR), NBTConstants.COLOR, sides));
        }
        return result;
    }

    static CompoundTag transformByDirection(CompoundTag tag, String prefix, DirectionTransform transform) {
        var directions = new int[Direction.values().length];
        for (var direction : Direction.values()) {
            directions[direction.ordinal()] = transform.transform(direction).ordinal();
        }
        return permute(tag, prefix, directions);
    }

    private static CompoundTag permute(CompoundTag tag, String prefix, int[] targets) {
        var result = tag.copy();
        for (int index = 0; index < targets.length; index++) {
            result.remove(prefix + index);
        }
        for (int index = 0; index < targets.length; index++) {
            var value = tag.get(prefix + index);
            if (value != null) {
                result.put(prefix + targets[index], value.copy());
            }
        }
        return result;
    }
}
