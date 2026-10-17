package lu.kolja.matter_manipulator.client.preview;

import net.neoforged.neoforge.model.data.ModelData;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

final class PreviewContentLevel implements BlockAndTintGetter {
    private final PreviewContent content;
    private final BlockAndTintGetter world;
    private final BlockPos tintOrigin;
    private final BlockPos entityPos;
    private final BlockEntity entity;
    private final BlockPos dataPos;
    private final ModelData data;

    PreviewContentLevel(PreviewContent content, BlockAndTintGetter world, BlockPos tintOrigin) {
        this(content, world, tintOrigin, null, null, null, ModelData.EMPTY);
    }

    private PreviewContentLevel(PreviewContent content, BlockAndTintGetter world, BlockPos tintOrigin,
            BlockPos entityPos, BlockEntity entity, BlockPos dataPos, ModelData data) {
        this.content = content;
        this.world = world;
        this.tintOrigin = tintOrigin;
        this.entityPos = entityPos;
        this.entity = entity;
        this.dataPos = dataPos;
        this.data = data;
    }

    PreviewContentLevel withBlockEntity(BlockPos pos, BlockEntity blockEntity) {
        return new PreviewContentLevel(content, world, tintOrigin, pos.immutable(), blockEntity, dataPos, data);
    }

    PreviewContentLevel withModelData(BlockPos pos, ModelData modelData) {
        return new PreviewContentLevel(content, world, tintOrigin, entityPos, entity, pos.immutable(), modelData);
    }

    @Override
    public ModelData getModelData(BlockPos pos) {
        if (dataPos != null && dataPos.equals(pos)) {
            return data;
        }
        return entityPos != null && entityPos.equals(pos) ? entity.getModelData() : ModelData.EMPTY;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        var state = content.state(pos.getX(), pos.getY(), pos.getZ());
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return entityPos != null && entityPos.equals(pos) ? entity : null;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return CardinalLighting.DEFAULT;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkness) {
        return 15;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return world.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return world.getBlockTint(pos.offset(tintOrigin), resolver);
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getMinY() {
        return world.getMinY();
    }
}
