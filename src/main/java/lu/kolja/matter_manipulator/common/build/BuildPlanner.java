package lu.kolja.matter_manipulator.common.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintServerCache;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.api.CableIntegration;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.BlockSlot;
import lu.kolja.matter_manipulator.common.manipulator.CableRouteMode;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class BuildPlanner {

    private BuildPlanner() {}

    public enum Use {
        STATUS,
        ACTION,
        SYNCHRONOUS
    }

    public static Result plan(ServerPlayer player, ItemStack tool) {
        return plan(player, tool, FloatingPlacement.anchors(player, ToolState.get(tool)), Use.SYNCHRONOUS);
    }

    public static Result plan(ServerPlayer player, ItemStack tool, FloatingPlacement.Anchors anchors, Use use) {
        if (!(player.level() instanceof ServerLevel level)) {
            return Result.error(MMMessage.NOT_ON_SERVER_LEVEL.text());
        }
        var state = ToolState.get(tool);
        if (state.mode() != ManipulatorMode.BLUEPRINT && !state.isSameDimension(level)) {
            return Result.error(MMMessage.MARKS_IN_OTHER_DIMENSION.text());
        }
        if (!(tool.getItem() instanceof ManipulatorItem item)) {
            return Result.error(MMMessage.NOT_A_MANIPULATOR.text());
        }

        var mode = state.mode();
        var marked = new ArrayList<BlockPos>();
        anchors.a().ifPresent(marked::add);
        anchors.b().ifPresent(marked::add);
        anchors.c().ifPresent(marked::add);
        if (mode == ManipulatorMode.CABLE && state.cableRouteMode() == CableRouteMode.EDGES) {
            marked.addAll(state.cableWaypoints());
        }
        var rangeError = checkRange(player.blockPosition(), item.tier().range(), marked);
        if (rangeError.isPresent()) {
            return Result.error(rangeError.get());
        }
        long started = PlanStats.start();
        var result = switch (mode) {
            case GEOMETRY -> planGeometryCompiled(player, level, state, anchors, item.tier());
            case COPY -> planCopy(player, level, state, anchors, item.tier(), use);
            case CABLE -> planCable(level, player, state, anchors);
            case EXCHANGE -> planExchange(level, player, state, anchors, item.tier());
            case MOVE -> planMove(level, player, state, anchors);
            case BLUEPRINT -> planBlueprint(player, level, state, anchors, item.tier(), use);
        };
        PlanStats.end(PlanStats.Section.PLAN, started);
        return result;
    }

    private static Result planGeometryCompiled(
            ServerPlayer player,
            ServerLevel level,
            ToolState state,
            FloatingPlacement.Anchors anchors,
            ManipulatorTier tier
    ) {
        var key = CompiledPlans.Key.of(level.dimension(), state, anchors, tier);
        int now = player.server.getTickCount();
        var cached = BuildManager.COMPILED.get(player.getUUID(), key, now);
        if (cached != null) {
            PlanStats.count(PlanStats.Counter.PLAN_CACHE_HITS);
            return cached;
        }
        PlanStats.count(PlanStats.Counter.PLAN_COMPILES);
        var result = planGeometry(level, state, anchors, tier);
        if (result.plan().isPresent()) {
            BuildManager.COMPILED.put(player.getUUID(), key, result, now);
        }
        return result;
    }

    private static Result planGeometry(
            ServerLevel level,
            ToolState state,
            FloatingPlacement.Anchors anchors,
            ManipulatorTier tier
    ) {
        var a = anchors.a();
        var b = anchors.b();
        if (a.isEmpty() || b.isEmpty()) {
            return Result.missingCoords();
        }
        var changes = new ArrayList<BlockChange>();
        int limit = MMConfig.maxQueuedBlocks();
        var targetChecks = new IdentityHashMap<BlockStateSpec, Optional<Component>>();
        var targetError = new Component[1];
        var slotSpecs = new EnumMap<BlockSlot, Optional<BlockStateSpec>>(BlockSlot.class);
        var slotCaptures = new EnumMap<BlockSlot, Optional<CapturedBlock>>(BlockSlot.class);
        boolean complete = ShapeGenerator.generate(state.shape(), a.get(), b.get(), anchors.c().orElse(null), limit + 1, (pos, slot) -> {
            var spec = slotSpecs.computeIfAbsent(slot,
                    ignored -> state.preferredSlot(slot).or(() -> state.slot(BlockSlot.VOLUME)));
            spec.ifPresent(blockStateSpec -> {
                var error = targetChecks.computeIfAbsent(
                        blockStateSpec,
                        checked -> checkPlacementTarget(checked, tier, state.removeMode())
                );
                if (error.isPresent()) {
                    targetError[0] = error.get();
                    return;
                }
                var captured = slotCaptures.computeIfAbsent(slot, ignored -> state.preferredCapturedSlot(slot));
                changes.add(captured.isEmpty() || blockStateSpec.isAir()
                        ? targetChange(pos, blockStateSpec)
                        : BlockChange.placeCaptured(pos, captured.get(), PlacementPurpose.COPY_TEMPLATE));
            });
        });
        if (targetError[0] != null) {
            return Result.error(targetError[0]);
        }
        if (!complete || changes.size() > limit) {
            return Result.error(MMMessage.SELECTION_OVER_LIMIT.text(limit));
        }
        if (changes.isEmpty()) {
            return Result.error(MMMessage.EMPTY_SLOT.text(BlockSlot.VOLUME.displayName()));
        }
        return capped(level.dimension(), changes);
    }

    @SuppressWarnings("deprecation")
    private static Result planCable(ServerLevel level, ServerPlayer player, ToolState state, FloatingPlacement.Anchors anchors) {
        var cable = state.preferredSlot(BlockSlot.CABLE);
        if (cable.isEmpty()) {
            return Result.error(MMMessage.EMPTY_SLOT.text(BlockSlot.CABLE.displayName()));
        }
        var spec = cable.get();
        var targetError = checkTarget(spec);
        if (targetError.isPresent()) {
            return Result.error(targetError.get());
        }
        if (spec.isAir()) {
            return Result.error(MMMessage.AIR_CABLE.text());
        }
        var target = spec.resolve();
        var changes = new ArrayList<BlockChange>();
        var connectionFinalizers = new ArrayList<BlockChange>();
        var template = state.preferredCapturedSlot(BlockSlot.CABLE).orElse(null);
        CableRoute.Result route;
        if (state.cableRouteMode() == CableRouteMode.EDGES) {
            var waypoints = state.cableWaypoints();
            if (waypoints.size() < 2) {
                return Result.error(MMMessage.CABLE_ROUTE_MISSING.text());
            }
            route = CableRoute.expand(waypoints, MMConfig.maxQueuedBlocks());
            if (!route.complete()) {
                if (route.failure() == CableRoute.Failure.MALFORMED_SEGMENT) {
                    return Result.error(MMMessage.CABLE_ROUTE_NOT_AXIS_ALIGNED.text());
                }
                return Result.error(MMMessage.CABLE_ROUTE_TOO_LARGE.text(
                        MMConfig.maxQueuedBlocks()
                ));
            }
        } else {
            var a = anchors.a();
            var b = anchors.b();
            if (a.isEmpty() || b.isEmpty()) {
                return Result.missingCoords();
            }
            var search = CablePathfinder.find(
                    level,
                    player,
                    a.get(),
                    b.get(),
                    target,
                    template,
                    state.removeMode(),
                    MMConfig.maxQueuedBlocks()
            );
            if (!search.successful()) {
                for (var endpoint : List.of(a.get(), b.get())) {
                    var refusal = IntegrationDispatcher.cableRouteRefusal(level, endpoint, target, template);
                    if (refusal != null) {
                        return Result.error(cableRefusalAt(refusal, endpoint));
                    }
                }
                return Result.error(Component.literal(
                        "No cable path found (" + search.failureReason().description() + ")."));
            }
            route = CableRoute.expand(search.path(), MMConfig.maxQueuedBlocks());
        }
        var loadError = checkRouteLoaded(route.positions(), level::hasChunkAt);
        if (loadError.isPresent()) {
            return Result.error(loadError.get());
        }
        var refused = new LinkedHashMap<Component, Integer>();
        var firstRefused = new HashMap<Component, BlockPos>();
        for (var pos : route.positions()) {
            var lineDirections = CableRoute.directions(route.connectionMask(pos));
            var preparation = IntegrationDispatcher.prepareCable(level, pos, target, lineDirections, template);
            if (IntegrationDispatcher.canReuseCable(level, pos, level.getBlockState(pos), target, template)
                    && state.removeMode() != RemoveMode.ALL) {
                if (preparation.hasIntegrationData()) {
                    connectionFinalizers.add(BlockChange.configure(pos, preparation.captured()));
                }
                continue;
            }
            var refusal = IntegrationDispatcher.cableRouteRefusal(level, pos, target, template);
            if (refusal != null) {
                refused.merge(refusal, 1, Integer::sum);
                firstRefused.putIfAbsent(refusal, pos);
                continue;
            }
            if (!preparation.hasIntegrationData()) {
                changes.add(BlockChange.place(pos, spec));
            } else if (preparation.applyTiming() == CableIntegration.ApplyTiming.AFTER_PATH) {
                changes.add(BlockChange.place(pos, spec));
                connectionFinalizers.add(BlockChange.configure(pos, preparation.captured()));
            } else {
                changes.add(BlockChange.placeCaptured(pos, preparation.captured()));
            }
        }
        var leftOut = new LinkedHashMap<Component, Integer>();
        refused.forEach((refusal, count) ->
                leftOut.put(SkipReason.explained(cableRefusalAt(refusal, firstRefused.get(refusal))), count));
        if (changes.isEmpty() && connectionFinalizers.isEmpty() && !leftOut.isEmpty()) {
            return Result.error(leftOutNotice(leftOut));
        }
        return capped(level.dimension(), changes, connectionFinalizers).withNotice(leftOutNotice(leftOut));
    }

    private static Component cableRefusalAt(Component refusal, BlockPos pos) {
        return MMMessage.CABLE_ROUTE_REFUSED_AT.text(refusal,
                pos.getX(), pos.getY(), pos.getZ());
    }

    private static Result planExchange(
            ServerLevel level,
            ServerPlayer player,
            ToolState state,
            FloatingPlacement.Anchors anchors,
            ManipulatorTier tier
    ) {
        var a = anchors.a();
        var b = anchors.b();
        var target = state.exchangeTarget();
        var whitelist = state.replaceWhitelist();
        if (a.isEmpty() || b.isEmpty()) {
            return Result.missingCoords();
        }
        if (target.isEmpty()) {
            return Result.error(MMMessage.EMPTY_SLOT.text(BlockSlot.EXCHANGE_TARGET.displayName()));
        }
        if (whitelist.isEmpty()) {
            return Result.error(MMMessage.PICK_REPLACE_WHITELIST.text());
        }
        var regionError = checkRegion(a.get(), b.get(), MMConfig.MAX_REGION_VOLUME.get(), loadedCheck(level));
        if (regionError.isPresent()) {
            return Result.error(regionError.get());
        }
        var targetSpec = target.get();
        var targetError = checkTarget(targetSpec).or(() -> checkTarget(whitelist.get()));
        if (targetError.isPresent()) {
            return Result.error(targetError.get());
        }
        if (!tier.canRemove()) {
            return Result.error(MMMessage.EXCHANGE_NEEDS_REMOVE.text());
        }
        var match = whitelist.get().resolve();
        var targetState = targetSpec.resolve();
        var changes = new ArrayList<BlockChange>();
        var leftOut = new LinkedHashMap<Component, Integer>();
        for (var pos : ShapeGenerator.region(a.get(), b.get())) {
            if (!level.getBlockState(pos).equals(match)) {
                continue;
            }
            var decision = PlacementDecision.exchange(
                    PlacementDecision.Existing.at(level, player, pos, targetState),
                    match,
                    targetState,
                    tier.canRemove()
            );
            if (decision.detail() != null) {
                leftOut.merge(SkipReason.explained(decision.detail()), 1, Integer::sum);
                continue;
            }
            if (decision.action() == PlacementDecision.Action.NOOP || decision.skipped()) {
                continue;
            }
            var compat = targetState.isAir()
                    ? new CompoundTag()
                    : IntegrationDispatcher.captureExchange(level, pos, match, targetState);
            if (compat.isEmpty()) {
                changes.add(targetChange(pos, targetSpec).withExchanged(match));
            } else {
                changes.add(BlockChange.placeCaptured(
                        pos,
                        new CapturedBlock(targetSpec, new CompoundTag(), compat)
                ).withExchanged(match));
            }
            if (changes.size() > MMConfig.maxQueuedBlocks()) {
                return Result.error(MMMessage.SELECTION_OVER_LIMIT.text(MMConfig.maxQueuedBlocks()));
            }
        }
        if (changes.isEmpty() && !leftOut.isEmpty()) {
            return Result.error(leftOutNotice(leftOut));
        }
        return capped(level.dimension(), changes).withNotice(leftOutNotice(leftOut));
    }

    private static Result planCopy(
            ServerPlayer player,
            ServerLevel level,
            ToolState state,
            FloatingPlacement.Anchors anchors,
            ManipulatorTier tier,
            Use use
    ) {
        var a = anchors.a();
        var b = anchors.b();
        var c = anchors.c();
        if (a.isEmpty() || b.isEmpty() || c.isEmpty()) {
            return Result.missingCoords();
        }
        var regionError = checkRegion(a.get(), b.get(), MMConfig.MAX_REGION_VOLUME.get(), loadedCheck(level));
        if (regionError.isPresent()) {
            return Result.error(regionError.get());
        }
        var key = CompiledPlans.Key.of(level.dimension(), state, anchors, tier);
        var sourceKey = new CopyCompilation.SourceKey(level.dimension(), min(a.get(), b.get()), max(a.get(), b.get()),
                key.integrations(), null);
        return BuildManager.COMPILED.copy(player.getUUID(), key, level, sourceKey, null, state.transform(), c.get(),
                player.server.getTickCount(), use);
    }

    private static Result planBlueprint(
            ServerPlayer player,
            ServerLevel level,
            ToolState state,
            FloatingPlacement.Anchors anchors,
            ManipulatorTier tier,
            Use use
    ) {
        var ref = state.blueprintRef();
        if (ref.isEmpty()) {
            return Result.error(MMMessage.BLUEPRINT_NONE.text());
        }
        var c = anchors.c();
        if (c.isEmpty()) {
            return Result.missingCoords();
        }
        var blueprint = BlueprintServerCache.require(player, ref.get().hash(), ref.get().size());
        if (blueprint == null) {
            return Result.error(MMMessage.BLUEPRINT_NOT_UPLOADED.text());
        }
        var key = CompiledPlans.Key.of(level.dimension(), state, anchors, tier);
        var sourceKey = new CopyCompilation.SourceKey(level.dimension(), BlockPos.ZERO,
                blueprint.header().size().offset(-1, -1, -1), key.integrations(), blueprint.header().hash());
        return BuildManager.COMPILED.copy(player.getUUID(), key, level, sourceKey, blueprint, state.transform(), c.get(),
                player.server.getTickCount(), use);
    }

    private static Result planMove(ServerLevel level, ServerPlayer player, ToolState state, FloatingPlacement.Anchors anchors) {
        var a = anchors.a();
        var b = anchors.b();
        var c = anchors.c();
        if (a.isEmpty() || b.isEmpty() || c.isEmpty()) {
            return Result.missingCoords();
        }

        var origin = min(a.get(), b.get());
        var sourceMax = max(a.get(), b.get());
        var sourceSize = sourceMax.subtract(origin);
        var destination = c.get();
        var transform = state.transform();
        var destinationMax = destination.offset(transform.transformedSize(sourceSize));
        var destinationMin = min(destination, destinationMax);
        var destinationBoxMax = max(destination, destinationMax);
        var loaded = loadedCheck(level);
        var regionError = checkRegion(origin, sourceMax, MMConfig.MAX_REGION_VOLUME.get(), loaded)
                .or(() -> checkRegion(destination, destinationMax, MMConfig.MAX_REGION_VOLUME.get(), loaded));
        if (regionError.isPresent()) {
            return Result.error(regionError.get());
        }

        var changes = new ArrayList<BlockChange>();
        var linkedSources = new LongOpenHashSet();
        var peers = new HashMap<BlockPos, Set<BlockPos>>();
        var refusals = new HashMap<BlockPos, Component>();
        var leftOut = new LinkedHashMap<Component, Integer>();
        int limit = MMConfig.maxQueuedBlocks();
        var selection = BoundingBox.fromCorners(origin, sourceMax);
        for (var sourcePos : ShapeGenerator.region(a.get(), b.get())) {
            if (!BlockCapture.canCapture(level, sourcePos)) {
                continue;
            }
            var sourceState = level.getBlockState(sourcePos);

            var relative = sourcePos.subtract(origin);
            var transformedRelative = transform.transformRelative(relative, sourceSize);
            var destinationPos = destination.offset(transformedRelative);
            var destinationState = level.getBlockState(destinationPos);
            boolean vacated = regionsOverlap(destinationPos, destinationPos, origin, sourceMax)
                    && BlockCapture.canCapture(level, destinationPos);
            boolean canSwap = false;
            if (!vacated) {
                var existing = PlacementDecision.Existing.at(level, player, destinationPos, sourceState);
                canSwap = state.removeMode() == RemoveMode.ALL && !existing.replaceable()
                        && !regionsOverlap(sourcePos, sourcePos, destinationMin, destinationBoxMax);
                var decision = PlacementDecision.moveDestination(existing, state.removeMode(), canSwap);
                if (decision.reason() == SkipReason.POLICY) {
                    continue;
                }
                if (decision.detail() != null) {
                    leftOut.merge(SkipReason.explained(decision.detail()), 1, Integer::sum);
                    continue;
                }
                if (decision.skipped()) {
                    return Result.error(MMMessage.MOVE_DESTINATION_BLOCKED.text(
                            destinationPos.toShortString(),
                            decision.reason().text()
                    ));
                }
            }
            if (IntegrationDispatcher.transformState(sourceState, transform).isEmpty()) {
                return Result.error(MMMessage.MOVE_ORIENTATION.text(
                        BuiltInRegistries.BLOCK.getKey(sourceState.getBlock()).toString(),
                        sourcePos.toShortString()
                ));
            }
            if (sourceState.getBlock() instanceof FallingBlock
                    || !sourceState.isCollisionShapeFullBlock(level, sourcePos)
                    && (IntegrationDispatcher.hasBlockIntegration(sourceState) || NeighbourDependence.dependsOnNeighbours(sourceState))) {
                linkedSources.add(sourcePos.asLong());
            }
            if (IntegrationDispatcher.hasBlockIntegration(sourceState)) {
                var refusal = IntegrationDispatcher.moveRefusal(level, sourcePos, sourceState, selection, transform);
                if (refusal != null) {
                    refusals.put(sourcePos, refusal);
                }
                var sourcePeers = IntegrationDispatcher.moveGroupPeers(level, sourcePos, sourceState, selection);
                if (!sourcePeers.isEmpty()) {
                    peers.put(sourcePos, sourcePeers);
                }
            }
            changes.add(BlockChange.move(
                    sourcePos,
                    destinationPos,
                    sourceState,
                    destinationState,
                    canSwap,
                    transform,
                    0,
                    selection
            ));
            if (changes.size() > limit) {
                return Result.error(MMMessage.SELECTION_OVER_LIMIT.text(limit));
            }
        }
        if (changes.isEmpty()) {
            return Result.error(leftOut.isEmpty()
                    ? MMMessage.NO_MOVABLE_BLOCKS.text()
                    : leftOutNotice(leftOut));
        }
        int groupLimit = MMConfig.maxAtomicMoveGroup();
        var grouping = MoveGroups.group(changes, linkedSources, peers, refusals, groupLimit);
        grouping.refusedByIntegration().forEach((reason, count) ->
                leftOut.merge(SkipReason.explained(reason), count, Integer::sum));
        var notice = leftOutNotice(leftOut);
        if (grouping.refusedBlocks() > 0) {
            if (!notice.getSiblings().isEmpty()) {
                notice.append(" ");
            }
            notice.append(MMMessage.MOVE_GROUP_TOO_LARGE.text(
                    grouping.refusedBlocks(),
                    grouping.largestRefusedGroup(),
                    groupLimit
            ));
        }
        if (grouping.changes().isEmpty()) {
            return Result.error(notice);
        }
        int group = -1;
        int size = 0;
        int weight = 0;
        int heaviestSize = 0;
        int heaviestWeight = 0;
        for (var change : grouping.changes()) {
            if (change.move().group() != group) {
                group = change.move().group();
                size = 0;
                weight = 0;
            }
            size++;
            weight += PreparedMove.commitWeight(change);
            if (weight > heaviestWeight) {
                heaviestSize = size;
                heaviestWeight = weight;
            }
        }
        if (heaviestWeight > 4 * PreparationBudget.COMMIT_WEIGHT_PER_TICK) {
            if (!notice.getSiblings().isEmpty()) {
                notice.append(" ");
            }
            notice.append(MMMessage.MOVE_GROUP_HEAVY.text(
                    heaviestSize, heaviestWeight, PreparationBudget.COMMIT_WEIGHT_PER_TICK));
        }
        return capped(level.dimension(), grouping.changes()).withNotice(notice);
    }

    private static MutableComponent leftOutNotice(Map<Component, Integer> leftOut) {
        if (leftOut.isEmpty()) {
            return Component.empty();
        }
        int total = leftOut.values().stream().mapToInt(Integer::intValue).sum();
        return MMMessage.REFUSED_LEFT_OUT.text(total,
                SkipReason.describe(Map.of(), leftOut));
    }

    static Optional<Component> checkTarget(BlockStateSpec spec) {
        var resolved = spec.tryResolve();
        if (resolved.isEmpty()) {
            return Optional.of(MMMessage.UNKNOWN_BLOCK.text(spec.displayName()));
        }
        if (!BlockPolicy.canManipulate(resolved.get())) {
            return Optional.of(MMMessage.IMMOVABLE_BLOCK.text(spec.displayName()));
        }
        return Optional.empty();
    }

    static Optional<Component> checkPlacementTarget(BlockStateSpec spec, ManipulatorTier tier, RemoveMode removeMode) {
        return checkTarget(spec).or(() -> spec.isAir() && (!tier.canRemove() || removeMode == RemoveMode.NONE)
                ? Optional.of(MMMessage.AIR_NEEDS_REMOVE.text())
                : Optional.empty());
    }

    static BlockChange targetChange(BlockPos pos, BlockStateSpec spec) {
        return spec.isAir() ? BlockChange.remove(pos) : BlockChange.place(pos, spec);
    }

    static Optional<Component> checkRegion(BlockPos a, BlockPos b, long maxVolume, BiPredicate<BlockPos, BlockPos> loaded) {
        long volume = regionVolume(a, b);
        if (volume > maxVolume) {
            return Optional.of(MMMessage.REGION_TOO_LARGE.text(
                    volume == Long.MAX_VALUE ? ">" + maxVolume : Long.toString(volume),
                    maxVolume
            ));
        }
        if (!loaded.test(min(a, b), max(a, b))) {
            return Optional.of(MMMessage.REGION_NOT_LOADED.text());
        }
        return Optional.empty();
    }

    static Optional<Component> checkRouteLoaded(List<BlockPos> route, Predicate<BlockPos> loaded) {
        long lastChunk = Long.MIN_VALUE;
        for (var pos : route) {
            long chunk = ChunkPos.asLong(pos);
            if (chunk == lastChunk) {
                continue;
            }
            if (!loaded.test(pos)) {
                return Optional.of(MMMessage.CABLE_ROUTE_NOT_LOADED.text());
            }
            lastChunk = chunk;
        }
        return Optional.empty();
    }

    public static long regionVolume(BlockPos a, BlockPos b) {
        long x = Math.abs((long) a.getX() - b.getX()) + 1;
        long y = Math.abs((long) a.getY() - b.getY()) + 1;
        long z = Math.abs((long) a.getZ() - b.getZ()) + 1;
        try {
            return Math.multiplyExact(Math.multiplyExact(x, y), z);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static Optional<Component> checkPasteAnchor(ServerPlayer player, ManipulatorTier tier, ToolState state,
            BlockPos anchor) {
        if (state.mode() == ManipulatorMode.BLUEPRINT) {
            return checkRange(player.blockPosition(), tier.range(), List.of(anchor));
        }
        return checkSelection(player, tier, state.coordA().orElseThrow(), state.coordB().orElseThrow(), List.of(anchor));
    }

    public static Optional<Component> checkSelection(ServerPlayer player, ManipulatorTier tier, BlockPos a, BlockPos b,
            Collection<BlockPos> marks) {
        return checkSelection(player.blockPosition(), tier.range(), MMConfig.MAX_REGION_VOLUME.get(), a, b, marks);
    }

    public static Optional<Component> checkSelection(BlockPos origin, int range, long maxVolume, BlockPos a, BlockPos b,
            Collection<BlockPos> marks) {
        return checkRange(origin, range, marks).or(() -> checkRegion(a, b, maxVolume, (min, max) -> true));
    }

    public static Optional<Component> checkRange(BlockPos origin, int range, Collection<BlockPos> positions) {
        if (range < 0) {
            return Optional.empty();
        }
        double rangeSquared = (double) range * range;
        for (var pos : positions) {
            if (origin.distSqr(pos) > rangeSquared) {
                return Optional.of(MMMessage.OUT_OF_RANGE.text(range));
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("deprecation")
    static BiPredicate<BlockPos, BlockPos> loadedCheck(ServerLevel level) {
        return (min, max) -> level.hasChunksAt(min.getX(), min.getZ(), max.getX(), max.getZ());
    }

    static boolean regionsOverlap(BlockPos firstMin, BlockPos firstMax, BlockPos secondMin, BlockPos secondMax) {
        return firstMin.getX() <= secondMax.getX() && firstMax.getX() >= secondMin.getX()
                && firstMin.getY() <= secondMax.getY() && firstMax.getY() >= secondMin.getY()
                && firstMin.getZ() <= secondMax.getZ() && firstMax.getZ() >= secondMin.getZ();
    }

    private static Result capped(ResourceKey<Level> dimension, List<BlockChange> changes) {
        return capped(dimension, changes, new ArrayList<>());
    }

    private static Result capped(
            ResourceKey<Level> dimension,
            List<BlockChange> changes,
            List<BlockChange> finalizers
    ) {
        long total = changes.size() + (long) finalizers.size();
        if (total > MMConfig.maxQueuedBlocks()) {
            return Result.error(MMMessage.SELECTION_TOO_LARGE.text(total));
        }
        var checked = checkPlacements(changes, false, PlacementCost.INTEGRATIONS);
        if (checked.error().isPresent()) {
            return Result.error(checked.error().get());
        }
        return Result.ok(new BuildPlan(dimension, checked.changes(), finalizers));
    }

    static PlacementCheck checkPlacements(
            List<BlockChange> changes,
            boolean skipUnplaceable,
            PlacementCost.Requirements integrations
    ) {
        long started = PlanStats.start();
        try {
            var checker = new PlacementChecker(skipUnplaceable, integrations, changes.size());
            for (var change : changes) {
                if (!checker.add(change)) {
                    break;
                }
            }
            return checker.finish();
        } finally {
            PlanStats.end(PlanStats.Section.PLAN_PLACEMENT_CHECK, started);
        }
    }

    static final class PlacementChecker {
        private final boolean skipUnplaceable;
        private final PlacementCost.Requirements integrations;
        private final IdentityHashMap<Object, PlacementCost.Resolved> shared = new IdentityHashMap<>();
        private final ArrayList<BlockCost> costs;
        private final ArrayList<BlockChange> kept;
        private final LinkedHashMap<BlockCost.Unsupported, Integer> skipped = new LinkedHashMap<>();
        private boolean hasCompanion;
        @Nullable
        private PlacementCheck refused;

        PlacementChecker(boolean skipUnplaceable, PlacementCost.Requirements integrations, int expected) {
            this.skipUnplaceable = skipUnplaceable;
            this.integrations = integrations;
            costs = new ArrayList<>(expected);
            kept = new ArrayList<>(expected);
        }

        boolean add(BlockChange change) {
            if (!isPlacement(change)) {
                kept.add(change);
                costs.add(null);
                return true;
            }
            var key = change.captured() != null ? change.captured() : change.target();
            var resolved = shared.get(key);
            if (resolved == null || !resolved.validFor(change, integrations)) {
                resolved = PlacementCost.resolved(change, integrations);
                shared.put(key, resolved);
            }
            var cost = resolved.cost();
            if (cost instanceof BlockCost.Unsupported unsupported) {
                if (skipUnplaceable) {
                    skipped.merge(unsupported, 1, Integer::sum);
                    return true;
                }
                refused = PlacementCheck.refused(MMMessage.UNPLACEABLE_BLOCK.text(
                        change.target().displayName(),
                        unsupported.describe()
                ));
                return false;
            }
            hasCompanion |= cost instanceof BlockCost.Companion;
            kept.add(change.cost() == resolved ? change : change.withCost(resolved));
            costs.add(cost);
            return true;
        }

        PlacementCheck finish() {
            if (refused != null) {
                return refused;
            }
            if (!hasCompanion) {
                return new PlacementCheck(kept, Optional.empty(), skipped, 0);
            }

            var targets = new HashMap<BlockPos, BlockState>();
            for (var change : kept) {
                if (isPlacement(change)) {
                    targets.put(change.pos(), change.target().resolve());
                }
            }
            var ordered = new ArrayList<BlockChange>(kept.size());
            var companions = new ArrayList<BlockChange>();
            int orphans = 0;
            for (int index = 0; index < kept.size(); index++) {
                var change = kept.get(index);
                if (!(costs.get(index) instanceof BlockCost.Companion companion)) {
                    ordered.add(change);
                    continue;
                }
                if (!companion.isPrimary(targets.get(companion.primaryPos(change.pos())))) {
                    if (skipUnplaceable) {
                        orphans++;
                        continue;
                    }
                    return PlacementCheck.refused(MMMessage.COMPANION_WITHOUT_PRIMARY.text(
                            change.target().displayName()
                    ));
                }
                companions.add(change);
            }
            ordered.addAll(companions);
            return new PlacementCheck(ordered, Optional.empty(), skipped, orphans);
        }
    }

    private static boolean isPlacement(BlockChange change) {
        return change.kind() == BlockChange.Kind.PLACE || change.kind() == BlockChange.Kind.PLACE_CAPTURED;
    }

    record PlacementCheck(
            List<BlockChange> changes,
            Optional<Component> error,
            Map<BlockCost.Unsupported, Integer> skipped,
            int orphans
    ) {
        PlacementCheck {
            skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
        }

        static PlacementCheck refused(Component error) {
            return new PlacementCheck(List.of(), Optional.of(error), Map.of(), 0);
        }

        int skippedTotal() {
            return skipped.values().stream().mapToInt(Integer::intValue).sum() + orphans;
        }

        Component notice() {
            if (skippedTotal() == 0) {
                return Component.empty();
            }
            var details = Component.empty();
            boolean first = true;
            for (var entry : skipped.entrySet()) {
                if (!first) {
                    details.append("; ");
                }
                details.append(MMMessage.COPY_SKIPPED_ENTRY.text(entry.getValue(),
                        entry.getKey().describe()));
                first = false;
            }
            if (orphans > 0) {
                if (!first) {
                    details.append("; ");
                }
                details.append(MMMessage.COPY_SKIPPED_ENTRY.text(orphans,
                        MMMessage.COPY_SKIPPED_COMPANION.text()));
            }
            return MMMessage.COPY_SKIPPED.text(skippedTotal(), details);
        }
    }

    public static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ())
        );
    }

    public static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())
        );
    }

    public record Result(Optional<BuildPlan> plan, Component error, Component notice, boolean compiling) {
        public Result {
            notice = notice == null ? Component.empty() : notice;
        }

        public static Result ok(BuildPlan plan) {
            return new Result(Optional.of(plan), Component.empty(), Component.empty(), false);
        }

        public static Result error(Component error) {
            return new Result(Optional.empty(), error, Component.empty(), false);
        }

        static Result compiling(Component progress) {
            return new Result(Optional.empty(), progress, Component.empty(), true);
        }

        Result withNotice(Component notice) {
            return plan.isPresent() ? new Result(plan, error, notice, false) : this;
        }

        public boolean hasNotice() {
            return !notice.getString().isEmpty();
        }

        public static Result missingCoords() {
            return error(MMMessage.MISSING_COORDS.text());
        }
    }
}
