package lu.kolja.matter_manipulator.api.client;

import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.api.BlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class ClientIntegrationContext {

    private ClientIntegrationContext() {}

    public record CapturedPreview(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable BlockSnapshot captured,
            Set<Direction> lineDirections,
            PreviewDetail detail
    ) {
        public CapturedPreview {
            lineDirections = Set.copyOf(lineDirections);
        }

        public CompoundTag payload(ResourceLocation id) {
            return captured == null ? new CompoundTag() : captured.payload(id);
        }
    }

    public record WorldPreview(
            Level level,
            BlockPos sourcePos,
            BlockState sourceState,
            BlockPos previewPos,
            BlockState previewState,
            DirectionTransform transform,
            PreviewDetail detail
    ) {
        @Nullable
        public BlockEntity sourceBlockEntity() {
            return level.getBlockEntity(sourcePos);
        }
    }

    /**
     * {@code view} holds the preview blocks at preview-local positions, and {@code level} only supplies tint and
     * shade. COPY and MOVE blocks carry a live {@code sourcePos}, templates and EXCHANGE targets {@code captured}.
     */
    public record StaticPreview(
            Level level,
            BlockAndTintGetter view,
            BlockPos pos,
            BlockState state,
            @Nullable BlockSnapshot captured,
            @Nullable BlockPos sourcePos,
            DirectionTransform transform,
            Set<Direction> lineDirections,
            PreviewDetail detail
    ) {
        public StaticPreview {
            lineDirections = Set.copyOf(lineDirections);
        }

        public CompoundTag payload(ResourceLocation id) {
            return captured == null ? new CompoundTag() : captured.payload(id);
        }

        @Nullable
        public BlockEntity sourceBlockEntity() {
            return sourcePos == null ? null : level.getBlockEntity(sourcePos);
        }
    }
}
