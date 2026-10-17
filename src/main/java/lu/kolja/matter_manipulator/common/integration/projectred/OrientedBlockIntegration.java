package lu.kolja.matter_manipulator.common.integration.projectred;

import codechicken.lib.vec.Rotation;
import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import mrtjp.projectred.core.block.ProjectRedBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

final class OrientedBlockIntegration implements BlockIntegration {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("projectred_core", "orientation");

    static boolean isOriented(BlockState state) {
        return state.getBlock() instanceof ProjectRedBlock
                && (state.hasProperty(ProjectRedBlock.SIDE) || state.hasProperty(ProjectRedBlock.ROTATION));
    }

    @Override
    public @Nullable BlockState transformState(IntegrationContext.Transform context, @Nullable BlockState transformed) {
        if (transformed == null) {
            return null;
        }
        var state = context.state();
        var transform = context.transform();
        if (!state.hasProperty(ProjectRedBlock.SIDE)) {
            var rotation = state.getValue(ProjectRedBlock.ROTATION);
            int direction = PartTransforms.side(transform, Rotation.rotateSide(0, rotation));
            return transformed.setValue(ProjectRedBlock.ROTATION, direction < 2 ? rotation : Rotation.rotationTo(0, direction));
        }
        var side = state.getValue(ProjectRedBlock.SIDE);
        int turnedSide = PartTransforms.side(transform, side);
        var result = transformed.setValue(ProjectRedBlock.SIDE, turnedSide);
        if (!state.hasProperty(ProjectRedBlock.ROTATION)) {
            return result;
        }
        int reference = PartTransforms.side(transform, Rotation.rotateSide(side, state.getValue(ProjectRedBlock.ROTATION)));
        return result.setValue(ProjectRedBlock.ROTATION, Rotation.rotationTo(turnedSide, reference));
    }

    @Override
    public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
        if (context.state().hasProperty(ProjectRedBlock.SIDE) && blockEntity.contains("side", Tag.TAG_BYTE)) {
            blockEntity.putByte("side", (byte) PartTransforms.side(context.transform(), blockEntity.getByte("side")));
        }
        if (blockEntity.contains("connMap", Tag.TAG_LONG)) {
            blockEntity.putLong("connMap", 0L);
        }
        return blockEntity;
    }
}
