package lu.kolja.matter_manipulator.client.integration.framedblocks;

import lu.kolja.matter_manipulator.api.client.ClientIntegrationContext;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;
import lu.kolja.matter_manipulator.api.client.ClientPreviewIntegration;
import lu.kolja.matter_manipulator.common.integration.framedblocks.FramedBlocksIntegration;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.TriState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import io.github.xfacthd.framedblocks.api.block.IFramedBlock;
import io.github.xfacthd.framedblocks.api.block.IFramedDoubleBlock;
import io.github.xfacthd.framedblocks.api.block.blockentity.IFramedBlockEntity;
import io.github.xfacthd.framedblocks.api.model.data.AbstractFramedBlockData;
import io.github.xfacthd.framedblocks.api.model.data.FramedBlockData;
import io.github.xfacthd.framedblocks.api.model.data.FramedDoubleBlockData;
import io.github.xfacthd.framedblocks.common.block.cube.FramedCollapsibleBlock;
import io.github.xfacthd.framedblocks.common.block.cube.FramedCollapsibleCopycatBlock;
import io.github.xfacthd.framedblocks.common.block.slab.FramedAdjustableDoubleBlock;
import io.github.xfacthd.framedblocks.common.blockentity.PackedCollapsibleBlockOffsets;
import io.github.xfacthd.framedblocks.common.blockentity.doubled.slab.FramedAdjustableDoubleBlockEntity;
import io.github.xfacthd.framedblocks.common.blockentity.special.FramedFlowerPotBlockEntity;
import io.github.xfacthd.framedblocks.common.data.BlockType;

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
            if (!(context.sourceBlockEntity() instanceof IFramedBlockEntity framed)) {
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
        boolean reinforced = payload.getBooleanOr(FramedBlocksIntegration.REINFORCED, false);
        var first = framedData(state, payload, FramedBlocksIntegration.CAMO, reinforced, false);
        var builder = ModelData.builder();
        var block = state.getBlock();
        if (block instanceof IFramedDoubleBlock doubleBlock) {
            var parts = doubleBlock.getCache(state).getParts();
            var second = framedData(state, payload, FramedBlocksIntegration.CAMO_TWO, reinforced, true);
            builder.with(AbstractFramedBlockData.PROPERTY, new FramedDoubleBlockData(parts, first, second));
            if (block instanceof FramedAdjustableDoubleBlock && payload.contains(FramedBlocksIntegration.FIRST_HEIGHT)) {
                int height = payload.getIntOr(FramedBlocksIntegration.FIRST_HEIGHT, 0);
                var type = framedBlock.getBlockType();
                FramedAdjustableDoubleBlockEntity.OffsetPacker packer =
                        type == BlockType.FRAMED_ADJ_DOUBLE_COPYCAT_SLAB || type == BlockType.FRAMED_ADJ_DOUBLE_COPYCAT_PANEL
                                ? FramedAdjustableDoubleBlockEntity::getPackedOffsetsCopycat
                                : FramedAdjustableDoubleBlockEntity::getPackedOffsetsStandard;
                builder.with(PackedCollapsibleBlockOffsets.PROPERTY, packer.packDouble(state, height));
            }
        } else {
            builder.with(AbstractFramedBlockData.PROPERTY, first);
            if ((block instanceof FramedCollapsibleBlock || block instanceof FramedCollapsibleCopycatBlock)
                    && payload.contains(FramedBlocksIntegration.OFFSETS)) {
                builder.with(PackedCollapsibleBlockOffsets.PROPERTY,
                        new PackedCollapsibleBlockOffsets.Single(payload.getIntOr(FramedBlocksIntegration.OFFSETS, 0)));
            }
        }
        if (flower != null) {
            builder.with(FramedFlowerPotBlockEntity.FLOWER_BLOCK, flower);
        }
        return builder.build();
    }

    private static FramedBlockData framedData(BlockState state, CompoundTag payload, String key, boolean reinforced,
            boolean secondPart) {
        var camo = FramedBlocksIntegration.camo(payload, key);
        return new FramedBlockData(state, camo, (byte) 0, secondPart, reinforced, false, TriState.DEFAULT, null, null);
    }
}
