package lu.kolja.matter_manipulator.client.preview;

import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.build.BlockCost;
import lu.kolja.matter_manipulator.common.build.BlockPolicy;
import lu.kolja.matter_manipulator.common.build.ExchangeMatch;
import lu.kolja.matter_manipulator.common.build.PlacementCost;
import lu.kolja.matter_manipulator.common.build.ShapeGenerator;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.manipulator.BlockSlot;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.ShapeType;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class PreviewSources {
    private PreviewSources() {}

    static PreviewContent geometry(Map<BlockSlot, Optional<SlotPreview>> slots, ShapeType shape, BlockPos a,
            BlockPos b, BlockPos c, int limit, PreviewContent previous) {
        var builder = new PreviewContent.Builder(limit);
        boolean complete = ShapeGenerator.generate(shape, a, b, c, limit, (pos, slot) -> slots.get(slot)
                .ifPresent(target -> builder.add(pos.getX() - a.getX(), pos.getY() - a.getY(), pos.getZ() - a.getZ(),
                        target.state(), target.visual())));
        if (!complete) {
            builder.markTruncated();
        }
        return builder.build(previous);
    }

    static Map<BlockSlot, Optional<SlotPreview>> geometrySlots(ToolState state) {
        var slots = new EnumMap<BlockSlot, Optional<SlotPreview>>(BlockSlot.class);
        for (var slot : List.of(BlockSlot.CORNER, BlockSlot.EDGE, BlockSlot.FACE, BlockSlot.VOLUME)) {
            slots.put(slot, slotPreview(state, slot));
        }
        return slots;
    }

    private static Optional<SlotPreview> slotPreview(ToolState state, BlockSlot slot) {
        var captured = preferredCapturedSlot(state, slot).orElse(null);
        if (captured != null) {
            return captured.state().tryResolve().map(resolved -> new SlotPreview(resolved,
                    new PreviewVisual.Captured(captured, 0)));
        }
        return state.preferredSlot(slot).or(() -> state.slot(BlockSlot.VOLUME))
                .flatMap(BlockStateSpec::tryResolve)
                .map(resolved -> new SlotPreview(resolved, null));
    }

    record SlotPreview(BlockState state, PreviewVisual visual) {}

    static Optional<CapturedBlock> preferredCapturedSlot(ToolState state, BlockSlot slot) {
        return state.preferredCapturedSlot(slot).filter(block -> block.state().isKnown());
    }

    static PreviewContent cable(List<BlockPos> path, BlockPos origin, BlockState cableState, CapturedBlock captured,
            CablePreviewTopology topology, int limit, PreviewContent previous) {
        var builder = new PreviewContent.Builder(limit);
        for (var pos : path) {
            if (!builder.add(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ(),
                    cableState, new PreviewVisual.Captured(captured, topology.connectionMask(pos)))) {
                break;
            }
        }
        return builder.build(previous);
    }

    static PreviewContent exchange(PreviewSourceSnapshot snapshot, BlockState match, BlockState target,
            CapturedBlock capturedTarget, int limit, PreviewContent previous) {
        var min = snapshot.key.min();
        var builder = new PreviewContent.Builder(limit);
        var exchange = new ExchangeMatch(match, target);
        snapshot.forEach((pos, state, stamp) -> exchange.target(state).ifPresent(oriented ->
                builder.add(pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ(), oriented,
                        new PreviewVisual.Exchange(pos.immutable(), state, stamp, capturedTarget))));
        return builder.build(previous);
    }

    public static Function<BlockState, Optional<BlockState>> orientation(ManipulatorMode mode, ToolTransform transform) {
        return mode == ManipulatorMode.MOVE
                ? state -> IntegrationDispatcher.transformState(state, transform)
                : state -> IntegrationDispatcher.transformStateApproximately(state, transform);
    }

    public static PreviewContent transformed(PreviewSourceSnapshot snapshot, ToolTransform transform, int stackCount,
            Function<BlockState, Optional<BlockState>> orient, boolean visuals, int limit, PreviewContent previous) {
        var min = snapshot.key.min();
        var sourceSize = snapshot.key.max().subtract(min);
        var transformedSize = transform.transformedSize(sourceSize);
        var states = new IdentityHashMap<BlockState, Optional<BlockState>>();
        var builder = new PreviewContent.Builder(limit);
        var relative = new BlockPos.MutableBlockPos();
        for (int stackIndex = 0; stackIndex < stackCount; stackIndex++) {
            var stackOffset = transform.stackOffset(transformedSize, stackIndex);
            snapshot.forEach((pos, state, stamp) -> {
                var transformed = states.computeIfAbsent(state, source -> BlockPolicy.canManipulate(source)
                        ? orient.apply(source) : Optional.empty());
                if (transformed.isEmpty()) {
                    return;
                }
                relative.set(pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ());
                var local = transform.transformRelative(relative, sourceSize).offset(stackOffset);
                builder.add(local, transformed.get(),
                        visuals ? new PreviewVisual.FromSource(pos.immutable(), state, stamp, transform) : null);
            });
        }
        return builder.build(previous);
    }

    static long transformedCount(PreviewSourceSnapshot snapshot, int stackCount) {
        var count = new long[1];
        var capturable = new IdentityHashMap<BlockState, Boolean>();
        snapshot.forEach((pos, state, stamp) -> {
            if (capturable.computeIfAbsent(state, BlockPolicy::canManipulate)) {
                count[0]++;
            }
        });
        return count[0] * stackCount;
    }

    public static PreviewContent blueprint(Blueprint blueprint, ToolTransform transform, int stackCount, boolean visuals,
            int limit, PreviewContent previous) {
        var size = blueprint.header().size();
        var sourceSize = size.offset(-1, -1, -1);
        var transformedSize = transform.transformedSize(sourceSize);
        var placedBy = new IdentityHashMap<CapturedBlock, Optional<Placed>>();
        boolean hasCompanion = false;
        for (var entry : blueprint.palette()) {
            if (entry != null) {
                var placed = placed(entry, transform);
                placedBy.put(entry, placed);
                hasCompanion |= placed.filter(target -> target.companion() != null).isPresent();
            }
        }
        var targets = new HashMap<BlockPos, BlockState>();
        var companions = new ArrayList<Map.Entry<BlockPos, Placed>>();
        var builder = new PreviewContent.Builder(limit);
        var relative = new BlockPos.MutableBlockPos();
        for (int stackIndex = 0; stackIndex < stackCount; stackIndex++) {
            var stackOffset = transform.stackOffset(transformedSize, stackIndex);
            for (int index = 0; index < blueprint.volume(); index++) {
                var entry = blueprint.at(index);
                if (entry == null) {
                    continue;
                }
                var placed = placedBy.computeIfAbsent(entry, captured -> placed(captured, transform));
                if (placed.isEmpty()) {
                    continue;
                }
                relative.set(index % size.getX(), index / size.getX() % size.getY(), index / size.getX() / size.getY());
                var local = transform.transformRelative(relative, sourceSize).offset(stackOffset);
                if (hasCompanion) {
                    targets.put(local, placed.get().state());
                }
                if (placed.get().companion() != null) {
                    companions.add(Map.entry(local, placed.get()));
                    continue;
                }
                builder.add(local, placed.get().state(), visuals ? new PreviewVisual.Captured(placed.get().captured(), 0)
                        : null);
            }
        }
        for (var companion : companions) {
            var placed = companion.getValue();
            if (placed.companion().isPrimary(targets.get(placed.companion().primaryPos(companion.getKey())))) {
                builder.add(companion.getKey(), placed.state(),
                        visuals ? new PreviewVisual.Captured(placed.captured(), 0) : null);
            }
        }
        return builder.build(previous);
    }

    private static Optional<Placed> placed(CapturedBlock entry, ToolTransform transform) {
        if (!placeable(entry)) {
            return Optional.empty();
        }
        return entry.transformed(transform).flatMap(transformed -> {
            var state = transformed.state().resolve();
            var cost = PlacementCost.resolve(state, transformed);
            if (cost instanceof BlockCost.Unsupported) {
                return Optional.empty();
            }
            return Optional.of(new Placed(transformed, state,
                    cost instanceof BlockCost.Companion companion ? companion : null));
        });
    }

    private record Placed(CapturedBlock captured, BlockState state, @Nullable BlockCost.Companion companion) {}

    static long blueprintCount(Blueprint blueprint, int stackCount) {
        long count = 0L;
        var placeable = new IdentityHashMap<CapturedBlock, Boolean>();
        for (int index = 0; index < blueprint.volume(); index++) {
            var entry = blueprint.at(index);
            if (entry != null && placeable.computeIfAbsent(entry, PreviewSources::placeable)) {
                count++;
            }
        }
        return count * stackCount;
    }

    private static boolean placeable(CapturedBlock entry) {
        var state = entry.state().tryResolve().orElse(null);
        return state != null && !state.isAir() && BlockPolicy.canManipulate(state);
    }
}
