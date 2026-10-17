package lu.kolja.matter_manipulator.api;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface BlockIntegration {

    default CompoundTag capture(IntegrationContext.Capture context) {
        return new CompoundTag();
    }

    default CompoundTag captureExchange(IntegrationContext.Exchange context) {
        return new CompoundTag();
    }

    default ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
        return ApplyResult.REJECTED_UNCHANGED;
    }

    /**
     * Whether {@link #apply} can turn {@code existing} into {@code desired} in place. If it can't, core replaces
     * the block. Must not access the world.
     */
    default boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
        return false;
    }

    /**
     * Other blocks whose saved state {@link #apply} changes. Core checks break protection on each before anything
     * mutates. Report unloaded positions without loading them, and don't mutate the world.
     */
    default Set<BlockPos> additionalChangedPositions(IntegrationContext.SideEffects context, CompoundTag payload) {
        return Set.of();
    }

    /** Must not access inventories or mutate the world. */
    default PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
        return PlacementPreparationPlan.unchanged(payload);
    }

    default CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
        return payload.copy();
    }

    /**
     * Returns the owned copy without world-local values such as network IDs, or empty to leave it out of blueprints.
     * Players can edit blueprint files, so {@link #apply} must never trust a payload. Must not access the world.
     */
    default CompoundTag portable(IntegrationContext.Placement context, CompoundTag payload) {
        return payload;
    }

    /**
     * Returns the state to place, or {@code null} to refuse. {@code transformed} is core's own result and may be
     * {@code null}. Must not access the world.
     */
    @Nullable
    default BlockState transformState(IntegrationContext.Transform context, @Nullable BlockState transformed) {
        return transformed;
    }

    /**
     * Remaps positions in an owned copy of moved block-entity NBT, before {@link #transformBlockEntity} runs.
     * Must not access the world. Throwing refuses the whole group before anything mutates.
     */
    default CompoundTag relocateBlockEntity(IntegrationContext.Relocation context, CompoundTag blockEntity) {
        return blockEntity;
    }

    /** Core caches the answer, so it must depend only on the state. */
    default boolean relocatesBlockEntity(BlockState state) {
        return false;
    }

    /** Receives an owned copy and is never called for copy templates. Must not access the world. */
    default CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
        return blockEntity;
    }

    /** {@code false} skips the {@link #transformBlockEntity} copy. Cached, so it must depend only on the state. */
    default boolean transformsBlockEntity(BlockState state) {
        return true;
    }

    /** Must depend only on the state. Core rejects two handlers opting in for one state. Throwing counts as true. */
    default boolean createsTransferredBlockEntity(BlockState state) {
        return false;
    }

    /**
     * Must not touch the world. Returning {@code null} or a block entity for another position rolls the group
     * back, and so does throwing.
     */
    @Nullable
    default BlockEntity createTransferredBlockEntity(IntegrationContext.Transfer context, CompoundTag blockEntity) {
        return null;
    }

    /** Runs in placement order after a move group settles, including on rollback. Exceptions are only reported. */
    default void afterTransfer(IntegrationContext.Transferred context) {}

    /**
     * Why the block must not be removed, or {@code null}. A MOVE can still take it. Also asked on the client, so
     * it must depend only on the state. Throwing refuses.
     */
    @Nullable
    default Component removalRefusal(BlockState state) {
        return null;
    }

    /**
     * Why the block must not be moved, or {@code null}. Asked while planning, and a refusal drops the whole MOVE
     * group. Must not mutate the world. Throwing refuses.
     */
    @Nullable
    default Component moveRefusal(IntegrationContext.Capture context) {
        return null;
    }

    /**
     * Positions that must move together with this block, all or nothing. Positions outside the move are ignored,
     * so refuse those through {@link #moveRefusal}. Must not mutate the world.
     */
    default Set<BlockPos> moveGroupPeers(IntegrationContext.Capture context) {
        return Set.of();
    }

    default void beforeRemove(IntegrationContext.Removal context) {}

    /**
     * Whether {@code drop}, a copy, is the only thing holding the block's contents. If so, even creative players
     * get it back. Settings don't count as contents. Must not mutate the world.
     */
    default boolean dropCarriesContents(IntegrationContext.Removal context, ItemStack drop) {
        return false;
    }

    default PlacementRequirements placementRequirements(IntegrationContext.Placement context, CompoundTag payload) {
        return PlacementRequirements.defaults();
    }

    enum ApplyResult {
        APPLIED,
        /** Nothing was installed, and the caller refunds every prepared resource. */
        REJECTED_UNCHANGED,
        /** Some state may be installed. The handler has already refunded the prepared resources it didn't use. */
        PARTIAL_RESOURCES_SETTLED
    }
}
