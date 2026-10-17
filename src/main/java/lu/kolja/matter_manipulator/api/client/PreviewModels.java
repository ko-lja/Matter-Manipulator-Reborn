package lu.kolja.matter_manipulator.api.client;

import lu.kolja.matter_manipulator.client.preview.PreviewDebugStats;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.model.data.ModelData;
import net.neoforged.neoforge.model.data.ModelProperty;

import java.util.Map;
import java.util.HashMap;

/** Helpers for models that need a substitute preview block entity or render world. */
public final class PreviewModels {
    public static final ModelProperty<BlockAndTintGetter> RENDER_LEVEL = new ModelProperty<>();

    /** Optional immutable value keys for opaque properties. Each must cover every rendering input of its property. */
    public static final ModelProperty<Map<ModelProperty<?>, Object>> PROPERTY_KEYS = new ModelProperty<>();

    /** Key for a stateless renderer or model. Mutable rendering state needs a value key instead. */
    public record IdentityKey(Object value) {
        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey key && value == key.value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }

    public static ModelData withPropertyKey(ModelData data, ModelProperty<?> property, Object valueKey) {
        var keys = new HashMap<ModelProperty<?>, Object>();
        var existing = data.get(PROPERTY_KEYS);
        if (existing != null) {
            keys.putAll(existing);
        }
        keys.put(property, valueKey);
        return data.derive().with(PROPERTY_KEYS, Map.copyOf(keys)).build();
    }

    private PreviewModels() {}

    public static ModelData blockModelData(
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            ModelData baseData
    ) {
        PreviewDebugStats.modelDataBuilt();
        var data = level.getModelData(pos);
        return data.getProperties().isEmpty() ? baseData : data;
    }

    /** Holds a single preview block and its detached block entity. The delegate only supplies shade, light and tint. */
    public record SingleBlockPreviewLevel(
            BlockAndTintGetter delegate,
            BlockPos previewPos,
            BlockState previewState,
            BlockEntity previewBlockEntity
    ) implements BlockAndTintGetter {

        @Override
        public CardinalLighting cardinalLighting() {
            return delegate.cardinalLighting();
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return delegate.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return delegate.getBlockTint(pos, colorResolver);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return previewPos.equals(pos) ? previewBlockEntity : null;
        }

        @Override
        public ModelData getModelData(BlockPos pos) {
            return previewPos.equals(pos) && previewBlockEntity != null ? previewBlockEntity.getModelData() : ModelData.EMPTY;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return previewPos.equals(pos) ? previewState : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return delegate.getHeight();
        }

        @Override
        public int getMinY() {
            return delegate.getMinY();
        }
    }
}
