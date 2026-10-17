package lu.kolja.matter_manipulator.common.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.energy.StackEnergyStorage;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.integration.StorageIntegrationException;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.item.ToolIdentity;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.matter_manipulator.common.util.InventoryUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class BuildTask {

    static final int MAX_STEPS_PER_TICK = 512;
    private static final int BLOCK = 20;
    private static final long SLOW_COMMIT_NANOS = 250_000_000L;
    private static final int MAX_WARNING_POSITIONS = 8;

    private final ServerPlayer player;
    private final ManipulatorMode mode;
    private final UUID toolId;
    private final ManipulatorTier tier;
    private final BuildPlan plan;
    private final RemoveMode removeMode;
    private final BlockPos operationOrigin;
    private final LongSet affectedPositions;
    private final boolean autoCraftMissing;
    private int index;
    private int finalizerIndex;
    private int changed;
    private int skipped;
    private int unchanged;
    private final Map<SkipReason, Integer> skipReasons = new EnumMap<>(SkipReason.class);
    private final Map<Component, Integer> explainedSkips = new LinkedHashMap<>();
    private final Map<String, List<BlockPos>> warnings = new LinkedHashMap<>();
    private int budget;
    private final List<ItemRequirement> missingRequirements = new ArrayList<>();
    private int ticks;
    private int lastChangeTick;
    @Nullable
    private PreparedMove preparing;
    private Component lastResult = MMMessage.BUILD_RESULT_NONE.text();

    public BuildTask(
            ServerPlayer player,
            ItemStack tool,
            ManipulatorTier tier,
            BuildPlan plan,
            RemoveMode removeMode,
            boolean autoCraftMissing
    ) {
        this.player = player;
        mode = ToolState.get(tool).mode();
        toolId = ToolIdentity.bind(player, tool);
        this.tier = tier;
        this.plan = plan;
        this.removeMode = removeMode;
        this.autoCraftMissing = autoCraftMissing;
        operationOrigin = player.blockPosition();
        affectedPositions = affectedPositions(plan);
    }

    public boolean tick() {
        return tick(PreparationBudget.DEFAULT.slice(1));
    }

    public boolean tick(PreparationBudget.Slice slice) {
        if (player.isRemoved()) {
            return true;
        }
        if (!player.isAlive()) {
            return stop(MMMessage.BUILD_PLAYER_DIED);
        }
        if (ToolIdentity.copies(player, toolId) > 1) {
            return stop(MMMessage.BUILD_TOOL_DUPLICATE);
        }
        var tool = ToolIdentity.carried(player, toolId);
        if (!(tool.getItem() instanceof ManipulatorItem item) || item.tier() != tier) {
            return stop(MMMessage.BUILD_TOOL_LOST);
        }
        var plannedLevel = plannedLevel(plan.dimension(), player.level().dimension(), player.server::getLevel);
        if (plannedLevel.isEmpty()) {
            return stop(MMMessage.BUILD_DIMENSION_LEFT);
        }
        var level = plannedLevel.get();

        ticks++;
        int perTick = ToolState.placementSpeed(tool, tier);
        budget = Math.min(budget + perTick, perTick + BLOCK);
        int steps = 0;
        try (var storageFailures = InventoryUtil.trackStorageFailures()) {
            while (steps < MAX_STEPS_PER_TICK && (preparing != null || hasWork() && (budget >= BLOCK || nextIsMove()))) {
                boolean finalizer = preparing == null && index >= plan.changes().size();
                List<BlockChange> batch;
                if (preparing != null) {
                    batch = preparing.moves();
                } else {
                    batch = nextBatch();
                    steps += batch.size();
                    if (!batch.stream().allMatch(this::withinRange)) {
                        skip(SkipReason.OUT_OF_RANGE, null, batch.size());
                        continue;
                    }
                }
                boolean move = batch.get(0).kind() == BlockChange.Kind.MOVE;
                long cost = 0L;
                long precheckStarted = PlanStats.start();
                var decided = move ? null : WorldOperations.decide(player, level, tool, batch.get(0), removeMode);
                if (placesOrRemoves(decided)) {
                    for (var change : batch) {
                        cost += operationCost(change);
                    }
                }
                PlanStats.end(PlanStats.Section.EXECUTION_PRECHECK, precheckStarted);
                var energy = new StackEnergyStorage(tool, tier);
                if (cost > 0 && !player.getAbilities().instabuild && !energy.consume(cost, true)) {
                    player.displayClientMessage(MMMessage.NO_ENERGY.text(), true);
                    return true;
                }
                if (move && preparing == null) {
                    preparing = new PreparedMove(batch, removeMode);
                }
                if (move && (!preparing.prepare(player, level, ticks, slice) || budget < BLOCK
                        || !slice.commits().allow(batch.size(), preparing.weight()))) {
                    break;
                }
                WorldOperations.Result result;
                long applyStarted = PlanStats.start();
                long commitStarted = System.nanoTime();
                try {
                    result = move
                            ? WorldOperations.commitMove(player, level, preparing, ticks)
                            : WorldOperations.apply(player, level, tool, batch.get(0), removeMode, decided);
                } catch (StorageIntegrationException exception) {
                    reportStorageFailure(exception);
                    InventoryUtil.reportUncertain(player, exception);
                    return true;
                }
                PlanStats.end(PlanStats.Section.EXECUTION_APPLY, applyStarted);
                if (move) {
                    long commitNanos = System.nanoTime() - commitStarted;
                    slice.commits().add(batch.size(), preparing.weight(), commitNanos);
                    if (commitNanos > SLOW_COMMIT_NANOS) {
                        MatterManipulator.LOGGER.warn("A MOVE group of {} connected blocks (commit weight {}) at {} took "
                                        + "{} ms to commit in one tick; it cannot be split without breaking what joins it",
                                batch.size(), preparing.weight(), batch.get(0).pos().toShortString(),
                                commitNanos / 1_000_000);
                    }
                    preparing = null;
                    budget -= BLOCK * batch.size();
                }
                if (result.rollbackFailed()) {
                    player.displayClientMessage(MMMessage.MOVE_ROLLBACK_FAILED.text(
                            batch.get(0).pos().toShortString(),
                            changed,
                            skipped
                    ), false);
                    return true;
                }
                missingRequirements.addAll(result.missingRequirements());
                if (result.applied() || result.outcome() == WorldOperations.Outcome.RECONFIGURED) {
                    if (cost > 0 && !player.getAbilities().instabuild) {
                        energy.consume(cost, false);
                    }
                    if (!finalizer) {
                        changed += batch.size();
                    }
                    if (!move) {
                        budget -= BLOCK * batch.size();
                    }
                    for (var warning : result.warnings()) {
                        warnings.computeIfAbsent(warning.translationKey(), key -> new ArrayList<>()).add(warning.pos());
                    }
                    lastChangeTick = ticks;
                    lastResult = MMMessage.BUILD_RESULT_CHANGED.text();
                } else if (result.outcome() == WorldOperations.Outcome.UNCHANGED) {
                    unchanged += batch.size();
                    lastResult = MMMessage.BUILD_RESULT_UNCHANGED.text();
                } else {
                    skip(result.skipReason(), result.skipDetail(), batch.size());
                }
                if (storageFailures.first() != null) {
                    reportStorageFailure(storageFailures.first());
                    return true;
                }
            }
        }

        if (!hasWork() && preparing == null) {
            player.displayClientMessage(
                    MMMessage.BUILD_COMPLETE.text(changed, skipped),
                    false
            );
            if (unchanged > 0) {
                player.displayClientMessage(
                        MMMessage.BUILD_UNCHANGED.text(unchanged),
                        false
                );
            }
            if (!skipReasons.isEmpty() || !explainedSkips.isEmpty()) {
                player.displayClientMessage(
                        MMMessage.BUILD_SKIPPED.text(
                                SkipReason.describe(skipReasons, explainedSkips)),
                        false
                );
            }
            reportWarnings();
            var missing = BuildRequirements.missing(player, tool, missingRequirements);
            if (!missing.isEmpty()) {
                var summary = missing.stream()
                        .map(demand -> demand.amount() + "x " + demand.stack().getHoverName().getString())
                        .collect(Collectors.joining(", "));
                player.displayClientMessage(
                        MMMessage.COPY_MISSING_COMPONENTS.text(summary),
                        false
                );
                if (autoCraftMissing) {
                    IntegrationDispatcher.requestCrafting(player, tool, missing);
                }
            }
            return true;
        }
        return false;
    }

    private static boolean placesOrRemoves(@Nullable PlacementDecision decided) {
        return decided == null || decided.action() != PlacementDecision.Action.NOOP
                && decided.action() != PlacementDecision.Action.RECONFIGURE;
    }

    public int unchanged() {
        return unchanged;
    }

    public int skipped() {
        return skipped;
    }

    public Map<String, List<BlockPos>> warnings() {
        return warnings;
    }

    private boolean stop(MMMessage reason) {
        player.displayClientMessage(reason.text(changed, skipped), false);
        reportWarnings();
        return true;
    }

    private void reportWarnings() {
        warnings.forEach((key, positions) -> {
            var listed = positions.stream().distinct().limit(MAX_WARNING_POSITIONS).map(BlockPos::toShortString)
                    .collect(Collectors.joining("; "));
            var more = positions.stream().distinct().count() > MAX_WARNING_POSITIONS ? "; ..." : "";
            player.displayClientMessage(Component.translatable(key, positions.size(), listed + more), false);
        });
    }

    private void skip(SkipReason reason, @Nullable Component detail, int count) {
        skipped += count;
        Component described;
        if (detail == null) {
            described = reason.text();
            skipReasons.merge(reason, count, Integer::sum);
        } else {
            described = SkipReason.explained(detail);
            explainedSkips.merge(described, count, Integer::sum);
        }
        lastResult = MMMessage.BUILD_RESULT_SKIPPED.text(described);
    }

    public Component status() {
        var tool = ToolIdentity.carried(player, toolId);
        int perTick = tool.getItem() instanceof ManipulatorItem
                ? ToolState.placementSpeed(tool, tier)
                : tier.blocksPerSecond();
        Component state;
        if (preparing != null) {
            int groups = plan.changes().get(plan.changes().size() - 1).move().group() + 1;
            state = preparing.done()
                    ? MMMessage.BUILD_STATE_COMMITTING.text(
                            preparing.size(), preparing.weight(), preparing.group() + 1, groups)
                    : MMMessage.BUILD_STATE_PREPARING.text(
                            preparing.prepared(), preparing.size(), preparing.weight(), preparing.group() + 1, groups);
        } else if (budget < 0) {
            state = MMMessage.BUILD_STATE_WAITING.text(
                    (BLOCK - budget + perTick - 1) / perTick - 1);
        } else {
            state = MMMessage.BUILD_STATE_WORKING.text();
        }
        int remaining = plan.changes().size() - index + plan.finalizers().size() - finalizerIndex
                + (preparing == null ? 0 : preparing.size());
        return MMMessage.BUILD_ACTIVE_STATUS.text(
                mode.displayName(), state, remaining, plan.totalOperations(), changed, skipped, unchanged,
                lastChangeTick == 0 ? ticks : ticks - lastChangeTick, lastResult);
    }

    private void reportStorageFailure(StorageIntegrationException failure) {
        player.displayClientMessage(MMMessage.STORAGE_FAILURE.text(
                failure.integrationId().toString(),
                changed,
                skipped
        ), false);
    }

    public boolean isFor(UUID playerId) {
        return player.getUUID().equals(playerId);
    }

    public boolean conflictsWith(BuildPlan otherPlan) {
        return conflicts(plan.dimension(), affectedPositions, otherPlan);
    }

    static boolean conflicts(ResourceKey<Level> dimension, LongSet positions, BuildPlan otherPlan) {
        if (!dimension.equals(otherPlan.dimension())) {
            return false;
        }
        var otherPositions = affectedPositions(otherPlan);
        var smaller = otherPositions.size() < positions.size() ? otherPositions : positions;
        var larger = smaller == positions ? otherPositions : positions;
        for (var iterator = smaller.iterator(); iterator.hasNext(); ) {
            if (larger.contains(iterator.nextLong())) {
                return true;
            }
        }
        return false;
    }

    static Optional<ServerLevel> plannedLevel(
            ResourceKey<Level> planned,
            ResourceKey<Level> playerDimension,
            Function<ResourceKey<Level>, ServerLevel> lookup
    ) {
        if (!planned.equals(playerDimension)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lookup.apply(planned));
    }

    @Nullable
    public PreparedMove preparing() {
        return preparing == null || preparing.done() ? null : preparing;
    }

    private boolean nextIsMove() {
        return index < plan.changes().size() && plan.changes().get(index).kind() == BlockChange.Kind.MOVE;
    }

    private boolean hasWork() {
        return index < plan.changes().size() || finalizerIndex < plan.finalizers().size();
    }

    private List<BlockChange> nextBatch() {
        if (index >= plan.changes().size()) {
            return List.of(plan.finalizers().get(finalizerIndex++));
        }
        var first = plan.changes().get(index++);
        if (first.kind() != BlockChange.Kind.MOVE) {
            return List.of(first);
        }
        var batch = new ArrayList<BlockChange>();
        batch.add(first);
        while (index < plan.changes().size()) {
            var next = plan.changes().get(index);
            if (next.kind() != BlockChange.Kind.MOVE || next.move().group() != first.move().group()) {
                break;
            }
            batch.add(next);
            index++;
        }
        return batch;
    }

    private boolean withinRange(BlockChange change) {
        if (tier.range() < 0) {
            return true;
        }
        double rangeSquared = (double) tier.range() * tier.range();
        if (operationOrigin.distSqr(change.pos()) > rangeSquared) {
            return false;
        }
        return change.kind() != BlockChange.Kind.MOVE
                || change.move() != null && operationOrigin.distSqr(change.move().destination()) <= rangeSquared;
    }

    private int operationCost(BlockChange change) {
        return operationCost(change, tier);
    }

    public static long requiredEnergy(BuildPlan plan, ManipulatorTier tier) {
        long total = 0L;
        for (var change : plan.changes()) {
            total += operationCost(change, tier);
        }
        return total;
    }

    private static int operationCost(BlockChange change, ManipulatorTier tier) {
        int base = tier.fePerBlock();
        return switch (change.kind()) {
            case REMOVE -> base == 0 ? 0 : Math.max(1, base / 2);
            case PLACE -> base;
            case PLACE_CAPTURED -> base * 2;
            case CONFIGURE -> 0;
            case MOVE -> base * 2 + (base == 0 ? 0 : Math.max(1, base / 2));
        };
    }

    static LongSet affectedPositions(BuildPlan plan) {
        var positions = new LongOpenHashSet(plan.totalOperations());
        for (var change : plan.changes()) {
            positions.add(change.pos().asLong());
            if (change.kind() == BlockChange.Kind.MOVE && change.move() != null) {
                positions.add(change.move().destination().asLong());
            }
        }
        for (var finalizer : plan.finalizers()) {
            positions.add(finalizer.pos().asLong());
        }
        return positions;
    }
}
