package lu.kolja.matter_manipulator.client.integration.framedblocks;

import lu.kolja.matter_manipulator.api.client.ClientIntegrationContext;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;
import lu.kolja.matter_manipulator.api.client.ClientPreviewIntegration;
import lu.kolja.matter_manipulator.common.integration.framedblocks.FramedBlocksIntegration;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.block.IFramedBlock;
import xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;
import xfacthd.framedblocks.api.block.blockentity.IFramedDoubleBlockEntity;
import xfacthd.framedblocks.api.camo.CamoContainerHelper;
import xfacthd.framedblocks.api.model.data.FramedBlockData;
import xfacthd.framedblocks.common.block.cube.FramedCollapsibleBlock;
import xfacthd.framedblocks.common.block.cube.FramedCollapsibleCopycatBlock;
import xfacthd.framedblocks.common.block.slab.FramedAdjustableDoubleBlock;
import xfacthd.framedblocks.common.blockentity.doubled.slab.FramedAdjustableDoubleBlockEntity;
import xfacthd.framedblocks.common.blockentity.special.FramedCollapsibleBlockEntity;
import xfacthd.framedblocks.common.blockentity.special.FramedCollapsibleCopycatBlockEntity;
import xfacthd.framedblocks.common.blockentity.special.FramedFlowerPotBlockEntity;
import xfacthd.framedblocks.common.data.BlockType;

public final class FramedBlocksClientIntegration {

    private FramedBlocksClientIntegration() {}

    public static void register(ClientIntegrationRegistrar registrar) {
        registrar.preview(FramedBlocksIntegration.CAMO_ID, new CamoPreview(), state -> state.getBlock() instanceof IFramedBlock);
    }

    private static final class CamoPreview implements ClientPreviewIntegration {

        @Override
        public ModelData create(ClientIntegrationContext.CapturedPreview context) {
            return modelData(context.state(), context.payload(FramedBlocksIntegration.CAMO_ID), null);
        }

        @Override
        public ModelData fromWorld(ClientIntegrationContext.WorldPreview context) {
            if (!(context.sourceBlockEntity() instanceof FramedBlockEntity framed)) {
                return ModelData.EMPTY;
            }
            var payload = FramedBlocksIntegration.transformTag(
                    context.sourceState(), FramedBlocksIntegration.payloadOf(framed), context.transform());
            var flower = framed instanceof FramedFlowerPotBlockEntity pot && pot.hasFlowerBlock() ? pot.getFlowerBlock() : null;
            return modelData(context.previewState(), payload, flower);
        }
    }

    private static ModelData modelData(BlockState state, CompoundTag payload, @Nullable Block flower) {
        if (!(state.getBlock() instanceof IFramedBlock framedBlock)) {
            return ModelData.EMPTY;
        }
        boolean reinforced = payload.getBoolean(FramedBlocksIntegration.REINFORCED);
        var first = framedData(payload, FramedBlocksIntegration.CAMO, reinforced, false);
        var builder = ModelData.builder().with(FramedBlockData.PROPERTY, first);
        var block = state.getBlock();
        if (framedBlock.getBlockType().isDoubleBlock()) {
            var left = ModelData.builder().with(FramedBlockData.PROPERTY, first);
            var right = ModelData.builder()
                    .with(FramedBlockData.PROPERTY, framedData(payload, FramedBlocksIntegration.CAMO_TWO, reinforced, true));
            if (block instanceof FramedAdjustableDoubleBlock && payload.contains(FramedBlocksIntegration.FIRST_HEIGHT)) {
                int height = payload.getInt(FramedBlocksIntegration.FIRST_HEIGHT);
                var type = framedBlock.getBlockType();
                if (type == BlockType.FRAMED_ADJ_DOUBLE_COPYCAT_SLAB || type == BlockType.FRAMED_ADJ_DOUBLE_COPYCAT_PANEL) {
                    left.with(FramedCollapsibleCopycatBlockEntity.OFFSETS,
                            FramedAdjustableDoubleBlockEntity.getPackedOffsetsCopycat(state, height, false));
                    right.with(FramedCollapsibleCopycatBlockEntity.OFFSETS,
                            FramedAdjustableDoubleBlockEntity.getPackedOffsetsCopycat(state, height, true));
                } else {
                    left.with(FramedCollapsibleBlockEntity.OFFSETS,
                            FramedAdjustableDoubleBlockEntity.getPackedOffsetsStandard(state, height, false));
                    right.with(FramedCollapsibleBlockEntity.OFFSETS,
                            FramedAdjustableDoubleBlockEntity.getPackedOffsetsStandard(state, height, true));
                }
            }
            builder.with(IFramedDoubleBlockEntity.DATA_ONE, left.build()).with(IFramedDoubleBlockEntity.DATA_TWO, right.build());
        } else if (block instanceof FramedCollapsibleBlock && payload.contains(FramedBlocksIntegration.OFFSETS)) {
            builder.with(FramedCollapsibleBlockEntity.OFFSETS, payload.getInt(FramedBlocksIntegration.OFFSETS));
        } else if (block instanceof FramedCollapsibleCopycatBlock && payload.contains(FramedBlocksIntegration.OFFSETS)) {
            builder.with(FramedCollapsibleCopycatBlockEntity.OFFSETS, payload.getInt(FramedBlocksIntegration.OFFSETS));
        }
        if (flower != null) {
            builder.with(FramedFlowerPotBlockEntity.FLOWER_BLOCK, flower);
        }
        return builder.build();
    }

    private static FramedBlockData framedData(CompoundTag payload, String key, boolean reinforced, boolean altModel) {
        var content = CamoContainerHelper.readFromDisk(payload.getCompound(key)).getContent();
        return new FramedBlockData(content, FramedBlockData.NO_CULLED_FACES, altModel, reinforced);
    }
}
