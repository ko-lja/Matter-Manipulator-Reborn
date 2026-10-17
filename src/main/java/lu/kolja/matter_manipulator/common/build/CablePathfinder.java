package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

public final class CablePathfinder {

    private static final int STEP_COST = 10;
    private static final int TURN_COST = 55;
    private static final int SOLID_REPLACE_COST = 180;
    private static final int HEURISTIC_WEIGHT = 4;
    private static final int MIN_SEARCH_MARGIN = 16;
    private static final int MAX_SEARCH_MARGIN = 256;
    private static final int MIN_VISITED_NODES = 16_384;
    static final int MAX_VISITED_NODES = 65_536;
    private static final Direction.Axis[][] AXIS_ORDERS = {
            { Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z },
            { Direction.Axis.X, Direction.Axis.Z, Direction.Axis.Y },
            { Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z },
            { Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X },
            { Direction.Axis.Z, Direction.Axis.X, Direction.Axis.Y },
            { Direction.Axis.Z, Direction.Axis.Y, Direction.Axis.X }
    };

    private CablePathfinder() {}

    public static SearchOutcome find(
            Level level,
            Player player,
            BlockPos start,
            BlockPos end,
            BlockState targetCable,
            @Nullable CapturedBlock template,
            RemoveMode removeMode,
            int maxPositions
    ) {
        var search = start(level, player, start, end, targetCable, template, removeMode, maxPositions);
        while (!search.isComplete()) {
            search.step(Integer.MAX_VALUE);
        }
        return search.outcome();
    }

    public static Search start(
            Level level,
            Player player,
            BlockPos start,
            BlockPos end,
            BlockState targetCable,
            @Nullable CapturedBlock template,
            RemoveMode removeMode,
            int maxPositions
    ) {
        return new Search(level, player, copy(start), copy(end), targetCable, template, removeMode, maxPositions);
    }

    static boolean exceedsPathCap(BlockPos start, BlockPos end, int maxPositions) {
        return (long) start.distManhattan(end) + 1L > maxPositions;
    }

