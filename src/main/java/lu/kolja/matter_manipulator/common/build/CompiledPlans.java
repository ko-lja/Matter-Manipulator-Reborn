package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

public final class CompiledPlans {

    static final int EXPIRY_TICKS = 600;
    public static final int IMMEDIATE_MEMBERS = 1024;

    private final Map<UUID, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private final LongSupplier operationBudget;
    private int immediateMembers = IMMEDIATE_MEMBERS;

    CompiledPlans(LongSupplier operationBudget) {
        this.operationBudget = operationBudget;
    }

    @Nullable
    BuildPlanner.Result get(UUID player, Key key, long now) {
        var entry = entries.get(player);
        if (entry == null || entry.copy != null || !entry.key.equals(key)) {
            return null;
        }
        entry.usedAt = now;
        return entry.result;
    }

    void put(UUID player, Key key, BuildPlanner.Result result, long now) {
        entries.put(player, new Entry(key, result, null, now));
        evict(player);
    }

    BuildPlanner.Result copy(UUID player, Key key, ServerLevel level, CopyCompilation.SourceKey sourceKey,
                             @Nullable Blueprint blueprint, ToolTransform transform, BlockPos destination, long now,
                             BuildPlanner.Use use) {
        var entry = entries.get(player);
        var compilation = entry != null && entry.copy != null && entry.key.equals(key) ? entry.copy : null;
        if (compilation == null && use == BuildPlanner.Use.STATUS && entry != null && entry.copy != null
                && entry.copy.authoritative()) {
            return BuildPlanner.Result.compiling(entry.copy.progress());
        }
        boolean asked = compilation == null;
        if (compilation == null) {
            var source = entry != null && entry.copy != null && entry.copy.source().key.equals(sourceKey)
                    ? entry.copy.source()
                    : blueprint != null ? new CopyCompilation.Source(sourceKey, blueprint)
                    : new CopyCompilation.Source(sourceKey);
            compilation = new CopyCompilation(source, key, transform, destination, MMConfig.maxQueuedBlocks());
            entry = new Entry(key, null, compilation, now);
            entries.put(player, entry);
            PlanStats.count(PlanStats.Counter.PLAN_COMPILES);
        }
        entry.usedAt = now;
        if (use == BuildPlanner.Use.STATUS) {
            var latest = compilation.result();
            if (latest != null) {
                PlanStats.count(PlanStats.Counter.PLAN_CACHE_HITS);
                compilation.requestCheck();
                return latest;
            }
        } else if (compilation.takeExact()) {
            return compilation.result();
        } else {
            asked |= compilation.promote();
        }
        if (use == BuildPlanner.Use.SYNCHRONOUS) {
            compilation.advance(level, PreparationBudget.UNLIMITED.slice(1));
        } else if (asked && compilation.remaining() <= immediateMembers) {
            compilation.advance(level, PreparationBudget.members(Math.max(1, immediateMembers)).slice(1));
        }
        if (compilation.failure() != null) {
            entries.remove(player);
            return BuildPlanner.Result.error(compilation.failure());
        }
        if (!compilation.ready()) {
            return BuildPlanner.Result.compiling(compilation.progress());
        }
        evict(player);
        if (use != BuildPlanner.Use.STATUS) {
            compilation.takeExact();
        }
        return compilation.result();
    }

    void advance(Function<ResourceKey<Level>, ServerLevel> levels, PreparationBudget budget) {
        var running = new ArrayList<UUID>();
        for (var entry : entries.entrySet()) {
            var compilation = entry.getValue().copy;
            if (compilation != null) {
                compilation.expireExact();
                if (!compilation.ready()) {
                    running.add(entry.getKey());
                }
            }
        }
        for (var player : running) {
            var compilation = entries.get(player).copy;
            var level = levels.apply(compilation.key().dimension());
            if (level == null) {
                entries.remove(player);
            } else if (compilation.advance(level, budget.slice(running.size()))) {
                if (compilation.failure() != null) {
                    entries.remove(player);
                } else {
                    evict(player);
                }
            }
        }
    }

    void expireExact() {
        for (var entry : entries.values()) {
            if (entry.copy != null) {
                entry.copy.expireExact();
            }
        }
    }

    void expire(long now) {
        entries.values().removeIf(entry -> !entry.running() && (now - entry.usedAt >= EXPIRY_TICKS || now < entry.usedAt));
    }

    void forget(UUID player) {
        entries.remove(player);
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    @Nullable
    public CopyCompilation compilation(UUID player) {
        var entry = entries.get(player);
        return entry == null ? null : entry.copy;
    }

    public void immediateMembers(int members) {
        immediateMembers = members;
    }

    private void evict(UUID keep) {
        long budget = operationBudget.getAsLong();
        long total = 0L;
        for (var entry : entries.values()) {
            total += entry.operations();
        }
        var iterator = entries.entrySet().iterator();
        while (total > budget && iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().equals(keep) || entry.getValue().running()) {
                continue;
            }
            total -= entry.getValue().operations();
            iterator.remove();
        }
    }

    record Key(
            ManipulatorMode mode,
            ResourceKey<Level> dimension,
            CompoundTag toolState,
            FloatingPlacement.Anchors anchors,
            ManipulatorTier tier,
            int maxQueuedBlocks,
            int maxRegionVolume,
            int integrations
    ) {
        static Key of(ResourceKey<Level> dimension, ToolState state, FloatingPlacement.Anchors anchors, ManipulatorTier tier) {
            return new Key(state.mode(), dimension, state.copyTag(), anchors, tier, MMConfig.maxQueuedBlocks(),
                    MMConfig.MAX_REGION_VOLUME.get(), IntegrationDispatcher.generation());
        }
    }

    static final class Entry {
        private final Key key;
        @Nullable
        private final BuildPlanner.Result result;
        @Nullable
        private final CopyCompilation copy;
        private long usedAt;

        Entry(Key key, @Nullable BuildPlanner.Result result, @Nullable CopyCompilation copy, long usedAt) {
            this.key = key;
            this.result = result;
            this.copy = copy;
            this.usedAt = usedAt;
        }

        private boolean running() {
            return copy != null && !copy.ready();
        }

        long operations() {
            var latest = copy != null ? copy.result() : result;
            return latest == null ? 0 : latest.plan().map(BuildPlan::totalOperations).orElse(0);
        }
    }
}
