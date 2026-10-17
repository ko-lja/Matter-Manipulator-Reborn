package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.MatterManipulator;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockPolicy {

    public static final TagKey<Block> IMMOVABLE =
            BlockTags.create(MatterManipulator.id("immovable"));

    private BlockPolicy() {}

    public static boolean canManipulate(BlockGetter level, BlockPos pos, BlockState state) {
        return !state.is(IMMOVABLE) && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    public static boolean canManipulate(BlockState state) {
        return canManipulate(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state);
    }
}
