package lu.kolja.matter_manipulator.api;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Don't keep a reference after the {@link Integrations#register} callback returns. */
public interface IntegrationRegistrar {
    void block(Identifier id, BlockIntegration handler, Block... blocks);

    void block(Identifier id, BlockIntegration handler, Predicate<BlockState> selector);

    void cable(Identifier id, CableIntegration handler, Block... blocks);

    void cable(Identifier id, CableIntegration handler, Predicate<BlockState> selector);

    void placementCost(Identifier id, PlacementCostIntegration handler, Block... blocks);

    void placementCost(Identifier id, PlacementCostIntegration handler, Predicate<BlockState> selector);

    void storage(Identifier id, ItemStorageIntegration handler);

    void crafting(Identifier id, CraftingIntegration handler);
}
