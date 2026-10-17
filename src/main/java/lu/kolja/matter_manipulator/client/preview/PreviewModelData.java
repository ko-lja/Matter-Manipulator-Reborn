package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.integration.ClientIntegrationDispatcher;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.api.client.PreviewModels;
import lu.kolja.matter_manipulator.common.build.CableRoute;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

public final class PreviewModelData {

    private static final Set<ResourceLocation> FAILED_MODEL_DATA = new HashSet<>();

    private PreviewModelData() {}

    static ModelData resolve(Level level, PreviewVisual visual, BlockPos localPos, BlockState state,
            PreviewDetail detail) {
        try {
            if (visual instanceof PreviewVisual.Captured captured) {
                return ClientIntegrationDispatcher.create(level, localPos, state, captured.captured(),
                        CableRoute.directions(captured.lineMask()), detail);
            }
            if (visual instanceof PreviewVisual.FromSource source) {
                return fromSource(level, source, localPos, state, detail);
            }
            if (visual instanceof PreviewVisual.Exchange exchange) {
                return exchange(level, exchange, localPos, state, detail);
            }
            return ModelData.EMPTY;
        } catch (RuntimeException | LinkageError exception) {
            return failed(state, exception);
        }
    }

    static ClientIntegrationDispatcher.StaticRender renderStatic(Level level, BlockAndTintGetter view, PreviewVisual visual,
            BlockPos localPos, BlockState state, PreviewDetail detail, PoseStack pose,
            Function<RenderType, VertexConsumer> buffers) {
        if (visual instanceof PreviewVisual.FromSource source) {
            return ClientIntegrationDispatcher.renderStatic(level, view, localPos, state, null, source.sourcePos(),
                    source.transform(), Set.of(), detail, pose, buffers);
        }
        if (visual instanceof PreviewVisual.Captured slot) {
            return ClientIntegrationDispatcher.renderStatic(level, view, localPos, state, slot.captured(), null,
                    ToolTransform.IDENTITY, CableRoute.directions(slot.lineMask()), detail, pose, buffers);
        }
        var captured = visual instanceof PreviewVisual.Exchange exchange ? exchange.target() : null;
        return ClientIntegrationDispatcher.renderStatic(level, view, localPos, state, captured, null, ToolTransform.IDENTITY,
                Set.of(), detail, pose, buffers);
    }

    private static ModelData fromSource(Level level, PreviewVisual.FromSource source, BlockPos localPos,
            BlockState state, PreviewDetail detail) {
        var modelData = ClientIntegrationDispatcher.fromWorld(level, source.sourcePos(), source.sourceState(), localPos, state,
                source.transform(), detail);
        if (hasProperties(modelData) || detail != PreviewDetail.FULL) {
            return modelData;
        }
        return PreviewModels.blockModelData(level, source.sourcePos(), source.sourceState(), ModelData.EMPTY);
    }

    private static ModelData exchange(Level level, PreviewVisual.Exchange exchange, BlockPos localPos,
            BlockState state, PreviewDetail detail) {
        var inherited = ClientIntegrationDispatcher.fromWorld(level, exchange.pos(), exchange.sourceState(), localPos, state,
                ToolTransform.IDENTITY, detail);
        if (hasProperties(inherited)) {
            return inherited;
        }
        return ClientIntegrationDispatcher.create(level, localPos, state, exchange.target(), Set.of(), detail);
    }

    public static boolean hasProperties(ModelData modelData) {
        return modelData != null && !modelData.getProperties().isEmpty();
    }

    private static ModelData failed(BlockState state, Throwable exception) {
        var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (FAILED_MODEL_DATA.add(blockId)) {
            MatterManipulator.LOGGER.warn("Falling back to basic preview data for {}", blockId, exception);
        }
        return ModelData.EMPTY;
    }
}
