package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

public final class BlockCapture {

    private BlockCapture() {}

    public static CapturedBlock capture(ServerLevel level, BlockPos pos, CapturePurpose purpose) {
        return capture(level, pos, purpose, null);
    }

    public static CapturedBlock capture(
            ServerLevel level, BlockPos pos, CapturePurpose purpose, @Nullable BoundingBox selection
    ) {
        PlanStats.count(PlanStats.Counter.SOURCE_CAPTURES);
        var state = level.getBlockState(pos);
        var blockEntityTag = new CompoundTag();
        if (purpose == CapturePurpose.MOVE_TRANSFER) {
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                long serializeStarted = PlanStats.start();
                blockEntityTag = blockEntity.saveWithFullMetadata(level.registryAccess());
                PlanStats.end(PlanStats.Section.CAPTURE_BLOCK_ENTITY, serializeStarted);
            }
        }
        long integrationStarted = PlanStats.start();
        var compat = IntegrationDispatcher.capture(level, pos, state, selection);
        PlanStats.end(PlanStats.Section.CAPTURE_INTEGRATION, integrationStarted);
        return new CapturedBlock(BlockStateSpec.fromState(state), blockEntityTag, compat);
    }

    public static boolean canCapture(BlockGetter level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return !state.isAir() && BlockPolicy.canManipulate(level, pos, state);
    }
}
