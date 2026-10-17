package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.build.BlockCost.Companion;
import lu.kolja.matter_manipulator.common.build.BlockCost.Reason;
import lu.kolja.matter_manipulator.common.build.BlockCost.Unsupported;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class PlacementCost {

    private PlacementCost() {}

    @FunctionalInterface
    public interface Requirements {
        PlacementRequirements of(BlockState target, CapturedBlock captured);
    }

    public static final Requirements INTEGRATIONS = IntegrationDispatcher::placementRequirements;

    public record Resolved(
            BlockCost cost,
            List<ItemRequirement> requirements,
            PlacementPurpose purpose,
            Requirements integrations,
            int generation
    ) {
        public Resolved {
            requirements = List.copyOf(requirements);
        }

        boolean validFor(BlockChange change, Requirements integrations) {
            return this.integrations == integrations && purpose == change.placementPurpose()
                    && generation == IntegrationDispatcher.generation();
        }
    }

    public static BlockCost resolve(BlockState target, CapturedBlock captured) {
        return resolve(target, captured, IntegrationDispatcher::placementRequirements);
    }

    public static BlockCost resolve(BlockState target, CapturedBlock captured, Requirements integrations) {
        PlanStats.count(PlanStats.Counter.PLACEMENT_COSTS);
        var core = BlockCost.of(target);
        if (core instanceof Unsupported unsupported && !unsupported.reason().integrationMayReplace()) {
            return core;
        }
        return combine(core, integrations.of(target, captured));
    }

    public static Resolved resolved(BlockChange change, Requirements integrations) {
        var attached = change.cost();
        if (attached != null && attached.validFor(change, integrations)) {
            return attached;
        }
        int generation = IntegrationDispatcher.generation();
        var purpose = change.placementPurpose();
        var target = change.target().tryResolve();
        if (target.isEmpty()) {
            return new Resolved(BlockCost.unsupported(Reason.UNKNOWN_BLOCK), List.of(), purpose, integrations, generation);
        }
        var cost = resolve(target.get(), change.captured(), integrations);
        if (!cost.placeable() || !purpose.consumesItems()) {
            return new Resolved(cost, List.of(), purpose, integrations, generation);
        }
        var requirements = new ArrayList<>(consumedRequirements(cost, purpose));
        if (change.captured() != null && purpose.preparesIntegrations()) {
            requirements.addAll(IntegrationDispatcher.collectPlacementRequirements(target.get(), change.captured(), purpose));
        }
        return new Resolved(cost, requirements, purpose, integrations, generation);
    }

    static BlockCost combine(BlockCost core, PlacementRequirements integration) {
        if (integration.unsupportedReason() != null) {
            return new Unsupported(Reason.EMPTY_REPLACEMENT, integration.unsupportedReason());
        }
        var extra = integration.requirements();
        if (!integration.requireDefaultBlockItem()) {
            if (extra.isEmpty()) {
                return BlockCost.unsupported(Reason.EMPTY_REPLACEMENT);
            }
            return core instanceof Companion companion ? companion.withRequirements(extra) : new BlockCost.Items(extra);
        }
        if (extra.isEmpty() || core instanceof Unsupported) {
            return core;
        }
        var merged = new ArrayList<ItemRequirement>(core.requirements());
        merged.addAll(extra);
        return core instanceof Companion companion ? companion.withRequirements(merged) : new BlockCost.Items(merged);
    }

    public static List<ItemRequirement> consumedRequirements(BlockCost cost, PlacementPurpose purpose) {
        return purpose.consumesItems() ? cost.requirements() : List.of();
    }
}
