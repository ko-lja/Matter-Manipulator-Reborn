package lu.kolja.matter_manipulator.client.integration.gtceu;

import com.gregtechceu.gtceu.api.block.PipeBlock;
import com.gregtechceu.gtceu.api.blockentity.IPaintable;
import com.gregtechceu.gtceu.api.blockentity.PipeBlockEntity;
import lu.kolja.matter_manipulator.client.preview.PreviewDebugStats;
import lu.kolja.matter_manipulator.api.client.PreviewModels;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationContext;
import lu.kolja.matter_manipulator.api.client.ClientPreviewIntegration;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.common.integration.gtceu.GtceuIntegration;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.api.BlockSnapshot;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashMap;
import java.util.Map;

public final class GtceuClientIntegration {

    private static final String PIPE_CONNECTIONS = "pipe_connections";
    private static final String PIPE_BLOCKED_CONNECTIONS = "pipe_blocked_connections";
    private static final String PAINTING_COLOR = "painting_color";
    private static final Map<SimplePipe, DetachedPipeLevel> SIMPLE_PIPES = new HashMap<>();

    private GtceuClientIntegration() {}

    public static void register(ClientIntegrationRegistrar registrar) {
        registrar.preview(
                GtceuIntegration.PIPE_ID,
                new PipePreviewIntegration(),
                state -> state.getBlock() instanceof PipeBlock<?, ?, ?>
        );
    }

    private static final class PipePreviewIntegration implements ClientPreviewIntegration {

        @Override
        public ModelData create(ClientIntegrationContext.CapturedPreview context) {
            if (!(context.state().getBlock() instanceof PipeBlock<?, ?, ?> pipeBlock)) {
                return ModelData.EMPTY;
            }
            var tag = payload(context.captured());
            var masks = routeMasks(tag, context.lineDirections());
            if (context.detail() != PreviewDetail.FULL || simpleSyntheticPipe(context.captured(), tag)) {
                return simplePipeModelData(pipeBlock, context.state(), masks);
            }
            PreviewDebugStats.fakeBlockEntityCreated();
            PipeBlockEntity<?, ?> pipe = pipeBlock.newBlockEntity(context.pos(), context.state());
            if (pipe == null) {
                return ModelData.EMPTY;
            }
            pipe.setLevel(context.level());
            pipe.setConnections(masks.connections());
            pipe.setBlockedConnections(masks.blockedConnections());
            if (tag.contains(PAINTING_COLOR)) {
                pipe.setPaintingColor(tag.getInt(PAINTING_COLOR));
            }
            var previewLevel = new PreviewModels.SingleBlockPreviewLevel(
                    context.level(),
                    context.pos(),
                    context.state(),
                    pipe
            );
            return pipeModelData(
                    previewLevel,
                    context.pos(),
                    context.state(),
                    ModelData.builder().with(PreviewModels.RENDER_LEVEL, previewLevel).build()
            );
        }

        @Override
        public ModelData fromWorld(ClientIntegrationContext.WorldPreview context) {
            if (!(context.previewState().getBlock() instanceof PipeBlock<?, ?, ?> pipeBlock)
                    || !(context.level().getBlockEntity(context.sourcePos()) instanceof PipeBlockEntity<?, ?> sourcePipe)) {
                return ModelData.EMPTY;
            }
            if (context.detail() != PreviewDetail.FULL) {
                return simplePipeModelData(pipeBlock, context.previewState(), new PipeMasks(
                        transformDirectionMask(sourcePipe.getConnections(), context.transform()),
                        transformDirectionMask(sourcePipe.getBlockedConnections(), context.transform())));
            }
            PreviewDebugStats.fakeBlockEntityCreated();
            PipeBlockEntity<?, ?> previewPipe = pipeBlock.newBlockEntity(context.previewPos(), context.previewState());
            if (previewPipe == null) {
                return ModelData.EMPTY;
            }
            previewPipe.setLevel(context.level());
            previewPipe.setConnections(transformDirectionMask(sourcePipe.getConnections(), context.transform()));
            previewPipe.setBlockedConnections(transformDirectionMask(sourcePipe.getBlockedConnections(), context.transform()));
            previewPipe.setPaintingColor(sourcePipe.getPaintingColor());
            var previewLevel = new PreviewModels.SingleBlockPreviewLevel(
                    context.level(),
                    context.previewPos(),
                    context.previewState(),
                    previewPipe
            );
            return pipeModelData(
                    previewLevel,
                    context.previewPos(),
                    context.previewState(),
                    ModelData.builder().with(PreviewModels.RENDER_LEVEL, previewLevel).build()
            );
        }
    }

