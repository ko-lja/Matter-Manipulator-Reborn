package lu.kolja.matter_manipulator.api;

/** Rewrites the placement cost of blocks that need no {@link BlockIntegration}. Selected blocks capture no payload. */
@FunctionalInterface
public interface PlacementCostIntegration {
    PlacementRequirements placementRequirements(IntegrationContext.Placement context);
}
