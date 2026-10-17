package lu.kolja.matter_manipulator.common.integration;

import lu.kolja.matter_manipulator.common.build.PlanStats;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.CableIntegration;
import lu.kolja.matter_manipulator.api.CraftingIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.ItemDemand;
import lu.kolja.matter_manipulator.api.ItemStorageIntegration;
import lu.kolja.matter_manipulator.api.PlacementCostIntegration;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.api.StorageUnchangedException;
import lu.kolja.matter_manipulator.common.localization.MMIntegrationText;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import lu.kolja.matter_manipulator.common.util.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class IntegrationRegistry {

    private final HandlerIndex<BlockIntegration> blocks;
    private final HandlerIndex<CableIntegration> cables;
    private final HandlerIndex<PlacementCostIntegration> costs;
    private final Map<BlockState, List<HandlerEntry<BlockIntegration>>> blockEntityTransforms = new ConcurrentHashMap<>();
    private final Map<BlockState, List<HandlerEntry<BlockIntegration>>> blockEntityRelocations = new ConcurrentHashMap<>();
    private final Map<BlockState, HandlerEntry<BlockIntegration>> transferredBlockEntities;
    private final List<StorageEntry> storage;
    private final List<CraftingEntry> crafting;

    private IntegrationRegistry(Builder builder) {
        blocks = new HandlerIndex<>(builder.blockEntries);
        cables = new HandlerIndex<>(builder.cableEntries);
        costs = new HandlerIndex<>(builder.costEntries);
        transferredBlockEntities = transferredBlockEntityCreators(blocks, builder.blockEntries);
        storage = List.copyOf(builder.storageEntries);
        crafting = List.copyOf(builder.craftingEntries);
    }

    public CompoundTag capture(ServerLevel level, BlockPos pos, BlockState state) {
        return capture(level, pos, state, null);
    }

    public CompoundTag capture(ServerLevel level, BlockPos pos, BlockState state, @Nullable BoundingBox selection) {
        PlanStats.count(PlanStats.Counter.INTEGRATION_CAPTURES);
        var root = new CompoundTag();
        var context = new IntegrationContext.Capture(level, pos, state, selection);
        for (var entry : blocks.matching(state)) {
            try {
                var payload = Objects.requireNonNull(entry.handler().capture(context), "Integration capture payload");
                if (payload != null && !payload.isEmpty()) {
                    root.put(entry.id().toString(), payload.copy());
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "capture", state, exception);
            }
        }
        return root;
    }

    public CompoundTag captureExchange(
            ServerLevel level,
            BlockPos pos,
            BlockState sourceState,
            BlockState targetState
    ) {
        var root = new CompoundTag();
        var targetHandlers = new HashSet<ResourceLocation>();
        for (var entry : blocks.matching(targetState)) {
            targetHandlers.add(entry.id());
        }
        var context = new IntegrationContext.Exchange(level, pos, sourceState, targetState);
        for (var entry : blocks.matching(sourceState)) {
            if (!targetHandlers.contains(entry.id())) {
                continue;
            }
            try {
                var payload = Objects.requireNonNull(entry.handler().captureExchange(context), "Integration exchange payload");
                if (payload != null && !payload.isEmpty()) {
                    root.put(entry.id().toString(), payload.copy());
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "capture_exchange", sourceState, exception);
            }
        }
        return root;
    }

    public IntegrationApplyReport apply(
            ServerLevel level,
            BlockPos pos,
            ServerPlayer player,
            CompoundTag root,
            Consumer<ItemStack> refund,
            PlacementPurpose purpose
    ) {
        if (root == null || root.isEmpty()) {
            return IntegrationApplyReport.NONE;
        }
        var state = level.getBlockState(pos);
        return applyWithContexts(state, id -> new IntegrationContext.Apply(level, pos, state, player, refund, purpose,
                work -> DeferredIntegrationWork.enqueue(id, "after_tick", state, work)), root);
    }

    IntegrationApplyReport apply(BlockState state, IntegrationContext.Apply context, CompoundTag root) {
        return applyWithContexts(state, ignored -> context, root);
    }

    private IntegrationApplyReport applyWithContexts(
            BlockState state, Function<ResourceLocation, IntegrationContext.Apply> contexts, CompoundTag root
    ) {
        var applied = new LinkedHashSet<ResourceLocation>();
        var rejected = new LinkedHashSet<ResourceLocation>();
        var partial = new LinkedHashSet<ResourceLocation>();
        var uncertain = new LinkedHashSet<ResourceLocation>();
        Component rejectionReason = null;
        var warnings = new ArrayList<String>();
        for (var entry : blocks.matching(state)) {
            var key = entry.id().toString();
            if (!root.contains(key)) {
                continue;
            }
            try {
                var context = contexts.apply(entry.id());
                var refunded = new boolean[1];
                var reason = new Component[1];
                var warned = new ArrayList<String>();
                boolean transfer = context != null && context.purpose() == PlacementPurpose.MOVE_TRANSFER;
                var guarded = context == null ? null : new IntegrationContext.Apply(
                        context.level(), context.pos(), context.state(), context.player(), stack -> {
                            if (!stack.isEmpty()) {
                                refunded[0] = true;
                                if (!transfer) {
                                    context.refund(stack);
                                }
                            }
                        }, context.purpose(), context.afterTick(), given -> reason[0] = given, warned::add);
                var result = Objects.requireNonNull(
                        entry.handler().apply(guarded, root.getCompound(key).copy()),
                        "Integration apply result"
                );
                if (refunded[0] && transfer) {
                    IntegrationFailures.report(entry.id(), "move_transfer_refund", state,
                            new IllegalStateException("An integration must not refund items during MOVE_TRANSFER"));
                    uncertain.add(entry.id());
                    continue;
                }
                if (refunded[0] && result != BlockIntegration.ApplyResult.PARTIAL_RESOURCES_SETTLED) {
                    throw new IllegalStateException("An integration that refunds items must report PARTIAL_RESOURCES_SETTLED");
                }
                if (result == BlockIntegration.ApplyResult.REJECTED_UNCHANGED) {
                    rejected.add(entry.id());
                    if (rejectionReason == null) {
                        rejectionReason = reason[0];
                    }
                } else if (result == BlockIntegration.ApplyResult.PARTIAL_RESOURCES_SETTLED) {
                    partial.add(entry.id());
                    warnings.addAll(warned);
                } else {
                    applied.add(entry.id());
                    warnings.addAll(warned);
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "apply", state, exception);
                uncertain.add(entry.id());
            }
        }
        return new IntegrationApplyReport(applied, rejected, partial, uncertain, rejectionReason, warnings);
    }

    public boolean canReconfigure(BlockState state, CompoundTag existing, CompoundTag desired) {
        var differing = new HashSet<>(existing.getAllKeys());
        differing.addAll(desired.getAllKeys());
        differing.removeIf(key -> Objects.equals(existing.get(key), desired.get(key)));
        var context = new IntegrationContext.Placement(state, null);
        for (var entry : blocks.matching(state)) {
            var key = entry.id().toString();
            if (!differing.remove(key)) {
                continue;
            }
            if (!desired.contains(key, Tag.TAG_COMPOUND)) {
                return false;
            }
            try {
                if (!entry.handler().canReconfigure(context, existing.getCompound(key), desired.getCompound(key))) {
                    return false;
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "can_reconfigure", state, exception);
                return false;
            }
        }
        return differing.isEmpty();
    }

    @Nullable
    public Set<BlockPos> additionalChangedPositions(
            ServerLevel level, BlockPos pos, BlockState state, CompoundTag root, PlacementPurpose purpose
    ) {
        var context = new IntegrationContext.SideEffects(level, pos, state, purpose);
        var positions = new LinkedHashSet<BlockPos>();
        for (var entry : blocks.matching(state)) {
            var key = entry.id().toString();
            if (!root.contains(key, Tag.TAG_COMPOUND)) {
                continue;
            }
            try {
                positions.addAll(entry.handler().additionalChangedPositions(context, root.getCompound(key).copy()));
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "additional_changed_positions", state, exception);
                return null;
            }
        }
        positions.remove(pos);
        return positions;
    }

    public PreparedPlacement preparePlacement(
            ServerPlayer player,
            ItemStack tool,
            BlockState target,
            CapturedBlock captured
    ) {
        return preparePlacement(player, tool, target, captured, PlacementPurpose.COPY_TEMPLATE);
    }

    public PreparedPlacement preparePlacement(
            ServerPlayer player,
            ItemStack tool,
            BlockState target,
            CapturedBlock captured,
            PlacementPurpose purpose
    ) {
        return preparePlacement(target, captured, purpose, () -> new InventoryUtil.Acquisition(player, tool));
    }

    PreparedPlacement preparePlacement(
            BlockState target,
            CapturedBlock captured,
            PlacementPurpose purpose,
            Supplier<InventoryUtil.Acquisition> acquisitions
    ) {
        var input = captured == null ? new CompoundTag() : captured.compat();
        if (purpose == PlacementPurpose.MOVE_TRANSFER) {
            return new PreparedPlacement(input, Map.of(), List.of());
        }
        var output = new CompoundTag();
        var consumed = new LinkedHashMap<ResourceLocation, List<ItemStack>>();
        var missing = new ArrayList<ItemRequirement>();
        var funded = new ArrayList<InventoryUtil.Acquisition>();
        var context = new IntegrationContext.Preparation(target, captured == null ? null : captured.snapshot(), purpose);
        for (var entry : blocks.matching(target)) {
            var key = entry.id().toString();
            if (!input.contains(key)) {
                continue;
            }
            var acquisition = acquisitions.get();
            funded.add(acquisition);
            try {
                var plan = Objects.requireNonNull(entry.handler().preparation(context, input.getCompound(key)),
                        "Integration preparation plan");
                var prepared = plan.prepare(acquisition);
                var withdrawn = acquisition.consumed();
                if (prepared.isEmpty() && !withdrawn.isEmpty()) {
                    throw new IllegalStateException("Funded preparation must retain a payload for application");
                }
                if (!prepared.isEmpty()) {
                    output.put(key, prepared.copy());
                }
                if (!withdrawn.isEmpty()) {
                    consumed.put(entry.id(), withdrawn);
                }
                missing.addAll(acquisition.missing());
            } catch (StorageIntegrationException exception) {
                funded.forEach(funding -> funding.refundAfterStorageFailure(exception));
                throw exception;
            } catch (RuntimeException | LinkageError exception) {
                acquisition.refund();
                IntegrationFailures.report(entry.id(), "prepare_placement", target, exception);
            }
        }
        return new PreparedPlacement(output, consumed, missing);
    }

    public List<ItemRequirement> collectPlacementRequirements(
            BlockState target,
            CapturedBlock captured,
            PlacementPurpose purpose
    ) {
        PlanStats.count(PlanStats.Counter.PREPARATION_REQUIREMENTS);
        var input = captured == null ? new CompoundTag() : captured.compat();
        if (purpose == PlacementPurpose.MOVE_TRANSFER) {
            return List.of();
        }
        var requirements = new ArrayList<ItemRequirement>();
        var context = new IntegrationContext.Preparation(target, captured == null ? null : captured.snapshot(), purpose);
        for (var entry : blocks.matching(target)) {
            var key = entry.id().toString();
            if (!input.contains(key)) {
                continue;
            }
            try {
                var plan = Objects.requireNonNull(entry.handler().preparation(context, input.getCompound(key)),
                        "Integration preparation plan");
                requirements.addAll(plan.requirements());
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "collect_placement_requirements", target, exception);
            }
        }
        return List.copyOf(requirements);
    }

    public CompoundTag transform(BlockState state, CompoundTag root, ToolTransform transform) {
        return transform(state, root, transform, false);
    }

    public CompoundTag transform(BlockState state, CompoundTag root, ToolTransform transform, boolean strict) {
        if (root == null || root.isEmpty()) {
            return new CompoundTag();
        }
        var output = new CompoundTag();
        var context = new IntegrationContext.Transform(state, transform);
        for (var entry : blocks.matching(state)) {
            var key = entry.id().toString();
            if (!root.contains(key)) {
                continue;
            }
            var payload = root.getCompound(key);
            try {
                var transformed = Objects.requireNonNull(entry.handler().transform(context, payload.copy()), "Integration transformed payload");
                output.put(key, transformed == null ? payload.copy() : transformed.copy());
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "transform", state, exception);
                if (strict) {
                    throw exception;
                }
                output.put(key, payload.copy());
            }
        }
        return output;
    }

    public CompoundTag portable(BlockState state, CompoundTag root, Runnable failed) {
        var output = new CompoundTag();
        var context = new IntegrationContext.Placement(state, null);
        for (var entry : blocks.matching(state)) {
            var key = entry.id().toString();
            if (!root.contains(key, Tag.TAG_COMPOUND)) {
                continue;
            }
            try {
                var portable = Objects.requireNonNull(entry.handler().portable(context, root.getCompound(key).copy()),
                        "Integration portable payload");
                if (!portable.isEmpty()) {
                    output.put(key, portable.copy());
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "portable", state, exception);
                failed.run();
            }
        }
        return output;
    }

    public Optional<BlockState> transformState(BlockState state, ToolTransform transform) {
        return transformState(state, transform, false);
    }

    public Optional<BlockState> transformStateApproximately(BlockState state, ToolTransform transform) {
        return transformState(state, transform, true);
    }

    private Optional<BlockState> transformState(BlockState state, ToolTransform transform, boolean approximate) {
        PlanStats.count(PlanStats.Counter.STATE_TRANSFORMS);
        if (transform.isIdentity()) {
            return Optional.of(state);
        }
        var result = (approximate ? transform.transformApproximately(state) : transform.transform(state)).orElse(null);
        var context = new IntegrationContext.Transform(state, transform);
        for (var entry : blocks.matching(state)) {
            try {
                result = entry.handler().transformState(context, result);
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "transform_state", state, exception);
                return Optional.empty();
            }
        }
        return Optional.ofNullable(result);
    }

    public CompoundTag transformBlockEntity(BlockState state, CompoundTag blockEntity, ToolTransform transform) {
        var output = blockEntity.copy();
        if (transform.isIdentity()) {
            return output;
        }
        var context = new IntegrationContext.Transform(state, transform);
        for (var entry : blockEntityTransforms(state)) {
            output = Objects.requireNonNull(
                    entry.handler().transformBlockEntity(context, output),
                    "Integration transformed block entity"
            ).copy();
        }
        return output;
    }

    public boolean hasBlockEntityTransform(BlockState state) {
        return !blockEntityTransforms(state).isEmpty();
    }

    public CompoundTag relocateBlockEntity(IntegrationContext.Relocation context, CompoundTag blockEntity) {
        var output = blockEntity.copy();
        for (var entry : blockEntityRelocations(context.state())) {
            output = Objects.requireNonNull(
                    entry.handler().relocateBlockEntity(context, output),
                    "Integration relocated block entity"
            ).copy();
        }
        return output;
    }

    public boolean hasBlockEntityRelocation(BlockState state) {
        return !blockEntityRelocations(state).isEmpty();
    }

    private List<HandlerEntry<BlockIntegration>> blockEntityRelocations(BlockState state) {
        var cached = blockEntityRelocations.get(state);
        if (cached != null) {
            return cached;
        }
        var computed = new ArrayList<HandlerEntry<BlockIntegration>>();
        for (var entry : blocks.matching(state)) {
            try {
                if (entry.handler().relocatesBlockEntity(state)) {
                    computed.add(entry);
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "relocates_block_entity", state, exception);
                computed.add(entry);
            }
        }
        var handlers = List.copyOf(computed);
        var previous = blockEntityRelocations.putIfAbsent(state, handlers);
        return previous != null ? previous : handlers;
    }

    private List<HandlerEntry<BlockIntegration>> blockEntityTransforms(BlockState state) {
        var cached = blockEntityTransforms.get(state);
        if (cached != null) {
            return cached;
        }
        var computed = new ArrayList<HandlerEntry<BlockIntegration>>();
        for (var entry : blocks.matching(state)) {
            try {
                if (entry.handler().transformsBlockEntity(state)) {
                    computed.add(entry);
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "transforms_block_entity", state, exception);
                computed.add(entry);
            }
        }
        var handlers = List.copyOf(computed);
        var previous = blockEntityTransforms.putIfAbsent(state, handlers);
        return previous != null ? previous : handlers;
    }

    private static Map<BlockState, HandlerEntry<BlockIntegration>> transferredBlockEntityCreators(
            HandlerIndex<BlockIntegration> blocks,
            List<HandlerEntry<BlockIntegration>> entries
    ) {
        var candidates = entries.stream().filter(entry -> overridesCreatesTransferred(entry.handler())).toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }
        var creators = new HashMap<BlockState, HandlerEntry<BlockIntegration>>();
        for (var block : BuiltInRegistries.BLOCK) {
            for (var state : block.getStateDefinition().getPossibleStates()) {
                if (!state.hasBlockEntity()) {
                    continue;
                }
                for (var entry : blocks.matching(state)) {
                    if (!candidates.contains(entry)) {
                        continue;
                    }
                    boolean creates;
                    try {
                        creates = entry.handler().createsTransferredBlockEntity(state);
                    } catch (RuntimeException | LinkageError exception) {
                        IntegrationFailures.report(entry.id(), "creates_transferred_block_entity", state, exception);
                        creates = true;
                    }
                    if (!creates) {
                        continue;
                    }
                    var previous = creators.putIfAbsent(state, entry);
                    if (previous != null) {
                        throw new IllegalArgumentException("Block integrations " + previous.id() + " and " + entry.id()
                                + " both build the moved block entity of " + state);
                    }
                }
            }
        }
        return Map.copyOf(creators);
    }

    private static boolean overridesCreatesTransferred(BlockIntegration handler) {
        try {
            return handler.getClass().getMethod("createsTransferredBlockEntity", BlockState.class).getDeclaringClass()
                    != BlockIntegration.class;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public boolean createsTransferredBlockEntity(BlockState state) {
        return transferredBlockEntities.containsKey(state);
    }

    @Nullable
    public BlockEntity createTransferredBlockEntity(ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntity) {
        var entry = transferredBlockEntities.get(state);
        if (entry == null) {
            throw new IllegalStateException("No integration builds the moved block entity of " + state);
        }
        try {
            return entry.handler().createTransferredBlockEntity(new IntegrationContext.Transfer(level, pos, state), blockEntity);
        } catch (RuntimeException | LinkageError exception) {
            IntegrationFailures.report(entry.id(), "create_transferred_block_entity", state, exception);
            return null;
        }
    }

    public void afterTransfer(ServerLevel level, BlockPos pos, boolean committed) {
        var state = level.getBlockState(pos);
        var context = new IntegrationContext.Transferred(level, pos, state, level.getBlockEntity(pos), committed);
        for (var entry : blocks.matching(state)) {
            try {
                entry.handler().afterTransfer(context);
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "after_transfer", state, exception);
            }
        }
    }

    @Nullable
    public Component removalRefusal(BlockState state) {
        for (var entry : blocks.matching(state)) {
            try {
                var reason = entry.handler().removalRefusal(state);
                if (reason != null) {
                    return reason;
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "removal_refusal", state, exception);
                return MMIntegrationText.REFUSAL_INTEGRATION_FAILED.text();
            }
        }
        return null;
    }

    @Nullable
    public Component moveRefusal(ServerLevel level, BlockPos pos, BlockState state, BoundingBox selection,
                                 @Nullable ToolTransform transform) {
        var context = new IntegrationContext.Capture(level, pos, state, selection, transform);
        for (var entry : blocks.matching(state)) {
            try {
                var reason = entry.handler().moveRefusal(context);
                if (reason != null) {
                    return reason;
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "move_refusal", state, exception);
                return MMIntegrationText.REFUSAL_INTEGRATION_FAILED.text();
            }
        }
        return null;
    }

    public Set<BlockPos> moveGroupPeers(ServerLevel level, BlockPos pos, BlockState state, BoundingBox selection) {
        var context = new IntegrationContext.Capture(level, pos, state, selection);
        var peers = new HashSet<BlockPos>();
        for (var entry : blocks.matching(state)) {
            try {
                peers.addAll(entry.handler().moveGroupPeers(context));
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "move_group_peers", state, exception);
            }
        }
        return peers;
    }

    public void beforeRemove(
            ServerLevel level,
            BlockPos pos,
            ServerPlayer player,
            boolean collectDrops,
            Consumer<ItemStack> dropSink
    ) {
        var state = level.getBlockState(pos);
        var context = new IntegrationContext.Removal(level, pos, state, player, collectDrops, dropSink);
        for (var entry : blocks.matching(state)) {
            try {
                entry.handler().beforeRemove(context);
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "before_remove", state, exception);
            }
        }
    }

    public boolean dropCarriesContents(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack drop) {
        var state = level.getBlockState(pos);
        var context = new IntegrationContext.Removal(level, pos, state, player, true, stack -> {
            throw new UnsupportedOperationException("dropCarriesContents must not hand out drops");
        });
        for (var entry : blocks.matching(state)) {
            try {
                if (entry.handler().dropCarriesContents(context, drop.copy())) {
                    return true;
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "drop_carries_contents", state, exception);
                return true;
            }
        }
        return false;
    }

    public PlacementRequirements placementRequirements(BlockState target, CapturedBlock captured) {
        PlanStats.count(PlanStats.Counter.INTEGRATION_REQUIREMENTS);
        var result = PlacementRequirements.defaults();
        var context = new IntegrationContext.Placement(target, captured == null ? null : captured.snapshot());
        var root = captured == null ? new CompoundTag() : captured.compat();
        for (var entry : blocks.matching(target)) {
            var payload = root == null ? new CompoundTag() : root.getCompound(entry.id().toString());
            try {
                var contribution = Objects.requireNonNull(entry.handler().placementRequirements(context, payload), "Integration placement requirements");
                if (contribution != null) {
                    result = result.merge(contribution);
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "placement_requirements", target, exception);
            }
        }
        for (var entry : costs.matching(target)) {
            try {
                result = result.merge(Objects.requireNonNull(entry.handler().placementRequirements(context),
                        "Integration placement cost"));
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "placement_cost", target, exception);
            }
        }
        return result;
    }

    public boolean hasBlockIntegration(BlockState state) {
        return !blocks.matching(state).isEmpty();
    }

    public boolean isCable(BlockState state) {
        return !cables.matching(state).isEmpty();
    }

    public boolean canReuseCable(
            BlockGetter level, BlockPos pos, BlockState existing, BlockState target, @Nullable CapturedBlock template
    ) {
        var existingIds = cables.matching(existing).stream()
                .map(HandlerEntry::id)
                .collect(Collectors.toSet());
        var context = new IntegrationContext.CableReuse(level, pos, existing, target,
                template == null ? null : template.snapshot());
        for (var entry : cables.matching(target)) {
            if (!existingIds.contains(entry.id())) {
                continue;
            }
            try {
                if (entry.handler().canReuse(context)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "can_reuse_cable", target, exception);
            }
        }
        return false;
    }

    public @Nullable Component cableRouteRefusal(
            Level level, BlockPos pos, BlockState target, @Nullable CapturedBlock template
    ) {
        var entries = cables.matching(target);
        if (entries.isEmpty()) {
            return null;
        }
        var context = new IntegrationContext.CableSite(level, pos, target, template == null ? null : template.snapshot());
        for (var entry : entries) {
            try {
                var refusal = entry.handler().routeRefusal(context);
                if (refusal != null) {
                    return refusal;
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "cable_route_refusal", target, exception);
            }
        }
        return null;
    }

    public CablePreparation prepareCable(
            ServerLevel level,
            BlockPos pos,
            BlockState target,
            Set<Direction> lineDirections,
            CapturedBlock template
    ) {
        var root = new CompoundTag();
        var blockEntity = template == null || template.blockEntity() == null
                ? new CompoundTag()
                : template.blockEntity().copy();
        var timing = CableIntegration.ApplyTiming.DURING_PLACEMENT;
        var context = new IntegrationContext.Cable(level, pos, target, Set.copyOf(lineDirections), template == null ? null : template.snapshot());
        for (var entry : cables.matching(target)) {
            try {
                var data = Objects.requireNonNull(entry.handler().prepare(context), "Integration cable placement data");
                if (data == null) {
                    continue;
                }
                if (!data.payload().isEmpty()) {
                    root.put(entry.id().toString(), data.payload().copy());
                }
                if (data.applyTiming() == CableIntegration.ApplyTiming.AFTER_PATH) {
                    timing = CableIntegration.ApplyTiming.AFTER_PATH;
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "prepare_cable", target, exception);
            }
        }
        return new CablePreparation(
                new CapturedBlock(BlockStateSpec.fromState(target), blockEntity, root),
                timing
        );
    }

    public ItemStack extractItem(ServerPlayer player, ItemStack tool, ItemStack required) {
        var context = new IntegrationContext.Storage(player, tool);
        var remainder = required.copy();
        for (var entry : storage) {
            if (remainder.isEmpty()) {
                break;
            }
            try {
                var returned = entry.handler().extract(context, remainder.copy());
                remainder = validatedRemainder(entry.id(), remainder, returned);
            } catch (StorageUnchangedException exception) {
                IntegrationFailures.report(entry.id(), "extract_item_unchanged", null, exception);
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "extract_item", null, exception);
                var withdrawn = required.copyWithCount(required.getCount() - remainder.getCount());
                throw new StorageIntegrationException(entry.id(), "extract_item", withdrawn, remainder, exception);
            }
        }
        return remainder;
    }

    private static ItemStack validatedRemainder(
            ResourceLocation integrationId,
            ItemStack requested,
            ItemStack returned
    ) {
        Objects.requireNonNull(returned, "Storage integration " + integrationId + " returned null remainder");
        if (returned.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!InventoryUtil.isSameMaterial(requested, returned) || returned.getCount() > requested.getCount()) {
            throw new IllegalStateException(
                    "Storage integration " + integrationId + " returned an invalid remainder"
            );
        }
        return returned.copy();
    }

    public ItemStack insertItem(ServerPlayer player, ItemStack tool, ItemStack stack) {
        return insertItem(player, tool, stack, Set.of());
    }

    public ItemStack insertItem(ServerPlayer player, ItemStack tool, ItemStack stack, Set<ResourceLocation> excluded) {
        var context = new IntegrationContext.Storage(player, tool);
        var remainder = stack.copy();
        for (var entry : storage) {
            if (remainder.isEmpty()) {
                break;
            }
            if (excluded.contains(entry.id())) {
                continue;
            }
            try {
                var returned = entry.handler().insert(context, remainder.copy());
                remainder = validatedRemainder(entry.id(), remainder, returned);
            } catch (StorageUnchangedException exception) {
                IntegrationFailures.report(entry.id(), "insert_item_unchanged", null, exception);
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "insert_item", null, exception);
                throw new StorageIntegrationException(entry.id(), "insert_item", ItemStack.EMPTY, remainder, exception);
            }
        }
        return remainder;
    }

    public long availableItem(ServerPlayer player, ItemStack tool, ItemStack stack) {
        var context = new IntegrationContext.Storage(player, tool);
        long available = 0L;
        for (var entry : storage) {
            try {
                available = Math.addExact(available, Math.max(0, entry.handler().available(context, stack.copy())));
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "available_item", null, exception);
            }
        }
        return available;
    }

    public List<ItemStack> storedVariants(ServerPlayer player, ItemStack tool, ItemStack like) {
        var context = new IntegrationContext.Storage(player, tool);
        var found = new ArrayList<ItemStack>();
        for (var entry : storage) {
            try {
                for (var stack : entry.handler().variants(context, like.copyWithCount(1))) {
                    if (stack.is(like.getItem())) {
                        found.add(stack.copy());
                    }
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "stored_variants", null, exception);
            }
        }
        return found;
    }

    public boolean requestCrafting(ServerPlayer player, ItemStack tool, List<ItemDemand> demands) {
        for (var entry : crafting) {
            try {
                if (entry.handler().request(new IntegrationContext.Crafting(player, tool, demands))) {
                    return true;
                }
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "request_crafting", null, exception);
            }
        }
        return false;
    }

    public void tickCrafting() {
        for (var entry : crafting) {
            try {
                entry.handler().tick();
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "tick_crafting", null, exception);
            }
        }
    }

    public void stopCrafting() {
        for (var entry : crafting) {
            try {
                entry.handler().serverStopping();
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "stop_crafting", null, exception);
            }
        }
    }

    public void forgetCrafting(UUID player) {
        for (var entry : crafting) {
            try {
                entry.handler().playerLoggedOut(player);
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(entry.id(), "forget_crafting", null, exception);
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements IntegrationRegistrar {

        private final List<HandlerEntry<BlockIntegration>> blockEntries = new ArrayList<>();
        private final List<HandlerEntry<CableIntegration>> cableEntries = new ArrayList<>();
        private final List<HandlerEntry<PlacementCostIntegration>> costEntries = new ArrayList<>();
        private final List<StorageEntry> storageEntries = new ArrayList<>();
        private final List<CraftingEntry> craftingEntries = new ArrayList<>();
        private final Set<ResourceLocation> blockIds = new HashSet<>();
        private final Set<ResourceLocation> cableIds = new HashSet<>();
        private final Set<ResourceLocation> costIds = new HashSet<>();
        private final Set<ResourceLocation> storageIds = new HashSet<>();
        private final Set<ResourceLocation> craftingIds = new HashSet<>();
        private int registrationOrder;
        private boolean frozen;

        @Override
        public void block(ResourceLocation id, BlockIntegration handler, Block... blocks) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            var selected = Set.copyOf(Arrays.asList(blocks));
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("Exact selector for " + id + " cannot be empty");
            }
            requireUnique(blockIds, id, "block");
            blockEntries.add(HandlerEntry.exact(id, handler, selected, registrationOrder++));
        }

        @Override
        public void block(ResourceLocation id, BlockIntegration handler, Predicate<BlockState> selector) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(selector, "selector");
            requireUnique(blockIds, id, "block");
            blockEntries.add(HandlerEntry.predicate(id, handler, selector, registrationOrder++));
        }

        @Override
        public void cable(ResourceLocation id, CableIntegration handler, Block... blocks) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            var selected = Set.copyOf(Arrays.asList(blocks));
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("Exact selector for " + id + " cannot be empty");
            }
            requireUnique(cableIds, id, "cable");
            cableEntries.add(HandlerEntry.exact(id, handler, selected, registrationOrder++));
        }

        @Override
        public void cable(ResourceLocation id, CableIntegration handler, Predicate<BlockState> selector) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(selector, "selector");
            requireUnique(cableIds, id, "cable");
            cableEntries.add(HandlerEntry.predicate(id, handler, selector, registrationOrder++));
        }

        @Override
        public void placementCost(ResourceLocation id, PlacementCostIntegration handler, Block... blocks) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            var selected = Set.copyOf(Arrays.asList(blocks));
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("Exact selector for " + id + " cannot be empty");
            }
            requireUnique(costIds, id, "placement cost");
            costEntries.add(HandlerEntry.exact(id, handler, selected, registrationOrder++));
        }

        @Override
        public void placementCost(ResourceLocation id, PlacementCostIntegration handler, Predicate<BlockState> selector) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(selector, "selector");
            requireUnique(costIds, id, "placement cost");
            costEntries.add(HandlerEntry.predicate(id, handler, selector, registrationOrder++));
        }

        @Override
        public void storage(ResourceLocation id, ItemStorageIntegration handler) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            requireUnique(storageIds, id, "storage");
            storageEntries.add(new StorageEntry(id, handler));
        }

        @Override
        public void crafting(ResourceLocation id, CraftingIntegration handler) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            requireUnique(craftingIds, id, "crafting");
            craftingEntries.add(new CraftingEntry(id, handler));
        }

        public IntegrationRegistry build() {
            ensureMutable();
            frozen = true;
            return new IntegrationRegistry(this);
        }

        void freeze() {
            frozen = true;
        }

        private void ensureMutable() {
            if (frozen) {
                throw new IllegalStateException("Integration registry is already frozen");
            }
        }

        private static void requireUnique(Set<ResourceLocation> ids, ResourceLocation id, String kind) {
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate " + kind + " integration id: " + id);
            }
        }
    }

    private record HandlerEntry<T>(
            ResourceLocation id,
            T handler,
            Set<Block> exactBlocks,
            Predicate<BlockState> predicate,
            int order
    ) {
        static <T> HandlerEntry<T> exact(ResourceLocation id, T handler, Collection<Block> blocks, int order) {
            return new HandlerEntry<>(id, handler, Set.copyOf(blocks), null, order);
        }

        static <T> HandlerEntry<T> predicate(ResourceLocation id, T handler, Predicate<BlockState> predicate, int order) {
            return new HandlerEntry<>(id, handler, Set.of(), predicate, order);
        }
    }

    private record StorageEntry(ResourceLocation id, ItemStorageIntegration handler) {}

    private record CraftingEntry(ResourceLocation id, CraftingIntegration handler) {}

    private static final class HandlerIndex<T> {

        private final Map<Block, List<HandlerEntry<T>>> exact = new HashMap<>();
        private final List<HandlerEntry<T>> predicates = new ArrayList<>();
        private final Map<BlockState, List<HandlerEntry<T>>> matched = new ConcurrentHashMap<>();

        private HandlerIndex(List<HandlerEntry<T>> entries) {
            for (var entry : entries) {
                if (entry.predicate() != null) {
                    predicates.add(entry);
                    continue;
                }
                for (var block : entry.exactBlocks()) {
                    exact.computeIfAbsent(block, ignored -> new ArrayList<>()).add(entry);
                }
            }
            exact.replaceAll((ignored, value) -> List.copyOf(value));
        }

        private List<HandlerEntry<T>> matching(BlockState state) {
            PlanStats.count(PlanStats.Counter.HANDLER_MATCHES);
            var cached = matched.get(state);
            if (cached != null) {
                return cached;
            }
            var computed = match(state);
            var previous = matched.putIfAbsent(state, computed);
            return previous != null ? previous : computed;
        }

        private List<HandlerEntry<T>> match(BlockState state) {
            PlanStats.count(PlanStats.Counter.HANDLER_MATCH_MISSES);
            var matches = new LinkedHashMap<ResourceLocation, HandlerEntry<T>>();
            for (var entry : exact.getOrDefault(state.getBlock(), List.of())) {
                matches.put(entry.id(), entry);
            }
            for (var entry : predicates) {
                try {
                    if (entry.predicate().test(state)) {
                        matches.put(entry.id(), entry);
                    }
                } catch (RuntimeException | LinkageError exception) {
                    IntegrationFailures.report(entry.id(), "match", state, exception);
                }
            }
            return matches.values().stream()
                    .sorted(Comparator.comparingInt(HandlerEntry::order))
                    .toList();
        }
    }
}
