package lu.kolja.matter_manipulator.common.manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public enum StackAxis {
    X("X"),
    Y("Y"),
    Z("Z");

    private final String name;

    StackAxis(String name) {
        this.name = name;
    }

    public StackAxis next() {
        var values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public BlockPos offset(int step) {
        return switch (this) {
            case X -> new BlockPos(step, 0, 0);
            case Y -> new BlockPos(0, step, 0);
            case Z -> new BlockPos(0, 0, step);
        };
    }

    public Component displayName() {
        return Component.literal(name);
    }

    public static StackAxis byName(String name) {
        for (var axis : values()) {
            if (axis.name.equalsIgnoreCase(name)) {
                return axis;
            }
        }
        return X;
    }
}
