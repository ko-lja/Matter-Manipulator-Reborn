package lu.kolja.matter_manipulator.common.integration.ae2;

import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;
import appeng.block.crafting.PushDirection;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import net.minecraft.nbt.CompoundTag;

public final class Ae2OrientationTransform {

    private static final String PUSH_DIRECTION = "ae2:exported_push_direction";

    private Ae2OrientationTransform() {}

    public static BlockOrientation transform(BlockOrientation orientation, DirectionTransform transform) {
        return BlockOrientation.get(
                transform.transform(orientation.getSide(RelativeSide.FRONT)),
                transform.transform(orientation.getSide(RelativeSide.TOP))
        );
    }

    public static PushDirection transform(PushDirection push, DirectionTransform transform) {
        var direction = push.getDirection();
        return direction == null ? push : PushDirection.fromDirection(transform.transform(direction));
    }

    public static CompoundTag transformPushDirection(CompoundTag memoryCard, DirectionTransform transform) {
        var output = memoryCard.copy();
        var name = memoryCard.getString(PUSH_DIRECTION).orElse(null);
        if (name == null) {
            return output;
        }
        for (var push : PushDirection.values()) {
            if (push.getSerializedName().equals(name)) {
                output.putString(PUSH_DIRECTION, transform(push, transform).getSerializedName());
            }
        }
        return output;
    }
}
