package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CopyCompilation {

    static final int MAX_CHANGED_ROUNDS = 3;

    public enum Stage { SNAPSHOT, VALIDATE, CHECK, ASSEMBLE, READY }

    private final Source source;
    private final CompiledPlans.Key key;
    private final ToolTransform transform;
    private final BlockPos destination;
    private final int maxOperations;
    private Stage stage;
    private boolean authoritative;
    private boolean validated;
    private boolean checkRequested;
    private boolean exact;
    private int changedRounds;
    private long steps;
    private Stage cursorStage;
    private int cursor;
    private boolean roundChanged;
    @Nullable
    private Assembly assembly;
    @Nullable
    private BuildPlanner.Result result;
    private int resultVersion = -1;
    @Nullable
    private Component failure;

    CopyCompilation(Source source, CompiledPlans.Key key, ToolTransform transform, BlockPos destination, int maxOperations) {
        this.source = source;
        this.key = key;
        this.transform = transform;
        this.destination = destination;
        this.maxOperations = maxOperations;
        stage = next();
    }

    public Source source() {
        return source;
    }

    CompiledPlans.Key key() {
        return key;
    }

    public Stage stage() {
        return stage;
    }

    @Nullable
    BuildPlanner.Result result() {
        return result;
    }

    public boolean ready() {
        return stage == Stage.READY;
    }

    public long steps() {
        return steps;
    }

    public boolean authoritative() {
        return authoritative;
    }

    @Nullable
    Component failure() {
        return failure;
    }

    boolean promote() {
        if (authoritative) {
            return false;
        }
        authoritative = true;
        validated = false;
        changedRounds = 0;
        stage = next();
        if (stage == Stage.READY) {
            exact = true;
            authoritative = false;
        }
        return true;
    }

    void requestCheck() {
        if (stage == Stage.READY) {
            checkRequested = true;
            stage = next();
        }
    }

    boolean takeExact() {
        boolean taken = exact;
        exact = false;
        return taken;
    }

    void expireExact() {
        exact = false;
    }

    long remaining() {
        if (stage == Stage.READY) {
            return 0;
        }
        long left = (long) source.volume - source.read;
        if (source.fromWorld() && (authoritative && !validated || checkRequested)) {
            left += source.volume - (cursorStage == stage ? cursor : 0);
        }
        if (result == null || resultVersion != source.version) {
            int captured = source.captured + (source.volume - source.read);
            left += assembly == null ? source.volume + 2L * captured * transform.stackCount()
                    : assembly.total() - assembly.done();
        }
        return left;
    }

    Component progress() {
        return switch (stage) {
            case SNAPSHOT -> MMMessage.COMPILING_SOURCE.text(
                    count(source.read), count(source.volume));
            case VALIDATE, CHECK -> MMMessage.COMPILING_VERIFY.text(
                    count(cursorStage == stage ? cursor : 0), count(source.volume));
            case ASSEMBLE, READY -> {
                long done = assembly == null ? 0 : assembly.done();
                long total = assembly == null ? source.volume : assembly.total();
                yield MMMessage.COMPILING_PLAN.text(count(done), count(total));
            }
        };
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    boolean advance(ServerLevel level, PreparationBudget.Slice slice) {
        PlanStats.count(PlanStats.Counter.COMPILE_SLICES);
        boolean snapshotStartsHere = source.read == 0;
        slice.open();
        try {
            while (true) {
                var previous = stage;
                stage = next();
                if (stage != previous && stage == Stage.READY && authoritative) {
                    exact = true;
                    authoritative = false;
                    validated = false;
                }
                if (stage == Stage.READY) {
                    return true;
                }
                if (stage != Stage.ASSEMBLE && !loaded(level)) {
                    failure = MMMessage.REGION_NOT_LOADED.text();
                    return true;
                }
                if (stage != cursorStage) {
                    cursorStage = stage;
                    cursor = 0;
                    roundChanged = false;
                }
                boolean finished = switch (stage) {
                    case SNAPSHOT -> snapshot(level, slice);
                    case VALIDATE -> validate(level, slice);
                    case CHECK -> check(level, slice);
                    case ASSEMBLE -> assemble(slice);
                    case READY -> true;
                };
                if (!finished) {
                    return false;
                }
                if (stage == Stage.SNAPSHOT && snapshotStartsHere) {
                    validated = true;
                }
            }
        } finally {
            slice.close();
        }
    }

    private Stage next() {
        if (source.read < source.volume) {
            return Stage.SNAPSHOT;
        }
        if (source.fromWorld() && authoritative && !validated) {
            return Stage.VALIDATE;
        }
        if (source.fromWorld() && checkRequested) {
            return Stage.CHECK;
        }
        if (result == null || resultVersion != source.version) {
            return Stage.ASSEMBLE;
        }
        return Stage.READY;
    }

    @SuppressWarnings("deprecation")
    private boolean loaded(ServerLevel level) {
        return level.hasChunksAt(source.min.getX(), source.min.getZ(), source.max.getX(), source.max.getZ());
    }

    private boolean snapshot(ServerLevel level, PreparationBudget.Slice slice) {
        long started = PlanStats.start();
        while (source.read < source.volume && slice.take()) {
            source.read(level, source.read);
            source.read++;
            steps++;
        }
        PlanStats.end(PlanStats.Section.PLAN_SOURCE, started);
        return source.read == source.volume;
    }

    private boolean validate(ServerLevel level, PreparationBudget.Slice slice) {
        long started = PlanStats.start();
        boolean atOnce = changedRounds >= MAX_CHANGED_ROUNDS && cursor == 0;
        while (cursor < source.volume && (atOnce || slice.take())) {
            roundChanged |= source.reread(level, cursor, true);
            cursor++;
            steps++;
        }
        PlanStats.end(PlanStats.Section.PLAN_VALIDATE, started);
        if (cursor < source.volume) {
            return false;
        }
        PlanStats.count(PlanStats.Counter.SOURCE_VALIDATIONS);
        cursorStage = null;
        if (!roundChanged || atOnce) {
            validated = true;
            checkRequested = false;
        } else {
            changedRounds++;
        }
        return true;
    }

    private boolean check(ServerLevel level, PreparationBudget.Slice slice) {
        long started = PlanStats.start();
        while (cursor < source.volume && slice.take()) {
            source.reread(level, cursor, false);
            cursor++;
            steps++;
        }
        PlanStats.end(PlanStats.Section.PLAN_VALIDATE, started);
        if (cursor < source.volume) {
            return false;
        }
        PlanStats.count(PlanStats.Counter.SOURCE_CHECKS);
        cursorStage = null;
        checkRequested = false;
        return true;
    }

    private boolean assemble(PreparationBudget.Slice slice) {
        if (assembly == null || assembly.version != source.version) {
            assembly = new Assembly(source.version);
        }
        var assembled = assembly.advance(slice);
        if (assembled == null) {
            return false;
        }
        result = assembled;
        resultVersion = assembly.version;
        assembly = null;
        return true;
    }

    public record SourceKey(ResourceKey<Level> dimension, BlockPos min, BlockPos max, int integrations,
                     @Nullable String blueprint) {}

    public static final class Source {
        final SourceKey key;
        public final BlockPos min;
        public final BlockPos max;
        final BoundingBox selection;
        final int sizeX;
        final int sizeY;
        public final int volume;
        public final BlockState[] states;
        public final CapturedBlock[] captures;
        private final Map<CapturedBlock, CapturedBlock> distinct = new HashMap<>();
        public int read;
        public int captured;
        int version;
        int unknown;

        public Source(SourceKey key) {
            this.key = key;
            min = key.min();
            max = key.max();
            selection = BoundingBox.fromCorners(min, max);
            sizeX = max.getX() - min.getX() + 1;
            sizeY = max.getY() - min.getY() + 1;
            volume = (int) BuildPlanner.regionVolume(min, max);
            states = new BlockState[volume];
            captures = new CapturedBlock[volume];
        }

        public Source(SourceKey key, Blueprint blueprint) {
            this(key);
            for (int index = 0; index < volume; index++) {
                if (blueprint.isEmpty(index)) {
                    continue;
                }
                var entry = blueprint.at(index);
                var state = entry == null ? null : entry.state().tryResolve().orElse(null);
                if (state == null || state.isAir() || !BlockPolicy.canManipulate(state)) {
                    unknown++;
                    continue;
                }
                states[index] = state;
                captures[index] = entry;
                captured++;
            }
            read = volume;
        }

        boolean fromWorld() {
            return key.blueprint() == null;
        }

        BlockPos pos(int index) {
            return min.offset(index % sizeX, index / sizeX % sizeY, index / sizeX / sizeY);
        }

        public void read(ServerLevel level, int index) {
            var pos = pos(index);
            var state = level.getBlockState(pos);
            if (!state.isAir() && BlockPolicy.canManipulate(level, pos, state)) {
                var capture = BlockCapture.capture(level, pos, CapturePurpose.COPY_TEMPLATE, selection);
                var shared = distinct.putIfAbsent(capture, capture);
                states[index] = state;
                captures[index] = shared != null ? shared : capture;
                captured++;
            }
        }

        boolean reread(ServerLevel level, int index, boolean payloads) {
            var pos = pos(index);
            var state = level.getBlockState(pos);
            var expected = states[index];
            boolean capturable = !state.isAir() && BlockPolicy.canManipulate(level, pos, state);
            boolean same = capturable ? state == expected : expected == null;
            if (same && capturable && payloads && IntegrationDispatcher.hasBlockIntegration(state)) {
                same = captures[index].compatEquals(IntegrationDispatcher.capture(level, pos, state, selection));
            }
            if (same) {
                return false;
            }
            PlanStats.count(PlanStats.Counter.SOURCE_REREADS);
            if (captures[index] != null) {
                captured--;
            }
            states[index] = null;
            captures[index] = null;
            read(level, index);
            version++;
            return true;
        }
    }

    private final class Assembly {
        final int version;
        private final int stackCount;
        private final int[] sources;
        private final CapturedBlock[] transformed;
        private final IdentityHashMap<CapturedBlock, Optional<CapturedBlock>> transformedBy = new IdentityHashMap<>();
        private final IdentityHashMap<BlockState, Boolean> exactStates = new IdentityHashMap<>();
        private final BuildPlanner.PlacementChecker checker;
        private final List<BlockChange> copies;
        private int visited;
        private int count;
        private int unoriented;
        private int approximated;
        private int checked;

        Assembly(int version) {
            this.version = version;
            stackCount = transform.stackCount();
            sources = new int[source.captured];
            transformed = new CapturedBlock[source.captured];
            int expected = (int) Math.min((long) source.captured * stackCount, maxOperations + 1L);
            checker = new BuildPlanner.PlacementChecker(true, PlacementCost.INTEGRATIONS, expected);
            copies = new ArrayList<>(expected);
        }

        long total() {
            return source.volume + 2L * count * stackCount;
        }

        long done() {
            return visited + copies.size() + checked;
        }

        @Nullable
        BuildPlanner.Result advance(PreparationBudget.Slice slice) {
            if (visited == 0 && count == 0) {
                if (source.captured == 0) {
                    return BuildPlanner.Result.error(MMMessage.NO_MOVABLE_BLOCKS.text());
                }
                if ((long) source.captured * stackCount > maxOperations) {
                    long projected = ((long) maxOperations / stackCount + 1) * stackCount;
                    return BuildPlanner.Result.error(MMMessage.SELECTION_TOO_LARGE_OPERATIONS.text(projected));
                }
            }
            if (visited < source.volume && !transform(slice)) {
                return null;
            }
            int total = count * stackCount;
            if (copies.size() < total && !emit(slice, total)) {
                return null;
            }
            if (checked < copies.size() && !check(slice)) {
                return null;
            }
            return finish();
        }

        private boolean transform(PreparationBudget.Slice slice) {
            long started = PlanStats.start();
            while (visited < source.volume && slice.take()) {
                int index = visited++;
                steps++;
                var capture = source.captures[index];
                if (capture == null) {
                    continue;
                }
                var result = transformedBy.computeIfAbsent(capture, captured -> captured.transformed(transform));
                if (result.isEmpty()) {
                    unoriented++;
                    continue;
                }
                sources[count] = index;
                transformed[count++] = result.get();
                if (!exactStates.computeIfAbsent(capture.state().resolve(),
                        sourceState -> IntegrationDispatcher.transformState(sourceState, transform).isPresent())) {
                    approximated++;
                }
            }
            PlanStats.end(PlanStats.Section.PLAN_TRANSFORM, started);
            return visited == source.volume;
        }

        private boolean emit(PreparationBudget.Slice slice, int total) {
            long started = PlanStats.start();
            var sourceSize = source.max.subtract(source.min);
            var transformedSize = transform.transformedSize(sourceSize);
            while (copies.size() < total && slice.take()) {
                int next = copies.size();
                int index = sources[next % count];
                var stackOffset = transform.stackOffset(transformedSize, next / count);
                var relative = new BlockPos(index % source.sizeX, index / source.sizeX % source.sizeY,
                        index / source.sizeX / source.sizeY);
                var targetPos = destination.offset(stackOffset).offset(transform.transformRelative(relative, sourceSize));
                copies.add(BlockChange.placeCaptured(targetPos, transformed[next % count], PlacementPurpose.COPY_TEMPLATE));
                steps++;
            }
            PlanStats.end(PlanStats.Section.PLAN_TRANSFORM, started);
            return copies.size() == total;
        }

        private boolean check(PreparationBudget.Slice slice) {
            long started = PlanStats.start();
            while (checked < copies.size() && slice.take()) {
                checker.add(copies.get(checked++));
                steps++;
            }
            PlanStats.end(PlanStats.Section.PLAN_PLACEMENT_CHECK, started);
            return checked == copies.size();
        }

        private BuildPlanner.Result finish() {
            long started = PlanStats.start();
            var copyCheck = checker.finish();
            PlanStats.end(PlanStats.Section.PLAN_PLACEMENT_CHECK, started);
            var changes = copyCheck.changes();
            var notice = copyCheck.notice();
            if (unoriented > 0) {
                var orientationNotice = MMMessage.COPY_ORIENTATION_SKIPPED.text(
                        unoriented * stackCount);
                notice = notice.getString().isEmpty() ? orientationNotice : notice.copy().append(" ").append(orientationNotice);
            }
            if (source.unknown > 0) {
                var unknownNotice = MMMessage.BLUEPRINT_UNKNOWN_SKIPPED.text(
                        (long) source.unknown * stackCount);
                notice = notice.getString().isEmpty() ? unknownNotice : notice.copy().append(" ").append(unknownNotice);
            }
            if (changes.isEmpty()) {
                var error = MMMessage.NOTHING_PLACEABLE.text();
                return BuildPlanner.Result.error(notice.getString().isEmpty() ? error : error.append(" ").append(notice));
            }
            if (changes.size() > maxOperations) {
                return BuildPlanner.Result.error(MMMessage.SELECTION_TOO_LARGE.text(changes.size()));
            }
            if (approximated > 0) {
                var approximatedNotice = MMMessage.COPY_ORIENTATION_APPROXIMATED.text(
                        approximated * stackCount);
                notice = notice.getString().isEmpty() ? approximatedNotice : notice.copy().append(" ").append(approximatedNotice);
            }
            return BuildPlanner.Result.ok(new BuildPlan(key.dimension(), changes)).withNotice(notice);
        }
    }
}