    private static BlockPos copy(BlockPos pos) {
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static List<BlockPos> directAxisPath(WorldCache world, BlockPos start, BlockPos end, int maxPositions) {
        var best = List.<BlockPos>of();
        int bestScore = Integer.MAX_VALUE;
        for (var order : orderedAxisPermutations(start, end)) {
            var path = pathForOrder(start, end, order);
            if (path.size() > maxPositions || !canUseAll(world, path)) {
                continue;
            }
            int penalty = pathPenalty(world, path);
            if (penalty > 0) {
                continue;
            }
            int score = turnCount(path) * 10_000 - longestSegment(path);
            if (score < bestScore) {
                best = path;
                bestScore = score;
            }
        }
        return best;
    }

    private static List<Direction.Axis[]> orderedAxisPermutations(BlockPos start, BlockPos end) {
        var result = new ArrayList<>(Arrays.asList(AXIS_ORDERS));
        result.sort(Comparator.comparingInt(order -> -delta(start, end, order[0])));
        return result;
    }

    private static List<BlockPos> pathForOrder(BlockPos start, BlockPos end, Direction.Axis[] order) {
        var path = new ArrayList<BlockPos>();
        var current = start;
        path.add(current);
        for (var axis : order) {
            int target = coordinate(end, axis);
            while (coordinate(current, axis) != target) {
                current = current.relative(directionToward(current, end, axis));
                path.add(current);
            }
        }
        return path;
    }

    private static List<BlockPos> reconstruct(NodeKey end, HashMap<NodeKey, NodeKey> parentByNode) {
        var reversed = new ArrayList<BlockPos>();
        var current = end;
        while (current != null) {
            reversed.add(current.pos());
            current = parentByNode.get(current);
        }
        var path = new ArrayList<BlockPos>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    private static List<Direction> orderedDirections(BlockPos current, BlockPos end, Direction previous) {
        var directions = new ArrayList<Direction>(List.of(Direction.values()));
        directions.sort(Comparator
                .comparingInt((Direction direction) -> direction == previous ? 0 : 1)
                .thenComparingInt(direction -> reducesDistance(current, end, direction) ? 0 : 1)
                .thenComparingInt(direction -> -delta(current, end, direction.getAxis())));
        return directions;
    }

    private static boolean canUseAll(WorldCache world, List<BlockPos> path) {
        for (var pos : path) {
            if (!world.canUse(pos)) {
                return false;
            }
        }
        return true;
    }

    private static int pathPenalty(WorldCache world, List<BlockPos> path) {
        int penalty = 0;
        for (var pos : path) {
            penalty += world.placementPenalty(pos);
        }
        return penalty;
    }

    private static int turnCount(List<BlockPos> path) {
        int turns = 0;
        Direction previous = null;
        for (int i = 1; i < path.size(); i++) {
            var direction = directionBetween(path.get(i - 1), path.get(i));
            if (previous != null && previous != direction) {
                turns++;
            }
            previous = direction;
        }
        return turns;
    }

    private static int longestSegment(List<BlockPos> path) {
        int longest = 0;
        int currentLength = 0;
        Direction previous = null;
        for (int i = 1; i < path.size(); i++) {
            var direction = directionBetween(path.get(i - 1), path.get(i));
            if (direction == previous) {
                currentLength++;
            } else {
                longest = Math.max(longest, currentLength);
                currentLength = 1;
            }
            previous = direction;
        }
        return Math.max(longest, currentLength);
    }

    private static Direction directionBetween(BlockPos from, BlockPos to) {
        return Direction.getNearest(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    private static int heuristic(BlockPos current, BlockPos end) {
        return current.distManhattan(end) * STEP_COST * HEURISTIC_WEIGHT;
    }

    private static int usefulSearchMargin(BlockPos start, BlockPos end, int maxPositions) {
        int distance = start.distManhattan(end);
        return Math.max(0, (maxPositions - distance - 1) / 2);
    }

    private static int searchMargin(BlockPos start, BlockPos end, Direction.Axis axis, int maxPositions) {
        int usefulMargin = usefulSearchMargin(start, end, maxPositions);
        if (usefulMargin == 0) {
            return 0;
        }
        int requestedMargin = Math.max(MIN_SEARCH_MARGIN, delta(start, end, axis) / 2);
        return Math.min(MAX_SEARCH_MARGIN, Math.min(usefulMargin, requestedMargin));
    }

    private static boolean inside(BlockPos pos, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return pos.getX() >= minX
                && pos.getY() >= minY
                && pos.getZ() >= minZ
                && pos.getX() <= maxX
                && pos.getY() <= maxY
                && pos.getZ() <= maxZ;
    }

    private static boolean reducesDistance(BlockPos current, BlockPos end, Direction direction) {
        return current.relative(direction).distManhattan(end) < current.distManhattan(end);
    }

    private static int delta(BlockPos start, BlockPos end, Direction.Axis axis) {
        return Math.abs(coordinate(end, axis) - coordinate(start, axis));
    }

    private static int coordinate(BlockPos pos, Direction.Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    private static Direction directionToward(BlockPos current, BlockPos end, Direction.Axis axis) {
        return switch (axis) {
            case X -> end.getX() > current.getX() ? Direction.EAST : Direction.WEST;
            case Y -> end.getY() > current.getY() ? Direction.UP : Direction.DOWN;
            case Z -> end.getZ() > current.getZ() ? Direction.SOUTH : Direction.NORTH;
        };
    }

    private static int searchVolume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        long x = maxX - minX + 1L;
        long y = maxY - minY + 1L;
        long z = maxZ - minZ + 1L;
        return (int) Math.min(Integer.MAX_VALUE, x * y * z);
    }

    static int maxVisited(int maxPositions, int searchVolume) {
        long requested = Math.max(MIN_VISITED_NODES, maxPositions * 64L);
        long boundedByVolume = Math.min(requested, searchVolume * (long) Direction.values().length);
        return (int) Math.min(MAX_VISITED_NODES, boundedByVolume);
    }

    private static final class WorldCache {

        private final Level level;
        private final Player player;
        private final BlockState targetCable;
        private final @Nullable CapturedBlock template;
        private final RemoveMode removeMode;
        private final HashMap<Long, NodeType> typeByPos = new HashMap<>();

        private WorldCache(
                Level level, Player player, BlockState targetCable, @Nullable CapturedBlock template, RemoveMode removeMode
        ) {
            this.level = level;
            this.player = player;
            this.targetCable = targetCable;
            this.template = template;
            this.removeMode = removeMode;
        }

        private boolean canUse(BlockPos pos) {
            return type(pos) != NodeType.BLOCKED;
        }

        private int placementPenalty(BlockPos pos) {
            return type(pos) == NodeType.SOLID ? SOLID_REPLACE_COST : 0;
        }

        private NodeType type(BlockPos pos) {
            return typeByPos.computeIfAbsent(pos.asLong(), ignored -> inspect(pos));
        }

        private NodeType inspect(BlockPos pos) {
            if (pos.getY() < level.getMinBuildHeight()
                    || pos.getY() >= level.getMaxBuildHeight()
                    || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.hasChunkAt(pos)) {
                return NodeType.BLOCKED;
            }
            var state = level.getBlockState(pos);
            if (!BlockPolicy.canManipulate(level, pos, state)
                    || IntegrationDispatcher.cableRouteRefusal(level, pos, targetCable, template) != null) {
                return NodeType.BLOCKED;
            }
            if (state.isAir() || IntegrationDispatcher.canReuseCable(level, pos, state, targetCable, template)) {
                return NodeType.OPEN;
            }
            var existing = PlacementDecision.Existing.at(level, player, pos, targetCable);
            return nodeType(existing, targetCable, IntegrationDispatcher.isCable(state), removeMode);
        }
    }

    static NodeType nodeType(
            PlacementDecision.Existing existing, BlockState targetCable, boolean cable, RemoveMode removeMode
    ) {
        var decision = PlacementDecision.placeCable(existing, targetCable, cable, removeMode, true);
        if (decision.skipped()) {
            return NodeType.BLOCKED;
        }
        return existing.replaceable() || decision.action() == PlacementDecision.Action.NOOP
                ? NodeType.OPEN
                : NodeType.SOLID;
    }

    enum NodeType {
        BLOCKED,
        OPEN,
        SOLID
    }

    public static final class Search {

        private final WorldCache world;
        private final BlockPos end;
        private final int maxPositions;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;
        private final int maxVisited;
        private final PriorityQueue<Node> queue;
        private final HashMap<NodeKey, Integer> costByNode;
        private final HashMap<Long, Integer> costByPos;
        private final HashMap<NodeKey, NodeKey> parentByNode;
        private final HashSet<NodeKey> closed;
        private final long startedAtNanos;
        private boolean complete;
        private long finishedAtNanos;
        private FailureReason failureReason = FailureReason.NONE;
        private List<BlockPos> path = List.of();

        private Search(
                Level level,
                Player player,
                BlockPos start,
                BlockPos end,
                BlockState targetCable,
                @Nullable CapturedBlock template,
                RemoveMode removeMode,
                int maxPositions
        ) {
            startedAtNanos = System.nanoTime();
            world = new WorldCache(level, player, targetCable, template, removeMode);
            this.end = end;
            this.maxPositions = maxPositions;
            boolean tooLong = exceedsPathCap(start, end, maxPositions);

            int marginX = searchMargin(start, end, Direction.Axis.X, maxPositions);
            int marginY = searchMargin(start, end, Direction.Axis.Y, maxPositions);
            int marginZ = searchMargin(start, end, Direction.Axis.Z, maxPositions);
            int lowY = Math.min(start.getY(), end.getY()) - marginY;
            int highY = Math.max(start.getY(), end.getY()) + marginY;
            minX = Math.min(start.getX(), end.getX()) - marginX;
            minY = tooLong ? lowY : Math.max(level.getMinBuildHeight(), lowY);
            minZ = Math.min(start.getZ(), end.getZ()) - marginZ;
            maxX = Math.max(start.getX(), end.getX()) + marginX;
            maxY = tooLong ? highY : Math.min(level.getMaxBuildHeight() - 1, highY);
            maxZ = Math.max(start.getZ(), end.getZ()) + marginZ;
            maxVisited = maxVisited(maxPositions, searchVolume(minX, minY, minZ, maxX, maxY, maxZ));

            queue = new PriorityQueue<>(Comparator
                    .comparingInt(Node::estimatedTotal)
                    .thenComparingInt(Node::turns)
                    .thenComparingInt(Node::cost));
            costByNode = new HashMap<>();
            costByPos = new HashMap<>();
            parentByNode = new HashMap<>();
            closed = new HashSet<>();

            if (tooLong) {
                finish(List.of(), FailureReason.PATH_TOO_LONG);
                return;
            }
            if (start.equals(end)) {
                if (world.canUse(start)) {
                    finish(List.of(start));
                } else {
                    finish(List.of(), FailureReason.INVALID_ENDPOINT);
                }
                return;
            }
            if (!world.canUse(start) || !world.canUse(end)) {
                finish(List.of(), FailureReason.INVALID_ENDPOINT);
                return;
            }

            var direct = directAxisPath(world, start, end, maxPositions);
            if (!direct.isEmpty()) {
                finish(direct);
                return;
            }

            var startKey = new NodeKey(start, null);
            costByNode.put(startKey, 0);
            costByPos.put(start.asLong(), 0);
            queue.add(new Node(startKey, 0, heuristic(start, end), 0));
        }

        public boolean step(int maxNodes) {
            if (complete) {
                return true;
            }

            int remaining = Math.max(1, maxNodes);
            while (remaining-- > 0 && !queue.isEmpty() && closed.size() < maxVisited) {
                var current = queue.poll();
                if (current.cost() != costByNode.getOrDefault(current.key(), Integer.MAX_VALUE)) {
                    continue;
                }
                var bestCurrentPosCost = costByPos.getOrDefault(current.key().pos().asLong(), Integer.MAX_VALUE);
                if (bestCurrentPosCost != Integer.MAX_VALUE && current.cost() >= bestCurrentPosCost + TURN_COST) {
                    continue;
                }
                if (!closed.add(current.key())) {
                    continue;
                }
                if (current.key().pos().equals(end)) {
                    var result = reconstruct(current.key(), parentByNode);
                    if (result.size() <= maxPositions) {
                        finish(result);
                    } else {
                        finish(List.of(), FailureReason.PATH_TOO_LONG);
                    }
                    return true;
                }

                for (var direction : orderedDirections(current.key().pos(), end, current.key().direction())) {
                    var nextPos = current.key().pos().relative(direction);
                    if (!inside(nextPos, minX, minY, minZ, maxX, maxY, maxZ) || !world.canUse(nextPos)) {
                        continue;
                    }
                    var nextKey = new NodeKey(nextPos, direction);
                    if (closed.contains(nextKey)) {
                        continue;
                    }
                    boolean turn = current.key().direction() != null && current.key().direction() != direction;
                    int nextCost = current.cost()
                            + STEP_COST
                            + (turn ? TURN_COST : 0)
                            + world.placementPenalty(nextPos);
                    if (nextCost >= costByNode.getOrDefault(nextKey, Integer.MAX_VALUE)) {
                        continue;
                    }
                    long posKey = nextPos.asLong();
                    var bestPosCost = costByPos.getOrDefault(posKey, Integer.MAX_VALUE);
                    if (bestPosCost != Integer.MAX_VALUE && nextCost >= bestPosCost + TURN_COST) {
                        continue;
                    }
                    if (nextCost < bestPosCost) {
                        costByPos.put(posKey, nextCost);
                    }
                    costByNode.put(nextKey, nextCost);
                    parentByNode.put(nextKey, current.key());
                    queue.add(new Node(
                            nextKey,
                            nextCost,
                            nextCost + heuristic(nextPos, end),
                            current.turns() + (turn ? 1 : 0)
                    ));
                }
            }

            if (queue.isEmpty()) {
                finish(List.of(), FailureReason.NO_PATH);
            } else if (closed.size() >= maxVisited) {
                finish(List.of(), FailureReason.VISITED_LIMIT);
            }
            return complete;
        }

        public boolean isComplete() {
            return complete;
        }

        public List<BlockPos> path() {
            return path;
        }

        public long elapsedMillis() {
            long endedAt = complete ? finishedAtNanos : System.nanoTime();
            return Math.max(0L, (endedAt - startedAtNanos) / 1_000_000L);
        }

        public int visitedCount() {
            return closed.size();
        }

        public int queuedCount() {
            return queue.size();
        }

        public FailureReason failureReason() {
            return failureReason;
        }

        public String failureDescription() {
            return failureReason.description;
        }

        public SearchOutcome outcome() {
            if (!complete) {
                throw new IllegalStateException("Cable search has not completed");
            }
            return new SearchOutcome(path, failureReason, visitedCount(), elapsedMillis());
        }

        public List<BlockPos> visitedPositions(int limit) {
            var positions = new ArrayList<BlockPos>();
            var seen = new HashSet<Long>();
            for (var node : closed) {
                if (positions.size() >= limit) {
                    break;
                }
                if (seen.add(node.pos().asLong())) {
                    positions.add(node.pos());
                }
            }
            return positions;
        }

        public List<BlockPos> queuedPositions(int limit) {
            var positions = new ArrayList<BlockPos>();
            var seen = new HashSet<Long>();
            for (var node : queue) {
                if (positions.size() >= limit) {
                    break;
                }
                if (seen.add(node.key().pos().asLong())) {
                    positions.add(node.key().pos());
                }
            }
            return positions;
        }

        private void finish(List<BlockPos> result) {
            finish(result, result.isEmpty() ? FailureReason.NO_PATH : FailureReason.NONE);
        }

        private void finish(List<BlockPos> result, FailureReason reason) {
            finishedAtNanos = System.nanoTime();
            failureReason = reason;
            path = List.copyOf(result);
            complete = true;
        }
    }

    public enum FailureReason {
        NONE("none"),
        NO_PATH("no open route"),
        INVALID_ENDPOINT("invalid endpoint"),
        PATH_TOO_LONG("path length cap"),
        VISITED_LIMIT("search limit");

        private final String description;

        FailureReason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public record SearchOutcome(
            List<BlockPos> path,
            FailureReason failureReason,
            int visitedCount,
            long elapsedMillis
    ) {
        public SearchOutcome {
            path = List.copyOf(path);
        }

        public boolean successful() {
            return failureReason == FailureReason.NONE;
        }
    }

    private record NodeKey(BlockPos pos, Direction direction) {}

    private record Node(NodeKey key, int cost, int estimatedTotal, int turns) {}
}
