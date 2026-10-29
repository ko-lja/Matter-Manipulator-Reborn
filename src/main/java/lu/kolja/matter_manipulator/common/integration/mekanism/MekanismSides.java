package lu.kolja.matter_manipulator.common.integration.mekanism;

import lu.kolja.matter_manipulator.api.DirectionTransform;
import mekanism.api.RelativeSide;
import mekanism.api.SerializationConstants;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

final class MekanismSides {

    private MekanismSides() {}

    static CompoundTag transformMachine(CompoundTag tag, Direction facing, Direction newFacing, DirectionTransform transform) {
        var result = tag.copy();
        var sides = new int[RelativeSide.values().length];
        for (var side : RelativeSide.values()) {
            var world = transform.transform(side.getDirection(facing));
            sides[side.ordinal()] = RelativeSide.fromDirections(newFacing, world).ordinal();
        }
        if (result.contains(SerializationConstants.COMPONENT_CONFIG, Tag.TAG_COMPOUND)) {
            var config = result.getCompound(SerializationConstants.COMPONENT_CONFIG);
            for (var key : config.getAllKeys()) {
                if (key.startsWith(SerializationConstants.CONFIG) && config.contains(key, Tag.TAG_INT_ARRAY)) {
                    config.putIntArray(key, permute(config.getIntArray(key), sides));
                }
            }
        }
        if (result.contains(SerializationConstants.COMPONENT_EJECTOR, Tag.TAG_COMPOUND)) {
            var ejector = result.getCompound(SerializationConstants.COMPONENT_EJECTOR);
            if (ejector.contains(SerializationConstants.INPUT_COLOR, Tag.TAG_INT_ARRAY)) {
                ejector.putIntArray(SerializationConstants.INPUT_COLOR, permute(ejector.getIntArray(SerializationConstants.INPUT_COLOR), sides));
            }
        }
        return result;
    }

    static CompoundTag transformByDirection(CompoundTag tag, String prefix, DirectionTransform transform) {
        var directions = directions(transform);
        var result = tag.copy();
        for (int index = 0; index < directions.length; index++) {
            result.remove(prefix + index);
        }
        for (int index = 0; index < directions.length; index++) {
            var value = tag.get(prefix + index);
            if (value != null) {
                result.put(prefix + directions[index], value.copy());
            }
        }
        return result;
    }

    static CompoundTag transformArrayByDirection(CompoundTag tag, String key, DirectionTransform transform) {
        if (!tag.contains(key, Tag.TAG_INT_ARRAY)) {
            return tag;
        }
        var result = tag.copy();
        result.putIntArray(key, permute(tag.getIntArray(key), directions(transform)));
        return result;
    }

    private static int[] directions(DirectionTransform transform) {
        var directions = new int[Direction.values().length];
        for (var direction : Direction.values()) {
            directions[direction.ordinal()] = transform.transform(direction).ordinal();
        }
        return directions;
    }

    private static int[] permute(int[] values, int[] targets) {
        var result = values.clone();
        for (int index = 0; index < values.length && index < targets.length; index++) {
            if (targets[index] < result.length) {
                result[targets[index]] = values[index];
            }
        }
        return result;
    }
}
