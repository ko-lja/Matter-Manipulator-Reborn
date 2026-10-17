package lu.kolja.matter_manipulator.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Don't keep a reference after the {@link Integrations#register} callback returns. */
public interface IntegrationRegistrar {
    void block(ResourceLocation id, BlockIntegration handler, Block... blocks);

    void block(ResourceLocation id, BlockIntegration handler, Predicate<BlockState> selector);

    void cable(ResourceLocation id, CableIntegration handler, Block... blocks);

    void cable(ResourceLocation id, CableIntegration handler, Predicate<BlockState> selector);

    void placementCost(ResourceLocation id, PlacementCostIntegration handler, Block... blocks);

    void placementCost(ResourceLocation id, PlacementCostIntegration handler, Predicate<BlockState> selector);

    void storage(ResourceLocation id, ItemStorageIntegration handler);

    void crafting(ResourceLocation id, CraftingIntegration handler);
}
