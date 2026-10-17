package lu.kolja.matter_manipulator.common.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.common.integration.IntegrationApplyReport;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.common.integration.PreparedPlacement;
import lu.kolja.matter_manipulator.common.integration.StorageIntegrationException;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import lu.kolja.matter_manipulator.common.util.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class WorldOperations {

    private static final int TRANSFER_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_MOVE_BY_PISTON;

    private WorldOperations() {}

    public static Result apply(
            ServerPlayer player,
            ServerLevel level,
            ItemStack tool,
            BlockChange change,
            RemoveMode removeMode
    ) {
        return apply(player, level, tool, change, removeMode, decide(player, level, tool, change, removeMode));
    }

    static Result apply(
            ServerPlayer player,
            ServerLevel level,
            ItemStack tool,
            BlockChange change,
            RemoveMode removeMode,
            @Nullable PlacementDecision decided
    ) {
        if (decided != null) {
            return change.kind() == BlockChange.Kind.REMOVE
                    ? removeTarget(player, tool, level, change, decided)
                    : place(player, tool, level, change, decided);
        }
        var untouchable = touchRefusal(player, level, change.pos());
        if (untouchable != null) {
            return Result.skipped(untouchable);
        }
        return switch (change.kind()) {
            case CONFIGURE -> configure(player, tool, level, change);
            case MOVE -> moveGroup(player, level, tool, List.of(change), removeMode);
            case REMOVE, PLACE, PLACE_CAPTURED -> throw new IllegalArgumentException("A " + change.kind() + " needs a decision");
        };
    }

    @Nullable
    static PlacementDecision decide(
            ServerPlayer player,
            ServerLevel level,
            ItemStack tool,
            BlockChange change,
            RemoveMode removeMode
    ) {
        var kind = change.kind();
        if (kind != BlockChange.Kind.PLACE && kind != BlockChange.Kind.PLACE_CAPTURED && kind != BlockChange.Kind.REMOVE) {
            return null;
        }
        var untouchable = touchRefusal(player, level, change.pos());
        if (untouchable != null) {
            return PlacementDecision.skip(untouchable);
        }
        return PlacementDecision.of(level, player, change, removeMode, tierCanRemove(tool));
    }

    public static boolean canTouch(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return touchRefusal(player, level, pos) == null;
    }

    @Nullable
    static SkipReason touchRefusal(ServerPlayer player, ServerLevel level, BlockPos pos) {
        PlanStats.count(PlanStats.Counter.TOUCH_CHECKS);
        if (!player.mayBuild() || !level.getWorldBorder().isWithinBounds(pos)) {
            return SkipReason.NOT_PERMITTED;
        }
        if (!level.hasChunkAt(pos)) {
            return SkipReason.UNLOADED;
        }
        return level.mayInteract(player, pos) ? null : SkipReason.NOT_PERMITTED;
    }

    static boolean mayBreak(ServerPlayer player, ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isAir()
                || !NeoForge.EVENT_BUS.post(new BreakBlockEvent(level, pos, state, player)).isCanceled();
    }

    static boolean mayPlace(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState placed) {
        var replaced = BlockSnapshot.create(level.dimension(), level, pos);
        var placedAgainst = level.getBlockState(pos.below());
        return !NeoForge.EVENT_BUS.post(new PendingPlaceEvent(replaced, placedAgainst, player, placed)).isCanceled();
    }

    @Nullable
    private static SkipReason sideEffectRefusal(ServerPlayer player, ServerLevel level, Set<BlockPos> positions) {
        for (var pos : positions) {
            var untouchable = touchRefusal(player, level, pos);
            if (untouchable != null) {
                return untouchable;
            }
            if (!mayBreak(player, level, pos)) {
                return SkipReason.PROTECTED;
            }
        }
        return null;
    }

    private static final class PendingPlaceEvent extends BlockEvent.EntityPlaceEvent {
        private final BlockState placed;

        PendingPlaceEvent(BlockSnapshot replaced, BlockState placedAgainst, ServerPlayer player, BlockState placed) {
            super(replaced, placedAgainst, player);
            this.placed = placed;
        }

        @Override
        public BlockState getPlacedBlock() {
            return placed;
        }

        @Override
        public BlockState getState() {
            return placed;
        }
    }

    static boolean tierCanRemove(ItemStack tool) {
        return tool.getItem() instanceof ManipulatorItem item && item.tier().canRemove();
    }

    private static Result place(
            ServerPlayer player,
            ItemStack tool,
            ServerLevel level,
            BlockChange change,
            PlacementDecision decision
    ) {
        if (decision.skipped()) {
            return Result.skipped(decision.reason(), decision.detail());
        }
        if (decision.action() == PlacementDecision.Action.NOOP) {
            return Result.unchanged();
        }
        var pos = change.pos();
        var captured = change.captured();
        if (decision.action() == PlacementDecision.Action.RECONFIGURE) {
            var live = IntegrationDispatcher.capture(level, pos, level.getBlockState(pos));
            return reconfigure(player, tool, level, pos, live, IntegrationDispatcher.reconfiguration(live, captured.compat()));
        }
        var purpose = change.placementPurpose();
        var target = change.target().resolve();
        boolean needsRemoval = decision.action() == PlacementDecision.Action.REPLACE
                && (!PlacementDecision.Existing.at(level, player, pos, target).replaceable() || level.getBlockEntity(pos) != null);

        var cost = PlacementCost.resolved(change, PlacementCost.INTEGRATIONS).cost();
        if (!cost.placeable()) {
            return Result.skipped(SkipReason.UNSUPPORTED_COST);
        }
        var companion = cost instanceof BlockCost.Companion found ? found : null;
        if (companion != null && !companion.hasPrimary(level, pos)) {
            return Result.skipped(SkipReason.COMPANION_MISSING);
        }

        var sideEffects = captured == null
                ? Set.<BlockPos>of()
                : IntegrationDispatcher.additionalChangedPositions(level, pos, target, captured.compat(), purpose);
        if (sideEffects == null) {
            return Result.skipped(SkipReason.CONFIGURATION_REFUSED);
        }

        var required = PlacementCost.consumedRequirements(cost, purpose);
        var paid = InventoryUtil.consumeForPlacement(player, tool, required);
        if (paid == null) {
            return new Result(Outcome.SKIPPED, SkipReason.MISSING_ITEMS, required, false);
        }
        var refusal = needsRemoval && !mayBreak(player, level, pos) || !mayPlace(player, level, pos, target)
                ? SkipReason.PROTECTED
                : sideEffectRefusal(player, level, sideEffects);
        if (refusal != null) {
            InventoryUtil.refundPlacement(player, tool, paid);
            return Result.skipped(refusal);
        }

        PreparedPlacement prepared;
        try {
            prepared = captured != null && purpose.preparesIntegrations()
                    ? IntegrationDispatcher.preparePlacement(player, tool, target, captured, purpose)
                    : null;
        } catch (StorageIntegrationException exception) {
            InventoryUtil.refundAfterStorageFailure(player, tool, paid, exception);
            throw exception;
        }

        if (needsRemoval && !remove(player, tool, level, pos, true)) {
            InventoryUtil.refundPlacement(player, tool, paid);
            refundPrepared(player, tool, prepared);
            return Result.skipped(SkipReason.WORLD_REFUSED);
        }
        if (companion != null && !companion.hasPrimary(level, pos)) {
            InventoryUtil.refundPlacement(player, tool, paid);
            refundPrepared(player, tool, prepared);
            return Result.skipped(SkipReason.COMPANION_MISSING);
        }

        boolean placed = level.setBlock(pos, target, Block.UPDATE_ALL);
        if (!placed) {
            InventoryUtil.refundPlacement(player, tool, paid);
            refundPrepared(player, tool, prepared);
            return Result.skipped(SkipReason.WORLD_REFUSED);
        }

        var missing = new ArrayList<ItemRequirement>();
        var warnings = new ArrayList<Warning>();
        if (prepared != null) {
            missing.addAll(prepared.missingRequirements());
        }
        if (captured != null) {
            var toApply = prepared == null
                    ? captured
                    : new CapturedBlock(captured.state(), captured.blockEntity(), prepared.payloads());
            var applyReport = applyCaptured(level, pos, player, toApply, stack -> {
                InventoryUtil.giveOrDrop(player, tool, stack);
                missing.add(ItemRequirement.exact(stack));
            }, purpose);
            if (purpose.consumesItems() && applyReport.rejectedEntirely() && removeRejected(level, pos, target)) {
                InventoryUtil.refundPlacement(player, tool, paid);
                refundPrepared(player, tool, prepared);
                return Result.skipped(SkipReason.INTEGRATION_REJECTED, applyReport.rejectionReason());
            }
            applyReport.warnings().forEach(key -> warnings.add(new Warning(key, pos)));
            if (prepared != null) {
                for (var id : applyReport.rejectedUnchanged()) {
                    for (var stack : prepared.consumedByIntegration().getOrDefault(id, List.of())) {
                        InventoryUtil.giveOrDrop(player, tool, stack.copy());
                        missing.add(ItemRequirement.exact(stack));
                    }
                }
            }
        }
        syncCaptured(level, pos);
        return new Result(Outcome.APPLIED, null, missing, false, null, warnings);
    }

    private static Result configure(ServerPlayer player, ItemStack tool, ServerLevel level, BlockChange change) {
        var captured = change.captured();
        var target = change.target().tryResolve();
        if (!captured.hasCompat() || target.isEmpty()) {
            return Result.skipped(SkipReason.WORLD_REFUSED);
        }
        if (!level.getBlockState(change.pos()).is(target.get().getBlock())) {
            return Result.skipped(SkipReason.WORLD_CHANGED);
        }
        var live = IntegrationDispatcher.capture(level, change.pos(), level.getBlockState(change.pos()));
        return reconfigure(player, tool, level, change.pos(), live, captured.compat());
    }

    private static Result reconfigure(
            ServerPlayer player,
            ItemStack tool,
            ServerLevel level,
            BlockPos pos,
            CompoundTag live,
            CompoundTag payloads
    ) {
        if (!IntegrationDispatcher.reconfiguration(live, payloads).isEmpty()) {
            var sideEffects = IntegrationDispatcher.additionalChangedPositions(
                    level, pos, level.getBlockState(pos), payloads, PlacementPurpose.RECONFIGURE);
            if (sideEffects == null) {
                return Result.skipped(SkipReason.CONFIGURATION_REFUSED);
            }
            var refusal = mayBreak(player, level, pos) ? sideEffectRefusal(player, level, sideEffects) : SkipReason.PROTECTED;
            if (refusal != null) {
                return Result.skipped(refusal);
            }
        }
        var report = IntegrationDispatcher.apply(
                level,
                pos,
                player,
                payloads,
                stack -> InventoryUtil.giveOrDrop(player, tool, stack),
                PlacementPurpose.RECONFIGURE
        );
        syncCaptured(level, pos);
        return report.successfulFor(PlacementPurpose.RECONFIGURE)
                ? Result.reconfigured()
                : Result.skipped(SkipReason.CONFIGURATION_REFUSED);
    }

    public static Result moveGroup(
            ServerPlayer player,
            ServerLevel level,
            ItemStack tool,
            List<BlockChange> moves,
            RemoveMode removeMode
    ) {
        var prepared = new PreparedMove(moves, removeMode);
        prepared.prepare(player, level, 0, PreparationBudget.UNLIMITED.slice(1));
        return commitMove(player, level, prepared, 0);
    }

    static Result commitMove(ServerPlayer player, ServerLevel level, PreparedMove prepared, int tick) {
        if (prepared.refusal() != null) {
            return Result.skipped(prepared.refusal().reason(), prepared.refusal().detail());
        }
        var moves = prepared.moves();
        if (!prepared.checkedIn(tick)) {
            long checkStarted = PlanStats.start();
            try {
                for (var change : moves) {
                    var refusal = moveRefusal(player, level, change, prepared.sources(), prepared.destinations(),
                            prepared.removeMode());
                    if (refusal != null) {
                        return Result.skipped(refusal.reason(), refusal.detail());
                    }
                }
            } finally {
                PlanStats.end(PlanStats.Section.MOVE_CHECK, checkStarted);
            }
        }
        PreparedMove.Captured captured;
        try {
            captured = prepared.capture(level);
        } catch (RuntimeException | LinkageError exception) {
            MatterManipulator.LOGGER.error("Could not capture live move state at {}", moves.get(0).pos(), exception);
            return Result.skipped(SkipReason.WORLD_REFUSED);
        }

        var originals = captured.originals();
        var placements = captured.placements();
        long protectionStarted = PlanStats.start();
        try {
            for (var original : originals) {
                if (!mayBreak(player, level, original.pos())) {
                    return Result.skipped(SkipReason.PROTECTED);
                }
            }
            for (var placement : placements) {
                if (!mayPlace(player, level, placement.pos(), placement.state())) {
                    return Result.skipped(SkipReason.PROTECTED);
                }
            }
        } finally {
            PlanStats.end(PlanStats.Section.MOVE_PROTECTION, protectionStarted);
        }
        sortForPlacement(level, originals);
        sortForPlacement(level, placements);

        var touched = new LinkedHashSet<BlockPos>();
        placements.forEach(placement -> touched.add(placement.pos()));
        originals.forEach(original -> touched.add(original.pos()));
        var guarded = new LongOpenHashSet(touched.size());
        touched.forEach(pos -> guarded.add(pos.asLong()));

        boolean moved = false;
        var warnings = new ArrayList<Warning>();
        try (var ignored = BlockDropGuard.suppress(level.dimension(), guarded)) {
            moved = transfer(level, player, originals, placements, warnings);
        } catch (RuntimeException | LinkageError exception) {
            MatterManipulator.LOGGER.error("Failed to move block group at {}; rolling it back", moves.get(0).pos(), exception);
        }
        var unrestored = List.<Transfer>of();
        if (!moved) {
            long rollbackStarted = PlanStats.start();
            try (var ignored = BlockDropGuard.suppress(level.dimension(), guarded)) {
                unrestored = rollback(level, player, touched, originals);
            }
            PlanStats.end(PlanStats.Section.MOVE_ROLLBACK, rollbackStarted);
        }
        long neighboursStarted = PlanStats.start();
        updateNeighbours(level, touched);
        PlanStats.end(PlanStats.Section.MOVE_NEIGHBOURS, neighboursStarted);
        for (var settled : moved ? placements : originals) {
            if (settled.blockEntity() != null && !unrestored.contains(settled)
                    && level.getBlockState(settled.pos()).is(settled.state().getBlock())) {
                IntegrationDispatcher.afterTransfer(level, settled.pos(), moved);
            }
        }

        if (!unrestored.isEmpty()) {
            for (var original : unrestored) {
                MatterManipulator.LOGGER.error(
                        "MOVE rollback could not restore {} at {}; the position now holds {}",
                        original.state(),
                        original.pos(),
                        level.getBlockState(original.pos())
                );
            }
            return Result.unrestored();
        }
        return moved
                ? new Result(Outcome.APPLIED, null, List.of(), false, null, warnings)
                : Result.skipped(SkipReason.WORLD_REFUSED);
    }

    @Nullable
    static PlacementDecision moveRefusal(
            ServerPlayer player,
            ServerLevel level,
            BlockChange change,
            LongSet sources,
            LongSet destinations,
            RemoveMode removeMode
    ) {
        var move = change.move();
        var sourcePos = change.pos();
        var destinationPos = move.destination();
        var untouchable = touchRefusal(player, level, sourcePos);
        if (untouchable == null) {
            untouchable = touchRefusal(player, level, destinationPos);
        }
        if (untouchable != null) {
            return PlacementDecision.skip(untouchable);
        }
        if (!level.getBlockState(sourcePos).equals(move.expectedSource())) {
            return PlacementDecision.skip(SkipReason.WORLD_CHANGED);
        }
        if (!BlockCapture.canCapture(level, sourcePos)) {
            return PlacementDecision.skip(SkipReason.POLICY);
        }
        if (sources.contains(destinationPos.asLong())) {
            return null;
        }
        if (!level.getBlockState(destinationPos).equals(move.expectedDestination())) {
            return PlacementDecision.skip(SkipReason.WORLD_CHANGED);
        }
        var existing = PlacementDecision.Existing.at(level, player, destinationPos, move.expectedSource());
        boolean canSwap = move.swapDestination() && !destinations.contains(sourcePos.asLong());
        var decision = PlacementDecision.moveDestination(existing, removeMode, canSwap);
        return decision.skipped() ? decision : null;
    }

    private static void sortForPlacement(ServerLevel level, List<Transfer> transfers) {
        transfers.sort(Comparator
                .comparing((Transfer transfer) -> !transfer.state().isCollisionShapeFullBlock(level, transfer.pos()))
                .thenComparingInt(transfer -> transfer.pos().getY()));
    }

    private static boolean transfer(
            ServerLevel level,
            ServerPlayer player,
            List<Transfer> originals,
            List<Transfer> placements,
            List<Warning> warnings
    ) {
        long clearStarted = PlanStats.start();
        for (var original : originals) {
            if (!removeForTransfer(level, original.pos(), player)) {
                return false;
            }
        }
        PlanStats.end(PlanStats.Section.MOVE_CLEAR, clearStarted);
        for (var placement : placements) {
            if (!placeTransferred(level, player, placement, warnings)) {
                return false;
            }
        }
        long survivalStarted = PlanStats.start();
        try {
            for (var placement : placements) {
                if (!standsAsPlaced(level, placement)) {
                    return false;
                }
            }
        } finally {
            PlanStats.end(PlanStats.Section.MOVE_SURVIVAL, survivalStarted);
        }
        return true;
    }

    private static boolean standsAsPlaced(ServerLevel level, Transfer placement) {
        var pos = placement.pos();
        var current = level.getBlockState(pos);
        return current.is(placement.state().getBlock())
                && current.canSurvive(level, pos)
                && (placement.blockEntity() == null || level.getBlockEntity(pos) != null);
    }

    private static List<Transfer> rollback(
            ServerLevel level,
            ServerPlayer player,
            Collection<BlockPos> touched,
            List<Transfer> originals
    ) {
        for (var pos : touched) {
            try {
                removeForTransfer(level, pos, player);
            } catch (RuntimeException | LinkageError exception) {
                MatterManipulator.LOGGER.error("MOVE rollback could not clear {}", pos, exception);
            }
        }
        var unrestored = new ArrayList<Transfer>();
        for (var original : originals) {
            try {
                if (!placeTransferred(level, player, original, new ArrayList<>())) {
                    unrestored.add(original);
                }
            } catch (RuntimeException | LinkageError exception) {
                MatterManipulator.LOGGER.error("MOVE rollback failed at {}", original.pos(), exception);
                unrestored.add(original);
            }
        }
        return unrestored;
    }

    private static void updateNeighbours(ServerLevel level, Collection<BlockPos> positions) {
        for (var pos : positions) {
            var state = level.getBlockState(pos);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
            state.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_ALL);
            level.updateNeighborsAt(pos, state.getBlock());
        }
    }

    private static boolean placeTransferred(ServerLevel level, ServerPlayer player, Transfer transfer,
                                            List<Warning> warnings) {
        var pos = transfer.pos();
        long placeStarted = PlanStats.start();
        if (!level.setBlock(pos, transfer.state(), TRANSFER_FLAGS)) {
            return false;
        }
        var tag = transfer.blockEntity();
        if (tag != null) {
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            BlockEntity blockEntity;
            if (IntegrationDispatcher.createsTransferredBlockEntity(transfer.state())) {
                blockEntity = IntegrationDispatcher.createTransferredBlockEntity(level, pos, transfer.state(), tag);
                if (blockEntity == null || !blockEntity.getBlockPos().equals(pos)) {
                    return false;
                }
                level.removeBlockEntity(pos);
                level.setBlockEntity(blockEntity);
                if (level.getBlockEntity(pos) != blockEntity) {
                    return false;
                }
            } else {
                blockEntity = level.getBlockEntity(pos);
                if (blockEntity == null) {
                    return false;
                }
                try (var reporter = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), MatterManipulator.LOGGER)) {
                    blockEntity.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), tag));
                }
            }
            blockEntity.setChanged();
        }
        PlanStats.end(PlanStats.Section.MOVE_PLACE, placeStarted);
        if (transfer.compat().isEmpty()) {
            return true;
        }
        long verifyStarted = PlanStats.start();
        try {
            var report = IntegrationDispatcher.apply(level, pos, player, transfer.compat(), ignored -> {},
                    PlacementPurpose.MOVE_TRANSFER);
            report.warnings().forEach(key -> warnings.add(new Warning(key, pos)));
            return report.successfulFor(PlacementPurpose.MOVE_TRANSFER);
        } finally {
            PlanStats.end(PlanStats.Section.MOVE_VERIFY, verifyStarted);
        }
    }

    private static boolean removeForTransfer(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (level.getBlockState(pos).isAir()) {
            return true;
        }
        IntegrationDispatcher.beforeRemove(level, pos, player, false, ignored -> {});
        if (level.getBlockEntity(pos) != null) {
            level.removeBlockEntity(pos);
        }
        return level.setBlock(pos, Blocks.AIR.defaultBlockState(), TRANSFER_FLAGS);
    }

    record Transfer(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntity, CompoundTag compat) {}

    private static IntegrationApplyReport applyCaptured(
            ServerLevel level,
            BlockPos pos,
            ServerPlayer player,
            CapturedBlock captured,
            Consumer<ItemStack> refund,
            PlacementPurpose purpose
    ) {
        if (captured.hasCompat()) {
            return IntegrationDispatcher.apply(
                    level,
                    pos,
                    player,
                    captured.compat(),
                    refund,
                    purpose);
        }
        return IntegrationApplyReport.NONE;
    }

    private static boolean removeRejected(ServerLevel level, BlockPos pos, BlockState placed) {
        if (!level.getBlockState(pos).is(placed.getBlock())) {
            return false;
        }
        try (var ignored = BlockDropGuard.suppress(level.dimension(), LongSet.of(pos.asLong()))) {
            return level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void syncCaptured(ServerLevel level, BlockPos pos) {
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntity.setChanged();
        }
        var state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    private static void refundPrepared(ServerPlayer player, ItemStack tool, PreparedPlacement prepared) {
        if (prepared == null) {
            return;
        }
        prepared.consumedByIntegration().values().forEach(stacks -> InventoryUtil.refundPlacement(player, tool, stacks));
    }

    private static Result removeTarget(
            ServerPlayer player,
            ItemStack tool,
            ServerLevel level,
            BlockChange change,
            PlacementDecision decision
    ) {
        if (decision.skipped()) {
            return Result.skipped(decision.reason(), decision.detail());
        }
        if (decision.action() == PlacementDecision.Action.NOOP) {
            return Result.unchanged();
        }
        if (!mayBreak(player, level, change.pos())) {
            return Result.skipped(SkipReason.PROTECTED);
        }
        return remove(player, tool, level, change.pos(), change.collectDrops())
                ? Result.success()
                : Result.skipped(SkipReason.WORLD_REFUSED);
    }

    private static boolean remove(ServerPlayer player, ItemStack tool, ServerLevel level, BlockPos pos, boolean collectDrops) {
        var state = level.getBlockState(pos);
        if (state.isAir() || !BlockPolicy.canManipulate(level, pos, state)) {
            return false;
        }
        boolean collect = collectDrops && !state.is(Tags.Blocks.ORES);
        var loot = new ArrayList<ItemStack>();
        var contentLoot = new ArrayList<ItemStack>();
        if (collect) {
            for (var drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, ItemStack.EMPTY)) {
                boolean carriesContents = storesItems(drop) || IntegrationDispatcher.dropCarriesContents(level, pos, player, drop);
                (carriesContents ? contentLoot : loot).add(drop.copy());
            }
        }
        var contents = new ArrayList<ItemStack>();
        var guard = collect
                ? BlockDropGuard.capture(level.dimension(), LongSet.of(pos.asLong()))
                : BlockDropGuard.suppress(level.dimension(), LongSet.of(pos.asLong()));
        try {
            IntegrationDispatcher.beforeRemove(level, pos, player, collect, drop -> contents.add(drop.copy()));
            return level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        } finally {
            guard.close();
            contents.addAll(guard.takeCaptured());
            if (!level.getBlockState(pos).is(state.getBlock())) {
                loot.forEach(drop -> InventoryUtil.giveOrDrop(player, tool, drop));
                contentLoot.forEach(drop -> InventoryUtil.returnContents(player, tool, drop));
            }
            contents.forEach(stack -> InventoryUtil.returnContents(player, tool, stack));
        }
    }

    static boolean storesItems(ItemStack drop) {
        if (drop.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems().iterator().hasNext()) {
            return true;
        }
        var data = drop.get(DataComponents.BLOCK_ENTITY_DATA);
        return data != null && !data.copyTagWithoutId().getListOrEmpty("Items").isEmpty();
    }

    public enum Outcome {
        APPLIED,
        UNCHANGED,
        RECONFIGURED,
        SKIPPED
    }

    public record Warning(String translationKey, BlockPos pos) {}

    public record Result(
            Outcome outcome,
            @Nullable SkipReason skipReason,
            List<ItemRequirement> missingRequirements,
            boolean rollbackFailed,
            @Nullable Component skipDetail,
            List<Warning> warnings
    ) {
        public Result {
            missingRequirements = missingRequirements == null ? List.of() : List.copyOf(missingRequirements);
            warnings = List.copyOf(warnings);
        }

        public Result(Outcome outcome, @Nullable SkipReason skipReason, List<ItemRequirement> missingRequirements,
                boolean rollbackFailed) {
            this(outcome, skipReason, missingRequirements, rollbackFailed, null, List.of());
        }

        public boolean applied() {
            return outcome == Outcome.APPLIED;
        }

        public static Result success() {
            return new Result(Outcome.APPLIED, null, List.of(), false);
        }

        public static Result unchanged() {
            return new Result(Outcome.UNCHANGED, null, List.of(), false);
        }

        public static Result reconfigured() {
            return new Result(Outcome.RECONFIGURED, null, List.of(), false);
        }

        public static Result skipped(SkipReason reason) {
            return new Result(Outcome.SKIPPED, reason, List.of(), false);
        }

        public static Result skipped(SkipReason reason, @Nullable Component detail) {
            return new Result(Outcome.SKIPPED, reason, List.of(), false, detail, List.of());
        }

        public static Result unrestored() {
            return new Result(Outcome.SKIPPED, SkipReason.WORLD_REFUSED, List.of(), true);
        }
    }
}
