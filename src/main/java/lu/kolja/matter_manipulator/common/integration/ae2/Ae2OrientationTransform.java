package lu.kolja.matter_manipulator.common.integration.ae2;

import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;
import appeng.block.crafting.PushDirection;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

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
        if (!memoryCard.contains(PUSH_DIRECTION, Tag.TAG_STRING)) {
            return output;
        }
        var name = memoryCard.getString(PUSH_DIRECTION);
        for (var push : PushDirection.values()) {
            if (push.getSerializedName().equals(name)) {
                output.putString(PUSH_DIRECTION, transform(push, transform).getSerializedName());
            }
        }
        return output;
    }
}
