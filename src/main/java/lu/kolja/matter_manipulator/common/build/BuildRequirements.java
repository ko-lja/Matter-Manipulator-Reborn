package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.api.ItemDemand;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.util.InventoryUtil;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuildRequirements {

    private BuildRequirements() {}

    public static List<ItemRequirement> collect(BuildPlan plan) {
        return collect(plan, PlacementCost.INTEGRATIONS);
    }

    static List<ItemRequirement> collect(BuildPlan plan, PlacementCost.Requirements integrations) {
        long started = PlanStats.start();
        var requirements = new ArrayList<ItemRequirement>();
        for (var change : plan.changes()) {
            if (change.kind() == BlockChange.Kind.PLACE || change.kind() == BlockChange.Kind.PLACE_CAPTURED) {
                requirements.addAll(PlacementCost.resolved(change, integrations).requirements());
            }
        }
        PlanStats.end(PlanStats.Section.STATUS_REQUIREMENTS, started);
        return List.copyOf(requirements);
    }

    public static Applicable collectApplicable(
            ServerPlayer player,
            ManipulatorTier tier,
            RemoveMode removeMode,
            BuildPlan plan
    ) {
        if (!(player.level() instanceof ServerLevel level) || !level.dimension().equals(plan.dimension())) {
            return new Applicable(List.of(), Map.of(), Map.of(), 0);
        }
        long started = PlanStats.start();
        var applicable = new ArrayList<BlockChange>();
        var skipped = new EnumMap<SkipReason, Integer>(SkipReason.class);
        var explained = new LinkedHashMap<Component, Integer>();
        int unchanged = 0;
        for (var change : plan.changes()) {
            var kind = change.kind();
            if (kind != BlockChange.Kind.PLACE && kind != BlockChange.Kind.PLACE_CAPTURED && kind != BlockChange.Kind.REMOVE) {
                continue;
            }
            var reason = WorldOperations.touchRefusal(player, level, change.pos());
            if (reason == null && !withinRange(player, tier, change)) {
                reason = SkipReason.OUT_OF_RANGE;
            }
            if (reason == null) {
                var decision = PlacementDecision.of(level, player, change, removeMode, tier.canRemove());
                if (decision.action() == PlacementDecision.Action.NOOP) {
                    unchanged++;
                    continue;
                }
                if (decision.action() == PlacementDecision.Action.RECONFIGURE) {
                    continue;
                }
                if (decision.detail() != null) {
                    explained.merge(SkipReason.explained(decision.detail()), 1, Integer::sum);
                    continue;
                }
                reason = decision.reason();
            }
            if (reason != null) {
                skipped.merge(reason, 1, Integer::sum);
                continue;
            }
            applicable.add(change);
        }
        PlanStats.end(PlanStats.Section.STATUS_DESTINATION, started);
        return new Applicable(collect(new BuildPlan(plan.dimension(), applicable)), skipped, explained, unchanged);
    }

    public record Applicable(
            List<ItemRequirement> requirements,
            Map<SkipReason, Integer> skipped,
            Map<Component, Integer> explained,
            int unchanged
    ) {
        public Applicable {
            requirements = List.copyOf(requirements);
            skipped = Map.copyOf(skipped);
            explained = Collections.unmodifiableMap(new LinkedHashMap<>(explained));
        }

        public Component notice() {
            var notice = Component.empty();
            if (unchanged > 0) {
                notice.append(MMMessage.STATUS_UNCHANGED.text(unchanged));
            }
            if (!skipped.isEmpty() || !explained.isEmpty()) {
                if (unchanged > 0) {
                    notice.append(" ");
                }
                int total = skipped.values().stream().mapToInt(Integer::intValue).sum()
                        + explained.values().stream().mapToInt(Integer::intValue).sum();
                notice.append(MMMessage.STATUS_SKIPPED.text(
                        total,
                        SkipReason.describe(skipped, explained)
                ));
            }
            return notice;
        }
    }

    public static List<ItemDemand> missing(
            ServerPlayer player,
            ItemStack tool,
            List<ItemRequirement> requirements
    ) {
        return ItemRequirementResolver.missing(requirements, InventoryUtil.placementSupply(player, tool));
    }

    public static List<ItemRequirementStatus> statuses(
            ServerPlayer player,
            ItemStack tool,
            List<ItemRequirement> requirements
    ) {
        long started = PlanStats.start();
        var statuses = ItemRequirementResolver.statuses(requirements, InventoryUtil.placementSupply(player, tool));
        PlanStats.end(PlanStats.Section.STATUS_SUPPLY, started);
        return statuses;
    }

    public static List<ItemRequirementStatus> creativeStatuses(List<ItemRequirement> requirements) {
        long started = PlanStats.start();
        var statuses = ItemRequirementResolver.statuses(requirements, stack -> Long.MAX_VALUE);
        PlanStats.end(PlanStats.Section.STATUS_SUPPLY, started);
        return statuses;
    }

    private static boolean withinRange(ServerPlayer player, ManipulatorTier tier, BlockChange change) {
        if (tier.range() < 0) {
            return true;
        }
        double rangeSquared = (double) tier.range() * tier.range();
        return player.blockPosition().distSqr(change.pos()) <= rangeSquared;
    }
}
