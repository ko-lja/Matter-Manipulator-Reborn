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
        var hash = tag.getString(TAG_HASH);
        var name = tag.getString(TAG_NAME);
        var size = new BlockPos(tag.getInt(TAG_SIZE_X), tag.getInt(TAG_SIZE_Y), tag.getInt(TAG_SIZE_Z));
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
