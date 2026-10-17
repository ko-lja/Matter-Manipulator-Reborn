package lu.kolja.matter_manipulator.common.integration.ae2;

import lu.kolja.matter_manipulator.api.DirectionTransform;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

public final class Ae2NbtTransform {

    private Ae2NbtTransform() {}

    public static CompoundTag transformCableBusDirections(CompoundTag input, DirectionTransform transform) {
        var output = input.copy();
        removeDirectionalKeys(output);

        for (var direction : Direction.values()) {
            copyKey(input, output, direction.getName(), transform.transform(direction).getName());
            copyKey(input, output, facadeKey(direction), facadeKey(transform.transform(direction)));
        }
        return output;
    }

    private static void removeDirectionalKeys(CompoundTag output) {
        for (var direction : Direction.values()) {
            output.remove(direction.getName());
            output.remove(facadeKey(direction));
        }
    }

    private static void copyKey(CompoundTag input, CompoundTag output, String oldKey, String newKey) {
        if (input.contains(oldKey)) {
            output.put(newKey, input.get(oldKey).copy());
        }
    }

    private static String facadeKey(Direction direction) {
        var name = direction.getName();
        return "facade" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
