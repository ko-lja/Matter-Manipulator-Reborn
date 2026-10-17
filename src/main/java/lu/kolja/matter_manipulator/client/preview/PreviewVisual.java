package lu.kolja.matter_manipulator.client.preview;

import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

sealed interface PreviewVisual {

    record Captured(CapturedBlock captured, int lineMask) implements PreviewVisual {}

    record FromSource(BlockPos sourcePos, BlockState sourceState, int stamp, ToolTransform transform)
            implements PreviewVisual {}

    record Exchange(BlockPos pos, BlockState sourceState, int stamp, CapturedBlock target) implements PreviewVisual {}
}
