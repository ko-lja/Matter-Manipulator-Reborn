package lu.kolja.matter_manipulator.common.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import lu.kolja.matter_manipulator.common.build.WorldOperations.Transfer;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class PreparedMove {

    static final int BLOCK_ENTITY_WEIGHT = 4;
    static final int INTEGRATION_WEIGHT = 4;

    private final List<BlockChange> moves;
    private final RemoveMode removeMode;
    private final LongSet sources;
    private final LongSet destinations;
    private final BlockState[] targets;
    private final int weight;
    private int prepared;
    private int checkedTick = -1;
    @Nullable
    private PlacementDecision refusal;
    private long nanos;

    PreparedMove(List<BlockChange> moves, RemoveMode removeMode) {
        this.moves = List.copyOf(moves);
        this.removeMode = removeMode;
        sources = new LongOpenHashSet(moves.size());
        destinations = new LongOpenHashSet(moves.size());
        for (var change : moves) {
            sources.add(change.pos().asLong());
            destinations.add(change.move().destination().asLong());
        }
        targets = new BlockState[moves.size()];
        int weight = 0;
        for (var change : moves) {
            weight += commitWeight(change);
        }
        this.weight = weight;
    }

    public static int commitWeight(BlockChange change) {
        var move = change.move();
        var source = move.expectedSource();
        int weight = 1;
        if (source.hasBlockEntity()) {
            weight += BLOCK_ENTITY_WEIGHT;
        }
        if (IntegrationDispatcher.hasBlockIntegration(source)) {
            weight += INTEGRATION_WEIGHT;
        }
        if (move.expectedDestination().hasBlockEntity()) {
            weight += BLOCK_ENTITY_WEIGHT;
        }
        return weight;
    }

    record Captured(List<Transfer> originals, List<Transfer> placements) {}

    boolean prepare(ServerPlayer player, ServerLevel level, int tick, PreparationBudget.Slice slice) {
        if (done()) {
            return true;
        }
        slice.open();
        try {
            while (prepared < moves.size()) {
                if (!slice.take()) {
                    return false;
                }
                if (prepared == 0) {
                    checkedTick = tick;
                }
                var change = moves.get(prepared);
                var refused = WorldOperations.moveRefusal(player, level, change, sources, destinations, removeMode);
                if (refused != null) {
                    refusal = refused;
                    return true;
                }
                var move = change.move();
                var target = IntegrationDispatcher.transformState(move.expectedSource(), move.transform());
                if (target.isEmpty()) {
                    refusal = PlacementDecision.skip(SkipReason.ORIENTATION);
                    return true;
                }
                targets[prepared] = target.get();
                prepared++;
                PlanStats.count(PlanStats.Counter.MOVE_MEMBERS_PREPARED);
            }
            return true;
        } finally {
            long spent = slice.close();
            nanos += spent;
            PlanStats.add(PlanStats.Section.MOVE_PREPARE, spent);
        }
    }

    boolean checkedIn(int tick) {
        return prepared == moves.size() && checkedTick == tick;
    }

    Captured capture(ServerLevel level) {
        long started = PlanStats.start();
        try {
            var originals = new ArrayList<Transfer>(moves.size());
            var placements = new ArrayList<Transfer>(moves.size());
            for (int i = 0; i < moves.size(); i++) {
                var change = moves.get(i);
                var move = change.move();
                var state = move.expectedSource();
                var source = capture(level, change.pos(), state, move.selection());
                var placed = source.blockEntity();
                var compat = source.compat();
                var transform = move.transform();
                if (placed != null && IntegrationDispatcher.hasBlockEntityRelocation(state)) {
                    long relocateStarted = PlanStats.start();
                    placed = IntegrationDispatcher.relocateBlockEntity(new IntegrationContext.Relocation(
                            state, level.dimension(), change.pos(), move.destination(), transform, destinations(change)), placed);
                    PlanStats.end(PlanStats.Section.TRANSFORM_BLOCK_ENTITY, relocateStarted);
                }
                if (!transform.isIdentity()) {
                    long compatStarted = PlanStats.start();
                    compat = IntegrationDispatcher.transform(state, compat, transform, true);
                    PlanStats.end(PlanStats.Section.TRANSFORM_COMPAT, compatStarted);
                    if (placed != null && IntegrationDispatcher.hasBlockEntityTransform(state)) {
                        long blockEntityStarted = PlanStats.start();
                        placed = IntegrationDispatcher.transformBlockEntity(state, placed, transform);
                        PlanStats.end(PlanStats.Section.TRANSFORM_BLOCK_ENTITY, blockEntityStarted);
                    }
                }
                originals.add(placed == source.blockEntity() ? isolated(source) : source);
                placements.add(new Transfer(move.destination(), targets[i], placed, compat));

                var destination = move.destination();
                if (sources.contains(destination.asLong())) {
                    continue;
                }
                var displacedState = level.getBlockState(destination);
                if (displacedState.isAir()) {
                    continue;
                }
                long destinationStarted = PlanStats.start();
                var displaced = capture(level, destination, displacedState, null);
                PlanStats.end(PlanStats.Section.MOVE_DESTINATION_CAPTURE, destinationStarted);
                if (move.swapDestination()) {
                    placements.add(new Transfer(change.pos(), displacedState, displaced.blockEntity(), displaced.compat()));
                    displaced = isolated(displaced);
                }
                originals.add(displaced);
            }
            return new Captured(originals, placements);
        } finally {
            PlanStats.end(PlanStats.Section.MOVE_CAPTURE, started);
        }
    }

    static Function<BlockPos, @Nullable BlockPos> destinations(BlockChange change) {
        var move = change.move();
        var selection = move.selection();
        var transform = move.transform();
        var origin = new BlockPos(selection.minX(), selection.minY(), selection.minZ());
        var sourceSize = new BlockPos(selection.getXSpan() - 1, selection.getYSpan() - 1, selection.getZSpan() - 1);
        var anchor = move.destination().subtract(transform.transformRelative(change.pos().subtract(origin), sourceSize));
        return pos -> selection.isInside(pos)
                ? anchor.offset(transform.transformRelative(pos.subtract(origin), sourceSize))
                : null;
    }

    private static Transfer capture(ServerLevel level, BlockPos pos, BlockState state, @Nullable BoundingBox selection) {
        PlanStats.count(PlanStats.Counter.SOURCE_CAPTURES);
        CompoundTag blockEntityTag = null;
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            long serializeStarted = PlanStats.start();
            blockEntityTag = blockEntity.saveWithFullMetadata(level.registryAccess());
            PlanStats.end(PlanStats.Section.CAPTURE_BLOCK_ENTITY, serializeStarted);
        }
        long integrationStarted = PlanStats.start();
        var compat = IntegrationDispatcher.capture(level, pos, state, selection);
        PlanStats.end(PlanStats.Section.CAPTURE_INTEGRATION, integrationStarted);
        return new Transfer(pos, state, blockEntityTag, compat);
    }

    private static Transfer isolated(Transfer transfer) {
        if (transfer.blockEntity() == null) {
            return transfer;
        }
        long started = PlanStats.start();
        var copy = transfer.blockEntity().copy();
        PlanStats.end(PlanStats.Section.MOVE_ISOLATE, started);
        return new Transfer(transfer.pos(), transfer.state(), copy, transfer.compat());
    }

    List<BlockChange> moves() {
        return moves;
    }

    RemoveMode removeMode() {
        return removeMode;
    }

    LongSet sources() {
        return sources;
    }

    LongSet destinations() {
        return destinations;
    }

    @Nullable
    PlacementDecision refusal() {
        return refusal;
    }

    boolean done() {
        return refusal != null || prepared == moves.size();
    }

    int size() {
        return moves.size();
    }

    int weight() {
        return weight;
    }

    int group() {
        return moves.get(0).move().group();
    }

    public int prepared() {
        return prepared;
    }

    long nanos() {
        return nanos;
    }
}
