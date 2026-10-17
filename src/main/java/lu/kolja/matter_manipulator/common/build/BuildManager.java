package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintServerCache;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.energy.StackEnergyStorage;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.item.ToolIdentity;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.CableRouteMode;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.network.GuiStatusPacket;
import lu.kolja.matter_manipulator.common.network.NetworkUtil;
import lu.kolja.matter_manipulator.common.network.ToolStateSyncPacket;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.matter_manipulator.common.util.ChatUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class BuildManager {

    private static final List<BuildTask> TASKS = new LinkedList<>();
    private static long preparedNanosLastTick;
    private static long preparedNanosTotal;
    private static final int MIN_PLAN_INTERVAL_TICKS = 20;
    public static final PlanningThrottle<GuiStatusPacket> PLANNING = new PlanningThrottle<>(MIN_PLAN_INTERVAL_TICKS);
    public static final CompiledPlans COMPILED = new CompiledPlans(() -> 4L * MMConfig.maxQueuedBlocks());
    static final PreparationBudget COMPILE_BUDGET = new PreparationBudget(16_384, 3_000_000L, 6_000_000L);
    private static final Map<UUID, PendingAction> PENDING = new LinkedHashMap<>();
    public static final Map<UUID, BlueprintCapture> SAVES = new LinkedHashMap<>();

    private BuildManager() {}

    public static void start(ServerPlayer player, @Nullable InteractionHand hand) {
        var tool = tool(player, hand);
        if (!(tool.getItem() instanceof ManipulatorItem item) || !admitAction(player)) {
            return;
        }
        var state = ToolState.get(tool);
        var tier = item.tier();
        var mode = state.mode();
        if (!tier.canUse(mode)) {
            ChatUtil.error(player, MMMessage.UNSUPPORTED_TIER.text(mode.displayName()));
            return;
        }
        if (state.removeMode() != RemoveMode.NONE && !tier.canRemove()) {
            ChatUtil.error(player, MMMessage.UNSUPPORTED_TIER.text(ManipulatorMode.GEOMETRY.displayName()));
            return;
        }
        var active = activeStatus(player.getUUID());
        if (active.isPresent()) {
            ChatUtil.error(player, active.get());
            return;
        }
        if (PENDING.containsKey(player.getUUID())) {
            ChatUtil.status(player, MMMessage.ACTION_WAITING.text());
            return;
        }

        var anchors = FloatingPlacement.anchors(player, state);
        var result = BuildPlanner.plan(player, tool, anchors, BuildPlanner.Use.ACTION);
        if (result.compiling()) {
            await(player, true, tool, anchors, result);
            return;
        }
        launch(player, tool, result);
    }

    private static void launch(ServerPlayer player, ItemStack tool, BuildPlanner.Result result) {
        if (result.plan().isEmpty()) {
            ChatUtil.error(player, result.error());
            return;
        }
        var state = ToolState.get(tool);
        var mode = state.mode();
        var tier = ((ManipulatorItem) tool.getItem()).tier();
        var plan = result.plan().get();
        if (TASKS.stream().anyMatch(task -> task.conflictsWith(plan))) {
            ChatUtil.error(player, MMMessage.BUILD_CONFLICT.text());
            return;
        }
        if (mode == ManipulatorMode.MOVE && !player.getAbilities().instabuild) {
            long requiredEnergy = BuildTask.requiredEnergy(plan, tier);
            int availableEnergy = new StackEnergyStorage(tool, tier).getEnergyStored();
            if (requiredEnergy > availableEnergy) {
                ChatUtil.error(player, MMMessage.MOVE_ENERGY.text(
                        requiredEnergy,
                        availableEnergy
                ));
                return;
            }
        }
        TASKS.add(new BuildTask(
                player,
                tool,
                tier,
                plan,
                state.removeMode(),
                state.autoCraftMissing()
        ));
        if (mode == ManipulatorMode.CABLE && state.cableRouteMode() == CableRouteMode.EDGES) {
            state.clearCableWaypoints();
            ToolStateSyncPacket.syncHeld(player);
        }
        ChatUtil.info(player, MMMessage.BUILD_STARTED.text(plan.totalOperations()));
        if (result.hasNotice()) {
            ChatUtil.info(player, result.notice());
        }
    }

    public static void requestMissing(ServerPlayer player, @Nullable InteractionHand hand) {
        var tool = tool(player, hand);
        if (!(tool.getItem() instanceof ManipulatorItem item) || !admitAction(player)) {
            return;
        }
        var state = ToolState.get(tool);
        if (!item.tier().canUse(state.mode())) {
            ChatUtil.error(player, MMMessage.UNSUPPORTED_TIER.text(
                    state.mode().displayName()
            ));
            return;
        }
        var active = activeStatus(player.getUUID());
        if (active.isPresent()) {
            ChatUtil.error(player, active.get());
            return;
        }
        if (PENDING.containsKey(player.getUUID())) {
            ChatUtil.status(player, MMMessage.ACTION_WAITING.text());
            return;
        }
        var anchors = FloatingPlacement.anchors(player, state);
        var result = BuildPlanner.plan(player, tool, anchors, BuildPlanner.Use.ACTION);
        if (result.compiling()) {
            await(player, false, tool, anchors, result);
            return;
        }
        requestMissing(player, tool, result);
    }

    private static void requestMissing(ServerPlayer player, ItemStack tool, BuildPlanner.Result result) {
        if (result.plan().isEmpty()) {
            ChatUtil.error(player, result.error());
            return;
        }
        var tier = ((ManipulatorItem) tool.getItem()).tier();
        var missing = BuildRequirements.missing(
                player,
                tool,
                BuildRequirements.collectApplicable(player, tier, ToolState.get(tool).removeMode(), result.plan().get())
                        .requirements()
        );
        if (missing.isEmpty()) {
            ChatUtil.info(player, MMMessage.CRAFT_NOTHING_MISSING.text());
            return;
        }
        IntegrationDispatcher.requestCrafting(player, tool, missing);
    }

    private static void await(ServerPlayer player, boolean start, ItemStack tool, FloatingPlacement.Anchors anchors,
                              BuildPlanner.Result progress) {
        var key = CompiledPlans.Key.of(player.level().dimension(), ToolState.get(tool), anchors,
                ((ManipulatorItem) tool.getItem()).tier());
        PENDING.put(player.getUUID(), new PendingAction(player, start, ToolIdentity.bind(player, tool), anchors, key));
        ChatUtil.status(player, progress.error());
    }

    record PendingAction(ServerPlayer player, boolean start, UUID toolId, FloatingPlacement.Anchors anchors,
                         CompiledPlans.Key key) {}

    private static void runPendingActions() {
        for (var iterator = PENDING.values().iterator(); iterator.hasNext(); ) {
            var action = iterator.next();
            var player = action.player();
            if (player.isRemoved() || !player.isAlive()) {
                iterator.remove();
                continue;
            }
            var tool = ToolIdentity.carried(player, action.toolId());
            if (!(tool.getItem() instanceof ManipulatorItem item) || ToolIdentity.copies(player, action.toolId()) > 1
                    || !action.key().equals(CompiledPlans.Key.of(player.level().dimension(), ToolState.get(tool),
                    action.anchors(), item.tier()))) {
                iterator.remove();
                ChatUtil.error(player, MMMessage.ACTION_CANCELLED.text());
                continue;
            }
            var result = BuildPlanner.plan(player, tool, action.anchors(), BuildPlanner.Use.ACTION);
            if (result.compiling()) {
                continue;
            }
            iterator.remove();
            if (action.start()) {
                launch(player, tool, result);
            } else {
                requestMissing(player, tool, result);
            }
        }
    }

    public static boolean hasPendingAction(UUID player) {
        return PENDING.containsKey(player);
    }

    public static void saveBlueprint(ServerPlayer player, InteractionHand hand, String name) {
        var tool = player.getItemInHand(hand);
        if (!(tool.getItem() instanceof ManipulatorItem item) || !admitAction(player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        var state = ToolState.get(tool);
        if (state.mode() != ManipulatorMode.COPY) {
            ChatUtil.error(player, MMMessage.BLUEPRINT_COPY_ONLY.text());
            return;
        }
        if (!item.tier().canUse(ManipulatorMode.COPY)) {
            ChatUtil.error(player, MMMessage.UNSUPPORTED_TIER.text(
                    ManipulatorMode.COPY.displayName()));
            return;
        }
        var a = state.coordA();
        var b = state.coordB();
        if (a.isEmpty() || b.isEmpty() || !state.isSameDimension(level)) {
            ChatUtil.error(player, MMMessage.MISSING_COORDS.text());
            return;
        }
        var regionError = BuildPlanner.checkRange(player.blockPosition(), item.tier().range(), List.of(a.get(), b.get()))
                .or(() -> BuildPlanner.checkRegion(a.get(), b.get(), MMConfig.MAX_REGION_VOLUME.get(),
                        BuildPlanner.loadedCheck(level)));
        if (regionError.isPresent()) {
            ChatUtil.error(player, regionError.get());
            return;
        }
        if (SAVES.containsKey(player.getUUID())) {
            ChatUtil.error(player, MMMessage.BLUEPRINT_SAVE_PENDING.text());
            return;
        }
        if (BlueprintServerCache.busy(player.getUUID())) {
            ChatUtil.error(player, MMMessage.BLUEPRINT_BUSY.text());
            return;
        }
        var trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > Blueprint.MAX_NAME_LENGTH) {
            ChatUtil.error(player, MMMessage.BLUEPRINT_NAME_INVALID.text(
                    Blueprint.MAX_NAME_LENGTH));
            return;
        }

        var sourceKey = new CopyCompilation.SourceKey(level.dimension(), BuildPlanner.min(a.get(), b.get()),
                BuildPlanner.max(a.get(), b.get()), IntegrationDispatcher.generation(), null);
        var compilation = COMPILED.compilation(player.getUUID());
        var source = compilation != null && compilation.source().key.equals(sourceKey)
                && compilation.source().read == compilation.source().volume
                ? compilation.source()
                : new CopyCompilation.Source(sourceKey);
        var capture = new BlueprintCapture(player, trimmed, source);
        if (source.read == source.volume) {
            capture.save();
            return;
        }
        SAVES.put(player.getUUID(), capture);
        ChatUtil.status(player, MMMessage.BLUEPRINT_CAPTURING.text(trimmed));
    }

    private static void advanceSaves(MinecraftServer server, PreparationBudget budget) {
        int saving = SAVES.size();
        for (var iterator = SAVES.values().iterator(); iterator.hasNext(); ) {
            var capture = iterator.next();
            var level = server.getLevel(capture.dimension());
            if (capture.player().isRemoved() || level == null || capture.advance(level, budget.slice(saving))) {
                iterator.remove();
            }
        }
    }

    public static void tick() {
        tick(PreparationBudget.DEFAULT);
    }

    public static void tick(PreparationBudget budget) {
        tick(budget, PreparationBudget.Commits.forTick());
    }

    public static void tick(PreparationBudget budget, PreparationBudget.Commits commits) {
        int preparing = (int) TASKS.stream().filter(task -> task.preparing() != null).count();
        var first = TASKS.isEmpty() ? null : TASKS.get(0);
        long nanos = 0L;
        for (var iterator = TASKS.iterator(); iterator.hasNext(); ) {
            var slice = budget.slice(preparing, commits);
            if (iterator.next().tick(slice)) {
                iterator.remove();
            }
            nanos += slice.spent();
        }
        if (TASKS.size() > 1 && TASKS.get(0) == first) {
            TASKS.add(TASKS.remove(0));
        }
        preparedNanosLastTick = nanos;
        preparedNanosTotal += nanos;
    }

    public record PreparationStats(int groupsPreparing, int membersPrepared, int membersRemaining, long nanosLastTick,
                                   long nanosTotal) {}

    public static PreparationStats preparationStats() {
        int groups = 0;
        int prepared = 0;
        int remaining = 0;
        for (var task : TASKS) {
            var move = task.preparing();
            if (move != null) {
                groups++;
                prepared += move.prepared();
                remaining += move.size() - move.prepared();
            }
        }
        return new PreparationStats(groups, prepared, remaining, preparedNanosLastTick, preparedNanosTotal);
    }

    public static long tasks(UUID player) {
        return TASKS.stream().filter(task -> task.isFor(player)).count();
    }

    public static boolean hasTask(UUID player) {
        return TASKS.stream().anyMatch(task -> task.isFor(player));
    }

    public static Optional<Component> activeStatus(UUID player) {
        return TASKS.stream().filter(task -> task.isFor(player)).findFirst().map(BuildTask::status);
    }

    public static void forget(UUID player) {
        TASKS.removeIf(task -> task.isFor(player));
        PENDING.remove(player);
        SAVES.remove(player);
        PLANNING.forget(player);
        COMPILED.forget(player);
    }

    public static void clear() {
        TASKS.clear();
        PENDING.clear();
        SAVES.clear();
        preparedNanosLastTick = 0;
        preparedNanosTotal = 0;
        PLANNING.clear();
        COMPILED.clear();
    }

    public static void forgetCompiledPlans() {
        COMPILED.clear();
    }

    public static boolean admitAction(ServerPlayer player) {
        int now = player.server.getTickCount();
        boolean repeat = PLANNING.repeats(player.getUUID(), now);
        if (PLANNING.admit(player.getUUID(), now)) {
            return true;
        }
        if (!repeat) {
            ChatUtil.status(player, MMMessage.PLANNING_BUSY.text());
        }
        return false;
    }

    public static boolean admitStatus(ServerPlayer player, Consumer<ServerPlayer> retry) {
        return PLANNING.admitStatus(player.getUUID(), player.server.getTickCount(), retry);
    }

    public static void runDeferredPlans(MinecraftServer server) {
        runDeferredPlans(server, COMPILE_BUDGET);
    }

    public static void runDeferredPlans(MinecraftServer server, PreparationBudget compileBudget) {
        COMPILED.expire(server.getTickCount());
        COMPILED.advance(server::getLevel, compileBudget);
        advanceSaves(server, compileBudget);
        runPendingActions();
        COMPILED.expireExact();
        for (var entry : PLANNING.drainReady(server.getTickCount())) {
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                entry.getValue().accept(player);
            }
        }
    }

    private static ItemStack tool(ServerPlayer player, @Nullable InteractionHand hand) {
        return hand == null ? NetworkUtil.heldManipulator(player) : player.getItemInHand(hand);
    }
}
