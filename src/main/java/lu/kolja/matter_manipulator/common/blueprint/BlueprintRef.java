package lu.kolja.matter_manipulator.common.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Optional;

public record BlueprintRef(String hash, String name, BlockPos size) {

    private static final String TAG_HASH = "Hash";
    private static final String TAG_NAME = "Name";
    private static final String TAG_SIZE_X = "SizeX";
    private static final String TAG_SIZE_Y = "SizeY";
    private static final String TAG_SIZE_Z = "SizeZ";

    public BlueprintRef {
        size = size.immutable();
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString(TAG_HASH, hash);
        tag.putString(TAG_NAME, name);
        tag.putInt(TAG_SIZE_X, size.getX());
        tag.putInt(TAG_SIZE_Y, size.getY());
        tag.putInt(TAG_SIZE_Z, size.getZ());
        return tag;
    }

    public static Optional<BlueprintRef> fromTag(CompoundTag tag) {
        var hash = tag.getStringOr(TAG_HASH, "");
        var name = tag.getStringOr(TAG_NAME, "");
        var size = new BlockPos(tag.getIntOr(TAG_SIZE_X, 0), tag.getIntOr(TAG_SIZE_Y, 0), tag.getIntOr(TAG_SIZE_Z, 0));
        if (!isHash(hash) || name.length() > Blueprint.MAX_NAME_LENGTH
                || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new BlueprintRef(hash, name, size));
    }

    public static boolean isHash(String value) {
        return value.length() == 64 && value.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f');
    }
}