    static CompoundTag payload(BlockSnapshot captured) {
        if (captured == null) {
            return new CompoundTag();
        }
        var pipe = captured.payload(GtceuIntegration.PIPE_ID);
        var paint = captured.payload(GtceuIntegration.PAINT_ID);
        if (paint.contains(PAINTING_COLOR)) {
            pipe.putInt(PAINTING_COLOR, paint.getInt(PAINTING_COLOR));
        }
        return pipe;
    }

    static ModelData simplePipeModelData(PipeBlock<?, ?, ?> block, BlockState state, PipeMasks masks) {
        var level = detachedPipe(block, state, masks);
        if (level == null) {
            return ModelData.EMPTY;
        }
        PreviewDebugStats.gtceuModelDataBuilt();
        return PreviewModels.blockModelData(level, BlockPos.ZERO, state, ModelData.EMPTY);
    }

    static DetachedPipeLevel detachedPipe(PipeBlock<?, ?, ?> block, BlockState state, PipeMasks masks) {
        return SIMPLE_PIPES.computeIfAbsent(new SimplePipe(state, masks), key -> {
            PipeBlockEntity<?, ?> pipe = block.newBlockEntity(BlockPos.ZERO, state);
            if (pipe == null) {
                return null;
            }
            PreviewDebugStats.fakeBlockEntityCreated();
            pipe.setConnections(masks.connections());
            pipe.setBlockedConnections(masks.blockedConnections());
            return new DetachedPipeLevel(state, pipe);
        });
    }

    private static boolean simpleSyntheticPipe(BlockSnapshot captured, CompoundTag payload) {
        boolean unpainted = !payload.contains(PAINTING_COLOR)
                || payload.getInt(PAINTING_COLOR) == IPaintable.UNPAINTED_COLOR;
        boolean hasCovers = captured != null && !captured.payload(GtceuIntegration.COVER_ID).isEmpty();
        return unpainted && !hasCovers;
    }

    static PipeMasks routeMasks(CompoundTag tag, Iterable<Direction> directions) {
        int connections = tag.getInt(PIPE_CONNECTIONS);
        int blockedConnections = tag.getInt(PIPE_BLOCKED_CONNECTIONS);
        boolean hasRoute = directions.iterator().hasNext();
        if (hasRoute) {
            connections = 0;
        }
        for (var direction : directions) {
            int mask = 1 << direction.ordinal();
            connections |= mask;
            blockedConnections &= ~mask;
        }
        return new PipeMasks(connections, blockedConnections);
    }

    private static ModelData pipeModelData(
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            ModelData baseData
    ) {
        PreviewDebugStats.gtceuModelDataBuilt();
        var modelData = PreviewModels.blockModelData(level, pos, state, baseData);
        if (modelData.has(PreviewModels.RENDER_LEVEL)) {
            return modelData;
        }
        return modelData.derive().with(PreviewModels.RENDER_LEVEL, level).build();
    }

    private static int transformDirectionMask(int mask, DirectionTransform transform) {
        int transformed = 0;
        for (var direction : Direction.values()) {
            if ((mask & (1 << direction.ordinal())) != 0) {
                transformed |= 1 << transform.transform(direction).ordinal();
            }
        }
        return transformed;
    }

    record PipeMasks(int connections, int blockedConnections) {}

    private record SimplePipe(BlockState state, PipeMasks masks) {}

    record DetachedPipeLevel(BlockState state, BlockEntity blockEntity) implements BlockAndTintGetter {

        @Override
        public float getShade(Direction direction, boolean shade) {
            return 1.0F;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            throw new UnsupportedOperationException("Pipe model lookup level has no lighting");
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return -1;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return BlockPos.ZERO.equals(pos) ? blockEntity : null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return BlockPos.ZERO.equals(pos) ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }
    }
}
