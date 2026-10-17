package lu.kolja.matter_manipulator.common.state;

import lu.kolja.matter_manipulator.common.build.PlanStats;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.api.BlockSnapshot;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public record CapturedBlock(BlockStateSpec state, CompoundTag blockEntity, CompoundTag compat) {

    private static final String TAG_STATE = "state";
    private static final String TAG_BLOCK_ENTITY = "block_entity";
    private static final String TAG_COMPAT = "compat";

    public CapturedBlock {
        blockEntity = blockEntity == null ? new CompoundTag() : blockEntity.copy();
        compat = compat == null ? new CompoundTag() : compat.copy();
    }

    public static CapturedBlock simple(BlockState state) {
        return new CapturedBlock(BlockStateSpec.fromState(state), new CompoundTag(), new CompoundTag());
    }

    public BlockSnapshot snapshot() {
        return new BlockSnapshot(state.resolve(), blockEntity, compat);
    }

    public boolean hasBlockEntity() {
        return !blockEntity.isEmpty();
    }

    public boolean hasCompat() {
        return !compat.isEmpty();
    }

    public boolean compatEquals(CompoundTag other) {
        return compat.equals(other);
    }

    @Override
    public CompoundTag blockEntity() {
        return blockEntity.copy();
    }

    @Override
    public CompoundTag compat() {
        return compat.copy();
    }

    public Optional<CapturedBlock> transformed(ToolTransform transform) {
        PlanStats.count(PlanStats.Counter.BLOCK_TRANSFORMS);
        var sourceState = state.resolve();
        long stateStarted = PlanStats.start();
        var transformedState = IntegrationDispatcher.transformStateApproximately(sourceState, transform);
        PlanStats.end(PlanStats.Section.TRANSFORM_STATE, stateStarted);
        if (transformedState.isEmpty()) {
            return Optional.empty();
        }
        long compatStarted = PlanStats.start();
        var transformedCompat = IntegrationDispatcher.transform(sourceState, compat, transform);
        PlanStats.end(PlanStats.Section.TRANSFORM_COMPAT, compatStarted);
        long blockEntityStarted = PlanStats.start();
        var transformedBlockEntity = blockEntity.isEmpty() || transform.isIdentity()
                ? blockEntity
                : IntegrationDispatcher.transformBlockEntity(sourceState, blockEntity, transform);
        PlanStats.end(PlanStats.Section.TRANSFORM_BLOCK_ENTITY, blockEntityStarted);
        return Optional.of(new CapturedBlock(
                BlockStateSpec.fromState(transformedState.get()),
                transformedBlockEntity,
                transformedCompat
        ));
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.put(TAG_STATE, state.save());
        tag.put(TAG_BLOCK_ENTITY, blockEntity.copy());
        tag.put(TAG_COMPAT, compat.copy());
        return tag;
    }

    public static Optional<CapturedBlock> fromTag(CompoundTag tag) {
        if (!tag.contains(TAG_STATE)) {
            return Optional.empty();
        }
        var state = BlockStateSpec.fromTag(tag.getCompound(TAG_STATE));
        return state.map(blockStateSpec -> new CapturedBlock(
                blockStateSpec,
                tag.getCompound(TAG_BLOCK_ENTITY),
                tag.getCompound(TAG_COMPAT)
        ));
    }
}
