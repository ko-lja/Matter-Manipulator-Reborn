package lu.kolja.matter_manipulator.common.build;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MoveGroups {

    private MoveGroups() {}

    static Grouping group(List<BlockChange> moves, LongSet linkedSources, int maxGroupSize) {
        return group(moves, linkedSources, Map.of(), Map.of(), maxGroupSize);
    }

    static Grouping group(
            List<BlockChange> moves,
            LongSet linkedSources,
            Map<BlockPos, Set<BlockPos>> peers,
            Map<BlockPos, Component> refusals,
            int maxGroupSize
    ) {
        var sourceIndex = new Long2IntOpenHashMap(moves.size());
        sourceIndex.defaultReturnValue(-1);
        var destinations = new LongOpenHashSet(moves.size());
        for (int i = 0; i < moves.size(); i++) {
            var change = moves.get(i);
            if (sourceIndex.put(change.pos().asLong(), i) != -1) {
                throw new IllegalArgumentException("Two moves share the source " + change.pos());
            }
            if (!destinations.add(change.move().destination().asLong())) {
                throw new IllegalArgumentException("Two moves share the destination " + change.move().destination());
            }
        }

        var parent = new int[moves.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < moves.size(); i++) {
            var source = moves.get(i).pos();
            boolean linked = linkedSources.contains(source.asLong());
            for (var direction : Direction.values()) {
                long neighbour = source.relative(direction).asLong();
                int j = sourceIndex.get(neighbour);
                if (j != -1 && (linked || linkedSources.contains(neighbour))) {
                    union(parent, i, j);
                }
            }
            int displaced = sourceIndex.get(moves.get(i).move().destination().asLong());
            if (displaced != -1) {
                union(parent, i, displaced);
            }
        }
        peers.forEach((source, linked) -> {
            int i = sourceIndex.get(source.asLong());
            for (var peer : linked) {
                int j = sourceIndex.get(peer.asLong());
                if (i != -1 && j != -1) {
                    union(parent, i, j);
                }
            }
        });

        var groupOfRoot = new int[moves.size()];
        var members = new ArrayList<List<BlockChange>>();
        for (int i = 0; i < moves.size(); i++) {
            int root = find(parent, i);
            if (root == i) {
                groupOfRoot[root] = members.size();
                members.add(new ArrayList<>());
            }
            members.get(groupOfRoot[root]).add(moves.get(i));
        }

        var grouped = new ArrayList<BlockChange>(moves.size());
        int refusedBlocks = 0;
        int largestRefused = 0;
        var refusedByIntegration = new LinkedHashMap<Component, Integer>();
        int group = 0;
        for (var groupMembers : members) {
            Component refusal = null;
            for (var change : groupMembers) {
                refusal = refusals.get(change.pos());
                if (refusal != null) {
                    break;
                }
            }
            if (refusal != null) {
                refusedByIntegration.merge(refusal, groupMembers.size(), Integer::sum);
                continue;
            }
            if (groupMembers.size() > maxGroupSize) {
                refusedBlocks += groupMembers.size();
                largestRefused = Math.max(largestRefused, groupMembers.size());
                continue;
            }
            for (var change : groupMembers) {
                var move = change.move();
                grouped.add(BlockChange.move(
                        change.pos(),
                        move.destination(),
                        move.expectedSource(),
                        move.expectedDestination(),
                        move.swapDestination(),
                        move.transform(),
                        group,
                        move.selection()
                ));
            }
            group++;
        }
        return new Grouping(grouped, refusedBlocks, largestRefused, refusedByIntegration);
    }

    record Grouping(
            List<BlockChange> changes,
            int refusedBlocks,
            int largestRefusedGroup,
            Map<Component, Integer> refusedByIntegration
    ) {}

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }
}
