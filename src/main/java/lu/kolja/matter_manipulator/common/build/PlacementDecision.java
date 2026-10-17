package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public record PlacementDecision(Action action, @Nullable SkipReason reason, @Nullable Component detail) {

    public enum Action {
        PLACE,
        REPLACE,
        REMOVE,
        NOOP,
        RECONFIGURE,
        SKIP
    }

    public enum Configuration {
        SAME,
        RECONFIGURABLE,
        DIFFERS
    }

    private static final PlacementDecision PLACE = new PlacementDecision(Action.PLACE, null);
    private static final PlacementDecision REPLACE = new PlacementDecision(Action.REPLACE, null);
    private static final PlacementDecision REMOVE = new PlacementDecision(Action.REMOVE, null);
    private static final PlacementDecision NOOP = new PlacementDecision(Action.NOOP, null);
    private static final PlacementDecision RECONFIGURE = new PlacementDecision(Action.RECONFIGURE, null);

    private static final Set<String> REPLACEABILITY_FAILURES = ConcurrentHashMap.newKeySet();

    PlacementDecision(Action action, @Nullable SkipReason reason) {
        this(action, reason, null);
    }

    public static PlacementDecision skip(SkipReason reason) {
        return new PlacementDecision(Action.SKIP, reason);
    }

    public static PlacementDecision skip(SkipReason reason, Component detail) {
        return new PlacementDecision(Action.SKIP, reason, detail);
    }

    public boolean skipped() {
        return action == Action.SKIP;
    }

    public record Existing(BlockState state, boolean replaceable, boolean manipulable, @Nullable Component removalRefusal) {

        public Existing(BlockState state, boolean replaceable, boolean manipulable) {
            this(state, replaceable, manipulable, null);
        }

        public static Existing at(Level level, Player player, BlockPos pos, BlockState target) {
            PlanStats.count(PlanStats.Counter.EXISTING_READS);
            var state = level.getBlockState(pos);
            if (state.isAir()) {
                return new Existing(state, true, true);
            }
            boolean replaceable = PlacementDecision.replaceable(state, target, () -> {
                var item = target.isAir() ? ItemStack.EMPTY : new ItemStack(target.getBlock());
                var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                return new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, item, hit);
            });
            return new Existing(state, replaceable, BlockPolicy.canManipulate(level, pos, state),
                    IntegrationDispatcher.removalRefusal(state));
        }
    }

    static boolean replaceable(BlockState state, BlockState target, Supplier<BlockPlaceContext> context) {
        if (!state.canBeReplaced() || state.is(target.getBlock())) {
            return false;
        }
        try {
            return state.canBeReplaced(context.get());
        } catch (RuntimeException exception) {
            var block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (REPLACEABILITY_FAILURES.add(block)) {
                MatterManipulator.LOGGER.error("{} failed to answer canBeReplaced; treating it as not replaceable", block, exception);
            }
            return false;
        }
    }

    public static PlacementDecision place(
            Existing existing,
            BlockState target,
            Configuration configuration,
            RemoveMode removeMode,
            boolean tierCanRemove
    ) {
        if (!BlockPolicy.canManipulate(target)) {
            return skip(SkipReason.POLICY);
        }
        boolean identical = existing.state().equals(target);
        if (identical && configuration == Configuration.SAME) {
            return NOOP;
        }
        if (identical && configuration == Configuration.RECONFIGURABLE) {
            return RECONFIGURE;
        }
        if (existing.state().isAir()) {
            return PLACE;
        }
        return clear(existing, removeMode, tierCanRemove, identical ? SkipReason.CONFIGURATION_DIFFERS : null, REPLACE);
    }

    public static PlacementDecision placeCable(
            Existing existing,
            BlockState target,
            boolean cable,
            RemoveMode removeMode,
            boolean tierCanRemove
    ) {
        return place(existing, target, cable ? Configuration.DIFFERS : Configuration.SAME, removeMode, tierCanRemove);
    }

    public static PlacementDecision exchange(
            Existing existing,
            BlockState expected,
            BlockState target,
            boolean tierCanRemove
    ) {
        if (!BlockPolicy.canManipulate(target)) {
            return skip(SkipReason.POLICY);
        }
        if (!existing.state().equals(expected)) {
            return skip(SkipReason.WORLD_CHANGED);
        }
        if (existing.state().equals(target)) {
            return NOOP;
        }
        if (existing.state().isAir()) {
            return PLACE;
        }
        if (!existing.manipulable()) {
            return skip(SkipReason.POLICY);
        }
        if (!tierCanRemove) {
            return skip(SkipReason.TIER_CANNOT_REMOVE);
        }
        if (existing.removalRefusal() != null) {
            return skip(SkipReason.REMOVAL_REFUSED, existing.removalRefusal());
        }
        return target.isAir() ? REMOVE : REPLACE;
    }

    public static PlacementDecision remove(Existing existing, RemoveMode removeMode, boolean tierCanRemove) {
        if (existing.state().isAir()) {
            return NOOP;
        }
        return clear(existing, removeMode, tierCanRemove, null, REMOVE);
    }

    public static PlacementDecision moveDestination(Existing existing, RemoveMode removeMode, boolean canSwap) {
        if (existing.state().isAir()) {
            return PLACE;
        }
        if (!existing.manipulable()) {
            return skip(SkipReason.POLICY);
        }
        if (existing.removalRefusal() != null && !canSwap) {
            return skip(SkipReason.REMOVAL_REFUSED, existing.removalRefusal());
        }
        if (removeMode == RemoveMode.NONE) {
            return skip(SkipReason.REMOVE_MODE_NONE);
        }
        if (existing.replaceable()) {
            return REPLACE;
        }
        if (removeMode == RemoveMode.REPLACEABLE) {
            return skip(SkipReason.NOT_REPLACEABLE);
        }
        return canSwap ? REPLACE : skip(SkipReason.OBSTRUCTED);
    }

    private static PlacementDecision clear(
            Existing existing,
            RemoveMode removeMode,
            boolean tierCanRemove,
            @Nullable SkipReason modeReason,
            PlacementDecision allowed
    ) {
        if (!existing.manipulable()) {
            return skip(SkipReason.POLICY);
        }
        if (removeMode == RemoveMode.NONE) {
            return skip(modeReason != null ? modeReason : SkipReason.REMOVE_MODE_NONE);
        }
        if (removeMode == RemoveMode.REPLACEABLE && !existing.replaceable()) {
            return skip(modeReason != null ? modeReason : SkipReason.NOT_REPLACEABLE);
        }
        if (!tierCanRemove) {
            return skip(SkipReason.TIER_CANNOT_REMOVE);
        }
        if (existing.removalRefusal() != null) {
            return skip(SkipReason.REMOVAL_REFUSED, existing.removalRefusal());
        }
        return allowed;
    }

    public static PlacementDecision of(
            ServerLevel level,
            ServerPlayer player,
            BlockChange change,
            RemoveMode removeMode,
            boolean tierCanRemove
    ) {
        PlanStats.count(PlanStats.Counter.DECISIONS);
        var pos = change.pos();
        if (change.kind() == BlockChange.Kind.REMOVE) {
            var air = Blocks.AIR.defaultBlockState();
            var existing = Existing.at(level, player, pos, air);
            return change.exchanged() != null
                    ? exchange(existing, change.exchanged(), air, tierCanRemove)
                    : remove(existing, removeMode, tierCanRemove);
        }
        if (change.kind() != BlockChange.Kind.PLACE && change.kind() != BlockChange.Kind.PLACE_CAPTURED) {
            throw new IllegalArgumentException("No placement decision for " + change.kind());
        }
        var resolved = change.target().tryResolve();
        if (resolved.isEmpty() || resolved.get().isAir()) {
            return skip(SkipReason.UNKNOWN_BLOCK);
        }
        var target = resolved.get();
        var existing = Existing.at(level, player, pos, target);
        if (change.exchanged() != null) {
            return exchange(existing, change.exchanged(), target, tierCanRemove);
        }
        var configuration = existing.state().equals(target)
                ? configuration(level, pos, existing.state(), change.captured())
                : Configuration.DIFFERS;
        return place(existing, target, configuration, removeMode, tierCanRemove);
    }

    private static Configuration configuration(
            ServerLevel level,
            BlockPos pos,
            BlockState existing,
            @Nullable CapturedBlock captured
    ) {
        if (captured == null || !captured.hasBlockEntity() && !captured.hasCompat()) {
            return Configuration.SAME;
        }
        if (captured.hasBlockEntity()) {
            return Configuration.DIFFERS;
        }
        var live = IntegrationDispatcher.capture(level, pos, existing);
        var desired = captured.compat();
        if (live.equals(desired)) {
            return Configuration.SAME;
        }
        return IntegrationDispatcher.canReconfigure(existing, live, desired) ? Configuration.RECONFIGURABLE : Configuration.DIFFERS;
    }
}
